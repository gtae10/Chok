"""test_cross_section.py — experiment_cross_section 자가점검 (assert 기반). 실행: PYTHONUTF8=1 python test_cross_section.py"""
import os

import numpy as np
import pandas as pd

import experiment_cross_section as xs

DAYS = pd.bdate_range("2021-01-04", periods=400)


def fake_ticker(t, close, open_=None, changes=None):
    close = np.asarray(close, float)
    return pd.DataFrame({"ticker": t, "trade_date": DAYS[:len(close)], "close_price": close, "Close": close,
                         "Open": close if open_ is None else open_,
                         "Changes": np.r_[0, np.diff(close)] if changes is None else changes,
                         "Marcap": close * 1e6, "Amount": 1e9})


def test_entry_return_by_hand():
    close = np.arange(100, 500, dtype=float)
    f = xs.ticker_frame(fake_ticker("000001", close))
    # T = 0행: 진입 1행(101), 청산 1+20행(121)
    assert abs(f["r_20"].iloc[0] - (121 / 101 - 1)) < 1e-12
    assert f["exit_20"].iloc[0] == DAYS[21]
    assert np.isnan(f["r_20"].iloc[-21])  # 청산일이 없으면 NaN


def panel(n_dates=60, n=50, seed=0):
    rng = np.random.default_rng(seed)
    rows = []
    for d in DAYS[:n_dates]:
        for i in range(n):
            rows.append({"trade_date": d, "ticker": f"{i:06d}", **{f"r_{h}": rng.normal(0, .1) for h in xs.HORIZONS}})
    return pd.DataFrame(rows)


def test_excess_mean_zero_and_ic_matches_raw():
    ds = xs.add_excess(panel())
    assert ds.groupby("trade_date")["e_20"].mean().abs().max() < 1e-12
    ds["f"] = ds["r_20"] + np.random.default_rng(1).normal(0, .1, len(ds))
    a = xs.daily_ic(ds, "f", "y_20").mean()
    b = xs.daily_ic(ds, "f", "r_20").mean()
    assert abs(a - b) < 0.01, (a, b)


def test_fundamentals_avail_date():
    ds = pd.DataFrame({"ticker": "000001", "trade_date": pd.to_datetime(["2022-03-10", "2022-03-11"]),
                       "Marcap": 100.0})
    p = pd.DataFrame({"ticker": ["000001"], "avail_date": pd.to_datetime(["2022-03-11"]),
                      "period_end": pd.to_datetime(["2021-12-31"]), "book_equity": [50.0], "ni_ttm": [10.0],
                      "op_income_ttm": [5.0], "total_assets": [200.0], "is_financial": [False], "amended": [False]})
    m = xs.add_fundamentals(ds, p).sort_values("trade_date")
    assert np.isnan(m["bp"].iloc[0])           # avail_date 전날(접수일)은 미사용
    assert m["bp"].iloc[1] == 0.5 and m["op_roa"].iloc[1] == 0.025  # avail_date 당일부터 사용
    # 지난 분기 정정이 새 분기 뒤에 와도 새 분기 값을 유지
    p2 = pd.concat([p, p.assign(avail_date=pd.Timestamp("2022-05-16"), period_end=pd.Timestamp("2022-03-31"),
                                book_equity=60.0),
                    p.assign(avail_date=pd.Timestamp("2022-06-01"), book_equity=1.0, amended=True)])
    ds2 = pd.DataFrame({"ticker": "000001", "trade_date": [pd.Timestamp("2022-06-10")], "Marcap": 100.0})
    assert xs.add_fundamentals(ds2, p2)["bp"].iloc[0] == 0.6
    # 금융업은 op_roa NaN
    assert np.isnan(xs.add_fundamentals(ds.iloc[[1]], p.assign(is_financial=True))["op_roa"].iloc[0])


def test_purge_boundary():
    ds = pd.DataFrame({"trade_date": pd.to_datetime(["2024-05-31", "2024-06-03", "2024-06-28", "2024-07-01"]),
                       "exit_20": pd.to_datetime(["2024-06-28", "2024-07-01", "2024-07-29", "2024-07-30"])})
    assert list(xs.split_dev(ds, 20)["trade_date"]) == [pd.Timestamp("2024-05-31")]


def test_gap_split_day():
    close = np.full(300, 50000.0)
    close[260:] = 10000.0  # 1:5 분할, 분할일 기준가 10000으로 조정 → Changes = 0
    open_ = close.copy()
    changes = np.zeros(300)
    g = fake_ticker("000001", close, open_, changes)
    g["close_price"] = 1.0  # 수정지수는 평평
    assert abs(xs.ticker_frame(g)["overnight"].iloc[-1]) < 1e-9
    # 실제 marcap: 원가격이 반 이하로 꺾였는데 등락률은 작은 날(분할) gap ≈ 0
    path = os.path.join("data", "marcap", "marcap-2021.parquet")
    if os.path.exists(path):
        m = pd.read_parquet(path, columns=["Code", "Date", "Close", "Open", "Changes", "ChangesRatio"])
        m = m.sort_values(["Code", "Date"])
        prev = m.groupby("Code")["Close"].shift()
        split = m[(m["Close"] < prev * 0.5) & (m["ChangesRatio"].abs() < 15) & (m["Open"] > 0)]
        gap = split["Open"] / (split["Close"] - split["Changes"]) - 1
        raw = split["Open"] / prev[split.index] - 1  # 조정 안 하면 -50% 이상
        # 분할·무상증자 권리락일엔 실제 시가 갭이 있다(카카오 2021-04-15 +7.6%) - 원가격 기준 -50%만 아니면 됨
        assert len(split) > 0 and gap.abs().median() < 0.1 and raw.median() < -0.4, (gap.abs().median(), raw.median())


def test_industry_momentum_excludes_self():
    ds = pd.DataFrame({"trade_date": DAYS[0], "ticker": list("abcde"),
                       "industry": ["X", "X", "X", "Y", "Y"], "mom12_1": [0.1, 0.2, np.nan, 0.5, 0.7]})
    out = xs.industry_momentum(ds)
    assert abs(out[0] - 0.2) < 1e-12 and abs(out[1] - 0.1) < 1e-12 and abs(out[2] - 0.15) < 1e-12
    assert out[3:].isna().all()  # 업종 내 2종목 → NaN


def test_canary():
    ds = xs.add_excess(panel(n_dates=120))
    ds["ticker"] = ds["ticker"]
    tdays = pd.Index(DAYS)
    ds["fut"] = ds["r_20"]
    m = xs.evaluate(ds, "fut", +1, 20, tdays)
    assert m["icMean"] > 0.95 and m["spreadWin"] > 0.1, m
    shuffled = ds.groupby("ticker")["r_20"].transform(lambda s: s.sample(frac=1, random_state=0).values)
    ds["shuf"] = shuffled
    m = xs.evaluate(ds, "shuf", +1, 20, tdays)
    assert abs(m["icMean"]) < 0.05, m


def test_holdout_gate():
    bad = {"icMean": 0.01, "sameSignFolds": 9, "spreadWin": 0.02}
    try:
        xs.holdout_eval(None, "x", +1, bad, None)
    except PermissionError:
        pass
    else:
        raise AssertionError("개발 불통과인데 holdout이 열림")
    assert xs.dev_pass({"icMean": 0.03, "sameSignFolds": 7, "spreadWin": 0.01})
    assert not xs.dev_pass({"icMean": 0.03, "sameSignFolds": 6, "spreadWin": 0.02})
    assert not xs.dev_pass({"icMean": 0.075, "sameSignFolds": 8, "spreadWin": 0.009961})  # 반올림하면 0.0100 - 2차 실행 버그


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
    print("all passed")
