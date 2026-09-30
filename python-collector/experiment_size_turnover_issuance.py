"""
experiment_size_turnover_issuance.py — 규모·회전율·순발행 팩터를 모델 입력으로 쓸 수 있는지 검증

가격 모양이 아니라 marcap의 시가총액·거래대금으로 만드는 후보 (기존 특징과 겹치지 않는 정보):
  size         log(시가총액) - 소형주 효과
  turnover20   20일 평균 (거래대금 / 시가총액) - 회전율(유동성·관심 과열)
  issuance252  1년간 주식수 변화 log(N_t / N_t-252) - 순발행(유상증자 +, 자사주 소각 -)
               N = 시가총액 / 수정종가지수. 상장주식수(Stocks)를 그대로 쓰면 액면분할 때
               수십 배로 튀므로, 분할에 연속인 수정종가로 나눠 실제 발행·소각만 남긴다.

유니버스·라벨·폴드(purge)·채택 기준은 experiment_indicators_patterns와 같다:
  날짜별 시총 상위 N(point-in-time), 특징은 날짜별 횡단면 순위(0~1)로 변환,
  IC >= 0.02 · base보다 높음 · IC>0 폴드 >= 7/10 (실행 전 고정). 후보 3개 × 기간 4개 동시 비교라
  일부 통과는 우연일 수 있다(다중비교).

실행: python experiment_size_turnover_issuance.py
결과: model/size_turnover_issuance_experiment_result.json (배포 모델 파일은 건드리지 않음)
"""
import json
import logging
import os

import numpy as np
import pandas as pd

import experiment_new_features as enf
from train_model import (
    build_dataset, load_pit_price_history, restrict_to_universe, FORWARD_DAYS, PIT_TOP_N,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [5, 10, 20, FORWARD_DAYS]
FACTORS = ["size", "turnover20", "issuance252"]
RESULT_PATH = os.path.join("model", "size_turnover_issuance_experiment_result.json")


def factors(g: pd.DataFrame) -> pd.DataFrame:
    shares = g["Marcap"] / g["close_price"]
    return pd.DataFrame({
        "ticker": g["ticker"], "trade_date": g["trade_date"],
        "size": np.log(g["Marcap"]),
        "turnover20": (g["Amount"] / g["Marcap"]).rolling(20).mean(),
        "issuance252": np.log(shares / shares.shift(252)),
    })


def main():
    price_df, universe = load_pit_price_history(extra_cols=("Marcap", "Amount"))
    extra = pd.concat([factors(g.sort_values("trade_date")) for _, g in price_df.groupby("ticker")],
                      ignore_index=True)
    dataset = build_dataset(price_df[["ticker", "trade_date", "close_price", "volume"]], HORIZONS)
    dataset = dataset.merge(extra, on=["ticker", "trade_date"], how="left")
    dataset[FACTORS] = dataset[FACTORS].replace([np.inf, -np.inf], np.nan)
    dataset = dataset.dropna(subset=FACTORS)  # 모든 후보가 같은 표본에서 비교되도록
    dataset = restrict_to_universe(dataset, universe, HORIZONS)
    for col in FACTORS:
        dataset[col] = dataset.groupby("trade_date")[col].rank(pct=True)
    log.info("PIT 데이터셋: %d행, %s ~ %s, 날짜당 평균 %.1f종목", len(dataset),
             dataset["trade_date"].min().date(), dataset["trade_date"].max().date(),
             dataset.groupby("trade_date").size().mean())

    enf.NEW_FEATURES = FACTORS  # evaluate_horizon의 후보 목록을 이번 팩터로 바꿔서 그대로 재사용
    results = {}
    for hz in HORIZONS:
        results[hz] = enf.evaluate_horizon(dataset, hz)
        log.info("=== %d영업일 ===", hz)
        log.info("  %-18s %7s %8s %7s %9s %10s", "후보", "AUC", "IC평균", "IC표준", "IC>0폴드", "상하위10%차")
        for name, s in results[hz].items():
            log.info("  %-18s %7s %8s %7s %6s/%-2d %10s", name, s["aucMean"], s["icMean"], s["icStd"],
                     s["icPositiveFolds"], s["nFolds"],
                     f"{s['spreadMean'] * 100:.2f}%" if s["spreadMean"] is not None else None)

    passed = {hz: [name for name, s in results[hz].items()
                   if name != "base" and not name.startswith("only:") and s["icMean"] is not None
                   and s["icMean"] >= enf.ADOPT_MIN_IC and s["icPositiveFolds"] >= enf.ADOPT_MIN_POSITIVE_FOLDS
                   and s["icMean"] > (results[hz]["base"]["icMean"] or 0)]
              for hz in HORIZONS}
    log.info("채택 기준(IC>=%.2f, 기간별 base 초과, IC>0 폴드>=%d) 통과: %s",
             enf.ADOPT_MIN_IC, enf.ADOPT_MIN_POSITIVE_FOLDS, passed)

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"topN": PIT_TOP_N, "horizons": results, "passed": passed},
                  f, ensure_ascii=False, indent=2, default=str)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
