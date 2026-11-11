"""
experiment_cross_section.py — B단계 1회차: 종목 간(cross-sectional) 순위 신호 사전 등록 실험 (PROJECT_PLAN.md D18)

[사전 등록 - 실행 전 고정. 바꾸면 2회차로 따로]
타깃: 신호 T 종가, 유니버스 = T 시점 marcap 시총 상위 100 (load_pit_price_history, PIT 2020~).
  진입 T+1 종가, 청산 T+1+N 종가, r = P(T+1+N)/P(T+1) - 1, e = r - 그날 유니버스 동일가중 평균,
  날짜 안 1·99분위 winsorize 후 z-score = y. N=20 판정, N=5·60은 보고만.
후보 (방향 고정, 단독 순위로 평가 - 모델 학습 없음):
  H1 bp        book_equity / Marcap(T)                 (+)
  H2 ep        ni_ttm / Marcap(T)                      (+)
  H3 op_roa    op_income_ttm / total_assets, 금융업 NaN (+)
  H4 ind_mom   같은 업종(KRX-DESC Industry, 없으면 KRX-DELISTING; data/krx_desc.csv 스냅샷)
               자기 제외 유니버스 종목 mom12_1 평균, 업종 내 유니버스 종목(자기 포함) 3 미만이면 NaN (+)
  H5 overnight 직전 252거래일(T 포함, 유효 200일 이상) gap 평균 × 252, gap = Open/(Close-Changes) - 1,
               Open=0이면 NaN (+)
  재무는 avail_date(최초 접수일 다음 거래일) <= T 인 가장 최근 값(merge_asof). 지난 분기 정정공시가
  더 새 분기 뒤에 오면 버린다(period_end가 그때까지 최대인 행만 사용).
확인용 (전 기간 이미 본 것 - 개발 기간 보고만, holdout 판정 금지):
  C1 issuance252 (-, experiment_size_turnover_issuance.factors), C2 short_ratio (-, T+2 지연,
  experiment_flow_short 캐시), C3 inst20 (-, 기관 20일 순매수/시총), C4 mom12_1 (+, experiment_new_features 정의)
진단: vol60 (연속 타깃에서 IC+ / 스프레드- 현상이 남는지)
지표: 날짜별 Spearman IC(피처 부호 맞춤, 날짜당 20종목 이상), make_time_folds 10폴드 IC(개발 기간 안 시간 구간),
  겹치지 않는 N일 창(evaluate_tags.non_overlapping_dates) IC 평균·t, 5분위 Q5-Q1 평균 초과수익(창, 원 e),
  0.3% 왕복 × 실제 회전율(직전 창 대비 바뀐 종목 비율, 첫 창 100%) 차감 스프레드, 커버리지(피처 결측률).
분할: 개발 신호 2021-01-01 ~ 2024-06-30, 청산일 >= 2024-07-01 표본 제거(purge). holdout 신호 2024-07-01 ~ 끝.
개발 통과 (H1~H5 각각, N=20): IC 평균 부호 = 가설, |IC| >= 0.02, 같은 부호 폴드 >= 7/10,
  Q5-Q1(비용 전) 같은 부호 >= 1.0%. Bonferroni(x5) p는 정보.
holdout: 개발 통과 후보만 딱 한 번(코드 게이트, 결과 json에 있으면 재계산 안 함):
  IC 부호 같음, IC >= 개발 IC x 0.5, Q5-Q1(비용 전) > 0.
합성: 개발 통과 >= 2개면 부호 맞춘 날짜별 순위 동일가중 합성 - holdout IC가 C4 단독보다 높아야 채택.

H1~H3은 fundamentals_dart.load_fundamentals_panel(fetch=False)가 필요. 없거나 비면 "데이터 대기".
실행: PYTHONUTF8=1 python experiment_cross_section.py
결과: model/cross_section_round1_result.json, model/experiment_log.jsonl 1줄 append
"""
import json
import logging
import math
import os
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd
from scipy import stats

import train_model as tm
from evaluate_tags import non_overlapping_dates
from experiment_size_turnover_issuance import factors as issuance_factors

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

HORIZONS = [5, 20, 60]
MAIN_N = 20
DEV_START, DEV_END, HOLDOUT_START = pd.Timestamp("2021-01-01"), pd.Timestamp("2024-06-30"), pd.Timestamp("2024-07-01")
HYPOTHESES = {"H1_bp": ("bp", +1), "H2_ep": ("ep", +1), "H3_op_roa": ("op_roa", +1),
              "H4_ind_mom": ("ind_mom", +1), "H5_overnight": ("overnight", +1)}
CONFIRM = {"C1_issuance": ("issuance252", -1), "C2_short_ratio": ("short_ratio", -1),
           "C3_inst20": ("inst20", -1), "C4_mom12_1": ("mom12_1", +1)}
FIN_FEATURES = {"bp", "ep", "op_roa"}
MIN_IC = 0.02
MIN_SAME_SIGN_FOLDS = 7
MIN_SPREAD = 0.01
HOLDOUT_IC_RATIO = 0.5
COST_ROUND_TRIP = 0.003
MIN_NAMES = 20
FUND_MIN_TICKER_SHARE = 0.8  # 데이터 준비 확인용(판정 임계값 아님)
N_FOLDS = 10
INDUSTRY_CSV = os.path.join("data", "krx_desc.csv")
RESULT_PATH = os.path.join("model", "cross_section_round1_result.json")
LOG_PATH = os.path.join("model", "experiment_log.jsonl")
PREREGISTERED = {
    "decision": "PROJECT_PLAN.md D18 (2026-11-11)",
    "target": "신호 T 종가, 유니버스 T 시점 시총 상위 100(PIT 2020~), r=P(T+1+N)/P(T+1)-1, e=r-유니버스 평균, "
              "날짜 안 1·99분위 winsorize 후 z=y. N=20 판정, 5·60 보고만",
    "candidates": {k: f"{v[0]} ({'+' if v[1] > 0 else '-'})" for k, v in HYPOTHESES.items()},
    "confirm": {k: f"{v[0]} ({'+' if v[1] > 0 else '-'}) - 보고만" for k, v in CONFIRM.items()},
    "diagnostic": "vol60",
    "split": "개발 신호 2021-01-01~2024-06-30(청산>=2024-07-01 제거), holdout 신호 2024-07-01~끝",
    "devPass": f"N={MAIN_N}: IC>={MIN_IC}(부호 맞춤), 같은 부호 폴드>={MIN_SAME_SIGN_FOLDS}/{N_FOLDS}, "
               f"창 Q5-Q1(비용 전)>={MIN_SPREAD:.1%}. Bonferroni x5 p는 정보",
    "holdout": f"개발 통과만 1회: IC 같은 부호, IC>=개발 IC x{HOLDOUT_IC_RATIO}, Q5-Q1(비용 전)>0",
    "composite": "개발 통과>=2개면 부호 맞춘 순위 동일가중 - holdout IC > C4 단독 holdout IC면 채택",
    "cost": f"{COST_ROUND_TRIP:.1%} 왕복 x 실제 회전율",
    "nCandidates": 9,
}


# ── 데이터 ──
def ticker_frame(g: pd.DataFrame) -> pd.DataFrame:
    """한 종목(날짜순) 가격 피처 + 타깃. 모든 shift는 그 종목의 거래일 행 기준."""
    c = g["close_price"]
    out = issuance_factors(g)[["ticker", "trade_date", "issuance252"]].copy()
    out["mom12_1"] = c.shift(21) / c.shift(252) - 1          # experiment_new_features 정의
    out["vol60"] = c.pct_change().rolling(60).std()
    base = g["Close"] - g["Changes"]                          # 전일 기준가(분할 시 조정됨)
    gap = (g["Open"] / base - 1).where((g["Open"] > 0) & (base > 0))
    out["overnight"] = gap.rolling(252, min_periods=200).mean() * 252
    entry = c.shift(-1)
    for n in HORIZONS:
        out[f"r_{n}"] = c.shift(-1 - n) / entry - 1
        out[f"exit_{n}"] = g["trade_date"].shift(-1 - n)
    return out


def flow_short_frame(g: pd.DataFrame) -> pd.DataFrame:
    """C2·C3: experiment_flow_short 캐시·정의 재사용 (Open>0·Close>0 행에서 계산)."""
    import experiment_flow_short as efs
    t = g["ticker"].iloc[0]
    end = g["trade_date"].max().strftime("%Y%m%d")
    flow = efs.cached(f"flow_{t}.pkl", lambda: efs.fetch_flow(t, end))
    short = efs.cached(f"short_{t}.pkl", lambda: efs.fetch_short(t, end))
    h = g[(g["Open"] > 0) & (g["Close"] > 0)].set_index("trade_date")[["Marcap"]]
    h = h.join(flow, how="left").join(short, how="left")
    return pd.DataFrame({"ticker": t, "trade_date": h.index,
                         "inst20": (h["inst"].rolling(20).sum() / h["Marcap"]).values,
                         "short_ratio": h["short_ratio"].shift(efs.SHORT_LAG).values})


def load_industry() -> pd.DataFrame:
    if not os.path.exists(INDUSTRY_CSV):  # 1회 스냅샷 - 재현을 위해 이후엔 파일만 읽는다
        import FinanceDataReader as fdr
        d = fdr.StockListing("KRX-DESC")[["Code", "Industry"]].assign(source="KRX-DESC")
        x = fdr.StockListing("KRX-DELISTING").rename(columns={"Symbol": "Code"})[["Code", "Industry"]]
        both = pd.concat([d, x.assign(source="KRX-DELISTING")]).dropna(subset=["Industry"])
        both["Code"] = both["Code"].astype(str).str.zfill(6)
        both.drop_duplicates("Code").assign(snapshot=date.today().isoformat()).to_csv(
            INDUSTRY_CSV, index=False, encoding="utf-8")
    ind = pd.read_csv(INDUSTRY_CSV, dtype={"Code": str})
    return ind.rename(columns={"Code": "ticker", "Industry": "industry"})[["ticker", "industry"]]


def industry_momentum(ds: pd.DataFrame, col="mom12_1") -> pd.Series:
    """같은 날·같은 업종 유니버스 종목 중 자기를 뺀 col 평균. ds는 유니버스 행만."""
    g = ds.groupby(["trade_date", "industry"])
    size = g["ticker"].transform("size")
    s, c = g[col].transform("sum"), g[col].transform("count")
    own = ds[col]
    peers = (s - own.fillna(0)) / (c - own.notna()).replace(0, np.nan)
    return peers.where((size >= 3) & ds["industry"].notna())


def load_fundamentals():
    try:
        from fundamentals_dart import load_fundamentals_panel
        p = load_fundamentals_panel(fetch=False)
    except Exception as e:  # 파일 없음/캐시 없음 → H1~H3 데이터 대기
        log.warning("재무 패널 없음 - H1~H3 데이터 대기: %s", e)
        return None
    if p is None or len(p) == 0:
        log.warning("재무 패널이 비어 있음 - H1~H3 데이터 대기")
        return None
    return p


def fundamentals_ready(panel, tickers) -> bool:
    """수집 중인 부분 캐시로 H1~H3의 한 번뿐인 평가를 쓰지 않도록, 신호 종목의 80% 이상이 있어야 진행."""
    have = len(set(panel["ticker"].astype(str).str.zfill(6)) & set(tickers)) / len(tickers)
    log.info("재무 패널 종목 커버리지 %.0f%% (%d종목)", have * 100, panel["ticker"].nunique())
    return have >= FUND_MIN_TICKER_SHARE


def add_fundamentals(ds: pd.DataFrame, panel: pd.DataFrame) -> pd.DataFrame:
    """avail_date <= T 인 최신 재무. 더 새 분기 뒤에 온 지난 분기 정정은 버린다."""
    p = panel.copy()
    p["ticker"] = p["ticker"].astype(str).str.zfill(6)
    p["avail_date"] = pd.to_datetime(p["avail_date"])
    p["period_end"] = pd.to_datetime(p["period_end"])
    p = p.sort_values(["ticker", "avail_date", "period_end"])
    p = p[p["period_end"] >= p.groupby("ticker")["period_end"].cummax()]
    p = p.sort_values("avail_date")
    cols = ["book_equity", "ni_ttm", "op_income_ttm", "total_assets", "is_financial"]
    m = pd.merge_asof(ds.sort_values("trade_date"), p[["ticker", "avail_date", *cols]],
                      left_on="trade_date", right_on="avail_date", by="ticker", direction="backward")
    m["bp"] = m["book_equity"] / m["Marcap"]
    m["ep"] = m["ni_ttm"] / m["Marcap"]
    fin = m["is_financial"].astype("boolean").fillna(False).astype(bool)
    m["op_roa"] = (m["op_income_ttm"] / m["total_assets"]).where(~fin & (m["total_assets"] > 0))
    return m.drop(columns=cols + ["avail_date"])


def add_excess(ds: pd.DataFrame) -> pd.DataFrame:
    """e = r - 그날 유니버스 평균, y = 날짜 안 1·99분위 winsorize 후 z-score."""
    for n in HORIZONS:
        g = ds.groupby("trade_date")[f"r_{n}"]
        ds[f"e_{n}"] = ds[f"r_{n}"] - g.transform("mean")
        g = ds.groupby("trade_date")[f"e_{n}"]
        w = ds[f"e_{n}"].clip(g.transform(lambda s: s.quantile(0.01)), g.transform(lambda s: s.quantile(0.99)))
        gw = w.groupby(ds["trade_date"])
        ds[f"y_{n}"] = (w - gw.transform("mean")) / gw.transform("std")
    return ds


def build(fund_panel):
    tm.PIT_YEARS_BACK = date.today().year - 2020
    price_df, universe = tm.load_pit_price_history(extra_cols=("Marcap", "Amount", "Open", "Close", "Changes"))
    price_df = price_df.sort_values(["ticker", "trade_date"])
    trading_days = pd.Index(np.sort(price_df["trade_date"].unique()))
    feats = pd.concat([ticker_frame(g) for _, g in price_df.groupby("ticker")], ignore_index=True)
    ds = universe.merge(price_df[["ticker", "trade_date", "Marcap"]], on=["ticker", "trade_date"])
    ds = ds.merge(feats, on=["ticker", "trade_date"], how="left")
    ds = ds[ds["trade_date"] >= DEV_START].reset_index(drop=True)

    sig_tickers = set(ds["ticker"])
    fs = pd.concat([flow_short_frame(g) for t, g in price_df.groupby("ticker") if t in sig_tickers],
                   ignore_index=True)
    ds = ds.merge(fs, on=["ticker", "trade_date"], how="left")
    ds = ds.merge(load_industry(), on="ticker", how="left")
    ds["ind_mom"] = industry_momentum(ds)
    if fund_panel is not None and not fundamentals_ready(fund_panel, sig_tickers):
        log.warning("재무 패널이 아직 부분 수집 상태 - H1~H3 데이터 대기")
        fund_panel = None
    if fund_panel is not None:
        ds = add_fundamentals(ds, fund_panel)
    ds = ds.replace([np.inf, -np.inf], np.nan)
    return add_excess(ds), trading_days


# ── 평가 ──
def split_dev(ds: pd.DataFrame, n: int) -> pd.DataFrame:
    return ds[(ds["trade_date"] >= DEV_START) & (ds["trade_date"] <= DEV_END) & (ds[f"exit_{n}"] < HOLDOUT_START)]


def daily_ic(d: pd.DataFrame, x: str, y: str) -> pd.Series:
    d = d[["trade_date", x, y]].dropna()
    return d.groupby("trade_date").apply(
        lambda s: s[x].rank().corr(s[y].rank()) if len(s) >= MIN_NAMES else np.nan).dropna()


def evaluate(df: pd.DataFrame, feat: str, sign: int, n: int, trading_days) -> dict:
    y, e = f"y_{n}", f"e_{n}"
    d = df.dropna(subset=[y])
    coverage = 1 - float(d[feat].isna().mean()) if len(d) else 0.0
    d = d.dropna(subset=[feat]).assign(x=lambda t: sign * t[feat])
    ic = daily_ic(d, "x", y)
    if len(ic) == 0:
        return {"coverage": round(coverage, 4), "days": 0}
    folds = [float(ic[(ic.index >= a) & (ic.index < b)].mean()) for a, b in tm.make_time_folds(d, N_FOLDS)]
    win = non_overlapping_dates(ic.index, trading_days, n)
    icw = ic.loc[win]
    t = float(icw.mean() / icw.std(ddof=1) * math.sqrt(len(icw))) if len(icw) > 2 else float("nan")
    p = float(2 * stats.t.sf(abs(t), len(icw) - 1)) if len(icw) > 2 else float("nan")

    w = d[d["trade_date"].isin(win)].copy()
    w["q"] = w.groupby("trade_date")["x"].rank(pct=True)
    spreads, costs, prev = [], [], {"top": set(), "bot": set()}
    for dt, s in w.groupby("trade_date"):
        legs = {"top": s[s["q"] > 0.8], "bot": s[s["q"] <= 0.2]}
        spreads.append(legs["top"][e].mean() - legs["bot"][e].mean())
        turn = 0.0
        for k, leg in legs.items():
            cur = set(leg["ticker"])
            turn += 1 - len(cur & prev[k]) / len(cur) if cur else 0
            prev[k] = cur
        costs.append(turn * COST_ROUND_TRIP)
    sp = float(np.nanmean(spreads)) if spreads else float("nan")
    return {"coverage": round(coverage, 4), "days": int(len(ic)), "icMean": float(ic.mean()),
            "folds": [round(f, 4) for f in folds], "sameSignFolds": int(sum(f > 0 for f in folds)),
            "nFolds": len(folds), "windows": len(icw), "icWinMean": round(float(icw.mean()), 4),
            "icWinT": round(t, 2), "pBonferroni": round(min(1.0, p * 5), 4),
            "spreadWin": sp, "spreadAfterCost": round(sp - float(np.mean(costs)), 4),
            "avgTurnover": round(float(np.mean(costs)) / COST_ROUND_TRIP / 2, 3)}


def dev_pass(m: dict) -> bool:
    return (m.get("icMean") is not None and m["icMean"] >= MIN_IC and m["sameSignFolds"] >= MIN_SAME_SIGN_FOLDS
            and m["spreadWin"] >= MIN_SPREAD)


def holdout_eval(ds, feat, sign, dev_metrics, trading_days) -> dict:
    """holdout 게이트: 개발 불통과면 holdout 표본을 아예 보지 않는다."""
    if not dev_pass(dev_metrics):
        raise PermissionError(f"{feat}: 개발 불통과 - holdout 계산 금지")
    h = ds[ds["trade_date"] >= HOLDOUT_START]
    out = {n: evaluate(h, feat, sign, n, trading_days) for n in HORIZONS}
    m = out[MAIN_N]
    out["pass"] = bool(m.get("icMean") is not None and m["icMean"] > 0
                       and m["icMean"] >= dev_metrics["icMean"] * HOLDOUT_IC_RATIO and m["spreadWin"] > 0)
    return out


def composite(ds: pd.DataFrame, items) -> pd.Series:
    ranks = [ds.groupby("trade_date")[f].rank(pct=True) * s for f, s in items]
    ranks = [r if s > 0 else 1 + r for r, (_, s) in zip(ranks, items)]  # 부호 맞춘 순위(0~1)
    return pd.concat(ranks, axis=1).mean(axis=1)


def fmt(m):
    if not m or not m.get("days"):
        return "표본 없음"
    return (f"IC {m['icMean']:+.4f} 폴드 {m['sameSignFolds']}/{m['nFolds']} 창 IC {m['icWinMean']:+.4f} "
            f"t {m['icWinT']:+.2f} Q5-Q1 {m['spreadWin']:+.2%} 비용후 {m['spreadAfterCost']:+.2%} "
            f"커버리지 {m['coverage']:.0%}")


def main():
    prior = json.load(open(RESULT_PATH, encoding="utf-8")) if os.path.exists(RESULT_PATH) else {}
    panel = load_fundamentals()
    ds, trading_days = build(panel)
    log.info("데이터: %d행, %s ~ %s, 날짜당 %.1f종목", len(ds), ds["trade_date"].min().date(),
             ds["trade_date"].max().date(), ds.groupby("trade_date").size().mean())

    def dev_metrics(feat, sign):
        return {n: evaluate(split_dev(ds, n), feat, sign, n, trading_days) for n in HORIZONS}

    dev, holdout = {}, dict(prior.get("holdout", {}))
    for name, (feat, sign) in HYPOTHESES.items():
        if feat in FIN_FEATURES and "bp" not in ds.columns:
            dev[name] = {"status": "데이터 대기"}
            log.info("%-14s 데이터 대기", name)
            continue
        dev[name] = dev_metrics(feat, sign)
        dev[name]["pass"] = dev_pass(dev[name][MAIN_N])
        log.info("%-14s 개발 N=20 %s → %s", name, fmt(dev[name][MAIN_N]), "통과" if dev[name]["pass"] else "불통과")
        for n in (5, 60):
            log.info("%-14s   (참고 N=%d) %s", "", n, fmt(dev[name][n]))
        if dev[name]["pass"]:
            if name in holdout:
                log.info("%-14s holdout은 이미 1회 계산됨 - 재사용", name)
            else:
                holdout[name] = holdout_eval(ds, feat, sign, dev[name][MAIN_N], trading_days)
            log.info("%-14s holdout N=20 %s → %s", name, fmt(holdout[name][str(MAIN_N)] if str(MAIN_N) in holdout[name]
                     else holdout[name][MAIN_N]), "통과" if holdout[name]["pass"] else "불통과")

    confirm = {name: dev_metrics(feat, sign) for name, (feat, sign) in CONFIRM.items()}
    for name, m in confirm.items():
        log.info("%-14s (확인용) 개발 N=20 %s", name, fmt(m[MAIN_N]))
    diagnostic = {"vol60": {n: evaluate(split_dev(ds, n), "vol60", +1, n, trading_days) for n in HORIZONS}}
    log.info("진단 vol60(+) 개발 N=20 %s", fmt(diagnostic["vol60"][MAIN_N]))
    if "bp" in ds.columns:  # 정정 누수 민감도(판정 아님, 개발 기간만): amended=True 행을 빼고 다시 계산
        clean = add_fundamentals(ds.drop(columns=sorted(FIN_FEATURES)), panel[~panel["amended"].astype(bool)])
        diagnostic["noAmended"] = {}
        for name, (feat, sign) in HYPOTHESES.items():
            if feat in FIN_FEATURES:
                diagnostic["noAmended"][name] = {n: evaluate(split_dev(clean, n), feat, sign, n, trading_days)
                                                 for n in HORIZONS}
                log.info("진단 %-12s 정정 제외 개발 N=20 %s", name, fmt(diagnostic["noAmended"][name][MAIN_N]))

    pending = [k for k, v in dev.items() if v.get("status")]
    passed = [k for k, v in dev.items() if v.get("pass")]
    comp = None
    if pending:
        comp = {"status": "보류 - 데이터 대기 후보가 있어 개발 통과 집합 미확정", "pending": pending}
    elif len(passed) >= 2:
        if "composite" in prior.get("final", {}) and isinstance(prior["final"]["composite"], dict) \
                and "holdoutIc" in prior["final"]["composite"]:
            comp = prior["final"]["composite"]
        else:
            items = [HYPOTHESES[k] for k in passed]
            ds["composite"] = composite(ds, items)
            h = ds[ds["trade_date"] >= HOLDOUT_START]
            ci = evaluate(h, "composite", +1, MAIN_N, trading_days)
            c4 = evaluate(h, "mom12_1", +1, MAIN_N, trading_days)
            comp = {"members": passed, "holdout": ci, "c4Holdout": c4, "holdoutIc": ci.get("icMean"),
                    "adopted": bool(ci.get("icMean") is not None and ci["icMean"] > (c4.get("icMean") or 0))}
    # 개발 불통과인데 holdout 기록이 있으면(2026-11-11 2차 실행: 반올림 값으로 판정한 버그로 H1이 잘못 열림)
    # 판정에서 빼고 무효 기록으로만 남긴다
    invalid = dict(prior.get("holdoutInvalid", {}))
    for k in [k for k in holdout if not dev.get(k, {}).get("pass")]:
        invalid[k] = {"reason": "개발 불통과(반올림 버그로 잘못 열림) - 판정 무효", "seen": holdout.pop(k)}
    final = {"devPassed": passed, "pending": pending, "holdoutInvalid": list(invalid),
             "holdoutPassed": [k for k, v in holdout.items() if v.get("pass")], "composite": comp}
    log.info("최종: %s", final)

    period = {"rows": int(len(ds)), "tickers": int(ds["ticker"].nunique()),
              "start": str(ds["trade_date"].min().date()), "end": str(ds["trade_date"].max().date())}
    result = {"preregistered": PREREGISTERED, "period": period, "dev": dev, "holdout": holdout, "holdoutInvalid": invalid,
              "confirm": confirm, "diagnostic": diagnostic, "final": final}
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    log.info("결과 저장: %s", RESULT_PATH)

    lines = [json.loads(l) for l in open(LOG_PATH, encoding="utf-8") if l.strip()]
    rerun = any(l.get("script") == "experiment_cross_section.py" for l in lines)
    attempt = max(l.get("attempt", 0) for l in lines) + (0 if rerun else 1)
    key = {k: {"dev20": {m: v[MAIN_N].get(m) for m in ("icMean", "sameSignFolds", "icWinT", "spreadWin",
                                                       "spreadAfterCost")} if MAIN_N in v else v}
           for k, v in {**dev, **confirm}.items()}
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps({
            "time": datetime.now(timezone.utc).isoformat(), "script": "experiment_cross_section.py",
            "featureSet": "cross_section_round1 (D18)", "features": list(HYPOTHESES) + list(CONFIRM),
            "model": "single-feature rank (no training)", "nCandidates": 9, "rerun": rerun,
            "params": {"horizons": HORIZONS, "mainN": MAIN_N, "topN": tm.PIT_TOP_N, "nFolds": N_FOLDS,
                       "cost": COST_ROUND_TRIP},
            "period": period, "keyMetrics": key, "final": final, "attempt": attempt,
            "prior_attempts_estimate": 105 + 9,
        }, ensure_ascii=False, default=str) + "\n")


if __name__ == "__main__":
    main()
