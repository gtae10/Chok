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

주의: 여기서 계산하는 8개 배포 특징의 공식은
      Chok(Java)/src/main/java/com/project/Chok/service/TechnicalAnalysisService.java 의
      buildFeatureVector() 와 반드시 동일해야 한다. 한쪽만 고치면 예측이 어긋난다.
      아래 후보 특징(52주 신고가 근접도/상대강도/감성점수)은 ablation 검증 통과 전까지는
      Java에 반영하지 않는다 - 검증 통과한 것만 buildFeatureVector()에 추가한다
      (거시지표 ablation 때와 동일한 원칙).
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
N_FOLDS = 10                     # walk-forward 폴드 개수 (5년치로 데이터가 늘어나 5->10으로 확대)
MIN_SAMPLES = 500                # 학습을 시도할 최소 샘플 수
MODEL_OUTPUT_PATH = os.path.join("model", "rise_model.json")
# ─────────────────────────────────────────────────────────────

FEATURE_NAMES = [
    "priceVsMa5", "ma5VsMa20", "ma20VsMa60", "rsiNorm",
    "macdHistNorm", "bbPercentB", "logVolumeRatio", "momentum90",
]

MOMENTUM_PERIOD = 90  # Java TechnicalAnalysisService.MOMENTUM_PERIOD 와 반드시 동일해야 함

# 시장 전체(종목과 무관하게 그날 공통으로 적용되는) 거시 지표 후보 - ablation으로 검증 후
# 실제로 AUC가 개선될 때만 FEATURE_NAMES에 합류시킨다 (아래 ADOPT_MACRO_FEATURES 참고).
MACRO_FEATURE_NAMES = ["kospiMomentum20", "kosdaqMomentum20", "usdKrwChange20"]
MACRO_MOMENTUM_PERIOD = 20  # 거래일 기준 (MA_MID=20과 동일한 스케일 - 약 1개월)
ADOPT_MACRO_FEATURES = False

# 52주 신고가 근접도 - 현재가 / 최근 252거래일(약 1년) 최고가. 1에 가까울수록 신고가 근접.
# Java TechnicalAnalysisService.HIGH_52W_PERIOD 와 반드시 동일해야 한다.
HIGH52W_PERIOD = 252
HIGH52W_FEATURE = "high52wRatio"
ADOPT_HIGH52W = False

# 시장대비 상대강도 - 종목의 REL_STRENGTH_PERIOD일 수익률에서 "그날 전체 종목의 평균
# 수익률"을 뺀 값. 라벨(label_rel_*)에 쓰는 시장중앙값과 같은 날짜별 groupby 방식이지만,
# 여기서는 라벨이 아니라 입력 특징이라 평균(mean)을 쓴다(요청 사항 그대로).
REL_STRENGTH_PERIOD = 20  # 거래일 기준 (MA_MID=20과 동일한 스케일 - 약 1개월)
REL_STRENGTH_FEATURE = "relativeStrength20"
ADOPT_REL_STRENGTH = False

# 감성점수 특징(작업 2) - 아직 "배관 + 유효표본 확인" 단계라 항상 검증 게이트를 거친다.
# 데이터가 MIN_SENTIMENT_SAMPLES 이상 쌓여야 walk-forward 대상 후보에 들어간다(아래 main()의
# sentiment_enabled 계산 참고) - 사람이 뒤집는 스위치가 아니라 애초에 검증 자체가
# 불가능한 상태를 자동으로 감지해서 스킵하는 게이트.
SENTIMENT_LOOKBACK_DAYS = 7  # 예측 시점 이전(당일 미포함) 최근 며칠 평균을 쓸지
SENTIMENT_FEATURE = "sentimentAvg7d"
ADOPT_SENTIMENT = False  # 검증 통과해도 사람이 walk_forward_detail.json 보고 판단 후 수동 전환
MIN_SENTIMENT_SAMPLES = 500  # 다른 특징과 같은 MIN_SAMPLES 기준 재사용

if ADOPT_MACRO_FEATURES:
    FEATURE_NAMES = FEATURE_NAMES + MACRO_FEATURE_NAMES
if ADOPT_HIGH52W:
    FEATURE_NAMES = FEATURE_NAMES + [HIGH52W_FEATURE]
if ADOPT_REL_STRENGTH:
    FEATURE_NAMES = FEATURE_NAMES + [REL_STRENGTH_FEATURE]
if ADOPT_SENTIMENT:
    FEATURE_NAMES = FEATURE_NAMES + [SENTIMENT_FEATURE]

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


def load_market_indicators() -> pd.DataFrame:
    """종목과 무관하게 그날 하루에 공통으로 적용되는 시장 전체 지표 (하루 1행)."""
    with db.get_conn() as conn:
        query = "SELECT trade_date, kospi_close, kosdaq_close, usd_krw_close FROM market_indicators ORDER BY trade_date ASC"
        df = pd.read_sql(query, conn)
    df["trade_date"] = pd.to_datetime(df["trade_date"])
    return df


def compute_macro_features(market_df: pd.DataFrame) -> pd.DataFrame:
    """시장 전체 모멘텀 특징을 하루 1행짜리 시계열로 계산한다 (종목별 momentum90과 동일한
    "N일 전 대비 누적변화율" 공식, 종목이 아니라 지수/환율에 적용). trade_date로 종목별
    데이터셋에 조인한다."""
    p = MACRO_MOMENTUM_PERIOD
    out = pd.DataFrame({"trade_date": market_df["trade_date"]})
    out["kospiMomentum20"] = market_df["kospi_close"] / market_df["kospi_close"].shift(p) - 1
    out["kosdaqMomentum20"] = market_df["kosdaq_close"] / market_df["kosdaq_close"].shift(p) - 1
    out["usdKrwChange20"] = market_df["usd_krw_close"] / market_df["usd_krw_close"].shift(p) - 1
    return out


def load_sentiment_scores() -> pd.DataFrame:
    """감성점수 특징(작업 2) 원본 데이터. sentiment_data_quality='OK'인 것만 사용한다 -
    FALLBACK(2026-06-27~07-23 API 장애로 638건이 오염됐던 사고 이후 추가된 플래그)은 물론,
    이 컬럼이 도입되기 전의 과거 레코드(NULL)도 오염 여부를 확인할 방법이 없어 안전하게
    함께 제외한다."""
    with db.get_conn() as conn:
        query = """
            SELECT ticker, rec_date, sentiment_score
            FROM recommendations
            WHERE sentiment_data_quality = 'OK'
            ORDER BY ticker, rec_date ASC
        """
        df = pd.read_sql(query, conn)
    df["rec_date"] = pd.to_datetime(df["rec_date"])
    return df


def compute_sentiment_feature(dataset: pd.DataFrame, sentiment_df: pd.DataFrame) -> pd.DataFrame:
    """각 (ticker, trade_date) 행에 대해 "그 날짜 이전"(당일 미포함) SENTIMENT_LOOKBACK_DAYS일간의
    평균 감성점수를 계산해 SENTIMENT_FEATURE 컬럼으로 붙인다.

    미래 정보 유출 방지: 감성분석은 그날 뉴스를 그날 계산하므로, 당일 분까지 창(window)에
    포함하면 "장 마감 후에야 완성되는 그날 요약"을 예측 시점에 이미 아는 셈이 되어 누수다.
    rolling 계산 후 shift(1)로 당일을 반드시 창에서 밀어내 이 문제를 막는다.
    """
    dataset = dataset.copy()
    if sentiment_df.empty:
        dataset[SENTIMENT_FEATURE] = np.nan
        return dataset

    frames = []
    for ticker, g in sentiment_df.groupby("ticker"):
        daily = g.groupby("rec_date")["sentiment_score"].mean()  # 같은 날 여러 분석 실행 시 평균
        daily = daily.asfreq("D")  # 결측일을 NaN으로 채운 연속 달력 (rolling('N D')에 필요)
        rolling = daily.rolling(window=f"{SENTIMENT_LOOKBACK_DAYS}D", min_periods=1).mean()
        rolling = rolling.shift(1)  # 당일 제외 - "예측 시점 이전"만 남기기 위한 핵심 한 줄
        frames.append(pd.DataFrame({
            "ticker": ticker, "trade_date": rolling.index, SENTIMENT_FEATURE: rolling.values
        }))

    sentiment_feature_df = pd.concat(frames, ignore_index=True)
    dataset = dataset.merge(sentiment_feature_df, on=["ticker", "trade_date"], how="left")
    return dataset


def report_sentiment_availability(dataset: pd.DataFrame, sentiment_raw: pd.DataFrame):
    """감성점수 특징이 실제로 몇 건이나 유효한지 확인하고, 부족하면 현재 축적 페이스로
    대략 언제쯤 재시도할 만한지까지 보고한다 (sentiment_predictive_check.py와 같은 취지)."""
    valid_count = int(dataset[SENTIMENT_FEATURE].notna().sum())
    log.info("감성점수 특징(%s) 유효 표본: %d건 (검증 기준: %d건 이상)",
              SENTIMENT_FEATURE, valid_count, MIN_SENTIMENT_SAMPLES)

    if valid_count >= MIN_SENTIMENT_SAMPLES:
        log.info("-> 검증 가능 - 아래 스윕/ablation 결과의 'sentiment' 항목 참고")
        return

    log.info("-> 데이터 부족으로 이번 회차는 검증 보류 (ablation에서 'sentiment' 항목은 자동 스킵되고,"
              " 나머지 특징 검증/배포 모델 학습은 그대로 진행됨)")

    if sentiment_raw.empty or valid_count == 0:
        log.info("   'OK' 품질 감성분석 데이터가 사실상 없어 증가 추세를 추정할 수 없음.")
        return

    span_days = max((sentiment_raw["rec_date"].max() - sentiment_raw["rec_date"].min()).days, 1)
    rate_per_day = valid_count / span_days
    if rate_per_day <= 0:
        log.info("   증가 추세를 추정할 수 없음 (표본이 늘고 있지 않음).")
        return

    days_needed = (MIN_SENTIMENT_SAMPLES - valid_count) / rate_per_day
    eta = "하루 이내" if days_needed < 1 else f"약 {int(round(days_needed))}일 후"
    log.info(
        "   최근 %d일간 %d건 축적(하루 평균 %.1f건 페이스) - 이 페이스가 유지되면 "
        "%s에 %d건에 도달해 재시도 가능할 것으로 예상.",
        span_days, valid_count, rate_per_day, eta, MIN_SENTIMENT_SAMPLES,
    )


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

    high_252 = close.rolling(HIGH52W_PERIOD).max()

    out = pd.DataFrame({
        "ticker": g["ticker"],
        "trade_date": g["trade_date"],
        "priceVsMa5": (close - ma5) / close,
        "ma5VsMa20": (ma5 - ma20) / ma20,
        "ma20VsMa60": (ma20 - ma60) / ma60,
        "rsiNorm": (rsi - 50) / 50,
        "macdHistNorm": macd_hist / close,
        "bbPercentB": bb_percent_b,
        "logVolumeRatio": np.log(volume_ratio.clip(lower=0.01)),
        "momentum90": close / close.shift(MOMENTUM_PERIOD) - 1,
        HIGH52W_FEATURE: close / high_252,
    })

    # 상대강도 계산용 원재료(종목 자체의 N일 수익률) - 아직 시장평균을 빼기 전이라
    # FEATURE_NAMES에는 넣지 않는다. 시장평균은 전 종목을 concat한 뒤에야 계산 가능
    # (build_dataset에서 처리).
    out["_return20"] = close / close.shift(REL_STRENGTH_PERIOD) - 1

    # 기간별 미래수익률 + 그 라벨이 실제로 참조하는 미래 날짜(target_date)
    # target_date는 폴드 분할 시 "학습 구간 라벨이 검증 구간 미래를 훔쳐보지 않게" purge하는 데 씀
    for h in horizons:
        future_close = close.shift(-h)
        out[f"fwd_return_{h}"] = (future_close - close) / close
        out[f"target_date_{h}"] = g["trade_date"].shift(-h)

    return out


def build_dataset(price_df: pd.DataFrame, horizons, macro_df: pd.DataFrame = None,
                   sentiment_df: pd.DataFrame = None) -> pd.DataFrame:
    frames = []
    for ticker, g in price_df.groupby("ticker"):
        g = g.sort_values("trade_date").reset_index(drop=True)
        if len(g) < 65:  # MA60 + 여유
            continue
        frames.append(compute_features_for_ticker(g, horizons))

    if not frames:
        return pd.DataFrame()

    dataset = pd.concat(frames, ignore_index=True)

    # 거시 지표는 종목이 아니라 날짜 하나에 공통으로 적용되는 값이라 trade_date로 조인한다.
    # FEATURE_NAMES 기준 dropna보다 먼저 merge해야 한다 - ADOPT_MACRO_FEATURES=True일 때는
    # FEATURE_NAMES 자체에 macro 컬럼이 포함되므로, 그 전에 컬럼이 존재해야 아래 dropna가 동작한다.
    # (ADOPT_MACRO_FEATURES=False일 때는 macro 컬럼이 FEATURE_NAMES에 없으니 이 merge로 인해
    # baseline/기존 특징 학습 표본이 줄어들지 않는다 - ablation 비교 시에만 따로 dropna한다.)
    if macro_df is not None and not macro_df.empty:
        dataset = dataset.merge(macro_df, on="trade_date", how="left")

    # 상대강도: 라벨(label_rel_*)의 시장중앙값과 같은 날짜별 groupby 방식이지만, 특징이라
    # 평균(mean)을 쓴다. macro와 같은 이유로 FEATURE_NAMES dropna 전에 계산해야 한다.
    market_mean_return = dataset.groupby("trade_date")["_return20"].transform("mean")
    dataset[REL_STRENGTH_FEATURE] = dataset["_return20"] - market_mean_return
    dataset = dataset.drop(columns=["_return20"])

    if sentiment_df is not None:
        dataset = compute_sentiment_feature(dataset, sentiment_df)

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


def _fit_eval_candidate(train_df, test_df, extra_feature_names, enabled):
    """기존 FEATURE_NAMES(이미 non-null 보장됨)에 extra_feature_names를 더해 학습/평가한다.
    enabled=False거나 표본이 부족하면 (None, None) - 호출부가 이를 "이 후보는 이번엔
    검증 못 함"으로 해석한다."""
    if not enabled:
        return None, None
    train_c = train_df.dropna(subset=extra_feature_names)
    test_c = test_df.dropna(subset=extra_feature_names)
    if len(train_c) < MIN_FOLD_TRAIN or len(test_c) < MIN_FOLD_TEST:
        return None, None
    _, _, acc, auc = _fit_eval(train_c, test_c, FEATURE_NAMES + extra_feature_names)
    return acc, auc


def walk_forward_evaluate(dataset_h: pd.DataFrame, candidate_groups=None, verbose=True) -> dict:
    """여러 시점으로 나눠 반복 검증해서, 단일 split의 우연성을 줄인 평균 성능을 낸다.

    candidate_groups: {이름: (추가할 특징 컬럼 목록, 활성화 여부)} - 기존 FEATURE_NAMES에 각
    후보를 추가했을 때 AUC가 실제로 개선되는지 나란히 비교한다 (거시지표 ablation과 동일한
    원칙을 임의 개수의 후보로 일반화한 것 - macro/52주고가/상대강도/감성점수 전부 이 하나의
    메커니즘을 공유한다)."""
    candidate_groups = candidate_groups or {}
    dataset_h = dataset_h.sort_values("trade_date")
    no_volume_features = [f for f in FEATURE_NAMES if f != "logVolumeRatio"]

    folds = make_time_folds(dataset_h, N_FOLDS)
    if not folds:
        if verbose:
            log.warning("폴드를 만들기엔 거래일 수가 부족해 walk-forward 검증을 건너뜁니다.")
        return {
            "nFolds": 0, "folds": [],
            "candidates": {name: {"aucMean": None, "aucStd": None, "accuracyMean": None, "accuracyStd": None}
                           for name in candidate_groups},
        }

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

        candidate_fold = {}
        for name, (extra_features, enabled) in candidate_groups.items():
            c_acc, c_auc = _fit_eval_candidate(train_df, test_df, extra_features, enabled)
            candidate_fold[name] = {"accuracy": c_acc, "auc": c_auc}

        if verbose:
            cand_log = " | ".join(
                f"{name}={round(v['auc'], 3) if v['auc'] is not None else None}"
                for name, v in candidate_fold.items()
            )
            log.info(
                "  [폴드 %d] train=%d(purge %d) test=%d | 베이스라인=%.3f | 거래량제외 auc=%s | "
                "거래량포함 auc=%s%s%s",
                i, len(train_df), purged, len(test_df), baseline_acc,
                round(nv_auc, 3) if nv_auc is not None else None,
                round(full_auc, 3) if full_auc is not None else None,
                " | " if cand_log else "", cand_log,
            )

        fold_results.append({
            "fold": i, "trainSize": len(train_df), "testSize": len(test_df),
            "baselineAccuracy": baseline_acc,
            "noVolumeAccuracy": nv_acc, "noVolumeAuc": nv_auc,
            "fullAccuracy": full_acc, "fullAuc": full_auc,
            "candidates": candidate_fold,
        })

    def agg(key):
        vals = [f[key] for f in fold_results if f.get(key) is not None]
        if not vals:
            return None, None
        return float(np.mean(vals)), float(np.std(vals))

    def agg_candidate(name, metric):
        vals = [f["candidates"][name][metric] for f in fold_results
                if f["candidates"].get(name, {}).get(metric) is not None]
        if not vals:
            return None, None
        return float(np.mean(vals)), float(np.std(vals))

    baseline_mean, baseline_std = agg("baselineAccuracy")
    nv_acc_mean, nv_acc_std = agg("noVolumeAccuracy")
    nv_auc_mean, nv_auc_std = agg("noVolumeAuc")
    full_acc_mean, full_acc_std = agg("fullAccuracy")
    full_auc_mean, full_auc_std = agg("fullAuc")

    candidates_summary = {}
    for name in candidate_groups:
        auc_mean, auc_std = agg_candidate(name, "auc")
        acc_mean, acc_std = agg_candidate(name, "accuracy")
        candidates_summary[name] = {
            "aucMean": auc_mean, "aucStd": auc_std,
            "accuracyMean": acc_mean, "accuracyStd": acc_std,
        }

    return {
        "nFolds": len(fold_results),
        "folds": fold_results,
        "baselineAccuracyMean": baseline_mean, "baselineAccuracyStd": baseline_std,
        "noVolumeAccuracyMean": nv_acc_mean, "noVolumeAccuracyStd": nv_acc_std,
        "noVolumeAucMean": nv_auc_mean, "noVolumeAucStd": nv_auc_std,
        "fullAccuracyMean": full_acc_mean, "fullAccuracyStd": full_acc_std,
        "fullAucMean": full_auc_mean, "fullAucStd": full_auc_std,
        "candidates": candidates_summary,
    }


def sweep_horizons(dataset: pd.DataFrame, horizons, label_mode: str, candidate_groups=None) -> dict:
    """예측 기간별로 walk-forward 검증을 돌려서 어느 기간이 그나마 신호가 있는지,
    그리고 각 후보 특징이 여러 기간에 걸쳐 일관되게 개선되는지 비교한다."""
    candidate_groups = candidate_groups or {}
    log.info("=" * 70)
    log.info("예측 기간 스윕 시작 (라벨 모드: %s, 기간 후보: %s, 후보 특징: %s)",
              label_mode, horizons, list(candidate_groups.keys()))
    log.info("=" * 70)

    rows = []
    for h in horizons:
        sub = dataset_for_horizon(dataset, h, label_mode)
        if len(sub) < MIN_SAMPLES:
            log.info("[%3d일] 샘플 부족(%d개)으로 스킵", h, len(sub))
            continue
        log.info("[%3d일] 샘플 %d개로 walk-forward 검증 중...", h, len(sub))
        summary = walk_forward_evaluate(sub, candidate_groups=candidate_groups, verbose=False)
        if summary["nFolds"] == 0:
            log.info("[%3d일] 유효 폴드 없음, 스킵", h)
            continue

        row = {
            "horizon": h,
            "samples": len(sub),
            "baselineAcc": summary["baselineAccuracyMean"],
            "noVolumeAuc": summary["noVolumeAucMean"],
            "fullAuc": summary["fullAucMean"],
        }
        for name, cand in summary["candidates"].items():
            row[f"{name}Auc"] = cand["aucMean"]
        rows.append(row)

        cand_str = " | ".join(
            f"{name}={round(row[name + 'Auc'], 3) if row.get(name + 'Auc') is not None else None}"
            for name in candidate_groups
        )
        log.info(
            "[%3d일] 베이스라인=%.3f | 거래량제외 AUC=%s | 거래량포함 AUC=%s%s%s",
            h, summary["baselineAccuracyMean"] or 0,
            round(summary["noVolumeAucMean"], 3) if summary["noVolumeAucMean"] else None,
            round(summary["fullAucMean"], 3) if summary["fullAucMean"] else None,
            " | " if cand_str else "", cand_str,
        )

    log.info("=" * 70)
    log.info("스윕 결과 요약 (AUC가 0.5보다 뚜렷이 높을수록 신호가 있다는 뜻)")
    for r in rows:
        extras = " | ".join(
            f"{name}={round(r[name + 'Auc'], 3) if r.get(name + 'Auc') is not None else 'N/A'}"
            for name in candidate_groups
        )
        log.info("  %3d일 | 샘플 %6d | 거래량포함 AUC=%s%s%s",
                  r["horizon"], r["samples"],
                  round(r["fullAuc"], 3) if r["fullAuc"] else None,
                  " | " if extras else "", extras)
    best = max(rows, key=lambda r: r["fullAuc"] or 0) if rows else None
    if best:
        log.info("가장 AUC가 높았던 기간(기존 특징 기준): %d일 (AUC=%.3f)", best["horizon"], best["fullAuc"] or 0)
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


def horizon_model_path(horizon: int) -> str:
    """FORWARD_DAYS는 기존 호환을 위해 MODEL_OUTPUT_PATH(rise_model.json)를 그대로 쓰고,
    그 외 스윕 기간들은 기간을 파일명에 넣어 따로 저장한다 (rise_model_30d.json 식)."""
    model_dir = os.path.dirname(MODEL_OUTPUT_PATH) or "."
    return os.path.join(model_dir, f"rise_model_{horizon}d.json")


def train_and_save_horizon(dataset: pd.DataFrame, horizon: int, label_mode: str, output_path: str,
                            candidate_groups=None):
    """한 예측기간에 대해 walk-forward 검증 + 최종 모델 학습 + 저장까지 수행한다.
    샘플 부족이거나 유효 폴드가 하나도 없으면 저장을 건너뛰고 (None, None)을 반환한다."""
    sub = dataset_for_horizon(dataset, horizon, label_mode)
    if len(sub) < MIN_SAMPLES:
        log.info("[%d일] 샘플 부족(%d개)으로 배포 모델 학습 스킵", horizon, len(sub))
        return None, None

    wf_summary = walk_forward_evaluate(sub, candidate_groups=candidate_groups, verbose=True)
    if wf_summary.get("nFolds", 0) == 0:
        log.info("[%d일] 유효 폴드 없음으로 배포 모델 학습 스킵", horizon)
        return None, None

    result = train_final_model(sub, wf_summary, horizon, label_mode)
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    log.info(
        "[%d일] 배포 모델 저장 완료: %s (샘플=%d, holdoutAuc=%s)",
        horizon, output_path, result["sampleCount"], result.get("holdoutAuc"),
    )
    return result, wf_summary


def main():
    log.info("=== 상승확률 모델 학습 시작 (라벨 모드: %s) ===", LABEL_MODE)
    price_df = load_price_history()
    log.info("가격 데이터 로드 완료: %d행", len(price_df))

    market_df = load_market_indicators()
    macro_df = compute_macro_features(market_df) if not market_df.empty else None
    log.info("거시 지표 로드 완료: %d행", len(market_df))

    sentiment_raw = load_sentiment_scores()
    log.info("감성점수(OK 품질) 로드 완료: %d행", len(sentiment_raw))

    horizons = sorted(set(SWEEP_HORIZONS + [FORWARD_DAYS]))
    dataset = build_dataset(price_df, horizons, macro_df, sentiment_raw)
    log.info("특징 계산 완료: %d행 (지표 계산 가능한 구간만)", len(dataset))

    if len(dataset) < MIN_SAMPLES:
        log.warning(
            "샘플 수(%d)가 최소 기준(%d)에 못 미쳐 학습을 건너뜁니다. "
            "데이터가 더 쌓인 뒤 다시 시도하세요. (기존 모델 파일은 그대로 둡니다)",
            len(dataset), MIN_SAMPLES
        )
        sys.exit(0)

    log.info("=" * 70)
    report_sentiment_availability(dataset, sentiment_raw)
    sentiment_valid_count = int(dataset[SENTIMENT_FEATURE].notna().sum())
    sentiment_enabled = (not ADOPT_SENTIMENT) and sentiment_valid_count >= MIN_SENTIMENT_SAMPLES
    log.info("=" * 70)

    # 후보 ablation 그룹 - 이미 채택된 것(ADOPT_*=True)은 full==candidate라 비교가
    # 무의미하므로 자동으로 건너뛴다 (거시지표 때와 동일한 원칙).
    candidate_groups = {
        "macro": (MACRO_FEATURE_NAMES,
                  (not ADOPT_MACRO_FEATURES) and all(c in dataset.columns for c in MACRO_FEATURE_NAMES)),
        "high52w": ([HIGH52W_FEATURE], (not ADOPT_HIGH52W) and HIGH52W_FEATURE in dataset.columns),
        "relStrength": ([REL_STRENGTH_FEATURE],
                         (not ADOPT_REL_STRENGTH) and REL_STRENGTH_FEATURE in dataset.columns),
        "high52w+relStrength": (
            [HIGH52W_FEATURE, REL_STRENGTH_FEATURE],
            (not ADOPT_HIGH52W) and (not ADOPT_REL_STRENGTH)
            and HIGH52W_FEATURE in dataset.columns and REL_STRENGTH_FEATURE in dataset.columns,
        ),
        "sentiment": ([SENTIMENT_FEATURE], sentiment_enabled),
    }

    sweep_result = sweep_horizons(dataset, horizons, LABEL_MODE, candidate_groups)

    log.info("=== 기간별 배포 모델 학습 시작 (기간 %d개: %s) ===", len(horizons), horizons)
    deploy_wf_summary = None
    saved_count, skipped_count = 0, 0
    for h in horizons:
        output_path = MODEL_OUTPUT_PATH if h == FORWARD_DAYS else horizon_model_path(h)
        result, wf_summary = train_and_save_horizon(dataset, h, LABEL_MODE, output_path, candidate_groups)
        if result is None:
            skipped_count += 1
        else:
            saved_count += 1
            if h == FORWARD_DAYS:
                deploy_wf_summary = wf_summary

    log.info(
        "=== 기간별 모델 학습 완료: %d개 저장, %d개 스킵 (전체 %d개 기간) ===",
        saved_count, skipped_count, len(horizons)
    )
    if deploy_wf_summary is None:
        log.warning("기본 배포 기간(%d일) 모델은 저장되지 않았습니다 - 기존 rise_model.json은 그대로 둡니다.", FORWARD_DAYS)
    else:
        full_auc = deploy_wf_summary.get("fullAucMean")
        log.info("=" * 70)
        log.info("배포 기간(%d일) 기준 ablation 결과: 기존특징 AUC=%s",
                  FORWARD_DAYS, round(full_auc, 4) if full_auc is not None else None)
        for name, cand in deploy_wf_summary.get("candidates", {}).items():
            auc = cand.get("aucMean")
            if auc is None:
                log.info("  %s: 검증 불가(비활성 또는 표본 부족)", name)
                continue
            delta = auc - full_auc if full_auc is not None else None
            log.info("  %s 추가 AUC=%.4f (%s%.4f)", name, auc,
                      "+" if (delta is not None and delta >= 0) else "", delta if delta is not None else 0.0)
        log.info("=" * 70)

    detail_path = os.path.join(os.path.dirname(MODEL_OUTPUT_PATH) or ".", "walk_forward_detail.json")
    with open(detail_path, "w", encoding="utf-8") as f:
        json.dump({"deployHorizon": deploy_wf_summary, "sweep": sweep_result}, f, ensure_ascii=False, indent=2)
    log.info("검증 상세 결과 저장: %s", detail_path)


if __name__ == "__main__":
    main()
