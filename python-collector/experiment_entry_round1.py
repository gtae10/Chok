"""
experiment_entry_round1.py — 사전 등록 1회차 진입 신호 실험. 아래 임계값·분할일·가설은 실행 전에 고정했다.
결과를 보고 바꾸지 않는다 (바꾸면 2회차로 새로 등록).

가설: 4후보 모두 +1 (신호 뒤 유니버스 평균보다 오른다).
매수 시점: 신호는 T 종가로 계산, 진입은 T+1(다음 거래일) 종가.
판정 기준 v1 (evaluate_tags.py 그대로): h = 5/20/50영업일 각각, 겹치지 않는 창 >= 6 (미만은 "창 부족"),
  창 평균 부호 = 가설, |창 평균| >= 0.5% / 1.0% / 2.0%, 가설과 같은 부호 창 >= 70%.
  창 값 = 그날(진입일) 신호 종목 평균 초과수익, 기준선 = 신호일 T의 유니버스(시총 상위 100) 동일가중 평균
  (같은 진입일~진입일+h 구간). T+1에 상위 100에서 빠진 종목도 잃지 않으려고 유니버스 키를 진입일로 옮겨 쓴다.

후보 (모두 신호일 T에 유니버스 안):
  A BREAKOUT_PULLBACK : T-20~T-1에 BREAKOUT_52W(종가 >= 직전 252일 최고 종가, 당일 미포함) 1회 이상,
                        종가/(T-20~T 최고 종가) - 1 <= -5%, 종가 > MA50, 직전 20거래일에 A 조건 없음(첫 눌림만)
  B SQUEEZE_BREAKOUT  : vol20(T-20~T-1 일간수익률 표준편차) <= 직전 252일 vol20의 20% 분위수,
                        종가 > T-20~T-1 최고 종가, 직전 20거래일에 B 조건 없음. 거래량 조건 없음
  C REGIME_BREAKOUT   : BREAKOUT_52W 이면서 KS11 종가 > KS11 MA200 (T 기준). KS11 받기 실패 시 C 중단
  D EARNINGS_DRIFT    : DART 캐시 제목에 "영업(잠정)실적", "["로 시작 안 함, (종목, 공시일 D) 중복 제거.
                        D1 = D 다음 거래일, EAR = (D 직전 거래일 종가 -> D1 종가) - 같은 구간 D1 유니버스 평균.
                        EAR >= +3% 이면 T = D1
기간 분할: 개발 = 신호일 2021-01-01~2024-06-30, 진입일+h 거래일이 2024-07-01 이후인 신호 제거(purge).
  확인 = 신호일 2024-07-01~끝. 개발 12쌍(4후보 x 3기간) 판정 -> 통과 쌍만 확인 기간 1회 판정.
  개발 불통과 쌍은 확인 기간을 계산하지 않는다. 최종 통과 = 두 구간 모두 통과.
PIT 기간 2021~2026 고정. 누출: rolling 최고가·vol20·분위수는 shift(1). MA50·KS11 MA200은 T 포함.
진단(판정 미포함, 개발 기간만): evaluate_entry 4규칙 T+0 vs T+1, C의 하락 국면 짝(KS11 <= MA200).
한계: marcap 상위 100 PIT라 상장폐지 종목도 들어가지만, 폐지 직전 가격이 끊기면 그 창의 수익률이 빠진다(잔여 편향).

실행: python experiment_entry_round1.py     결과: model/entry_round1_result.json
"""
import glob
import json
import logging
import os
import time
from datetime import datetime

import numpy as np
import pandas as pd

import evaluate_tags as et
import train_model as tm
from evaluate_entry import entry_masks

log = logging.getLogger(__name__)

RESULT_PATH = os.path.join("model", "entry_round1_result.json")
SIGN = {"BREAKOUT_PULLBACK": +1, "SQUEEZE_BREAKOUT": +1, "REGIME_BREAKOUT": +1, "EARNINGS_DRIFT": +1}
PIT_FIRST_YEAR, PIT_LAST_DAY = 2021, pd.Timestamp("2026-12-31")
DEV_START, DEV_END, HOLD_START = pd.Timestamp("2021-01-01"), pd.Timestamp("2024-06-30"), pd.Timestamp("2024-07-01")
LOOKBACK = 20
PULLBACK_MAX = -0.05
SQUEEZE_Q = 0.2
EAR_MIN = 0.03
DART_KEY = "영업(잠정)실적"

PREREGISTERED = {
    "hypothesis": SIGN, "entry": "T+1 close", "horizons": et.HORIZONS,
    "criteria": {"minWindows": et.MIN_WINDOWS, "minSameSignShare": et.MIN_SAME_SIGN_SHARE,
                 "minAbsExcess": et.MIN_ABS_EXCESS},
    "split": {"devSignal": ["2021-01-01", "2024-06-30"], "devPurge": "entry+h trading days >= 2024-07-01 removed",
              "holdoutSignal": ["2024-07-01", "end"], "gate": "holdout only for dev-passed pairs",
              "final": "pass in both"},
    "pitYears": [2021, 2026], "universe": "daily marcap top 100 (signal day T)",
    "candidates": {
        "BREAKOUT_PULLBACK": "BREAKOUT_52W in T-20..T-1; close/max(close T-20..T)-1 <= -5%; close > MA50; "
                             "no A-condition in prior 20 trading days",
        "SQUEEZE_BREAKOUT": "vol20(T-20..T-1) <= 20% quantile of prior 252 vol20; close > max(close T-20..T-1); "
                            "no B-condition in prior 20 trading days",
        "REGIME_BREAKOUT": "BREAKOUT_52W and KS11 close > KS11 MA200 (T)",
        "EARNINGS_DRIFT": "DART '영업(잠정)실적' non-correction, dedup (ticker, D); EAR(D-1 close -> D1 close) "
                          "- universe mean >= +3%; T = D1",
    },
}


def first_only(raw: pd.Series, ticker: pd.Series) -> pd.Series:
    """raw 조건이 참이고, 같은 종목 직전 LOOKBACK거래일에 raw가 한 번도 없었던 날만."""
    prior = raw.astype(float).groupby(ticker).transform(lambda s: s.shift(1).rolling(LOOKBACK, min_periods=1).max())
    return raw & (prior.fillna(0) == 0)


def to_entry(rows: pd.DataFrame, days: pd.Index) -> pd.DataFrame:
    """신호일 T(trade_date)를 진입일 T+1로 옮긴다. signal_date에 T를 남기고, 다음 거래일이 없으면 버린다."""
    pos = days.searchsorted(rows["trade_date"], side="right")
    ok = pos < len(days)
    out = rows.loc[ok].copy()
    out["signal_date"] = out["trade_date"]
    out["trade_date"] = days[pos[ok]]
    return out


def dev_rows(rows: pd.DataFrame, days: pd.Index, h: int) -> pd.DataFrame:
    """개발 구간 신호 + purge: 진입일 + h거래일(청산일)이 HOLD_START 이후면 제거."""
    r = rows[(rows["signal_date"] >= DEV_START) & (rows["signal_date"] <= DEV_END)]
    exit_pos = days.searchsorted(r["trade_date"]) + h
    exit_ok = exit_pos < len(days)
    exit_date = pd.Series(pd.NaT, index=r.index)
    exit_date[exit_ok] = days[exit_pos[exit_ok]]
    return r[exit_date.notna() & (exit_date < HOLD_START)]


def passed_pairs(dev: dict) -> list:
    return [(h, tag) for h, tags in dev.items() for tag, s in tags.items() if s.get("verdict") == "통과"]


def judge(rows, excess, days, h, signs):
    et.EXPECTED_SIGN = signs  # summarize가 모듈 전역을 읽는다 (evaluate_entry 방식)
    return et.summarize(rows[rows["tag"].isin(signs)], excess, days, h)


def holdout(rows, excess_by_h, days, pairs) -> dict:
    """개발 통과 쌍만 계산. pairs가 비면 아무것도 계산하지 않는다 (엿보기 방지)."""
    r = rows[rows["signal_date"] >= HOLD_START]
    out = {}
    for h, tag in pairs:
        out.setdefault(h, {}).update(judge(r, excess_by_h[h](), days, h, {tag: SIGN[tag]}))
    return out


def _self_check():
    t = pd.Series(["X"] * 6 + ["Y"] * 2)
    raw = pd.Series([True, True, False, True, False, False, True, True])
    assert first_only(raw, t).tolist() == [True, False, False, False, False, False, True, False]
    days = pd.DatetimeIndex(["2024-06-26", "2024-06-27", "2024-06-28", "2024-07-01", "2024-07-02"])
    rows = pd.DataFrame({"ticker": ["A", "B", "C"], "tag": "X",
                         "trade_date": pd.to_datetime(["2024-06-26", "2024-06-27", "2024-07-02"])})
    e = to_entry(rows, days)
    assert e["trade_date"].tolist() == list(pd.to_datetime(["2024-06-27", "2024-06-28"]))
    assert e["signal_date"].tolist() == list(pd.to_datetime(["2024-06-26", "2024-06-27"]))
    # 진입 06-27 + 1 = 06-28 (남음), 진입 06-28 + 1 = 07-01 (purge)
    assert dev_rows(e, days, 1)["ticker"].tolist() == ["A"]
    assert dev_rows(e, days, 2)["ticker"].tolist() == []
    called = []
    dev = {5: {"A1": {"verdict": "통과"}, "B1": {"verdict": "불통과"}}, 20: {"A1": {"verdict": "창 부족"}}}
    assert passed_pairs(dev) == [(5, "A1")]
    assert holdout(e, {5: lambda: called.append(5)}, days, []) == {} and called == []


def load_earnings(close: pd.DataFrame, universe: dict) -> tuple:
    days = close.index
    ev = set()
    for p in glob.glob(os.path.join("data", "dart", "list_*.json")):
        t = os.path.basename(p)[5:-5]
        with open(p, encoding="utf-8") as f:
            for r in json.load(f):
                nm = r["report_nm"].strip()
                if DART_KEY in nm and not nm.startswith("["):
                    ev.add((t, pd.Timestamp(r["rcept_dt"])))
    rows, ears = [], []
    for t, d in sorted(ev):
        i1, i0 = days.searchsorted(d, side="right"), days.searchsorted(d, side="left") - 1
        if t not in close.columns or i0 < 0 or i1 >= len(days):
            continue
        d0, d1 = days[i0], days[i1]
        uni = list(universe.get(d1, ()))
        if t not in uni:
            continue
        r = close.loc[d1, uni] / close.loc[d0, uni] - 1
        if pd.isna(r.get(t)):
            continue
        ear = r[t] - r.dropna().mean()
        ears.append(ear)
        if ear >= EAR_MIN:
            rows.append((t, d1))
    info = {"disclosures": len(ev), "inUniverse": len(ears), "signals": len(rows),
            "earMedian": round(float(np.median(ears)), 4) if ears else None}
    return pd.DataFrame(rows, columns=["ticker", "trade_date"]).assign(tag="EARNINGS_DRIFT"), info


def main():
    t0 = time.time()
    tm.PIT_YEARS_BACK = datetime.now().year - PIT_FIRST_YEAR  # 실행 연도와 무관하게 2021부터
    price_df, universe_df = tm.load_pit_price_history()
    price_df = price_df[price_df["trade_date"] <= PIT_LAST_DAY]
    universe_df = universe_df[universe_df["trade_date"] <= PIT_LAST_DAY]
    df = price_df.sort_values(["ticker", "trade_date"]).reset_index(drop=True)
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    days = close.index
    universe = universe_df.groupby("trade_date")["ticker"].apply(set).to_dict()
    df = df.merge(universe_df.assign(in_uni=True), on=["ticker", "trade_date"], how="left")
    in_uni = df["in_uni"].eq(True)

    tk, c = df["ticker"], df["close_price"]
    g = c.groupby(tk)
    old = entry_masks(df)
    brk = old["BREAKOUT_52W"].fillna(False)
    brk_recent = brk.astype(float).groupby(tk).transform(lambda s: s.shift(1).rolling(LOOKBACK).max()) > 0
    high21 = g.transform(lambda s: s.rolling(LOOKBACK + 1).max())          # T-20~T (당일 포함 - 정의대로)
    ma50 = g.transform(lambda s: s.rolling(50).mean())
    raw_a = brk_recent & (c / high21 - 1 <= PULLBACK_MAX) & (c > ma50)
    ret = g.pct_change()
    vol20 = ret.groupby(tk).transform(lambda s: s.shift(1).rolling(LOOKBACK).std())
    vol_q = vol20.groupby(tk).transform(lambda s: s.shift(1).rolling(252).quantile(SQUEEZE_Q))
    prior_high20 = g.transform(lambda s: s.shift(1).rolling(LOOKBACK).max())
    raw_b = (vol20 <= vol_q) & (c > prior_high20)
    masks = {"BREAKOUT_PULLBACK": first_only(raw_a, tk), "SQUEEZE_BREAKOUT": first_only(raw_b, tk)}

    ks_info = {}
    try:
        import FinanceDataReader as fdr
        ks = fdr.DataReader("KS11", "2020-01-01")["Close"]
        up = (ks > ks.rolling(200).mean()).where(ks.rolling(200).count() == 200)
        regime = df["trade_date"].map(up)
        masks["REGIME_BREAKOUT"] = brk & (regime == True)  # noqa: E712 (NaN은 거짓)
        down_mask = brk & (regime == False)  # noqa: E712
        ks_info = {"ks11Days": int(len(ks)), "ks11Last": str(ks.index[-1].date())}
    except Exception as e:  # 대체 정의 금지 - C 중단
        log.error("KS11 받기 실패 - C 중단: %s", e)
        ks_info, down_mask = {"error": str(e)}, None

    def rows_of(ms):
        return pd.concat([df.loc[(m.fillna(False) & in_uni).values, ["ticker", "trade_date"]].assign(tag=n)
                          for n, m in ms.items()], ignore_index=True)

    earn_rows, earn_info = load_earnings(close, universe)
    sig = pd.concat([rows_of(masks), earn_rows], ignore_index=True)
    entry = to_entry(sig, days)
    signs = {k: v for k, v in SIGN.items() if k in set(sig["tag"])}

    # 기준선 = 신호일 T의 유니버스, 진입일 키로 옮김
    uni_t1 = {days[i + 1]: universe[d] for i, d in enumerate(days[:-1]) if d in universe}
    cache = {}

    def excess(h, key="t1"):
        if (h, key) not in cache:
            cache[(h, key)] = et.excess_returns(close, uni_t1 if key == "t1" else universe, h)
        return cache[(h, key)]

    dev = {h: judge(dev_rows(entry, days, h), excess(h), days, h, signs) for h in et.HORIZONS}
    et.EXPECTED_SIGN = signs
    et.print_table("dev (T+1)", dev)
    pairs = passed_pairs(dev)
    log.info("개발 통과 쌍: %s", pairs)
    hold = holdout(entry, {h: (lambda h=h: excess(h)) for h in et.HORIZONS}, days, pairs)
    for h, tags in hold.items():
        et.EXPECTED_SIGN = {t: SIGN[t] for t in tags}
        et.print_table("holdout (T+1)", {h: tags})
    final = [f"{tag}:{h}" for h, tag in pairs if hold.get(h, {}).get(tag, {}).get("verdict") == "통과"]

    # 진단 (개발 구간만 - 확인 기간은 보지 않는다)
    old_sign = {k: +1 for k in old}
    old_rows = rows_of(old)
    diag = {"entryRules_T0": {}, "entryRules_T1": {}, "regimeDown_T1": {}}
    t0_rows = old_rows.assign(signal_date=old_rows["trade_date"])  # T+0: 진입일 = 신호일
    t1_rows = to_entry(old_rows, days)
    if down_mask is not None:
        down_rows = to_entry(rows_of({"REGIME_BREAKOUT_DOWN": down_mask}), days)
    for h in et.HORIZONS:
        diag["entryRules_T0"][h] = judge(dev_rows(t0_rows, days, h), excess(h, "t0"), days, h, old_sign)
        diag["entryRules_T1"][h] = judge(dev_rows(t1_rows, days, h), excess(h), days, h, old_sign)
        if down_mask is not None:
            diag["regimeDown_T1"][h] = judge(dev_rows(down_rows, days, h), excess(h), days, h,
                                             {"REGIME_BREAKOUT_DOWN": +1})
    for name, res in diag.items():
        if res:
            et.EXPECTED_SIGN = {k: +1 for k in res[et.HORIZONS[0]]}
            et.print_table(f"diag {name} (dev)", res)

    counts = {tag: {"signals": int((sig["tag"] == tag).sum()),
                    "holdoutSignals": int(((entry["tag"] == tag) & (entry["signal_date"] >= HOLD_START)).sum()),
                    "devAfterPurge": {h: int((dev_rows(entry, days, h)["tag"] == tag).sum()) for h in et.HORIZONS}}
              for tag in SIGN}
    elapsed = round(time.time() - t0, 1)
    result = {
        "preregistered": PREREGISTERED,
        "dev": dev,
        "holdout": hold,
        "diagnostic": {**diag, "note": "개발 구간만, 판정 미포함"},
        "final": final,
        "meta": {"priceRange": [str(days.min().date()), str(days.max().date())], "counts": counts,
                 "earnings": earn_info, "ks11": ks_info, "regimeStopped": "REGIME_BREAKOUT" not in signs,
                 "devPassedPairs": [f"{t}:{h}" for h, t in pairs], "elapsedSec": elapsed},
    }
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    log.info("최종 통과: %s  (%.1fs) -> %s", final, elapsed, RESULT_PATH)


if __name__ == "__main__":
    _self_check()
    main()
