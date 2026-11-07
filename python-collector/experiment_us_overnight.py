"""
experiment_us_overnight.py — "어젯밤 미국 시장 등락"이 오늘 한국 종목 간 수익률 차이를 설명하는지 검증

미국 지수 등락은 그날 모든 종목에 같은 값이라 횡단면 순위에 쓸 수 없다. 그래서 종목별 값으로 바꾼다:
  특징 = beta60(종목, 미국지수) × 미국지수 전날 수익률
  beta60은 과거 60거래일만으로 계산(당일 미포함).

시점: 한국 날짜 D에 대응하는 미국 세션은 "D보다 앞선 마지막 미국 거래일"(한국 시간 D일 새벽 6시경 마감).
  Chok은 08:30에 돌기 때문에 이 값은 한국장 시작 전에 알 수 있다.

대상 수익률(모두 그날 유니버스 안 횡단면 순위 상관 = IC):
  gap       전일 종가 → D 시가   (매매 불가, 연결 여부 확인용)
  intraday  D 시가 → D 종가      (08:30 판단 후 시가 매수 시 실제로 먹을 수 있는 부분)
  open5     D 시가 → D+4 종가

유니버스: 날짜별 시총 상위 PIT_TOP_N (train_model과 같음). 채택 기준(실행 전 고정):
  IC평균 >= 0.02 이고 시간순 10개 구간 중 IC>0 구간 >= 7.

실행: python experiment_us_overnight.py
결과: model/us_overnight_experiment_result.json (배포 모델 파일은 건드리지 않음)
"""
import json
import logging
import os

import FinanceDataReader as fdr
import numpy as np
import pandas as pd

from train_model import load_pit_price_history

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

US_INDEXES = {"sox": "^SOX", "nasdaq": "IXIC", "sp500": "US500"}
BETA_WINDOW = 60
TARGETS = ["gap", "intraday", "open5"]
N_FOLDS = 10
ADOPT_MIN_IC = 0.02
ADOPT_MIN_POSITIVE_FOLDS = 7
RESULT_PATH = os.path.join("model", "us_overnight_experiment_result.json")


def us_returns(start) -> pd.DataFrame:
    """미국 지수 일간 수익률 (미국 날짜 기준)."""
    out = {}
    for name, sym in US_INDEXES.items():
        close = fdr.DataReader(sym, start)["Close"]
        out[name] = close.pct_change()
    df = pd.DataFrame(out).dropna(how="all")
    df.index = pd.to_datetime(df.index).tz_localize(None)
    return df


def align_to_korea_dates(us: pd.DataFrame, kr_dates: pd.DatetimeIndex) -> pd.DataFrame:
    """한국 날짜 D마다 'D보다 앞선 마지막 미국 거래일'의 수익률을 붙인다 (같은 날짜는 미포함 = 아직 미마감)."""
    kr = pd.DataFrame({"trade_date": kr_dates.sort_values()})
    us = us.reset_index().rename(columns={"index": "us_date", "Date": "us_date"})
    us["us_date"] = pd.to_datetime(us["us_date"])
    us["key"] = us["us_date"] + pd.Timedelta(days=1)  # D-1 이하인 미국 날짜만 매칭되도록
    return pd.merge_asof(kr, us.sort_values("key"), left_on="trade_date", right_on="key",
                         direction="backward").drop(columns=["key"])


def per_ticker(g: pd.DataFrame, us_cols) -> pd.DataFrame:
    g = g.sort_values("trade_date").copy()
    ret = g["ChangesRatio"].fillna(0) / 100           # 분할 조정된 종가 수익률
    intraday = g["Close"] / g["Open"] - 1             # 같은 날 시가/종가라 분할 영향 없음
    g["gap"] = (1 + ret) / (1 + intraday) - 1
    g["intraday"] = intraday
    adj = g["close_price"]
    g["open5"] = adj.shift(-4) / adj * (1 + intraday) - 1  # D 시가 → D+4 종가
    for c in us_cols:
        cov = ret.rolling(BETA_WINDOW).cov(g[c])
        var = g[c].rolling(BETA_WINDOW).var()
        g[f"beta_{c}"] = (cov / var).shift(1)         # 당일 정보 미포함
        g[f"f_{c}"] = g[f"beta_{c}"] * g[c]
    return g


def daily_ic(ds: pd.DataFrame, feat: str, target: str) -> pd.Series:
    d = ds[["trade_date", feat, target]].dropna()
    return d.groupby("trade_date").apply(
        lambda x: x[feat].rank().corr(x[target].rank()) if len(x) >= 20 else np.nan).dropna()


def summarize(ic: pd.Series) -> dict:
    folds = np.array_split(ic.sort_index().values, N_FOLDS)
    return {"days": int(len(ic)), "icMean": round(float(ic.mean()), 4), "icStd": round(float(ic.std()), 4),
            "icPositiveFolds": int(sum(f.mean() > 0 for f in folds if len(f))), "nFolds": N_FOLDS}


def main():
    price_df, universe = load_pit_price_history(extra_cols=("Open", "Close", "ChangesRatio"))
    price_df = price_df[(price_df["Open"] > 0) & (price_df["Close"] > 0)]
    us = us_returns(price_df["trade_date"].min() - pd.Timedelta(days=10))
    aligned = align_to_korea_dates(us, pd.DatetimeIndex(price_df["trade_date"].unique()))
    us_cols = list(US_INDEXES)
    price_df = price_df.merge(aligned[["trade_date", *us_cols]], on="trade_date", how="left")

    ds = pd.concat([per_ticker(g, us_cols) for _, g in price_df.groupby("ticker")], ignore_index=True)
    ds = ds.merge(universe, on=["ticker", "trade_date"], how="inner")
    # 분할·거래정지 등으로 튀는 값 제거
    for t in TARGETS:
        ds.loc[ds[t].abs() > 0.5, t] = np.nan
    log.info("데이터셋: %d행, %s ~ %s", len(ds), ds["trade_date"].min().date(), ds["trade_date"].max().date())

    results, passed = {}, []
    for c in us_cols:
        for t in TARGETS:
            s = summarize(daily_ic(ds, f"f_{c}", t))
            # 미국이 크게 움직인 날(|수익률| >= 2%)만 따로
            big = ds[ds[c].abs() >= 0.02]
            s["bigMoveDays"] = summarize(daily_ic(big, f"f_{c}", t)) if len(big) else None
            results[f"{c}:{t}"] = s
            ok = s["icMean"] >= ADOPT_MIN_IC and s["icPositiveFolds"] >= ADOPT_MIN_POSITIVE_FOLDS
            if ok and t != "gap":
                passed.append(f"{c}:{t}")
            b = s["bigMoveDays"] or {}
            log.info("  %-16s IC %7.4f  양수구간 %2d/%d  (%d일) | 미국 ±2%%일 IC %s (%s일)",
                     f"{c}:{t}", s["icMean"], s["icPositiveFolds"], N_FOLDS, s["days"],
                     b.get("icMean"), b.get("days"))

    log.info("채택 기준(IC>=%.2f, 양수구간>=%d, gap 제외) 통과: %s", ADOPT_MIN_IC, ADOPT_MIN_POSITIVE_FOLDS, passed)
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"results": results, "passed": passed}, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
