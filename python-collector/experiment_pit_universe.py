"""
experiment_pit_universe.py — 생존편향을 제거한 유니버스(point-in-time)로 experiment_new_features 재검증

문제: collect.py는 "오늘 기준" 시총 상위 종목의 5년치 가격을 받으므로, 과거 검증 대상이
"결국 대형주가 된 종목"으로 치우친다(README 검증 과정 11번). 모멘텀처럼 과거 수익률에
기대는 신호가 특히 부풀려진다.

해결: FinanceData/marcap(KRX 전 종목 일별 시총/가격, 로그인 불필요)으로 **각 날짜마다
그날의 KOSPI+KOSDAQ 시총 상위 TOP_N**만 표본으로 쓴다. 중간에 순위 밖으로 밀려난 종목도
그 기간에는 포함된다.
  - 가격: marcap의 Close는 액면분할 미반영 원가격이라, KRX 등락률(ChangesRatio - 분할 시
    조정된 기준가 대비)을 누적곱해 수정주가 지수를 만든다.
  - 라벨: 시장중앙값 대비 상대라벨을 유니버스 안에서 다시 계산한다.
  - 잔여 편향: 예측기간 안에 상장폐지된 종목은 미래수익률이 없어 빠진다(대형주라 드묾).

데이터 준비 (약 140MB, git에 올리지 않음):
  https://raw.githubusercontent.com/FinanceData/marcap/master/data/marcap-{연도}.parquet
  를 data/marcap에 받는다(train_model.ensure_marcap_files가 자동으로 받음). pyarrow 필요.
실행: python experiment_pit_universe.py [MARCAP_DIR]
결과: model/pit_universe_experiment_result.json (배포 모델 파일은 건드리지 않음)
"""
import json
import logging
import os
import sys

import train_model
from train_model import (
    build_dataset, dataset_for_horizon, make_time_folds, load_pit_price_history, restrict_to_universe,
    N_FOLDS, FORWARD_DAYS, LABEL_MODE, PIT_TOP_N as TOP_N,
)
from experiment_new_features import (
    HORIZONS, NEW_FEATURES, ADOPT_MIN_IC, ADOPT_MIN_POSITIVE_FOLDS,
    add_new_features, evaluate_horizon, rank_metrics,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

RESULT_PATH = os.path.join("model", "pit_universe_experiment_result.json")


def main():
    if len(sys.argv) > 1:
        train_model.MARCAP_DIR = sys.argv[1]
    price_df, universe = load_pit_price_history()
    log.info("marcap 로드: 한 번이라도 상위 %d 안에 든 종목 %d개 (현재 방식은 오늘 기준 %d개만)",
             TOP_N, price_df["ticker"].nunique(), TOP_N)

    dataset = add_new_features(build_dataset(price_df, HORIZONS), price_df)
    dataset = restrict_to_universe(dataset, universe, HORIZONS)
    log.info("PIT 데이터셋: %d행, %s ~ %s, 날짜당 평균 %.1f종목", len(dataset),
             dataset["trade_date"].min().date(), dataset["trade_date"].max().date(),
             dataset.groupby("trade_date").size().mean())

    results = {}
    for h in HORIZONS:
        results[h] = evaluate_horizon(dataset, h)
        log.info("=== %d영업일 ===", h)
        log.info("  %-14s %7s %8s %7s %9s %10s", "후보", "AUC", "IC평균", "IC표준", "IC>0폴드", "상하위10%차")
        for name, s in results[h].items():
            log.info("  %-14s %7s %8s %7s %6s/%-2d %10s", name, s["aucMean"], s["icMean"], s["icStd"],
                     s["icPositiveFolds"], s["nFolds"],
                     f"{s['spreadMean'] * 100:.2f}%" if s["spreadMean"] is not None else None)

    sub = dataset_for_horizon(dataset, FORWARD_DAYS, LABEL_MODE).sort_values("trade_date")
    log.info("=== %d영업일: 폴드별 mom12_1 단독 ===", FORWARD_DAYS)
    for a, b in make_time_folds(sub, N_FOLDS):
        t = sub[(sub["trade_date"] >= a) & (sub["trade_date"] < b)]
        ic, spread = rank_metrics(t, t["mom12_1"].values, FORWARD_DAYS)
        log.info("  %s~%s  IC=%+.3f  spread=%+.2f%%", str(a)[:10], str(b)[:10], ic, spread * 100)

    deploy = results[FORWARD_DAYS]
    passed = [name for name, s in deploy.items()
              if name != "base" and s["icMean"] is not None
              and s["icMean"] >= ADOPT_MIN_IC and s["icPositiveFolds"] >= ADOPT_MIN_POSITIVE_FOLDS]
    log.info("채택 기준(%d일, IC>=%.2f, IC>0 폴드>=%d) 통과: %s",
             FORWARD_DAYS, ADOPT_MIN_IC, ADOPT_MIN_POSITIVE_FOLDS, passed or "없음")

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"topN": TOP_N, "horizons": results, "passed": passed}, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
