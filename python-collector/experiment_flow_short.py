"""
experiment_flow_short.py — 외국인·기관 순매수(수급)와 공매도 잔고가 이후 종목 간 수익률 차이를 설명하는지 검증

지금까지 탈락한 신호(뉴스 감정, 미국 시장, DART 공시)는 모두 "공개 정보"라 다음 날 시가에 이미 반영됐다.
수급은 "누가 사고 있는가"라 성격이 다르다.

특징 (날짜 D 기준, 종목별):
  foreign5 / foreign20   외국인 순매수 대금 5·20일 합 / 시가총액
  inst5 / inst20         기관 순매수 대금 5·20일 합 / 시가총액
  short_ratio            공매도 잔고 비중(%)   - KRX는 T+2에 공개하므로 2거래일 늦춰서 사용
  short_chg20            공매도 잔고 비중 20일 변화 (같은 2일 지연)
시점: 수급은 D 장 마감 후 공개 → Chok이 D 18:30 / D+1 08:30에 알고 D+1 시가에 매수.
대상: open5 = D+1 시가 → D+5 종가, open20 = D+1 시가 → D+20 종가.
평가: 날짜별 유니버스(시총 상위 PIT_TOP_N) 안 순위상관(IC), 시간순 10구간.
채택 기준(실행 전 고정): |IC평균| >= 0.02 이고 IC 부호가 평균과 같은 구간 >= 7/10.
  (공매도는 음의 IC 기대 - 잔고 많을수록 약세 - 라서 절댓값으로 본다.)
  주의: 2023-11 ~ 2025-03 공매도 전면 금지 기간이 포함돼 short_* 는 그 구간에서 거의 상수다.

인증: 환경변수 KRX_ID / KRX_PW (pykrx가 읽음). 응답은 data/krx/ 에 캐시.
실행: python experiment_flow_short.py
결과: model/flow_short_experiment_result.json
"""
import json
import logging
import os

import numpy as np
import pandas as pd
from pykrx import stock

from train_model import load_pit_price_history

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

CACHE_DIR = os.path.join("data", "krx")
START_YEAR = 2021
SHORT_LAG = 2
FEATURES = ["foreign5", "foreign20", "inst5", "inst20", "short_ratio", "short_chg20"]
TARGETS = {"open5": 5, "open20": 20}
N_FOLDS = 10
ADOPT_MIN_IC = 0.02
ADOPT_MIN_SAME_SIGN_FOLDS = 7
RESULT_PATH = os.path.join("model", "flow_short_experiment_result.json")


def cached(name, fetch):
    path = os.path.join(CACHE_DIR, name)
    if os.path.exists(path):
        return pd.read_pickle(path)
    df = fetch()
    df.to_pickle(path)
    return df


def fetch_flow(ticker, end):
    df = stock.get_market_trading_value_by_date(f"{START_YEAR}0101", end, ticker)
    return df[["외국인합계", "기관합계"]].rename(columns={"외국인합계": "foreign", "기관합계": "inst"})


def fetch_short(ticker, end):
    parts = []
    for y in range(START_YEAR, int(end[:4]) + 1):
        s = stock.get_shorting_balance_by_date(f"{y}0101", min(f"{y}1231", end), ticker)
        if len(s):
            parts.append(s[["비중"]].rename(columns={"비중": "short_ratio"}))
    return pd.concat(parts) if parts else pd.DataFrame(columns=["short_ratio"])


def daily_ic(ds, feat, target):
    d = ds[["trade_date", feat, target]].dropna()
    return d.groupby("trade_date").apply(
        lambda x: x[feat].rank().corr(x[target].rank()) if len(x) >= 20 else np.nan).dropna()


def main():
    if not (os.environ.get("KRX_ID") and os.environ.get("KRX_PW")):
        raise SystemExit("KRX_ID / KRX_PW 환경변수가 필요합니다")
    os.makedirs(CACHE_DIR, exist_ok=True)

    price_df, universe = load_pit_price_history(extra_cols=("Open", "Close", "Marcap"))
    price_df = price_df[(price_df["Open"] > 0) & (price_df["Close"] > 0)]
    end = price_df["trade_date"].max().strftime("%Y%m%d")
    tickers = sorted(price_df["ticker"].unique())

    rows = []
    for i, t in enumerate(tickers, 1):
        flow = cached(f"flow_{t}.pkl", lambda: fetch_flow(t, end))
        short = cached(f"short_{t}.pkl", lambda: fetch_short(t, end))
        g = price_df[price_df["ticker"] == t].sort_values("trade_date").set_index("trade_date")
        g = g.join(flow, how="left").join(short, how="left")
        adj_open = g["close_price"] * g["Open"] / g["Close"]
        out = pd.DataFrame(index=g.index)
        out["ticker"] = t
        for who in ("foreign", "inst"):
            for w in (5, 20):
                out[f"{who}{w}"] = g[who].rolling(w).sum() / g["Marcap"]
        sr = g["short_ratio"].shift(SHORT_LAG)
        out["short_ratio"] = sr
        out["short_chg20"] = sr - sr.shift(20)
        for name, h in TARGETS.items():
            out[name] = g["close_price"].shift(-h) / adj_open.shift(-1) - 1
        rows.append(out.reset_index())
        if i % 25 == 0:
            log.info("  수집 %d/%d", i, len(tickers))

    ds = pd.concat(rows, ignore_index=True).merge(universe, on=["ticker", "trade_date"], how="inner")
    for c in TARGETS:
        ds.loc[ds[c].abs() > 1.0, c] = np.nan
    log.info("데이터셋: %d행, %s ~ %s", len(ds), ds["trade_date"].min().date(), ds["trade_date"].max().date())

    results, passed = {}, []
    for f in FEATURES:
        for tname in TARGETS:
            ic = daily_ic(ds, f, tname)
            # 겹치는 기간 수익률이라 IC 일별값이 서로 상관 → 구간(폴드) 부호 일치로 안정성 판단
            folds = [x.mean() for x in np.array_split(ic.sort_index().values, N_FOLDS) if len(x)]
            m = float(ic.mean()) if len(ic) else float("nan")
            same = int(sum(np.sign(x) == np.sign(m) for x in folds))
            s = {"days": int(len(ic)), "icMean": round(m, 4), "sameSignFolds": same, "nFolds": len(folds),
                 "folds": [round(float(x), 4) for x in folds]}
            results[f"{f}:{tname}"] = s
            if abs(m) >= ADOPT_MIN_IC and same >= ADOPT_MIN_SAME_SIGN_FOLDS:
                passed.append(f"{f}:{tname}")
            log.info("  %-22s IC %+7.4f  같은부호 %2d/%d  (%d일)", f"{f}:{tname}", m, same, len(folds), len(ic))

    log.info("채택 기준(|IC|>=%.2f, 같은부호 구간>=%d) 통과: %s", ADOPT_MIN_IC, ADOPT_MIN_SAME_SIGN_FOLDS, passed)
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"results": results, "passed": passed}, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
