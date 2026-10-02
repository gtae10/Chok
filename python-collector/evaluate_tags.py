"""
evaluate_tags.py — 태그별 시장 대비 성과 집계 (C1). 태그 정의·판정 기준의 정본은 docs/PROJECT_PLAN.md 4·5장.

두 가지를 나란히 본다.
  backtest : marcap PIT(날짜별 시총 상위 N)에서 태그를 과거 날짜마다 다시 계산한 결과 - 1차 필터.
             뉴스 태그는 과거 뉴스를 소급 수집할 수 없어 제외된다.
  live     : tag_snapshots(분석 때마다 실제 화면에 붙은 태그) + price_history - 최종 판정.

성과 = 태그 종목의 h영업일 뒤 수익률 - 그날 분석 종목 전체의 평균 ("시장 대비").
  중앙값이 아니라 평균 대비인 이유: 수익률은 오른쪽 꼬리가 길어 "수익률 - 중앙값"의 평균은 아무 종목
  묶음이나 양수로 나온다(첫 실행에서 가설이 반대인 태그까지 전부 +로 나와 바로잡음 - 보조지표 실험과 같은 함정).
판정은 겹치지 않는 창(h영업일 간격으로 뽑은 날짜)으로만 센다 - 매일의 관측은 h일씩 겹쳐서
따로 세면 표본 수가 부풀려진다. 모든 날짜 평균(allDatesMean)은 참고로만 같이 적는다.

실행: python evaluate_tags.py            (backtest + live)
      python evaluate_tags.py --live-only
결과: model/tag_evaluation_result.json
"""
import argparse
import json
import logging
import os

import numpy as np
import pandas as pd

import db
from train_model import load_pit_price_history

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [5, 20, 50]
RESULT_PATH = os.path.join("model", "tag_evaluation_result.json")

# ── 판정 기준 v1 (2026-09-30 고정, 실전 기록 시작 전) ── 바꾸면 PROJECT_PLAN.md 5장과 함께 바꾼다
EXPECTED_SIGN = {  # 사전 가설 - 반대 방향으로 나오면 크기와 무관하게 불통과
    "MOMENTUM_TOP20": +1, "LOW_VOL20": +1, "ISSUANCE_UP": -1,
    "BUYBACK": +1, "NEWS_POS": +1, "NEWS_NEG": -1, "BREAKOUT_52W": +1,
}
MIN_WINDOWS = 6               # 겹치지 않는 창 최소 개수
MIN_SAME_SIGN_SHARE = 0.7     # 가설 방향과 같은 부호인 창 비율
MIN_ABS_EXCESS = {5: 0.005, 20: 0.01, 50: 0.02}  # 창 평균 초과수익 크기 (왕복 거래비용 약 0.3% 위)

# ── 태그 규칙: TagService.compute(Java)와 같은 규칙. 한쪽을 바꾸면 TagService.VERSION과 함께 둘 다 ──
TOP_SHARE = 0.2
ISSUANCE_THRESHOLD = 0.02


def top_share_mask(values: pd.Series, share: float) -> pd.Series:
    """TagService.topShareCutoff와 같음: 값 있는 종목 중 상위 ceil(n*share)번째 값 이상."""
    v = values.dropna()
    if v.empty:
        return pd.Series(False, index=values.index)
    k = max(1, int(np.ceil(len(v) * share)))
    cutoff = v.sort_values(ascending=False).iloc[k - 1]
    return values >= cutoff


def tags_for_day(day: pd.DataFrame) -> pd.DataFrame:
    """day: 한 날짜의 유니버스 (ticker, momentum12m, vol60, issuance252, gap52) -> (ticker, tag) 행."""
    masks = {
        "MOMENTUM_TOP20": top_share_mask(day["momentum12m"], TOP_SHARE),
        "LOW_VOL20": top_share_mask(-day["vol60"], TOP_SHARE),
        "ISSUANCE_UP": day["issuance252"] >= ISSUANCE_THRESHOLD,
        "BUYBACK": day["issuance252"] <= -ISSUANCE_THRESHOLD,
        "BREAKOUT_52W": day["gap52"] >= 0,
    }
    return pd.concat([pd.DataFrame({"ticker": day.loc[m.fillna(False), "ticker"], "tag": tag})
                      for tag, m in masks.items()], ignore_index=True)


def excess_returns(close: pd.DataFrame, universe: dict, h: int) -> pd.DataFrame:
    """close: 날짜 x 종목 종가(거래일 행). 그날 유니버스 평균 대비 h영업일 초과수익 (date, ticker, excess)."""
    fwd = close.shift(-h) / close - 1
    rows = []
    for date, tickers in universe.items():
        if date not in fwd.index:
            continue
        v = fwd.loc[date, fwd.columns.intersection(list(tickers))].dropna()
        if len(v) >= 10:
            rows.append(pd.DataFrame({"trade_date": date, "ticker": v.index, "excess": (v - v.mean()).values}))
    return pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=["trade_date", "ticker", "excess"])


def non_overlapping_dates(dates, trading_days: pd.Index, h: int) -> list:
    """정렬된 날짜 중 직전에 뽑은 날짜보다 h거래일 이상 뒤인 날짜만 순서대로 뽑는다."""
    pos = {d: i for i, d in enumerate(trading_days)}
    picked, last = [], None
    for d in sorted(dates):
        if d in pos and (last is None or pos[d] - last >= h):
            picked.append(d)
            last = pos[d]
    return picked


def summarize(tag_rows: pd.DataFrame, excess: pd.DataFrame, trading_days: pd.Index, h: int) -> dict:
    ev = tag_rows.merge(excess, on=["trade_date", "ticker"], how="inner")
    out = {}
    for tag in EXPECTED_SIGN:
        e = ev[ev["tag"] == tag]
        if e.empty:
            out[tag] = {"events": 0, "verdict": "기록 없음"}
            continue
        per_date = e.groupby("trade_date")["excess"].mean()
        windows = per_date.loc[non_overlapping_dates(per_date.index, trading_days, h)]
        mean = float(windows.mean()) if len(windows) else None
        same = int((np.sign(windows) == EXPECTED_SIGN[tag]).sum())
        passed = (len(windows) >= MIN_WINDOWS and mean is not None
                  and np.sign(mean) == EXPECTED_SIGN[tag] and abs(mean) >= MIN_ABS_EXCESS[h]
                  and same / len(windows) >= MIN_SAME_SIGN_SHARE)
        out[tag] = {
            "events": int(len(e)), "dates": int(per_date.size),
            "allDatesMean": round(float(per_date.mean()), 4),
            "windows": int(len(windows)), "windowMean": round(mean, 4) if mean is not None else None,
            "windowsExpectedSign": same,
            "verdict": "통과" if passed else ("창 부족" if len(windows) < MIN_WINDOWS else "불통과"),
        }
    return out


def backtest() -> dict:
    price_df, universe_df = load_pit_price_history(extra_cols=("Marcap",))
    price_df = price_df.sort_values(["ticker", "trade_date"])
    g = price_df.groupby("ticker")["close_price"]
    # TechnicalAnalysisService.momentum12m / volatility60, collect.compute_issuance와 같은 정의
    price_df["momentum12m"] = g.shift(21) / g.shift(252) - 1
    price_df["gap52"] = price_df["close_price"] / g.transform(lambda s: s.shift(1).rolling(252).max()) - 1
    price_df["vol60"] = g.transform(lambda s: s.pct_change().rolling(60).std())
    shares = price_df["Marcap"] / price_df["close_price"]
    price_df["issuance252"] = np.log(shares / shares.groupby(price_df["ticker"]).shift(252))
    price_df = price_df.replace([np.inf, -np.inf], np.nan)

    day_df = price_df.merge(universe_df, on=["ticker", "trade_date"], how="inner")
    tag_rows = pd.concat([tags_for_day(d).assign(trade_date=date) for date, d in day_df.groupby("trade_date")],
                         ignore_index=True)
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    universe = universe_df.groupby("trade_date")["ticker"].apply(set).to_dict()
    log.info("backtest: %s ~ %s, 태그 %d건", close.index.min().date(), close.index.max().date(), len(tag_rows))
    return {h: summarize(tag_rows, excess_returns(close, universe, h), close.index, h) for h in HORIZONS}


def live() -> dict:
    with db.get_conn() as conn:
        tag_rows = pd.read_sql("SELECT snap_date AS trade_date, ticker, tag FROM tag_snapshots", conn)
        recs = pd.read_sql("SELECT rec_date AS trade_date, ticker FROM recommendations", conn)
        prices = pd.read_sql("SELECT ticker, trade_date, close_price FROM price_history", conn)
    if tag_rows.empty:
        log.info("live: tag_snapshots 기록 없음 - 분석이 한 번 이상 끝나야 쌓이기 시작한다")
        return {}
    for df in (tag_rows, recs, prices):
        df["trade_date"] = pd.to_datetime(df["trade_date"])
    close = prices.pivot(index="trade_date", columns="ticker", values="close_price").astype(float).sort_index()
    # 유니버스 = 태그가 기록된 날짜에 분석된 종목 전체
    recs = recs[recs["trade_date"].isin(tag_rows["trade_date"].unique())]
    universe = recs.groupby("trade_date")["ticker"].apply(set).to_dict()
    log.info("live: %s ~ %s, 태그 %d건", tag_rows["trade_date"].min().date(),
             tag_rows["trade_date"].max().date(), len(tag_rows))
    return {h: summarize(tag_rows, excess_returns(close, universe, h), close.index, h) for h in HORIZONS}


def print_table(name: str, result: dict):
    for h, tags in result.items():
        log.info("=== %s %d영업일 (창 %d개 이상, |창 평균| >= %.1f%%, 가설 방향 >= %d%%) ===",
                 name, h, MIN_WINDOWS, MIN_ABS_EXCESS[h] * 100, MIN_SAME_SIGN_SHARE * 100)
        for tag, s in tags.items():
            if s["events"] == 0:
                log.info("  %-15s %s", tag, s["verdict"])
                continue
            log.info("  %-15s 가설 %+d | 사건 %6d | 창 %3d개 평균 %+6.2f%% (가설 방향 %d개) | 전체일 평균 %+6.2f%% | %s",
                     tag, EXPECTED_SIGN[tag], s["events"], s["windows"], (s["windowMean"] or 0) * 100,
                     s["windowsExpectedSign"], s["allDatesMean"] * 100, s["verdict"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live-only", action="store_true")
    args = parser.parse_args()

    result = {"horizons": HORIZONS, "criteria": {
        "minWindows": MIN_WINDOWS, "minSameSignShare": MIN_SAME_SIGN_SHARE,
        "minAbsExcess": MIN_ABS_EXCESS, "expectedSign": EXPECTED_SIGN}}
    if not args.live_only:
        result["backtest"] = backtest()
        print_table("backtest", result["backtest"])
    try:
        result["live"] = live()
        print_table("live", result["live"])
    except Exception as e:  # DB 접속 정보(DB_USER/DB_PASSWORD) 없이 돌려도 backtest는 남긴다
        log.error("live 집계 실패 - DB 접속 확인: %s", e)

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
