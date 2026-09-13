"""
experiment_tree_model.py — 트리 기반 모델(RandomForestClassifier) 재검증 실험

목적: 로지스틱 회귀로 5년치 walk-forward 검증 시 전 예측기간 AUC가 노이즈 수준
(0.49~0.51)이었던 결과가 "선형 모델의 한계"인지 "애초에 특징에 신호가 없는 것"인지
가려보기 위한 일회성 실험. train_model.py의 데이터 로딩/특징계산/폴드분할(purge 포함)
로직을 그대로 재사용해 로지스틱 회귀와 정확히 같은 조건(같은 폴드, 같은 8개 특징)으로
비교한다.

과적합 경계: 명목 샘플 수는 수만~수십만이지만, 예측기간이 겹치는 라벨(예: 50일 라벨은
인접한 날짜 표본끼리 최대 49일이 겹침)이라 실질적인 독립 표본 수는 훨씬 적다. 트리
개수/깊이를 낮게, 리프 최소 샘플 수를 높게 고정해 이 노이즈에 트리가 억지로 맞춰지지
않도록 강하게 정규화한다 (max_depth=3, min_samples_leaf=200).

결과는 model/tree_experiment_result.json에 기록한다. 채택 여부와 무관하게 배포 모델
(rise_model*.json)에는 이 스크립트가 손대지 않는다.
"""
import json
import logging
import os

import numpy as np

from train_model import (
    load_price_history, load_market_indicators, compute_macro_features,
    build_dataset, dataset_for_horizon, make_time_folds,
    FEATURE_NAMES, MIN_FOLD_TRAIN, MIN_FOLD_TEST, N_FOLDS, LABEL_MODE,
    SWEEP_HORIZONS, MIN_SAMPLES, MODEL_OUTPUT_PATH,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

# 과적합 방지를 위한 보수적 설정 - "복잡한 모델을 던져보는" 식이 아니라 얕은 트리 +
# 큰 리프 최소샘플로 강하게 규제한 상태에서도 신호가 나오는지만 본다.
RF_PARAMS = dict(
    n_estimators=200,
    max_depth=3,
    min_samples_leaf=200,
    max_features="sqrt",
    n_jobs=-1,
    random_state=42,
)

# 채택 기준: 스윕 기간(12개) 중 몇 개 이상에서 RF AUC>=0.55 (사용자 지정)여야
# "일관된 개선"으로 볼지 - 우연한 단일 기간 반짝임과 구분하기 위해 최소 1/4 이상으로 설정.
ADOPTION_MIN_STRONG_HORIZONS = 3
ADOPTION_AUC_THRESHOLD = 0.55


def _fit_eval_pair(train_df, test_df, feature_names):
    """같은 train/test 분할로 로지스틱 회귀와 랜덤포레스트를 둘 다 학습/평가한다."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import roc_auc_score

    X_train = train_df[feature_names].values
    y_train = train_df["label"].values
    X_test = test_df[feature_names].values
    y_test = test_df["label"].values

    if len(set(y_test)) < 2:
        return None

    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    lr = LogisticRegression(max_iter=1000)
    lr.fit(X_train_scaled, y_train)
    lr_auc = float(roc_auc_score(y_test, lr.predict_proba(X_test_scaled)[:, 1]))

    # 트리 모델은 스케일링이 필요 없어 원본 특징으로 학습
    rf = RandomForestClassifier(**RF_PARAMS)
    rf.fit(X_train, y_train)
    rf_auc = float(roc_auc_score(y_test, rf.predict_proba(X_test)[:, 1]))

    return lr_auc, rf_auc


def walk_forward_compare(dataset_h) -> dict:
    """train_model.walk_forward_evaluate와 동일한 폴드분할+purge로 LR/RF를 나란히 평가."""
    dataset_h = dataset_h.sort_values("trade_date")
    folds = make_time_folds(dataset_h, N_FOLDS)
    if not folds:
        return {"nFolds": 0}

    lr_aucs, rf_aucs = [], []
    for train_end, test_end in folds:
        train_df = dataset_h[dataset_h["trade_date"] < train_end]
        test_df = dataset_h[(dataset_h["trade_date"] >= train_end) & (dataset_h["trade_date"] < test_end)]
        # purge: train_model.py의 walk_forward_evaluate와 동일한 로직
        train_df = train_df[train_df["target_date"] < train_end]

        if len(train_df) < MIN_FOLD_TRAIN or len(test_df) < MIN_FOLD_TEST:
            continue

        result = _fit_eval_pair(train_df, test_df, FEATURE_NAMES)
        if result is None:
            continue
        lr_auc, rf_auc = result
        lr_aucs.append(lr_auc)
        rf_aucs.append(rf_auc)

    if not lr_aucs:
        return {"nFolds": 0}

    return {
        "nFolds": len(lr_aucs),
        "lrAucMean": float(np.mean(lr_aucs)),
        "lrAucStd": float(np.std(lr_aucs)),
        "rfAucMean": float(np.mean(rf_aucs)),
        "rfAucStd": float(np.std(rf_aucs)),
    }


def main():
    log.info("=== 트리 기반 모델(RandomForest) 재검증 실험 시작 ===")
    price_df = load_price_history()
    market_df = load_market_indicators()
    macro_df = compute_macro_features(market_df) if not market_df.empty else None

    horizons = sorted(set(SWEEP_HORIZONS))
    dataset = build_dataset(price_df, horizons, macro_df)
    log.info("특징 계산 완료: %d행, 특징: %s", len(dataset), FEATURE_NAMES)

    rows = []
    for h in horizons:
        sub = dataset_for_horizon(dataset, h, LABEL_MODE)
        if len(sub) < MIN_SAMPLES:
            log.info("[%3d일] 샘플 부족(%d개)으로 스킵", h, len(sub))
            continue
        summary = walk_forward_compare(sub)
        if summary["nFolds"] == 0:
            log.info("[%3d일] 유효 폴드 없음으로 스킵", h)
            continue
        rows.append({"horizon": h, "samples": len(sub), **summary})
        log.info(
            "[%3d일] 로지스틱 AUC=%.4f(+-%.3f) | 랜덤포레스트 AUC=%.4f(+-%.3f) | 차이=%+.4f",
            h, summary["lrAucMean"], summary["lrAucStd"],
            summary["rfAucMean"], summary["rfAucStd"],
            summary["rfAucMean"] - summary["lrAucMean"],
        )

    strong_horizons = [r for r in rows if r["rfAucMean"] >= ADOPTION_AUC_THRESHOLD]
    adopt = len(strong_horizons) >= ADOPTION_MIN_STRONG_HORIZONS

    conclusion = (
        f"랜덤포레스트(max_depth=3, min_samples_leaf=200)가 로지스틱 회귀 대비 "
        f"일관된 개선을 보이지 않음 (AUC>={ADOPTION_AUC_THRESHOLD} 기간 {len(strong_horizons)}/{len(rows)}개, "
        f"기준 {ADOPTION_MIN_STRONG_HORIZONS}개 미달) - 배포 모델은 로지스틱 회귀 그대로 유지."
        if not adopt else
        f"랜덤포레스트가 {len(strong_horizons)}개 기간에서 AUC>={ADOPTION_AUC_THRESHOLD} 로 "
        f"일관된 개선을 보여 채택 검토 필요."
    )

    log.info("=" * 70)
    log.info("실험 결론: %s", conclusion)
    log.info("=" * 70)

    output_path = os.path.join(os.path.dirname(MODEL_OUTPUT_PATH) or ".", "tree_experiment_result.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump({
            "rfParams": RF_PARAMS,
            "labelMode": LABEL_MODE,
            "nFoldsConfigured": N_FOLDS,
            "adoptionCriterion": f"AUC>={ADOPTION_AUC_THRESHOLD} in >= {ADOPTION_MIN_STRONG_HORIZONS} horizons",
            "results": rows,
            "adopted": adopt,
            "conclusion": conclusion,
        }, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", output_path)


if __name__ == "__main__":
    main()
