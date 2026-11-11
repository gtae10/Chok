"""forward_signals.compute 자체 점검 (DB·네트워크 없음). 실행: PYTHONUTF8=1 .venv/Scripts/python.exe test_forward_signals.py"""
import numpy as np
import pandas as pd

from forward_signals import compute, latest_reports, parent_equity

days = pd.bdate_range("2026-06-01", periods=70)
asof = days[-2]  # 마지막 날(days[-1])은 asof 이후 = 미래 데이터


def closes_for(t, seed):
    rng = np.random.default_rng(seed)
    return pd.DataFrame({"ticker": t, "trade_date": days, "close_price": 100 * np.cumprod(1 + rng.normal(0, 0.01, len(days)))})


closes = pd.concat([closes_for("000010", 1), closes_for("000020", 2)], ignore_index=True)
closes.loc[(closes["ticker"] == "000010") & (closes["trade_date"] == days[-1]), "close_price"] = 1e9  # 미래 급등
shorts = pd.DataFrame({"ticker": ["000010", "000010", "000010"], "trade_date": [days[-4], days[-3], days[-2]],
                       "short_ratio": [1.0, 2.0, 3.0]})
panel = pd.DataFrame({"ticker": ["000010"] * 3,
                      "avail_date": [days[10], days[20], days[-1]],
                      "period_end": pd.to_datetime(["2026-03-31", "2025-12-31", "2026-06-30"]),
                      "bsns_year": [2026, 2025, 2026], "quarter": [1, 4, 2], "currency": "KRW"})
# 누수: asof 이후 공시(2Q)는 안 쓰고, 더 새 분기(1Q) 뒤에 온 지난 분기(4Q) 정정도 버린다 → 1Q
lr = latest_reports(panel, asof)
assert lr[["bsns_year", "quarter"]].values.tolist() == [[2026, 1]], lr
assert latest_reports(panel, days[5]).empty

# 지배주주 자본: 연결은 지배기업 주주지분, 비지배지분 없으면 자본총계, 둘 다 모르면 NaN
bs = lambda *rows: {"list": [{"sj_div": "BS", "account_id": i, "account_nm": n, "thstrm_amount": v} for i, n, v in rows]}
assert parent_equity(bs(("ifrs-full_Equity", "자본총계", "54,902"), ("ifrs-full_EquityAttributableToOwnersOfParent", "지배기업 주주지분", "13,435"),
                        ("ifrs-full_NoncontrollingInterests", "비지배지분", "41,467"))) == 13435
assert parent_equity(bs(("-표준계정코드 미사용-", "지배기업의 소유주에게 귀속되는 자본", "700"), ("ifrs-full_NoncontrollingInterests", "비지배지분", "1"))) == 700
assert parent_equity(bs(("ifrs-full_Equity", "자본총계", "500"))) == 500
assert np.isnan(parent_equity(bs(("ifrs-full_Equity", "자본총계", "500"), ("ifrs-full_NoncontrollingInterests", "비지배지분", "1"))))

marcap = {"000010": 1000, "000020": 0, "000030": 2000, "000040": 2000}
equity = {"000010": (500.0, "KRW"), "000020": (9.0, "KRW"), "000030": (1.0, "USD"), "000040": (1.0, "EUR")}
tickers = list(marcap)
rows = compute(asof, tickers, marcap, closes, shorts, {"000010": 50.0, "000020": 7.0}, equity, {"USD": 1400.0})
got = {(t, s): v for _, t, s, v in rows}

assert got[("000010", "bp")] == 0.5, got
assert got[("000030", "bp")] == 0.7  # USD 자본 × 환율 / 시총
assert ("000040", "bp") not in got   # 환율 없는 통화는 기록 안 함
# 공매도는 asof 기준 2거래일 전 값(days[-4] = 1.0), asof·전날 값은 안 씀
assert got[("000010", "short_ratio")] == 1.0, got
# vol60: asof 이후 1e9 종가가 들어가면 표준편차가 폭발한다
a = closes[(closes["ticker"] == "000010") & (closes["trade_date"] <= asof)]["close_price"]
assert abs(got[("000010", "vol60")] - a.pct_change().iloc[-60:].std()) < 1e-12 and got[("000010", "vol60")] < 0.1
assert got[("000010", "inst20")] == 0.05
# 시총 0 → bp·inst20 기록 안 함, 데이터 없는 신호도 안 함
assert ("000020", "bp") not in got and ("000020", "inst20") not in got and ("000020", "short_ratio") not in got
assert ("000020", "vol60") in got and ("000030", "vol60") not in got
assert all(d == asof.date() for d, *_ in rows)
print("test_forward_signals passed")
