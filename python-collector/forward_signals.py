"""
forward_signals.py — G단계 전향 검증 기록 (PROJECT_PLAN D19)

B단계에서 가장 일관됐던 신호 4개를 "그날 알 수 있던 값"으로 매일 signal_snapshots 에 남긴다.
나중에 과거 데이터로 다시 계산하면 DART 정정값처럼 그때 몰랐던 값이 섞이므로, 기록 자체가 검증 대상이다.
정의·방향은 고정(D19). 바꾸면 새 실험이고 기록을 처음부터 다시 쌓는다.

  bp          지배주주 자본(avail_date <= T 최신 보고서, 외화는 T 환율로 원화) / 시가총액(T)  (+)
  short_ratio 공매도 잔고 비중, T 기준 2거래일 전 값 (KRX T+2 공개)       (-)
  inst20      기관 순매수 거래대금 최근 20거래일 합 / 시가총액(T)         (-)
  vol60       일간 수익률 60일 표준편차                                   (-)

같은 (날짜, 종목, 신호)는 처음 기록한 값을 유지한다(INSERT IGNORE) - 그때 알던 값이 남도록.
우선주·인프라펀드처럼 DART 정기보고서가 없는 종목은 bp가 없다(D18과 같음).
실행: collect.py 가 시세 수집 뒤 부른다. 단독: PYTHONUTF8=1 .venv/Scripts/python.exe forward_signals.py
"""
import datetime
import json
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


PARENT_ID = "ifrs-full_EquityAttributableToOwnersOfParent"
REPRT_BY_Q = {1: "11013", 2: "11012", 3: "11014", 4: "11011"}


def latest_reports(panel: pd.DataFrame, asof) -> pd.DataFrame:
    """종목별로 T에 알 수 있던 최신 보고서 1행. D18 add_fundamentals 와 같은 규칙:
    avail_date <= T, 더 새 분기 뒤에 온 지난 분기 정정은 버린다."""
    p = panel.sort_values(["ticker", "avail_date", "period_end"])
    p = p[p["period_end"] >= p.groupby("ticker")["period_end"].cummax()]
    return p[p["avail_date"] <= pd.Timestamp(asof)].groupby("ticker").tail(1)


def parent_equity(j: dict) -> float:
    """fnlttSinglAcntAll 응답 → 지배주주 자본. 비지배지분이 없으면(별도 등) 자본총계, 알 수 없으면 NaN."""
    from fundamentals_dart import to_amount
    bs = {x.get("account_id"): x for x in j.get("list") or [] if x.get("sj_div") == "BS"}
    if PARENT_ID in bs:
        return to_amount(bs[PARENT_ID]["thstrm_amount"])
    named = [x for x in bs.values() if "지배기업" in x["account_nm"] and "비지배" not in x["account_nm"]]
    if named:
        return to_amount(named[0]["thstrm_amount"])
    if "ifrs-full_NoncontrollingInterests" not in bs and "ifrs-full_Equity" in bs:
        return to_amount(bs["ifrs-full_Equity"]["thstrm_amount"])
    return np.nan


def compute(asof, tickers, marcap: dict, closes: pd.DataFrame, shorts: pd.DataFrame,
            inst_sum: dict, equity: dict, fx: dict) -> list:
    """네트워크·DB 없이 그날 신호를 계산한다 (테스트도 이걸 쓴다).
    closes: ticker, trade_date, close_price / shorts: ticker, trade_date, short_ratio
    inst_sum: {ticker: 최근 20거래일 기관 순매수 합} / equity: {ticker: (지배주주 자본, 통화)}
    fx: {통화: 원화 환율} (KRW는 1, 환율 없는 통화는 bp를 내지 않는다).
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
        amount, cur = equity.get(t, (np.nan, "KRW"))
        rate = 1.0 if cur == "KRW" else fx.get(cur, np.nan)
        if mc > 0:
            vals["bp"][t] = amount * rate / mc
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


def load_equity(tickers, asof) -> dict:
    """{ticker: (지배주주 자본, 통화)} - T에 알 수 있던 최신 보고서의 fnlttSinglAcntAll(연결, 없으면 별도).
    응답은 data/dart/fsall_{ticker}_{연도}_{보고서}.json 에 캐시 - 새 보고서가 나올 때만 호출한다."""
    from experiment_dart_events import CACHE_DIR, api_key, corp_codes
    from fundamentals_dart import DartStop, _get, fetch_all, load_fundamentals_panel
    try:
        key = api_key()
    except SystemExit:  # 키 없음
        key = ""
        log.warning("DART 키가 없어 캐시된 재무로만 bp 계산")
    if key:
        try:
            log.info("DART 재무 갱신: %s", fetch_all(tickers, key))
        except DartStop as e:
            log.error("DART 중단(%s) - 캐시된 재무로 bp 계산", e)
            key = ""
    panel = load_fundamentals_panel(fetch=False)
    latest = latest_reports(panel[panel["ticker"].isin(set(tickers))], asof)
    codes = corp_codes(key) if key else {}
    out, stats = {}, {"calls": 0, "fails": 0}
    for r in latest.itertuples():
        path = os.path.join(CACHE_DIR, f"fsall_{r.ticker}_{r.bsns_year}_{REPRT_BY_Q[r.quarter]}.json")
        j = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                j = json.load(f)
        elif key and r.ticker in codes:
            try:
                for fs_div in ("CFS", "OFS"):
                    j = _get("fnlttSinglAcntAll.json", {"crtfc_key": key, "corp_code": codes[r.ticker],
                             "bsns_year": str(r.bsns_year), "reprt_code": REPRT_BY_Q[r.quarter], "fs_div": fs_div}, stats)
                    if j is None or j["status"] == "000":
                        break
            except DartStop as e:
                log.error("DART 중단(%s) - 남은 종목은 bp 없이 기록", e)
                key = ""
            if j and j["status"] == "000":
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(j, f, ensure_ascii=False)
        if j and j.get("status") == "000":
            out[r.ticker] = (parent_equity(j), r.currency)
    log.info("지배주주 자본 %d/%d 종목 (DART 호출 %d, 실패 %d)", len(out), len(tickers), stats["calls"], stats["fails"])
    return out


def load_fx(asof) -> dict:
    with db.get_conn() as conn:
        cur = conn.cursor()
        cur.execute("""SELECT usd_krw_close FROM market_indicators
                       WHERE trade_date <= %s AND usd_krw_close IS NOT NULL
                       ORDER BY trade_date DESC LIMIT 1""", (asof.date(),))
        row = cur.fetchone()
        cur.close()
    return {"USD": float(row[0])} if row else {}


def insert_rows(rows):
    if not rows:
        return
    with db.get_conn() as conn:
        cur = conn.cursor()
        cur.executemany("""INSERT IGNORE INTO signal_snapshots (trade_date, ticker, signal_name, signal_value, recorded_at)
                           VALUES (%s, %s, %s, %s, NOW())""", rows)
        cur.close()


def usable_gaps(rows, days, start) -> list:
    """평가에 쓸 수 있는 행(recorded_at < 다음 거래일 15:30, D19)이 MIN_NAMES 미만인 (날짜, 신호) 목록.
    rows: (trade_date, signal_name, recorded_at) / days: 거래일 정렬, 마지막이 이번 asof(오늘 short는 비는 게 정상이라 제외)
    start: signal_snapshots 기록 시작일. 최근 10거래일 중 start 이후만 본다."""
    counts = {}
    for d, s, at in rows:
        counts.setdefault((pd.Timestamp(d), s), []).append(pd.Timestamp(at))
    out = []
    for i in range(max(0, len(days) - 11), len(days) - 1):
        d, deadline = days[i], days[i + 1] + pd.Timedelta(hours=15, minutes=30)
        if d < pd.Timestamp(start):
            continue
        out += [(d.date(), s) for s in SIGNALS if sum(at < deadline for at in counts.get((d, s), [])) < MIN_NAMES]
    return out


def check_gaps(days):
    # recorded_at 은 DB NOW() - MySQL 시간대가 KST라고 가정한다
    with db.get_conn() as conn:
        cur = conn.cursor()
        cur.execute("SELECT MIN(trade_date) FROM signal_snapshots")
        start = cur.fetchone()[0]
        cur.execute("SELECT trade_date, signal_name, recorded_at FROM signal_snapshots WHERE trade_date >= %s",
                    (days[-min(11, len(days))].date(),))
        rows = cur.fetchall()
        cur.close()
    gaps = usable_gaps(rows, days, start) if start else []
    if gaps:
        log.warning("평가용 기록 부족(최근 10거래일, %d종목 미만): %s", MIN_NAMES,
                    ", ".join(f"{d} {s}" for d, s in gaps))


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
        equity, fx = load_equity(tickers, asof), load_fx(asof)
    except Exception:
        log.exception("재무 로드 실패 - bp 없이 기록")
        equity, fx = {}, {}
    rows = compute(asof, tickers, marcap, closes, load_shorts(days[-min(10, len(days))]),
                   fetch_inst_sum(tickers, days), equity, fx)
    insert_rows(rows)
    counts = {s: sum(r[2] == s for r in rows) for s in SIGNALS}
    # 공매도는 KRX T+2 공개라 저녁 실행에선 비는 게 정상(다음 날 아침 채움). 오래 비면 collect가 따로 ERROR
    short_note = " - 공매도는 다음 실행에서 채움" if counts["short_ratio"] < MIN_NAMES else ""
    level = logging.ERROR if min(v for s, v in counts.items() if s != "short_ratio") < MIN_NAMES else logging.INFO
    log.log(level, "신호 기록 %s 기준: %s (종목 %d)%s", asof.date(), counts, len(tickers), short_note)
    try:
        check_gaps(days)
    except Exception:
        log.exception("기록 누락 점검 실패")
    return len(rows)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    from collect import get_top_n_stocks
    record(get_top_n_stocks())
