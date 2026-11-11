"""forward_signals.compute 자체 점검 (DB·네트워크 없음). 실행: PYTHONUTF8=1 .venv/Scripts/python.exe test_forward_signals.py"""
import numpy as np
import pandas as pd

from forward_signals import compute

days = pd.bdate_range("2026-06-01", periods=70)
asof = days[-2]  # 마지막 날(days[-1])은 asof 이후 = 미래 데이터


def closes_for(t, seed):
    rng = np.random.default_rng(seed)
    return pd.DataFrame({"ticker": t, "trade_date": days, "close_price": 100 * np.cumprod(1 + rng.normal(0, 0.01, len(days)))})


closes = pd.concat([closes_for("000010", 1), closes_for("000020", 2)], ignore_index=True)
closes.loc[(closes["ticker"] == "000010") & (closes["trade_date"] == days[-1]), "close_price"] = 1e9  # 미래 급등
shorts = pd.DataFrame({"ticker": ["000010", "000010", "000010"], "trade_date": [days[-4], days[-3], days[-2]],
                       "short_ratio": [1.0, 2.0, 3.0]})
panel = pd.DataFrame({"ticker": ["000010", "000010"], "avail_date": [days[10], days[-1]],
                      "period_end": pd.to_datetime(["2026-03-31", "2026-06-30"]),
                      "book_equity": [500.0, 9999.0], "ni_ttm": [1.0, 1.0], "op_income_ttm": [1.0, 1.0],
                      "total_assets": [1.0, 1.0], "revenue_ttm": [1.0, 1.0],
                      "is_financial": [False, False], "amended": [False, False]})
marcap = {"000010": 1000, "000020": 0}

rows = compute(asof, ["000010", "000020"], marcap, closes, shorts, {"000010": 50.0, "000020": 7.0}, panel)
got = {(t, s): v for _, t, s, v in rows}

# 누수: asof 이후 공시(9999)는 안 쓰고 이전 보고서 500/1000
assert got[("000010", "bp")] == 0.5, got
# 공매도는 asof 기준 2거래일 전 값(days[-4] = 1.0), asof·전날 값은 안 씀
assert got[("000010", "short_ratio")] == 1.0, got
# vol60: asof 이후 1e9 종가가 들어가면 표준편차가 폭발한다
a = closes[(closes["ticker"] == "000010") & (closes["trade_date"] <= asof)]["close_price"]
assert abs(got[("000010", "vol60")] - a.pct_change().iloc[-60:].std()) < 1e-12 and got[("000010", "vol60")] < 0.1
assert got[("000010", "inst20")] == 0.05
# 시총 0/없음 → bp·inst20 기록 안 함, 데이터 없는 신호도 안 함
assert ("000020", "bp") not in got and ("000020", "inst20") not in got and ("000020", "short_ratio") not in got
assert ("000020", "vol60") in got
assert all(d == asof.date() for d, *_ in rows)
# 재무 패널 없음 → bp만 빠진다
assert not any(s == "bp" for _, _, s, _ in compute(asof, ["000010"], marcap, closes, shorts, {}, None))
print("test_forward_signals passed")
