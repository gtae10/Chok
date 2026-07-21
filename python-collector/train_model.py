"""
train_model.py
- price_history 테이블에서 전 종목 시세를 읽어 특징(feature)을 계산하고,
  로지스틱 회귀로 상승 여부를 학습한다.
- 학습 결과(가중치/절편/정규화 통계)를 JSON으로 저장하면, Java(RiseProbabilityService)가
  그 파일을 읽어 실시간으로 sigmoid(w·((x-mean)/std) + b) 를 계산해 상승확률을 매긴다.

라벨은 두 가지 모드를 지원한다 (파일 상단 LABEL_MODE 설정):
- absolute : N일 뒤 종가가 지금보다 높았는가 (예전 방식) - 시장 전체 상승/하락 국면에 라벨이 크게 좌우됨
- relative : N일 뒤 수익률이 "그날 전체 종목 수익률의 중앙값"보다 높았는가 (기본값)
             평균이 아닌 중앙값을 쓰는 이유: 수익률 분포가 오른쪽으로 길게 늘어져 있어
             평균 기준으로 하면 기간이 길어질수록 클래스 불균형이 심해지는 문제가 있음
             시장 전체가 오르든 내리든 "그 안에서 상대적으로 잘한 종목"을 맞히는 문제로 바뀌어서
             국면 변화(베이스라인이 폴드마다 크게 요동치던 문제)에 덜 흔들릴 것으로 기대.

예측 기간(N일)도 여러 개를 한 번에 스윕해서 비교한다 (SWEEP_HORIZONS 설정).
실제 배포 모델은 그 중 FORWARD_DAYS로 지정한 기간 하나로 학습한다.
설정값은 전부 아래 "설정" 섹션에서 직접 수정하면 됨 (환경변수 불필요).

주의: 여기서 계산하는 7개 특징의 공식은
      Chok(Java)/src/main/java/com/project/Chok/service/TechnicalAnalysisService.java 의
      buildFeatureVector() 와 반드시 동일해야 한다. 한쪽만 고치면 예측이 어긋난다.
      (특징 계산 로직 자체는 이번에도 바뀌지 않았음 - 라벨/예측기간만 바뀜)
"""
import json
import logging
import os
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

import config
import db

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────
# 설정 - 여기 값들을 직접 바꿔서 실험하면 됨 (환경변수 설정 안 해도 됨)
# ─────────────────────────────────────────────────────────────
LABEL_MODE = "relative"          # "relative"(시장대비 상대수익률) 또는 "absolute"(그냥 상승여부)
FORWARD_DAYS = 50                # 실제 배포 모델이 쓸 예측 기간(영업일)
SWEEP_HORIZONS = [3, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100]  # 스윕(비교)해볼 기간 후보들
N_FOLDS = 5                      # walk-forward 폴드 개수
MIN_SAMPLES = 500                # 학습을 시도할 최소 샘플 수
MODEL_OUTPUT_PATH = os.path.join("model", "rise_model.json")
# ─────────────────────────────────────────────────────────────

FEATURE_NAMES = [
    "priceVsMa5", "ma5VsMa20", "ma20VsMa60", "rsiNorm",
    "macdHistNorm", "bbPercentB", "logVolumeRatio",
]

MIN_FOLD_TRAIN = 100
MIN_FOLD_TEST = 20


def load_price_history() -> pd.DataFrame:
    with db.get_conn() as conn:
        query = """
            SELECT ticker, trade_date, close_price, volume
            FROM price_history
            ORDER BY ticker, trade_date ASC
        """
        df = pd.read_sql(query, conn)
    df["trade_date"] = pd.to_datetime(df["trade_date"])
    df["close_price"] = df["close_price"].astype(float)
    df["volume"] = df["volume"].astype(float)
    return df


def compute_features_for_ticker(g: pd.DataFrame, horizons) -> pd.DataFrame:
    """티커 하나의 시계열(날짜 오름차순)에 대해 지표 + 특징 + 기간별 미래수익률을 계산한다."""
    close = g["close_price"]
    volume = g["volume"]

    ma5 = close.rolling(5).mean()
    ma20 = close.rolling(20).mean()
    ma60 = close.rolling(60).mean()

    diff = close.diff()
    gain = diff.clip(lower=0)
    loss = (-diff).clip(lower=0)
    avg_gain = gain.rolling(14).mean()
    avg_loss = loss.rolling(14).mean()
    rsi = 100 - (100 / (1 + avg_gain / avg_loss))
    rsi = rsi.where(avg_loss != 0, 100.0)

    # Java의 emaSeries()는 adjust=False EMA(첫 값 = 첫 종가)와 동일
    ema_fast = close.ewm(span=12, adjust=False).mean()
    ema_slow = close.ewm(span=26, adjust=False).mean()
    macd_line = ema_fast - ema_slow
    signal_line = macd_line.ewm(span=9, adjust=False).mean()
    macd_hist = macd_line - signal_line

    sma20 = close.rolling(20).mean()
    std20 = close.rolling(20).std(ddof=0)  # Java는 모표준편차(ddof=0) 사용
    bb_upper = sma20 + 2 * std20
    bb_lower = sma20 - 2 * std20
    bb_percent_b = (close - bb_lower) / (bb_upper - bb_lower)

    avg_volume20 = volume.rolling(20).mean()
    volume_ratio = volume / avg_volume20

    out = pd.DataFrame({
        "trade_date": g["trade_date"],
        "priceVsMa5": (close - ma5) / close,
        "ma5VsMa20": (ma5 - ma20) / ma20,
        "ma20VsMa60": (ma20 - ma60) / ma60,
        "rsiNorm": (rsi - 50) / 50,
        "macdHistNorm": macd_hist / close,
        "bbPercentB": bb_percent_b,
        "logVolumeRatio": np.log(volume_ratio.clip(lower=0.01)),
    })

    # 기간별 미래수익률 + 그 라벨이 실제로 참조하는 미래 날짜(target_date)
    # target_date는 폴드 분할 시 "학습 구간 라벨이 검증 구간 미래를 훔쳐보지 않게" purge하는 데 씀
    for h in horizons:
        future_close = close.shift(-h)
        out[f"fwd_return_{h}"] = (future_close - close) / close
        out[f"target_date_{h}"] = g["trade_date"].shift(-h)

    return out


def build_dataset(price_df: pd.DataFrame, horizons) -> pd.DataFrame:
    frames = []
    for ticker, g in price_df.groupby("ticker"):
        g = g.sort_values("trade_date").reset_index(drop=True)
        if len(g) < 65:  # MA60 + 여유
            continue
        frames.append(compute_features_for_ticker(g, horizons))

    if not frames:
        return pd.DataFrame()

    dataset = pd.concat(frames, ignore_index=True)
    # 특징 컬럼에 inf/NaN 있는 행만 제거 (기간별 수익률 컬럼은 기간마다 결측 범위가 달라서 따로 처리)
    dataset[FEATURE_NAMES] = dataset[FEATURE_NAMES].replace([np.inf, -np.inf], np.nan)
    dataset = dataset.dropna(subset=FEATURE_NAMES)

    # 기간별로 절대/상대 라벨 생성
    # 상대라벨은 평균(mean) 대신 중앙값(median) 기준으로 비교한다.
    # 주식 수익률은 오른쪽 꼬리가 긴 분포라(가끔 나오는 대박 종목이 평균을 끌어올림),
    # 평균을 기준으로 삼으면 기간이 길어질수록 "평균보다 잘한 종목"이 점점 소수가 되어
    # 클래스 불균형이 커지고 베이스라인이 기간에 비례해 계속 올라가는 착시가 생긴다.
    # 중앙값은 정의상 위/아래가 항상 절반씩이라 기간과 무관하게 클래스가 균형을 유지한다.
    for h in horizons:
        col = f"fwd_return_{h}"
        dataset[f"label_abs_{h}"] = (dataset[col] > 0).astype(float)
        dataset.loc[dataset[col].isna(), f"label_abs_{h}"] = np.nan

        market_median = dataset.groupby("trade_date")[col].transform("median")
        dataset[f"label_rel_{h}"] = (dataset[col] > market_median).astype(float)
        dataset.loc[dataset[col].isna(), f"label_rel_{h}"] = np.nan

    return dataset


def dataset_for_horizon(dataset: pd.DataFrame, horizon: int, label_mode: str) -> pd.DataFrame:
    label_col = f"label_{'rel' if label_mode == 'relative' else 'abs'}_{horizon}"
    sub = dataset.dropna(subset=[label_col]).copy()
    sub["label"] = sub[label_col]
    sub["target_date"] = sub[f"target_date_{horizon}"]
    return sub


def make_time_folds(dataset: pd.DataFrame, n_folds: int):
    """시간순으로 n_folds+1개 구간 경계를 잡아 expanding-window 폴드를 만든다.
    폴드 k: train = 경계[k] 이전 전체, test = [경계[k], 경계[k+1]) 구간.
    뒤로 갈수록 학습 데이터가 누적되어 늘어난다 (미래 데이터를 학습에 쓰는 일은 없음)."""
    dates = np.sort(dataset["trade_date"].unique())
    n = len(dates)
    if n < (n_folds + 1) * 10:
        return []

    edges = [dates[min(int(n * i / (n_folds + 1)), n - 1)] for i in range(n_folds + 2)]
    edges[-1] = dates[-1] + np.timedelta64(1, "D")  # 마지막 구간이 끝날짜를 포함하도록

    folds = []
    for k in range(n_folds):
        folds.append((edges[k + 1], edges[k + 2]))
    return folds


def _fit_eval(train_df, test_df, feature_names):
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import accuracy_score, roc_auc_score

    X_train = train_df[feature_names].values
    y_train = train_df["label"].values
    X_test = test_df[feature_names].values
    y_test = test_df["label"].values

    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    model = LogisticRegression(max_iter=1000)
    model.fit(X_train_scaled, y_train)

    accuracy, auc = None, None
    if len(test_df) > 0 and len(set(y_test)) > 1:
        pred = model.predict(X_test_scaled)
        proba = model.predict_proba(X_test_scaled)[:, 1]
        accuracy = float(accuracy_score(y_test, pred))
        auc = float(roc_auc_score(y_test, proba))
    return model, scaler, accuracy, auc


def walk_forward_evaluate(dataset_h: pd.DataFrame, verbose=True) -> dict:
    """여러 시점으로 나눠 반복 검증해서, 단일 split의 우연성을 줄인 평균 성능을 낸다."""
    dataset_h = dataset_h.sort_values("trade_date")
    no_volume_features = [f for f in FEATURE_NAMES if f != "logVolumeRatio"]

    folds = make_time_folds(dataset_h, N_FOLDS)
    if not folds:
        if verbose:
            log.warning("폴드를 만들기엔 거래일 수가 부족해 walk-forward 검증을 건너뜁니다.")
        return {"nFolds": 0, "folds": []}

    fold_results = []
    for i, (train_end, test_end) in enumerate(folds, start=1):
        train_df = dataset_h[dataset_h["trade_date"] < train_end]
        test_df = dataset_h[(dataset_h["trade_date"] >= train_end) & (dataset_h["trade_date"] < test_end)]

        # purge: 학습 샘플의 라벨이 참조하는 미래 날짜(target_date)가 검증 구간 시작(train_end) 이후면
        # 검증 구간의 미래 정보를 이미 훔쳐본 셈이므로 학습에서 제외한다.
        # (예측 기간이 길수록 이 경계 근처 샘플이 많아져서, 안 걸러내면 기간이 길수록
        #  AUC가 실제 예측력과 무관하게 계속 좋아 보이는 착시가 생긴다.)
        before_purge = len(train_df)
        train_df = train_df[train_df["target_date"] < train_end]
        purged = before_purge - len(train_df)

        if len(train_df) < MIN_FOLD_TRAIN or len(test_df) < MIN_FOLD_TEST:
            if verbose:
                log.info("  [폴드 %d] 데이터 부족으로 스킵 (train=%d, test=%d)", i, len(train_df), len(test_df))
            continue

        majority_class = train_df["label"].mode()[0]
        baseline_acc = float((test_df["label"] == majority_class).mean())

        _, _, full_acc, full_auc = _fit_eval(train_df, test_df, FEATURE_NAMES)
        _, _, nv_acc, nv_auc = _fit_eval(train_df, test_df, no_volume_features)

        if verbose:
            log.info(
                "  [폴드 %d] train=%d(purge %d) test=%d | 베이스라인=%.3f | 거래량제외 acc=%.3f auc=%s | 거래량포함 acc=%.3f auc=%s",
                i, len(train_df), purged, len(test_df), baseline_acc,
                nv_acc if nv_acc else -1, round(nv_auc, 3) if nv_auc else None,
                full_acc if full_acc else -1, round(full_auc, 3) if full_auc else None,
            )

        fold_results.append({
            "fold": i, "trainSize": len(train_df), "testSize": len(test_df),
            "baselineAccuracy": baseline_acc,
            "noVolumeAccuracy": nv_acc, "noVolumeAuc": nv_auc,
            "fullAccuracy": full_acc, "fullAuc": full_auc,
        })

    def agg(key):
        vals = [f[key] for f in fold_results if f.get(key) is not None]
        if not vals:
            return None, None
        return float(np.mean(vals)), float(np.std(vals))

    baseline_mean, baseline_std = agg("baselineAccuracy")
    nv_acc_mean, nv_acc_std = agg("noVolumeAccuracy")
    nv_auc_mean, nv_auc_std = agg("noVolumeAuc")
    full_acc_mean, full_acc_std = agg("fullAccuracy")
    full_auc_mean, full_auc_std = agg("fullAuc")

    return {
        "nFolds": len(fold_results),
        "folds": fold_results,
        "baselineAccuracyMean": baseline_mean, "baselineAccuracyStd": baseline_std,
        "noVolumeAccuracyMean": nv_acc_mean, "noVolumeAccuracyStd": nv_acc_std,
        "noVolumeAucMean": nv_auc_mean, "noVolumeAucStd": nv_auc_std,
        "fullAccuracyMean": full_acc_mean, "fullAccuracyStd": full_acc_std,
        "fullAucMean": full_auc_mean, "fullAucStd": full_auc_std,
    }


def sweep_horizons(dataset: pd.DataFrame, horizons, label_mode: str) -> dict:
    """예측 기간별로 walk-forward 검증을 돌려서 어느 기간이 그나마 신호가 있는지 비교한다."""
    log.info("=" * 70)
    log.info("예측 기간 스윕 시작 (라벨 모드: %s, 기간 후보: %s)", label_mode, horizons)
    log.info("=" * 70)

    rows = []
    for h in horizons:
        sub = dataset_for_horizon(dataset, h, label_mode)
        if len(sub) < MIN_SAMPLES:
            log.info("[%d일] 샘플 부족(%d개)으로 스킵", h, len(sub))
            continue
        log.info("[%d일] 샘플 %d개로 walk-forward 검증 중...", h, len(sub))
        summary = walk_forward_evaluate(sub, verbose=False)
        if summary["nFolds"] == 0:
            log.info("[%d일] 유효 폴드 없음, 스킵", h)
            continue
        rows.append({
            "horizon": h,
            "samples": len(sub),
            "baselineAcc": summary["baselineAccuracyMean"],
            "noVolumeAuc": summary["noVolumeAucMean"],
            "fullAuc": summary["fullAucMean"],
        })
        log.info(
            "[%d일] 베이스라인=%.3f | 거래량제외 AUC=%s | 거래량포함 AUC=%s",
            h, summary["baselineAccuracyMean"] or 0,
            round(summary["noVolumeAucMean"], 3) if summary["noVolumeAucMean"] else None,
            round(summary["fullAucMean"], 3) if summary["fullAucMean"] else None,
        )

    log.info("=" * 70)
    log.info("스윕 결과 요약 (AUC가 0.5보다 뚜렷이 높을수록 신호가 있다는 뜻)")
    for r in rows:
        log.info(
            "  %2d일 | 샘플 %6d | 거래량포함 AUC=%s",
            r["horizon"], r["samples"],
            round(r["fullAuc"], 3) if r["fullAuc"] else None
        )
    best = max(rows, key=lambda r: r["fullAuc"] or 0) if rows else None
    if best:
        log.info("가장 AUC가 높았던 기간: %d일 (AUC=%.3f)", best["horizon"], best["fullAuc"] or 0)
        if (best["fullAuc"] or 0) < 0.52:
            log.info("주의: 최고 AUC도 0.52 미만이면 사실상 어느 기간을 골라도 유의미한 신호는 없다고 보는 게 맞음")
    log.info("=" * 70)

    return {"labelMode": label_mode, "results": rows}


def train_final_model(dataset_h: pd.DataFrame, walk_forward_summary: dict, horizon: int, label_mode: str) -> dict:
    """평가는 walk-forward/스윕으로 이미 끝냈으니, 실제 배포용 계수는 가진 데이터 전체로 학습한다."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler

    X = dataset_h[FEATURE_NAMES].values
    y = dataset_h["label"].values

    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    model = LogisticRegression(max_iter=1000)
    model.fit(X_scaled, y)

    return {
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "sampleCount": int(len(dataset_h)),
        "positiveRate": float(dataset_h["label"].mean()),
        "forwardDays": horizon,
        "labelMode": label_mode,
        "featureNames": FEATURE_NAMES,
        "featureMeans": scaler.mean_.tolist(),
        "featureStds": scaler.scale_.tolist(),
        "weights": model.coef_[0].tolist(),
        "bias": float(model.intercept_[0]),
        "holdoutAccuracy": walk_forward_summary.get("fullAccuracyMean"),
        "holdoutAuc": walk_forward_summary.get("fullAucMean"),
        "baselineAccuracy": walk_forward_summary.get("baselineAccuracyMean"),
        "noVolumeAccuracy": walk_forward_summary.get("noVolumeAccuracyMean"),
        "noVolumeAuc": walk_forward_summary.get("noVolumeAucMean"),
        "walkForwardFolds": walk_forward_summary.get("nFolds"),
    }


def main():
    log.info("=== 상승확률 모델 학습 시작 (라벨 모드: %s) ===", LABEL_MODE)
    price_df = load_price_history()
    log.info("가격 데이터 로드 완료: %d행", len(price_df))

    horizons = sorted(set(SWEEP_HORIZONS + [FORWARD_DAYS]))
    dataset = build_dataset(price_df, horizons)
    log.info("특징 계산 완료: %d행 (지표 계산 가능한 구간만)", len(dataset))

    if len(dataset) < MIN_SAMPLES:
        log.warning(
            "샘플 수(%d)가 최소 기준(%d)에 못 미쳐 학습을 건너뜁니다. "
            "데이터가 더 쌓인 뒤 다시 시도하세요. (기존 모델 파일은 그대로 둡니다)",
            len(dataset), MIN_SAMPLES
        )
        sys.exit(0)

    sweep_result = sweep_horizons(dataset, horizons, LABEL_MODE)

    log.info("=== 배포 모델용 최종 검증 (기간=%d일, 라벨=%s) ===", FORWARD_DAYS, LABEL_MODE)
    deploy_dataset = dataset_for_horizon(dataset, FORWARD_DAYS, LABEL_MODE)
    if len(deploy_dataset) < MIN_SAMPLES:
        log.warning("배포 대상 기간(%d일)의 샘플이 부족해 모델 저장을 건너뜁니다.", FORWARD_DAYS)
        sys.exit(0)

    wf_summary = walk_forward_evaluate(deploy_dataset, verbose=True)
    if wf_summary.get("nFolds", 0) == 0:
        log.warning("유효한 폴드가 하나도 없어 검증을 완료하지 못했습니다.")
        sys.exit(0)

    log.info("=== 최종 배포 모델 학습 (전체 데이터 사용) ===")
    result = train_final_model(deploy_dataset, wf_summary, FORWARD_DAYS, LABEL_MODE)
    log.info(
        "학습 완료: 샘플=%d, walk-forward 평균 정확도=%s, 평균 AUC=%s",
        result["sampleCount"], result["holdoutAccuracy"], result["holdoutAuc"]
    )

    os.makedirs(os.path.dirname(MODEL_OUTPUT_PATH) or ".", exist_ok=True)
    with open(MODEL_OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    log.info("모델 저장 완료: %s", MODEL_OUTPUT_PATH)

    detail_path = os.path.join(os.path.dirname(MODEL_OUTPUT_PATH) or ".", "walk_forward_detail.json")
    with open(detail_path, "w", encoding="utf-8") as f:
        json.dump({"deployHorizon": wf_summary, "sweep": sweep_result}, f, ensure_ascii=False, indent=2)
    log.info("검증 상세 결과 저장: %s", detail_path)


if __name__ == "__main__":
    main()
