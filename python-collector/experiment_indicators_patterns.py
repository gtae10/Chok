"""
experiment_indicators_patterns.py — "지표 사전·패턴 사전" 후보를 모델 입력으로 쓸 수 있는지 검증

후보 (전부 시가·고가·저가·종가·거래량만으로 계산):
  지표  stoch14   스토캐스틱 %K(14) — 윌리엄스 %R은 %K와 순위가 같아 따로 보지 않는다
        cci20     CCI(20)
        adx14     ADX(14) 추세 강도 / dmi14 = +DI - -DI 추세 방향
        atr14     ATR(14) / 종가 (변동성)
        mfi14     MFI(14) (거래량 반영 RSI)
        disp20    이격도 = 종가 / MA20
        cloud     일목균형표 구름대 대비 위치 = (종가 - 구름 상단) / 종가 (선행 26일)
  캔들  doji, hammer, invHammer, bullEngulf, bearEngulf, morningStar, eveningStar,
        threeWhite, threeBlack (그날 완성된 패턴 = 0/1 사건)

유니버스·가격: train_model과 같은 point-in-time(날짜별 시총 상위 N) — 생존편향 제거(README 11·12번).
  marcap의 시가·고가·저가·종가는 액면분할 미반영이라, 그날의 (수정종가 / 원종가) 비율을 곱해
  수정 OHLC를 만든다 — 하루 안의 네 값은 같은 기준이라 비율이 그대로 유지된다.

판정 기준 (실행 전 고정):
  지표  배포 기간(50일) 모델 IC >= 0.02, base보다 높고, IC>0 폴드 >= 7/10
        (experiment_new_features와 같은 기준 · 같은 walk-forward/purge · 날짜별 횡단면 순위 변환)
  캔들  사건 >= 200건, 그날 전 종목 평균 대비 평균 초과수익 |x| >= 0.5%, 같은 부호 폴드 >= 7/10
        (중앙값이 아니라 평균 대비 — 수익률은 오른쪽 꼬리가 길어 "수익률 − 중앙값"의 평균은
        아무 종목 묶음이나 양수로 나온다. 첫 실행에서 반대 패턴까지 전부 양수로 나와 바로잡음)
        (사건 연구 — 드문 0/1 사건이라 로지스틱 계수보다 사건 뒤 수익률을 직접 본다)
  후보 16개 × 기간 5개를 한꺼번에 보므로 일부 통과는 우연일 수 있다(다중비교).

실행: python experiment_indicators_patterns.py
결과: model/indicators_patterns_experiment_result.json (배포 모델 파일은 건드리지 않음)
"""
import json
import logging
import os
from datetime import datetime

import numpy as np
import pandas as pd

import experiment_new_features as enf
import train_model
from train_model import (
    build_dataset, dataset_for_horizon, make_time_folds, restrict_to_universe, ensure_marcap_files,
    FORWARD_DAYS, LABEL_MODE, N_FOLDS, PIT_TOP_N, PIT_YEARS_BACK,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [3, 5, 10, 20, FORWARD_DAYS]
INDICATORS = ["stoch14", "cci20", "adx14", "dmi14", "atr14", "mfi14", "disp20", "cloud"]
PATTERNS = ["doji", "hammer", "invHammer", "bullEngulf", "bearEngulf",
            "morningStar", "eveningStar", "threeWhite", "threeBlack"]
PATTERN_MIN_EVENTS = 200
PATTERN_MIN_EXCESS = 0.005
PATTERN_MIN_SAME_SIGN_FOLDS = 7
RESULT_PATH = os.path.join("model", "indicators_patterns_experiment_result.json")


def load_pit_ohlcv():
    """train_model.load_pit_price_history와 같은 유니버스·수정종가 + 수정 시가/고가/저가."""
    this_year = datetime.now().year
    years = range(this_year - PIT_YEARS_BACK, this_year + 1)
    ensure_marcap_files(years)
    cols = ["Date", "Code", "Market", "Marcap", "ChangesRatio", "Volume", "Open", "High", "Low", "Close"]
    m = pd.concat([pd.read_parquet(os.path.join(train_model.MARCAP_DIR, f"marcap-{y}.parquet"), columns=cols)
                   for y in years], ignore_index=True)
    m = m[m["Market"].isin(["KOSPI", "KOSDAQ"])]
    m["Date"] = pd.to_datetime(m["Date"])
    m["rank"] = m.groupby("Date")["Marcap"].rank(ascending=False, method="first")
    m = m[m["Code"].isin(m.loc[m["rank"] <= PIT_TOP_N, "Code"].unique())].sort_values(["Code", "Date"])
    m["close_price"] = m.groupby("Code")["ChangesRatio"].transform(lambda r: (1 + r.fillna(0) / 100).cumprod())
    adj = m["close_price"] / m["Close"].where(m["Close"] > 0)
    for c in ("Open", "High", "Low"):
        m[c.lower()] = m[c] * adj
    # 거래정지 등으로 시가가 0이면 캔들 모양이 없으니 종가로 채운다(몸통 0 → 패턴 미해당)
    m["open"] = m["open"].where(m["Open"] > 0, m["close_price"])
    price_df = m.rename(columns={"Code": "ticker", "Date": "trade_date", "Volume": "volume"})
    price_df["volume"] = price_df["volume"].astype(float)
    universe = price_df.loc[price_df["rank"] <= PIT_TOP_N, ["ticker", "trade_date"]]
    keep = ["ticker", "trade_date", "open", "high", "low", "close_price", "volume"]
    return price_df[keep].reset_index(drop=True), universe


def indicators_and_patterns(g: pd.DataFrame) -> pd.DataFrame:
    o, h, l, c, v = g["open"], g["high"], g["low"], g["close_price"], g["volume"]
    prev_c = c.shift(1)

    hh14, ll14 = h.rolling(14).max(), l.rolling(14).min()
    tp = (h + l + c) / 3
    tp_ma = tp.rolling(20).mean()
    mad = tp.rolling(20).apply(lambda x: np.abs(x - x.mean()).mean(), raw=True)

    tr = pd.concat([h - l, (h - prev_c).abs(), (l - prev_c).abs()], axis=1).max(axis=1)
    up, down = h.diff(), -l.diff()
    plus_dm = up.where((up > down) & (up > 0), 0.0)
    minus_dm = down.where((down > up) & (down > 0), 0.0)
    wilder = lambda s: s.ewm(alpha=1 / 14, adjust=False).mean()  # noqa: E731
    atr = wilder(tr)
    plus_di, minus_di = 100 * wilder(plus_dm) / atr, 100 * wilder(minus_dm) / atr
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di)

    flow = tp * v
    pos = flow.where(tp > tp.shift(1), 0.0).rolling(14).sum()
    neg = flow.where(tp < tp.shift(1), 0.0).rolling(14).sum()

    tenkan = (h.rolling(9).max() + l.rolling(9).min()) / 2
    kijun = (h.rolling(26).max() + l.rolling(26).min()) / 2
    span_a = ((tenkan + kijun) / 2).shift(26)
    span_b = ((h.rolling(52).max() + l.rolling(52).min()) / 2).shift(26)

    # 캔들 — 몸통·꼬리 비율로 판정, 반전형은 직전 5일 추세 조건을 건다
    body = (c - o).abs()
    rng = (h - l).replace(0, np.nan)
    upper = h - pd.concat([o, c], axis=1).max(axis=1)
    lower = pd.concat([o, c], axis=1).min(axis=1) - l
    bull, bear = c > o, c < o
    down5, up5 = c.shift(1) < c.shift(6), c.shift(1) > c.shift(6)
    small = body <= 0.3 * rng
    o1, c1, o2, c2 = o.shift(1), c.shift(1), o.shift(2), c.shift(2)
    body1, body2 = (c1 - o1).abs(), (c2 - o2).abs()
    rng2 = (h.shift(2) - l.shift(2)).replace(0, np.nan)

    return pd.DataFrame({
        "ticker": g["ticker"], "trade_date": g["trade_date"],
        "stoch14": (c - ll14) / (hh14 - ll14),
        "cci20": (tp - tp_ma) / (0.015 * mad),
        "adx14": wilder(dx),
        "dmi14": plus_di - minus_di,
        "atr14": atr / c,
        "mfi14": 100 - 100 / (1 + pos / neg),
        "disp20": c / c.rolling(20).mean(),
        "cloud": (c - pd.concat([span_a, span_b], axis=1).max(axis=1)) / c,
        "doji": body <= 0.1 * rng,
        "hammer": down5 & (lower >= 2 * body) & (upper <= 0.3 * body + 0.1 * rng) & (body > 0),
        "invHammer": down5 & (upper >= 2 * body) & (lower <= 0.3 * body + 0.1 * rng) & (body > 0),
        "bullEngulf": down5 & bull & (c1 < o1) & (o <= c1) & (c >= o1) & (body > body1),
        "bearEngulf": up5 & bear & (c1 > o1) & (o >= c1) & (c <= o1) & (body > body1),
        "morningStar": down5.shift(1, fill_value=False) & (c2 < o2) & (body2 >= 0.5 * rng2)
                       & (body1 <= 0.3 * body2) & bull & (c >= (o2 + c2) / 2),
        "eveningStar": up5.shift(1, fill_value=False) & (c2 > o2) & (body2 >= 0.5 * rng2)
                       & (body1 <= 0.3 * body2) & bear & (c <= (o2 + c2) / 2),
        "threeWhite": bull & (c1 > o1) & (c2 > o2) & (c > c1) & (c1 > c2) & (upper <= 0.3 * body) & ~small,
        "threeBlack": bear & (c1 < o1) & (c2 < o2) & (c < c1) & (c1 < c2) & (lower <= 0.3 * body) & ~small,
    })


def pattern_study(dataset: pd.DataFrame, horizon: int) -> dict:
    sub = dataset_for_horizon(dataset, horizon, LABEL_MODE).sort_values("trade_date")
    fwd = f"fwd_return_{horizon}"
    sub["excess"] = sub[fwd] - sub.groupby("trade_date")[fwd].transform("mean")
    folds = make_time_folds(sub, N_FOLDS)
    out = {}
    for p in PATTERNS:
        ev = sub[sub[p] == 1]
        fold_means = []
        for a, b in folds:
            e = ev[(ev["trade_date"] >= a) & (ev["trade_date"] < b)]
            if len(e) >= 20:
                fold_means.append(float(e["excess"].mean()))
        mean = float(ev["excess"].mean()) if len(ev) else None
        same_sign = sum(np.sign(x) == np.sign(mean) for x in fold_means) if mean else 0
        out[p] = {
            "events": int(len(ev)),
            "excessMean": round(mean, 4) if mean is not None else None,
            "hitRate": round(float(ev["label"].mean()), 4) if len(ev) else None,
            "foldsWithEvents": len(fold_means),
            "sameSignFolds": int(same_sign),
            "passed": bool(len(ev) >= PATTERN_MIN_EVENTS and mean is not None
                           and abs(mean) >= PATTERN_MIN_EXCESS
                           and same_sign >= PATTERN_MIN_SAME_SIGN_FOLDS),
        }
    return out


def main():
    price_df, universe = load_pit_ohlcv()
    log.info("marcap 로드: 한 번이라도 상위 %d 안에 든 종목 %d개", PIT_TOP_N, price_df["ticker"].nunique())

    extra = pd.concat([indicators_and_patterns(g.sort_values("trade_date"))
                       for _, g in price_df.groupby("ticker")], ignore_index=True)
    dataset = build_dataset(price_df[["ticker", "trade_date", "close_price", "volume"]], HORIZONS)
    dataset = dataset.merge(extra, on=["ticker", "trade_date"], how="left")
    dataset[INDICATORS] = dataset[INDICATORS].replace([np.inf, -np.inf], np.nan)
    dataset = dataset.dropna(subset=INDICATORS)  # 모든 후보가 같은 표본에서 비교되도록
    dataset[PATTERNS] = dataset[PATTERNS].astype(float)
    dataset = restrict_to_universe(dataset, universe, HORIZONS)
    for col in INDICATORS:  # 라벨이 "그날 중앙값 대비"라 특징도 그날 종목 간 순위(0~1)로
        dataset[col] = dataset.groupby("trade_date")[col].rank(pct=True)
    log.info("PIT 데이터셋: %d행, %s ~ %s, 날짜당 평균 %.1f종목", len(dataset),
             dataset["trade_date"].min().date(), dataset["trade_date"].max().date(),
             dataset.groupby("trade_date").size().mean())
    log.info("캔들 패턴 발생률: %s", {p: f"{dataset[p].mean() * 100:.2f}%" for p in PATTERNS})

    enf.NEW_FEATURES = INDICATORS  # evaluate_horizon의 후보 목록을 이번 지표로 바꿔서 그대로 재사용
    indicator_results, pattern_results = {}, {}
    for hz in HORIZONS:
        indicator_results[hz] = enf.evaluate_horizon(dataset, hz)
        pattern_results[hz] = pattern_study(dataset, hz)
        log.info("=== %d영업일: 지표 ===", hz)
        log.info("  %-14s %7s %8s %7s %9s %10s", "후보", "AUC", "IC평균", "IC표준", "IC>0폴드", "상하위10%차")
        for name, s in indicator_results[hz].items():
            log.info("  %-14s %7s %8s %7s %6s/%-2d %10s", name, s["aucMean"], s["icMean"], s["icStd"],
                     s["icPositiveFolds"], s["nFolds"],
                     f"{s['spreadMean'] * 100:.2f}%" if s["spreadMean"] is not None else None)
        log.info("=== %d영업일: 캔들 패턴(그날 평균 대비 초과수익) ===", hz)
        for p, s in pattern_results[hz].items():
            log.info("  %-12s 사건 %6d  초과 %+6.2f%%  상위절반 %5.1f%%  같은부호 %d/%d %s", p, s["events"],
                     (s["excessMean"] or 0) * 100, (s["hitRate"] or 0) * 100,
                     s["sameSignFolds"], s["foldsWithEvents"], "통과" if s["passed"] else "")

    deploy = indicator_results[FORWARD_DAYS]
    base_ic = deploy["base"]["icMean"] or 0
    ind_passed = {hz: [name for name, s in indicator_results[hz].items()
                       if name != "base" and not name.startswith("only:") and s["icMean"] is not None
                       and s["icMean"] >= enf.ADOPT_MIN_IC and s["icPositiveFolds"] >= enf.ADOPT_MIN_POSITIVE_FOLDS
                       and s["icMean"] > (indicator_results[hz]["base"]["icMean"] or 0)]
                  for hz in HORIZONS}
    pat_passed = {hz: [p for p, s in pattern_results[hz].items() if s["passed"]] for hz in HORIZONS}
    log.info("지표 채택 기준(IC>=%.2f, base %.3f 초과, IC>0 폴드>=%d) 통과: %s",
             enf.ADOPT_MIN_IC, base_ic, enf.ADOPT_MIN_POSITIVE_FOLDS, ind_passed)
    log.info("캔들 기준(사건>=%d, |초과|>=%.1f%%, 같은부호>=%d폴드) 통과: %s",
             PATTERN_MIN_EVENTS, PATTERN_MIN_EXCESS * 100, PATTERN_MIN_SAME_SIGN_FOLDS, pat_passed)

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"topN": PIT_TOP_N, "indicators": indicator_results, "patterns": pattern_results,
                   "indicatorPassed": ind_passed, "patternPassed": pat_passed},
                  f, ensure_ascii=False, indent=2, default=str)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
