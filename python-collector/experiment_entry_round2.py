"""
experiment_entry_round2.py — 사전 등록 2회차: 시장 국면 신호. 임계값·분할·가설은 실행 전에 고정했고 결과를 보고 바꾸지 않는다.
1회차 진단의 BREAKOUT_52W h50 통과 결과는 이번 후보 선정에 쓰지 않았다 (후보는 시장 국면 지표만).

대상: KS11 절대수익. T 종가로 신호, T+1 종가 매수, 진입 h거래일 뒤 종가 매도. h = 20, 50.
기준선(항상 보유): 같은 기간 모든 거래일에 T+1 진입·h일 보유한 수익의 평균. 개발·확인 기간이 각자 자기 기간 평균,
  개발은 purge 뒤 남은 날로. 창 값 = 신호 켜진 날 진입 h일 수익 - 기준선.
  ticker="KS11" 1종목 행으로 evaluate_tags.summarize / non_overlapping_dates 재사용.
판정 v1 그대로: 겹치지 않는 창 >= 6, 창 평균 부호 +, |창 평균| >= 1.0%(20일) / 2.0%(50일), 같은 부호 창 >= 70%.
  추가: 창이 서로 다른 켜짐 국면에서 개발 >= 3개, 확인 >= 2개 나와야 한다.
  국면 = 신호 켜진 연속 구간. 꺼짐이 10거래일 미만이면 같은 국면으로 합친다 (9일 합침, 10일 분리).
후보 (가설 모두 +):
  M1 KS_TREND  : KS11 종가 > KS11 MA200 (T 포함 200일)
  M2 BREADTH50 : T의 PIT 시총 상위 100 중 종가 > 자기 MA50 인 종목 비율 > 50% (MA50 있는 종목 50개 미만이면 미정 = 꺼짐)
  M3 BOTH      : M1 AND M2
분할: 1회차 상수 재사용 (개발 신호일 2021-01-01~2024-06-30 + purge, 확인 2024-07-01~끝, PIT 2021~2026).
  3후보 x 2기간 = 6쌍. 개발 통과 쌍만 확인 기간 1회 판정, 불통과 쌍은 확인 기간 수익을 계산하지 않는다.
사전 점검(precheck): 수익을 보기 전에 후보·기간별 켜짐/꺼짐 날짜 수와 국면 수만 기록.
  확인 기간 켜짐 국면 < 2 인 쌍은 "확인 불가 -> 실전 기록 대기"로 미리 확정. 이 결과로 임계값·분할을 바꾸지 않는다.
진단(판정 미포함, 개발 기간만): 켜짐 보유/꺼짐 현금 vs 항상 보유의 MDD·연변동성·누적수익, 꺼진 날 진입 수익,
  같은 계산을 유니버스 동일가중 바스켓(전날 상위 100, 매일 리밸런싱)으로.
KS11 받기 실패: 대상 자체가 KS11이라 전 후보 중단 (대체 금지).

실행: python experiment_entry_round2.py     결과: model/entry_round2_result.json
"""
import json
import logging
import os
import sys
import time
from datetime import datetime

import numpy as np
import pandas as pd

import evaluate_tags as et
import experiment_entry_round1 as r1
import train_model as tm

log = logging.getLogger(__name__)

RESULT_PATH = os.path.join("model", "entry_round2_result.json")
HORIZONS = [20, 50]
CANDS = ["KS_TREND", "BREADTH50", "BOTH"]
MERGE_GAP = 10          # 꺼짐 이 일수 미만이면 같은 국면
MIN_REGIMES = {"dev": 3, "holdout": 2}
BREADTH_MIN, BREADTH_MIN_VALID = 0.5, 50
WAIT = "확인 불가 -> 실전 기록 대기"

PREREGISTERED = {
    "target": "KS11 absolute return, signal T close, buy T+1 close, sell entry+h close", "horizons": HORIZONS,
    "hypothesis": {c: +1 for c in CANDS},
    "baseline": "mean h-day return of T+1 entries on all days in the same period (dev after purge)",
    "criteria": {"minWindows": et.MIN_WINDOWS, "minSameSignShare": et.MIN_SAME_SIGN_SHARE,
                 "minAbsExcess": {h: et.MIN_ABS_EXCESS[h] for h in HORIZONS}, "minRegimes": MIN_REGIMES,
                 "regimeMerge": f"off gap < {MERGE_GAP} trading days merged"},
    "candidates": {"KS_TREND": "KS11 close > KS11 MA200 (incl. T)",
                   "BREADTH50": "share of PIT top-100 with close > own MA50 > 50%",
                   "BOTH": "KS_TREND and BREADTH50"},
    "split": {"devSignal": ["2021-01-01", "2024-06-30"], "devPurge": "entry+h >= 2024-07-01 removed",
              "holdoutSignal": ["2024-07-01", "end"], "gate": "holdout only for dev-passed pairs with >=2 holdout regimes"},
    "note": "round-1 diagnostic BREAKOUT_52W h50 not used for candidate selection",
}


def regime_ids(on: pd.Series) -> pd.Series:
    """on: 거래일 순 bool. 켜진 날에 국면 번호, 꺼진 날 NaN. 꺼짐 MERGE_GAP일 미만 사이는 같은 번호."""
    pos = np.flatnonzero(on.values)
    ids = pd.Series(np.nan, index=on.index)
    if len(pos):
        ids.iloc[pos] = np.cumsum(np.r_[True, np.diff(pos) - 1 >= MERGE_GAP])
    return ids


def hold_rows(rows, days, h):
    """확인 기간 신호 중 청산일(진입+h)이 데이터 안에 있는 것 (날짜만 본다, 수익은 안 본다)."""
    r = rows[rows["signal_date"] >= r1.HOLD_START]
    return r[days.searchsorted(r["trade_date"]) + h < len(days)]


def period_rows(rows, days, h, period):
    return r1.dev_rows(rows, days, h) if period == "dev" else hold_rows(rows, days, h)


def baseline(fwd: pd.Series, all_rows: pd.DataFrame) -> float:
    return float(fwd.reindex(all_rows["trade_date"]).dropna().mean())


def judge(rows, fwd, base, days, h, tag, ids, min_regimes):
    excess = pd.DataFrame({"trade_date": fwd.index, "ticker": "KS11", "excess": (fwd - base).values}).dropna()
    et.EXPECTED_SIGN = {tag: +1}
    s = et.summarize(rows, excess, days, h)[tag]
    ev = rows.merge(excess, on=["trade_date", "ticker"])
    win = et.non_overlapping_dates(ev["trade_date"], days, h)
    sig = ev.set_index("trade_date").loc[win, "signal_date"]
    s["regimes"] = int(ids.reindex(sig).nunique())
    s["baseline"] = round(base, 4)
    if s["verdict"] == "통과" and s["regimes"] < min_regimes:
        s["verdict"] = "불통과(국면 부족)"
    return s


def metrics(r: pd.Series) -> dict:
    eq = (1 + r.fillna(0)).cumprod()
    return {"cum": round(float(eq.iloc[-1] - 1), 4), "annVol": round(float(r.std() * np.sqrt(252)), 4),
            "mdd": round(float((eq / eq.cummax() - 1).min()), 4)}


def _self_check():
    on = pd.Series([True] + [False] * 9 + [True] + [False] * 10 + [True])
    ids = regime_ids(on)
    assert ids[0] == ids[10] and ids[21] != ids[10] and ids.dropna().nunique() == 2 and pd.isna(ids[1])
    days = pd.bdate_range("2024-01-01", periods=30)
    fwd = pd.Series(np.arange(30, dtype=float), index=days)
    rows = pd.DataFrame({"trade_date": days[[2, 4]]})
    assert baseline(fwd, rows) == 3.0
    days = pd.bdate_range("2024-01-01", periods=200)
    fwd = pd.Series(0.05, index=days)
    rows = pd.DataFrame({"ticker": "KS11", "tag": "T", "trade_date": days[:140], "signal_date": days[:140]})
    ids = pd.Series(np.repeat([1.0, 2.0], 100), index=days)
    s = judge(rows, fwd, 0.0, days, 20, "T", ids, 3)  # 창 7개 전부 +5%, 국면 2개 < 3
    assert s["windows"] == 7 and s["regimes"] == 2 and s["verdict"] == "불통과(국면 부족)"
    assert judge(rows, fwd, 0.0, days, 20, "T", ids, 2)["verdict"] == "통과"


def main():
    t0 = time.time()
    try:
        import FinanceDataReader as fdr
        ks_raw = fdr.DataReader("KS11", "2020-01-01")["Close"]
    except Exception as e:
        log.error("KS11 받기 실패 - 대상이 KS11이라 전 후보 중단: %s", e)
        with open(RESULT_PATH, "w", encoding="utf-8") as f:
            json.dump({"preregistered": PREREGISTERED, "error": str(e)}, f, ensure_ascii=False, indent=2)
        sys.exit(1)

    tm.PIT_YEARS_BACK = datetime.now().year - r1.PIT_FIRST_YEAR
    price_df, universe_df = tm.load_pit_price_history()
    price_df = price_df[price_df["trade_date"] <= r1.PIT_LAST_DAY]
    universe_df = universe_df[universe_df["trade_date"] <= r1.PIT_LAST_DAY]
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    days = close.index
    in_uni = (universe_df.assign(v=True).pivot(index="trade_date", columns="ticker", values="v")
              .reindex(index=days, columns=close.columns).eq(True))

    ks_ma = ks_raw.rolling(200).mean()
    ks = ks_raw.reindex(days)
    trend = (ks_raw > ks_ma).where(ks_ma.notna()).reindex(days)
    above = (close > close.rolling(50).mean()).where(close.rolling(50).count() == 50)
    valid = above.notna() & in_uni
    breadth = (above.eq(True) & in_uni).sum(axis=1) / valid.sum(axis=1)
    breadth = breadth.where(valid.sum(axis=1) >= BREADTH_MIN_VALID)
    sig = {"KS_TREND": trend.eq(True), "BREADTH50": breadth > BREADTH_MIN}
    sig["BOTH"] = sig["KS_TREND"] & sig["BREADTH50"]
    known = {"KS_TREND": trend.notna(), "BREADTH50": breadth.notna()}
    known["BOTH"] = known["KS_TREND"] & known["BREADTH50"]

    def rows_where(mask, tag):
        d = days[mask.values]
        return r1.to_entry(pd.DataFrame({"ticker": "KS11", "tag": tag, "trade_date": d}), days)

    all_rows = rows_where(pd.Series(True, index=days), "ALL")
    on_rows = {c: rows_where(sig[c], c) for c in CANDS}
    off_rows = {c: rows_where(~sig[c] & known[c], c) for c in CANDS}
    ids = {c: regime_ids(sig[c]) for c in CANDS}

    # ── precheck: 날짜·국면 수만 (수익 안 봄) ──
    precheck = {}
    for c in CANDS:
        for h in HORIZONS:
            p = {}
            for per in ("dev", "holdout"):
                on_r = period_rows(on_rows[c], days, h, per)
                p[per] = {"onDays": int(len(on_r)), "offDays": int(len(period_rows(off_rows[c], days, h, per))),
                          "regimes": int(ids[c].reindex(on_r["signal_date"]).nunique())}
            p["status"] = WAIT if p["holdout"]["regimes"] < MIN_REGIMES["holdout"] else "확인 가능"
            precheck.setdefault(c, {})[h] = p
            log.info("precheck %-9s h%d | dev 켜짐 %4d 꺼짐 %4d 국면 %d | holdout 켜짐 %4d 꺼짐 %4d 국면 %d | %s",
                     c, h, p["dev"]["onDays"], p["dev"]["offDays"], p["dev"]["regimes"],
                     p["holdout"]["onDays"], p["holdout"]["offDays"], p["holdout"]["regimes"], p["status"])

    fwd = {h: (ks.shift(-h) / ks - 1) for h in HORIZONS}
    dev_base = {h: baseline(fwd[h], r1.dev_rows(all_rows, days, h)) for h in HORIZONS}
    dev = {h: {c: judge(r1.dev_rows(on_rows[c], days, h), fwd[h], dev_base[h], days, h, c, ids[c],
                        MIN_REGIMES["dev"]) for c in CANDS} for h in HORIZONS}
    et.EXPECTED_SIGN = {c: +1 for c in CANDS}
    et.print_table("round2 dev (KS11, 기준선=항상 보유)", dev)
    for h in HORIZONS:
        log.info("  h%d 기준선 %+.2f%%, 국면 수 %s", h, dev_base[h] * 100, {c: dev[h][c]["regimes"] for c in CANDS})

    pairs = r1.passed_pairs(dev)
    gated = [(h, c) for h, c in pairs if precheck[c][h]["status"] != WAIT]
    log.info("개발 통과 쌍 %s, 확인 기간 판정 대상 %s", pairs, gated)
    holdout = {}
    for h, c in gated:  # 통과 쌍이 있을 때만 확인 기간 수익을 본다
        base = baseline(fwd[h], hold_rows(all_rows, days, h))
        holdout.setdefault(h, {})[c] = judge(hold_rows(on_rows[c], days, h), fwd[h], base, days, h, c, ids[c],
                                             MIN_REGIMES["holdout"])
        log.info("holdout %s h%d: %s", c, h, holdout[h][c])
    final = [f"{c}:{h}" for h, c in gated if holdout[h][c]["verdict"] == "통과"]
    waiting = [f"{c}:{h}" for h, c in pairs if (h, c) not in gated]

    # ── 진단 (개발 기간만) ──
    uni_prev = in_uni.shift(1, fill_value=False)
    basket_ret = close.pct_change(fill_method=None).where(uni_prev).mean(axis=1)
    basket = (1 + basket_ret.fillna(0)).cumprod()
    in_dev = (days >= r1.DEV_START) & (days <= r1.DEV_END)
    diag = {}
    for name, px, ret in (("KS11", ks, ks.pct_change()), ("basket", basket, basket_ret)):
        d = {"alwaysHold": metrics(ret[in_dev])}
        for c in CANDS:
            pos = sig[c].astype(float).shift(2).fillna(0)  # T 신호 -> T+1 종가 매수 -> T+2 수익부터
            d[c] = {"strategy": metrics((ret * pos)[in_dev]), "timeInMarket": round(float(pos[in_dev].mean()), 3)}
            for h in HORIZONS:
                f = px.shift(-h) / px - 1
                base = baseline(f, r1.dev_rows(all_rows, days, h))
                on_m = baseline(f, r1.dev_rows(on_rows[c], days, h))
                off_m = baseline(f, r1.dev_rows(off_rows[c], days, h))
                d[c][f"h{h}"] = {"baseline": round(base, 4), "onEntryMean": round(on_m, 4),
                                 "offEntryMean": round(off_m, 4), "offMinusBaseline": round(off_m - base, 4)}
        diag[name] = d
        log.info("diag %s: %s", name, json.dumps(d, ensure_ascii=False))

    elapsed = round(time.time() - t0, 1)
    result = {"preregistered": PREREGISTERED, "precheck": precheck, "dev": dev, "holdout": holdout,
              "diagnostic": {**diag, "note": "개발 구간만, 판정 미포함"}, "final": final,
              "meta": {"priceRange": [str(days.min().date()), str(days.max().date())],
                       "ks11MissingOnTradingDays": int(ks.isna().sum()), "devBaseline": dev_base,
                       "devPassedPairs": [f"{c}:{h}" for h, c in pairs], "waitingLive": waiting,
                       "elapsedSec": elapsed}}
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    log.info("최종 통과: %s  대기: %s  (%.1fs) -> %s", final, waiting, elapsed, RESULT_PATH)


if __name__ == "__main__":
    _self_check()
    main()
