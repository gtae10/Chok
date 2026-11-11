"""
evaluate_baseline.py — 현재 배포 모델(로지스틱, 8개 기술 특징)의 기준선 성능 (A단계 출발점).

train_model.py의 데이터(PIT 시총 상위 100, 상장폐지 포함)·특징·라벨(relative: 그날 유니버스 중앙값 초과)·
폴드(make_time_folds + purge)·학습(_fit_eval)을 그대로 써서, 폴드마다 과거로만 학습한 모델의
검증 구간 예측 확률을 모은 뒤 h = 5·20·50영업일 각각 아래를 잰다.

  AUC            : relative 라벨 기준. 전체 OOS 합친 값 + 폴드 평균(= train_model의 fullAucMean과 같은 계산)
  rank IC        : 날짜별 Spearman(예측 확률, h일 초과수익). 초과수익 = 그날 유니버스 동일가중 평균 대비
                   (evaluate_tags.excess_returns, D5). 날짜 안에서 상수를 빼는 것이라 순위 상관은 원수익률과 같다.
                   모든 날짜 버전(겹침 때문에 t가 부풀려짐, 참고용) + h거래일 간격 날짜만 쓴 버전(판정용).
  5분위 스프레드 : 날짜별 예측 5분위, 상위(Q5) - 하위(Q1) 평균 초과수익. 겹치지 않는 h일 간격 날짜만.
  Brier          : 예측 확률 vs 폴드 학습구간 양성비율(기저율)을 상수로 예측할 때.
  전략           : h일마다 상위 분위 / 상위 10종목 동일가중, 매 리밸런싱 왕복 0.3%(전량 교체 가정, 보수적) 차감,
                   유니버스 동일가중 대비 누적·연환산 초과수익, 상대 자산곡선 MDD.
  무작위 기준선  : 같은 날짜 안에서 예측을 섞은 결과(같은 지표) 1회 + N_SHUFFLES회 분포에서 실제 값의 위치.

실행: python evaluate_baseline.py   (DB 쓰기 없음. marcap 파일만 읽는다 - 올해 파일은 train_model 규칙대로 갱신 시도)
결과: model/baseline_metrics.json, model/experiment_log.jsonl 에 한 줄 append
"""
import json
import logging
import os
import time
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from evaluate_tags import excess_returns, non_overlapping_dates
from train_model import (
    FEATURE_NAMES, LABEL_MODE, MIN_FOLD_TEST, MIN_FOLD_TRAIN, N_FOLDS, PIT_TOP_N,
    _fit_eval, build_dataset, dataset_for_horizon, load_pit_price_history, make_time_folds,
    restrict_to_universe,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [5, 20, 50]
COST = 0.003          # 왕복 거래비용 (evaluate_tags MIN_ABS_EXCESS 주석과 같은 0.3%)
TOP_K = 10
N_QUANTILES = 5
N_SHUFFLES = 100
SEED = 42
RESULT_PATH = os.path.join("model", "baseline_metrics.json")
LOG_PATH = os.path.join("model", "experiment_log.jsonl")
# 근거(model/*_result.json + README 검증과정 1~15 + PROJECT_PLAN D14~D16)에서 후보 설정 수를 센 값.
# 기간(h) 반복은 세지 않음 - 세면 400개 이상.
PRIOR_ATTEMPTS_ESTIMATE = 105
PRIOR_ATTEMPTS_NOTE = (
    "후보 설정 수(기간 반복 제외) 대략: train_model 계열(로지스틱 기본·거래량 ablation·abs/rel 라벨·거시·RF·52주·상대강도·"
    "둘 조합·감성·공매도) ~10, new_features 7, pit_universe 7, size_turnover_issuance 7, indicators 18 + patterns 9, "
    "flow_short 12, us_overnight 9, dart_events 11, entry_evaluation 4, tag 백테스트 4, entry_round1 4, entry_round2 3 = 105. "
    "train_model 스윕 12개 기간 등 기간별로 세면 400+."
)


def walk_forward_predictions(sub: pd.DataFrame):
    """train_model.walk_forward_evaluate와 같은 폴드·purge·스킵 규칙으로 OOS 예측 확률을 모은다.
    반환: (preds: trade_date, ticker, label, p, p_base, fold), fold 정보 목록"""
    sub = sub.sort_values("trade_date")
    preds, folds = [], []
    for i, (train_end, test_end) in enumerate(make_time_folds(sub, N_FOLDS), start=1):
        train_df = sub[sub["trade_date"] < train_end]
        test_df = sub[(sub["trade_date"] >= train_end) & (sub["trade_date"] < test_end)]
        train_df = train_df[train_df["target_date"] < train_end]  # purge (train_model과 동일)
        if len(train_df) < MIN_FOLD_TRAIN or len(test_df) < MIN_FOLD_TEST:
            continue
        model, scaler, _, auc = _fit_eval(train_df, test_df, FEATURE_NAMES)
        p = model.predict_proba(scaler.transform(test_df[FEATURE_NAMES].values))[:, 1]
        base_rate = float(train_df["label"].mean())
        preds.append(test_df[["trade_date", "ticker", "label"]].assign(p=p, p_base=base_rate, fold=i))
        folds.append({
            "fold": i, "trainSize": len(train_df), "testSize": len(test_df), "auc": auc, "trainBaseRate": base_rate,
            "trainMaxDate": str(train_df["trade_date"].max().date()),
            "trainMaxTargetDate": str(train_df["target_date"].max().date()),
            "testStart": str(test_df["trade_date"].min().date()), "testEnd": str(test_df["trade_date"].max().date()),
        })
    return pd.concat(preds, ignore_index=True), folds


def rank_ic_by_date(df: pd.DataFrame, pred="p", target="excess") -> pd.Series:
    """날짜별 Spearman = 날짜 안 순위끼리의 Pearson."""
    g = df.groupby("trade_date")
    a = g[pred].rank() - g[pred].transform("size").add(1) / 2
    b = g[target].rank() - g[target].transform("size").add(1) / 2
    t = pd.DataFrame({"d": df["trade_date"], "ab": a * b, "aa": a * a, "bb": b * b}).groupby("d").sum()
    return (t["ab"] / np.sqrt(t["aa"] * t["bb"])).dropna()


def quantile_of(df: pd.DataFrame, pred="p", n=N_QUANTILES) -> pd.Series:
    """날짜별 예측 분위 1..n (n = 최상위)."""
    g = df.groupby("trade_date")[pred]
    return ((g.rank(method="first") - 1) / g.transform("size") * n).astype(int) + 1


def mean_t(s: pd.Series) -> dict:
    s = s.dropna()
    n = len(s)
    sd = float(s.std(ddof=1)) if n > 1 else None
    return {"n": n, "mean": float(s.mean()) if n else None, "std": sd,
            "t": float(s.mean() / sd * np.sqrt(n)) if sd else None}


def max_drawdown(wealth: np.ndarray) -> float:
    w = np.concatenate([[1.0], wealth])
    return float((w / np.maximum.accumulate(w) - 1).min())


def strategy(period_ret: pd.Series, bench_ret: pd.Series, h: int) -> dict:
    """period_ret: 리밸런싱 날짜별 보유 수익률(비용 차감 후), bench_ret: 같은 날짜 유니버스 동일가중 수익률."""
    ws, wb = np.cumprod(1 + period_ret.values), np.cumprod(1 + bench_ret.values)
    rel = ws / wb
    years = len(period_ret) * h / 252
    return {
        "periods": len(period_ret), "years": round(years, 2),
        "cumReturn": float(ws[-1] - 1), "benchCumReturn": float(wb[-1] - 1),
        "cumExcess": float(rel[-1] - 1), "annualExcess": float(rel[-1] ** (1 / years) - 1),
        "meanPeriodExcess": float((period_ret - bench_ret).mean()),
        "hitRate": float(((period_ret - bench_ret) > 0).mean()),
        "maxDrawdownRelative": max_drawdown(rel), "maxDrawdownAbsolute": max_drawdown(ws),
    }


def metrics(df: pd.DataFrame, rebal_dates: list, h: int, pred="p") -> dict:
    """df: trade_date, ticker, label, p(또는 pred), p_base, excess, fwd, bench"""
    ic = rank_ic_by_date(df, pred)
    q = quantile_of(df, pred)
    by_q = df.assign(q=q).groupby(["trade_date", "q"])["excess"].mean().unstack()
    spread = by_q[N_QUANTILES] - by_q[1]
    rb = df[df["trade_date"].isin(rebal_dates)]
    rq = quantile_of(rb, pred)
    top_k = rb.groupby("trade_date")[pred].rank(ascending=False, method="first") <= TOP_K
    bench = rb.groupby("trade_date")["bench"].first()
    return {
        "aucPooled": float(roc_auc_score(df["label"], df[pred])),
        "aucFoldMean": float(np.mean([roc_auc_score(g["label"], g[pred]) for _, g in df.groupby("fold")])),
        "rankIcAllDates": mean_t(ic),
        "rankIcNonOverlap": mean_t(ic[ic.index.isin(rebal_dates)]),
        "quintileMeanExcessNonOverlap": {int(k): float(v) for k, v in by_q.loc[by_q.index.isin(rebal_dates)].mean().items()},
        "spreadQ5minusQ1NonOverlap": mean_t(spread[spread.index.isin(rebal_dates)]),
        "spreadQ5minusQ1AllDates": mean_t(spread),
        "brier": float(((df[pred] - df["label"]) ** 2).mean()),
        "brierBaseRate": float(((df["p_base"] - df["label"]) ** 2).mean()),
        "strategyTopQuintile": strategy(rb[rq == N_QUANTILES].groupby("trade_date")["fwd"].mean() - COST, bench, h),
        "strategyTop10": strategy(rb[top_k].groupby("trade_date")["fwd"].mean() - COST, bench, h),
    }


def shuffle_within_date(df: pd.DataFrame, rng) -> np.ndarray:
    """df는 trade_date로 정렬돼 있어야 함. 같은 날짜 안에서만 p를 섞은 배열."""
    codes = df["trade_date"].values
    return df["p"].values[np.lexsort((rng.random(len(df)), codes))]


KEY_METRICS = {  # 무작위 분포와 비교할 핵심 지표
    "aucPooled": lambda m: m["aucPooled"],
    "rankIcNonOverlapMean": lambda m: m["rankIcNonOverlap"]["mean"],
    "spreadNonOverlapMean": lambda m: m["spreadQ5minusQ1NonOverlap"]["mean"],
    "top10AnnualExcess": lambda m: m["strategyTop10"]["annualExcess"],
    "topQuintileAnnualExcess": lambda m: m["strategyTopQuintile"]["annualExcess"],
}


def evaluate_horizon(dataset: pd.DataFrame, close: pd.DataFrame, universe: dict, h: int, rng) -> dict:
    sub = dataset_for_horizon(dataset, h, LABEL_MODE)
    preds, folds = walk_forward_predictions(sub)
    ex = excess_returns(close, universe, h)
    fwd = (close.shift(-h) / close - 1).stack().rename("fwd").reset_index()
    ex = ex.merge(fwd, on=["trade_date", "ticker"])
    ex["bench"] = ex["fwd"] - ex["excess"]
    df = preds.merge(ex, on=["trade_date", "ticker"], how="inner").sort_values("trade_date", kind="stable")
    df = df.reset_index(drop=True)
    rebal = non_overlapping_dates(df["trade_date"].unique(), close.index, h)

    actual = metrics(df, rebal, h)
    df["p_shuf"] = shuffle_within_date(df, rng)
    one_shuffle = metrics(df, rebal, h, pred="p_shuf")
    null = {k: [] for k in KEY_METRICS}
    for _ in range(N_SHUFFLES):
        df["p_shuf"] = shuffle_within_date(df, rng)
        m = metrics(df, rebal, h, pred="p_shuf")
        for k, f in KEY_METRICS.items():
            null[k].append(f(m))
    null_summary = {}
    for k, vals in null.items():
        v, a = np.array(vals), KEY_METRICS[k](actual)
        null_summary[k] = {"actual": a, "nullMean": float(v.mean()), "nullStd": float(v.std()),
                           "null2.5%": float(np.quantile(v, 0.025)), "null97.5%": float(np.quantile(v, 0.975)),
                           "shareNullGeActual": float((v >= a).mean())}
    return {
        "horizon": h, "samples": int(len(sub)), "oosPredictions": int(len(preds)), "evaluatedRows": int(len(df)),
        "period": {"datasetStart": str(sub["trade_date"].min().date()), "datasetEnd": str(sub["trade_date"].max().date()),
                   "oosStart": str(df["trade_date"].min().date()), "oosEnd": str(df["trade_date"].max().date())},
        "oosDates": int(df["trade_date"].nunique()), "rebalanceDates": len(rebal),
        "folds": folds, "metrics": actual, "shuffledOnce": one_shuffle,
        "shuffleNull": {"nShuffles": N_SHUFFLES, "metrics": null_summary},
    }


def append_experiment_log(entry: dict):
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    lines = []
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, encoding="utf-8") as f:
            lines = [ln for ln in f if ln.strip()]
    if not lines:
        entry = {"prior_attempts_estimate": PRIOR_ATTEMPTS_ESTIMATE, "prior_attempts_note": PRIOR_ATTEMPTS_NOTE, **entry}
    entry["attempt"] = len(lines) + 1
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def main():
    started = time.time()
    rng = np.random.default_rng(SEED)
    price_df, universe_df = load_pit_price_history()
    dataset = restrict_to_universe(build_dataset(price_df, HORIZONS), universe_df, HORIZONS)
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    universe = universe_df.groupby("trade_date")["ticker"].apply(set).to_dict()
    log.info("데이터: %d행, %s ~ %s", len(dataset), dataset["trade_date"].min().date(), dataset["trade_date"].max().date())

    results = {}
    for h in HORIZONS:
        log.info("[%d일] walk-forward 예측 + 지표 + 무작위 %d회...", h, N_SHUFFLES)
        r = evaluate_horizon(dataset, close, universe, h, rng)
        m = r["metrics"]
        log.info("[%d일] AUC %.4f(폴드평균 %.4f) | IC(겹침X) %+.4f t=%.2f | Q5-Q1 %+.2f%% | Brier %.5f vs 기저율 %.5f | "
                 "상위10 연 %+.2f%% MDD %.1f%%", h, m["aucPooled"], m["aucFoldMean"], m["rankIcNonOverlap"]["mean"],
                 m["rankIcNonOverlap"]["t"], m["spreadQ5minusQ1NonOverlap"]["mean"] * 100, m["brier"], m["brierBaseRate"],
                 m["strategyTop10"]["annualExcess"] * 100, m["strategyTop10"]["maxDrawdownRelative"] * 100)
        if m["aucPooled"] > 0.6 or abs(m["rankIcNonOverlap"]["mean"]) > 0.1:
            log.warning("[%d일] 비현실적으로 좋음 - 누수 의심", h)
        results[str(h)] = r

    out = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "config": {
            "model": "LogisticRegression(max_iter=1000) + StandardScaler (train_model._fit_eval)",
            "features": FEATURE_NAMES, "labelMode": LABEL_MODE,
            "label": "fwd_return_h > 그날 유니버스 중앙값 (train_model.restrict_to_universe)",
            "excessReturn": "h일 수익률 - 그날 유니버스(PIT 시총 상위 N) 동일가중 평균 (evaluate_tags.excess_returns)",
            "universe": f"PIT 일별 KOSPI+KOSDAQ 시총 상위 {PIT_TOP_N}, 상장폐지 포함 (marcap)",
            "folds": f"make_time_folds(N_FOLDS={N_FOLDS}) expanding window, purge target_date < train_end",
            "horizons": HORIZONS, "roundTripCost": COST, "costAssumption": "매 리밸런싱 전량 교체",
            "topK": TOP_K, "quantiles": N_QUANTILES, "benchmark": "유니버스 동일가중", "nShuffles": N_SHUFFLES, "seed": SEED,
        },
        "data": {"rows": int(len(dataset)), "tickers": int(dataset["ticker"].nunique()),
                 "start": str(dataset["trade_date"].min().date()), "end": str(dataset["trade_date"].max().date())},
        "horizons": results,
        "elapsedSec": round(time.time() - started, 1),
    }
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s (%.0f초)", RESULT_PATH, out["elapsedSec"])

    append_experiment_log({
        "time": out["createdAt"], "script": "evaluate_baseline.py", "featureSet": "base8 (FEATURE_NAMES)",
        "features": FEATURE_NAMES, "model": "logistic", "params": {"max_iter": 1000, "scaler": "standard",
                                                                    "labelMode": LABEL_MODE, "nFolds": N_FOLDS},
        "period": out["data"],
        "keyMetrics": {h: {"aucPooled": round(r["metrics"]["aucPooled"], 4),
                           "rankIcNonOverlap": round(r["metrics"]["rankIcNonOverlap"]["mean"], 4),
                           "rankIcNonOverlapT": round(r["metrics"]["rankIcNonOverlap"]["t"], 2),
                           "spreadNonOverlap": round(r["metrics"]["spreadQ5minusQ1NonOverlap"]["mean"], 4),
                           "top10AnnualExcessAfterCost": round(r["metrics"]["strategyTop10"]["annualExcess"], 4)}
                       for h, r in results.items()},
    })


if __name__ == "__main__":
    main()
