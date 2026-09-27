"""
experiment_new_features.py — 신규 특징 후보(장기 모멘텀/단기 반전/변동성) + 순위 기반 평가 실험

목적: 기존 8개 기술적 특징은 로지스틱/RF 모두 AUC 0.5 근처(신호 없음)였다. 모델이 아니라
입력이 문제라는 결론에 따라, 문헌상 반복 보고된 횡단면 신호 3개를 후보로 검증한다.
  - mom12_1   : 12-1개월 모멘텀 (t-252 ~ t-21 수익률, 최근 1개월 제외). 기존 momentum90과 다름
  - reversal5 : 1주 수익률 (단기 반전 효과 - 부호는 모델/IC가 알아서 드러냄)
  - vol60     : 60거래일 일간수익률 표준편차 (저변동성 효과)
신규 특징은 날짜별 횡단면 순위(0~1)로 변환한다 - 라벨이 "그날 중앙값 대비"라 특징도
그날 종목 간 상대 위치로 맞춰야 국면(상승장/하락장) 영향이 섞이지 않는다.

평가: AUC(기존 방식) + 대시보드 용도에 맞는 순위 지표를 함께 본다.
  - IC        : 날짜별 (모델 점수 vs 실제 미래수익률) 스피어만 순위상관의 폴드 평균
  - spread    : 날짜별 점수 상위 10% 평균수익률 - 하위 10% 평균수익률
채택 기준(실행 전 고정): 배포 기간(50일)에서 IC 평균 >= 0.02 이고 IC가 양(+)인 폴드가 7/10 이상.

train_model.py의 로딩/특징/폴드(purge 포함)를 그대로 재사용하고, 배포 모델 파일은 건드리지 않는다.
결과: model/new_features_experiment_result.json
"""
import json
import logging
import os

import numpy as np
import pandas as pd

from train_model import (
    load_price_history, build_dataset, dataset_for_horizon, make_time_folds, _fit_eval,
    FEATURE_NAMES, FORWARD_DAYS, LABEL_MODE, N_FOLDS, MIN_FOLD_TRAIN, MIN_FOLD_TEST,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [5, 10, 20, FORWARD_DAYS]
NEW_FEATURES = ["mom12_1", "reversal5", "vol60"]
ADOPT_MIN_IC = 0.02
ADOPT_MIN_POSITIVE_FOLDS = 7
RESULT_PATH = os.path.join("model", "new_features_experiment_result.json")


def add_new_features(dataset: pd.DataFrame, price_df: pd.DataFrame) -> pd.DataFrame:
    frames = []
    for ticker, g in price_df.groupby("ticker"):
        g = g.sort_values("trade_date")
        close = g["close_price"].astype(float)
        frames.append(pd.DataFrame({
            "ticker": ticker,
            "trade_date": g["trade_date"].values,
            "mom12_1": (close.shift(21) / close.shift(252) - 1).values,
            "reversal5": (close / close.shift(5) - 1).values,
            "vol60": close.pct_change().rolling(60).std().values,
        }))
    ds = dataset.merge(pd.concat(frames, ignore_index=True), on=["ticker", "trade_date"], how="left")
    ds[NEW_FEATURES] = ds[NEW_FEATURES].replace([np.inf, -np.inf], np.nan)
    # 기존/신규 모델이 정확히 같은 표본에서 비교되도록 신규 특징 결측 행은 한 번에 제거
    ds = ds.dropna(subset=NEW_FEATURES)
    for col in NEW_FEATURES:
        ds[col] = ds.groupby("trade_date")[col].rank(pct=True)
    return ds


def rank_metrics(test_df: pd.DataFrame, score, horizon: int):
    fwd = f"fwd_return_{horizon}"
    t = test_df[["trade_date", fwd]].assign(score=score)
    daily_ic = t.groupby("trade_date")[["score", fwd]].apply(
        lambda d: d["score"].corr(d[fwd], method="spearman") if len(d) >= 10 else np.nan
    ).dropna()
    t["q"] = t.groupby("trade_date")["score"].rank(pct=True)
    top = t[t["q"] > 0.9].groupby("trade_date")[fwd].mean()
    bottom = t[t["q"] <= 0.1].groupby("trade_date")[fwd].mean()
    spread = (top - bottom).dropna()
    return (float(daily_ic.mean()) if len(daily_ic) else None,
            float(spread.mean()) if len(spread) else None)


def evaluate_horizon(dataset: pd.DataFrame, horizon: int) -> dict:
    sub = dataset_for_horizon(dataset, horizon, LABEL_MODE).sort_values("trade_date")
    candidates = {"base": FEATURE_NAMES}
    candidates.update({f"+{f}": FEATURE_NAMES + [f] for f in NEW_FEATURES})
    candidates["+all3"] = FEATURE_NAMES + NEW_FEATURES

    per_fold = {name: [] for name in list(candidates) + [f"only:{f}" for f in NEW_FEATURES]}
    for train_end, test_end in make_time_folds(sub, N_FOLDS):
        train_df = sub[(sub["trade_date"] < train_end) & (sub["target_date"] < train_end)]  # purge
        test_df = sub[(sub["trade_date"] >= train_end) & (sub["trade_date"] < test_end)]
        if len(train_df) < MIN_FOLD_TRAIN or len(test_df) < MIN_FOLD_TEST:
            continue
        for name, feats in candidates.items():
            model, scaler, _, auc = _fit_eval(train_df, test_df, feats)
            score = model.predict_proba(scaler.transform(test_df[feats].values))[:, 1]
            ic, spread = rank_metrics(test_df, score, horizon)
            per_fold[name].append({"auc": auc, "ic": ic, "spread": spread})
        # 모델 없이 특징 순위 그대로의 IC - 신호 자체가 있는지(부호 포함) 확인용
        for f in NEW_FEATURES:
            ic, spread = rank_metrics(test_df, test_df[f].values, horizon)
            per_fold[f"only:{f}"].append({"auc": None, "ic": ic, "spread": spread})

    def summarize(rows):
        def mean(k):
            v = [r[k] for r in rows if r[k] is not None]
            return round(float(np.mean(v)), 4) if v else None
        ics = [r["ic"] for r in rows if r["ic"] is not None]
        return {"nFolds": len(rows), "aucMean": mean("auc"), "icMean": mean("ic"),
                "icStd": round(float(np.std(ics)), 4) if ics else None,
                "icPositiveFolds": sum(ic > 0 for ic in ics), "spreadMean": mean("spread")}

    return {name: summarize(rows) for name, rows in per_fold.items()}


def main():
    price_df = load_price_history()
    dataset = add_new_features(build_dataset(price_df, HORIZONS), price_df)
    log.info("실험 데이터셋: %d행, %s ~ %s", len(dataset),
             dataset["trade_date"].min().date(), dataset["trade_date"].max().date())

    results = {}
    for h in HORIZONS:
        results[h] = evaluate_horizon(dataset, h)
        log.info("=== %d영업일 ===", h)
        log.info("  %-14s %7s %8s %7s %9s %10s", "후보", "AUC", "IC평균", "IC표준", "IC>0폴드", "상하위10%차")
        for name, s in results[h].items():
            log.info("  %-14s %7s %8s %7s %6s/%-2d %10s", name, s["aucMean"], s["icMean"], s["icStd"],
                     s["icPositiveFolds"], s["nFolds"],
                     f"{s['spreadMean'] * 100:.2f}%" if s["spreadMean"] is not None else None)

    deploy = results[FORWARD_DAYS]
    adopted = [name for name, s in deploy.items()
               if name != "base" and not name.startswith("only:") and s["icMean"] is not None
               and s["icMean"] >= ADOPT_MIN_IC and s["icPositiveFolds"] >= ADOPT_MIN_POSITIVE_FOLDS
               and s["icMean"] > (deploy["base"]["icMean"] or 0)]
    log.info("채택 기준(%d일, IC>=%.2f, IC>0 폴드>=%d) 통과: %s",
             FORWARD_DAYS, ADOPT_MIN_IC, ADOPT_MIN_POSITIVE_FOLDS, adopted or "없음")

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"horizons": results, "adopted": adopted}, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
