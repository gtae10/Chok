"""
forward_signals.py — G단계 전향 검증 기록 (PROJECT_PLAN D19)

B단계에서 가장 일관됐던 신호 4개를 "그날 알 수 있던 값"으로 매일 signal_snapshots 에 남긴다.
나중에 과거 데이터로 다시 계산하면 DART 정정값처럼 그때 몰랐던 값이 섞이므로, 기록 자체가 검증 대상이다.
정의·방향은 고정(D19). 바꾸면 새 실험이고 기록을 처음부터 다시 쌓는다.

  bp          자본총계(avail_date <= T 최신 보고서) / 시가총액(T)          (+) 높을수록 좋음
  short_ratio 공매도 잔고 비중, T 기준 2거래일 전 값 (KRX T+2 공개)       (-)
  inst20      기관 순매수 거래대금 최근 20거래일 합 / 시가총액(T)         (-)
  vol60       일간 수익률 60일 표준편차                                   (-)

같은 (날짜, 종목, 신호)는 처음 기록한 값을 유지한다(INSERT IGNORE) - 그때 알던 값이 남도록.
실행: collect.py 가 시세 수집 뒤 부른다. 단독: PYTHONUTF8=1 .venv/Scripts/python.exe forward_signals.py
"""
import datetime
import logging
import os

import numpy as np
import pandas as pd

import db

log = logging.getLogger(__name__)

SIGNALS = {"bp": +1, "short_ratio": -1, "inst20": -1, "vol60": -1}  # 방향은 기록용 메타데이터(평가 때 사용)
SHORT_LAG = 2
INST_DAYS = 20
VOL_DAYS = 60
MIN_NAMES = 20  # 이보다 적게 계산되면 그날 횡단면 순위가 무의미 → ERROR


def compute(asof, tickers, marcap: dict, closes: pd.DataFrame, shorts: pd.DataFrame,
            inst_sum: dict, panel) -> list:
    """네트워크·DB 없이 그날 신호를 계산한다 (테스트도 이걸 쓴다).
    closes: ticker, trade_date, close_price / shorts: ticker, trade_date, short_ratio
    inst_sum: {ticker: 최근 20거래일 기관 순매수 합} / panel: fundamentals_dart 패널 또는 None.
    반환: (trade_date, ticker, signal, value) 목록, NaN은 뺀다."""
    asof = pd.Timestamp(asof)
    days = pd.DatetimeIndex(sorted(closes.loc[closes["trade_date"] <= asof, "trade_date"].unique()))
    short_day = days[-1 - SHORT_LAG] if len(days) > SHORT_LAG else None
    vals = {s: {} for s in SIGNALS}
    for t in tickers:
        mc = marcap.get(t) or np.nan
        c = closes[(closes["ticker"] == t) & (closes["trade_date"] <= asof)].sort_values("trade_date")
        if len(c) > VOL_DAYS and c["trade_date"].iloc[-1] == asof:
            vals["vol60"][t] = c["close_price"].pct_change().iloc[-VOL_DAYS:].std()
        s = shorts[(shorts["ticker"] == t) & (shorts["trade_date"] == short_day)]
        if len(s):
            vals["short_ratio"][t] = float(s["short_ratio"].iloc[0])
        if t in inst_sum and mc > 0:
            vals["inst20"][t] = inst_sum[t] / mc
    if panel is not None and len(panel):
        from experiment_cross_section import add_fundamentals  # B단계와 같은 시점 규칙(정정·분기 순서)
        ds = pd.DataFrame({"ticker": list(tickers), "trade_date": asof,
                           "Marcap": [marcap.get(t) or np.nan for t in tickers]})
        m = add_fundamentals(ds, panel)
        vals["bp"] = dict(zip(m["ticker"], m["bp"].where(m["Marcap"] > 0)))
    d = asof.date()
    return [(d, t, s, float(v)) for s, by in vals.items() for t, v in by.items() if np.isfinite(v)]


def load_closes(tickers) -> pd.DataFrame:
    since = datetime.date.today() - datetime.timedelta(days=150)
    with db.get_conn() as conn:
        df = pd.read_sql("SELECT ticker, trade_date, close_price FROM price_history WHERE trade_date >= %s",
                         conn, params=(since,))
    df = df[df["ticker"].isin(set(tickers))].copy()
    df["trade_date"] = pd.to_datetime(df["trade_date"])
    df["close_price"] = df["close_price"].astype(float)
    return df


def load_shorts(since) -> pd.DataFrame:
    with db.get_conn() as conn:
        df = pd.read_sql("SELECT ticker, trade_date, short_ratio FROM short_balance WHERE trade_date >= %s",
                         conn, params=(since.date(),))
    df["trade_date"] = pd.to_datetime(df["trade_date"])
    return df


def fetch_inst_sum(tickers, days: pd.DatetimeIndex) -> dict:
    """experiment_flow_short.fetch_flow 와 같은 pykrx 함수·열(기관합계). 최근 20거래일이 다 있어야 값을 낸다."""
    if not (os.environ.get("KRX_ID") and os.environ.get("KRX_PW")):
        log.warning("KRX_ID / KRX_PW 환경변수가 없어 inst20 기록을 건너뜁니다.")
        return {}
    from pykrx import stock
    want = days[-INST_DAYS:]
    out, fails = {}, 0
    for t in tickers:
        try:
            df = stock.get_market_trading_value_by_date(want[0].strftime("%Y%m%d"), want[-1].strftime("%Y%m%d"), t)
            inst = df["기관합계"].reindex(want)
        except Exception as e:
            fails += 1
            log.warning("기관 순매수 조회 실패 %s: %s", t, type(e).__name__)
            continue
        if inst.notna().all():
            out[t] = float(inst.sum())
    if fails:
        log.warning("기관 순매수 조회 실패 %d/%d 종목", fails, len(tickers))
    return out


def load_panel(tickers):
    from experiment_dart_events import api_key
    from fundamentals_dart import DartStop, fetch_all, load_fundamentals_panel
    try:
        key = api_key()
    except SystemExit:  # 키 없음
        key = ""
    if key:
        try:
            log.info("DART 재무 갱신: %s", fetch_all(tickers, key))
        except DartStop as e:
            log.error("DART 중단(%s) - 캐시된 재무로 bp 계산", e)
    else:
        log.warning("DART 키가 없어 새 재무를 받지 않고 캐시로 bp 계산")
    return load_fundamentals_panel(fetch=False)


def insert_rows(rows):
    if not rows:
        return
    with db.get_conn() as conn:
        cur = conn.cursor()
        cur.executemany("""INSERT IGNORE INTO signal_snapshots (trade_date, ticker, signal_name, signal_value, recorded_at)
                           VALUES (%s, %s, %s, %s, NOW())""", rows)
        cur.close()


def record(stocks):
    """stocks: collect.get_top_n_stocks() 결과 (ticker, market_cap)."""
    tickers = [s["ticker"] for s in stocks]
    marcap = {s["ticker"]: s["market_cap"] for s in stocks}
    closes = load_closes(tickers)
    if closes.empty:
        log.error("가격 이력이 없어 신호 기록을 건너뜁니다.")
        return 0
    asof = closes["trade_date"].max()
    days = pd.DatetimeIndex(sorted(closes["trade_date"].unique()))
    try:
        panel = load_panel(tickers)
    except Exception:
        log.exception("재무 패널 로드 실패 - bp 없이 기록")
        panel = None
    rows = compute(asof, tickers, marcap, closes, load_shorts(days[-min(10, len(days))]),
                   fetch_inst_sum(tickers, days), panel)
    insert_rows(rows)
    counts = {s: sum(r[2] == s for r in rows) for s in SIGNALS}
    level = logging.ERROR if min(counts.values()) < MIN_NAMES else logging.INFO
    log.log(level, "신호 기록 %s 기준: %s (종목 %d)", asof.date(), counts, len(tickers))
    return len(rows)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    from collect import get_top_n_stocks
    record(get_top_n_stocks())
