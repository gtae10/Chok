"""evaluate_baseline 자가점검 - 실행: python test_evaluate_baseline.py"""
import numpy as np
import pandas as pd

from evaluate_baseline import quantile_of, rank_ic_by_date, strategy, walk_forward_predictions
from train_model import (FEATURE_NAMES, build_dataset, compute_features_for_ticker, dataset_for_horizon,
                         restrict_to_universe)


def synthetic_prices(n_days=400, n_tickers=30, seed=0):
    rng = np.random.default_rng(seed)
    days = pd.bdate_range("2022-01-03", periods=n_days)
    rows = [pd.DataFrame({"ticker": f"T{t:02d}", "trade_date": days,
                          "close_price": 100 * np.cumprod(1 + rng.normal(0, 0.02, n_days)),
                          "volume": rng.uniform(1e5, 1e6, n_days)}) for t in range(n_tickers)]
    return pd.concat(rows, ignore_index=True)


if __name__ == "__main__":
    d = pd.Timestamp("2024-01-02")
    # rank IC: 완벽 순위 = 1, 역순 = -1 (예측 확률과 수익률 크기가 달라도 순위만 본다)
    df = pd.DataFrame({"trade_date": d, "p": [0.1, 0.2, 0.3, 0.4, 0.5], "excess": [-0.05, -0.01, 0.0, 0.02, 0.30]})
    assert np.isclose(rank_ic_by_date(df).iloc[0], 1.0)
    assert np.isclose(rank_ic_by_date(df.assign(excess=df["excess"].values[::-1])).iloc[0], -1.0)
    # 손계산: 순위 p=[1,2,3], e=[1,3,2] -> 편차 [-1,0,1]·[-1,1,0] = 1 / sqrt(2*2) = 0.5
    df3 = pd.DataFrame({"trade_date": d, "p": [0.1, 0.2, 0.3], "excess": [0.0, 0.2, 0.1]})
    assert np.isclose(rank_ic_by_date(df3).iloc[0], 0.5)

    # 5분위 스프레드 손계산: 10종목, 분위당 2개. Q5 = p 상위 2개(excess 0.04, 0.06 -> 0.05),
    # Q1 = 하위 2개(-0.03, -0.01 -> -0.02) -> 스프레드 0.07
    df10 = pd.DataFrame({"trade_date": d, "p": np.arange(10) / 10,
                         "excess": [-0.03, -0.01, 0, 0, 0, 0, 0, 0, 0.04, 0.06]})
    q = quantile_of(df10)
    assert q.tolist() == [1, 1, 2, 2, 3, 3, 4, 4, 5, 5], q.tolist()
    by_q = df10.assign(q=q).groupby("q")["excess"].mean()
    assert np.isclose(by_q[5] - by_q[1], 0.07)

    # Brier 손계산: p=[0.8,0.3], y=[1,0] -> (0.04+0.09)/2 = 0.065, 기저율 0.5 상수 -> 0.25
    p, y = np.array([0.8, 0.3]), np.array([1, 0])
    assert np.isclose(((p - y) ** 2).mean(), 0.065)
    assert np.isclose(((0.5 - y) ** 2).mean(), 0.25)

    # 전략: 2기간 보유 +10%, +10% vs 기준 0%, 0% -> 누적 초과 21%, 1기간 h=126일이면 1년 -> 연환산 21%
    s = strategy(pd.Series([0.1, 0.1]), pd.Series([0.0, 0.0]), 126)
    assert np.isclose(s["cumExcess"], 0.21) and np.isclose(s["annualExcess"], 0.21)
    # MDD: +10% 뒤 -50% -> 고점 1.1 대비 0.55 = -50%
    assert np.isclose(strategy(pd.Series([0.1, -0.5]), pd.Series([0.0, 0.0]), 5)["maxDrawdownRelative"], -0.5)

    # 누수 방지 1: 미래 가격을 바꿔도 오늘까지의 특징은 그대로 (fwd 수익률만 바뀌어야 정상)
    g = synthetic_prices(n_tickers=1)
    cut = 250
    g2 = g.copy()
    g2.loc[cut + 1:, "close_price"] *= 3.0
    g2.loc[cut + 1:, "volume"] *= 0.1
    f1, f2 = compute_features_for_ticker(g, [5]), compute_features_for_ticker(g2, [5])
    pd.testing.assert_frame_equal(f1.loc[:cut, FEATURE_NAMES], f2.loc[:cut, FEATURE_NAMES])
    assert not np.allclose(f1.loc[cut, "fwd_return_5"], f2.loc[cut, "fwd_return_5"])

    # 누수 방지 2: 폴드마다 학습 라벨 기간이 테스트 첫 날짜 전에 끝난다 (학습 최대 날짜 + h < 테스트 첫 날짜)
    prices = synthetic_prices()
    universe = prices[["ticker", "trade_date"]]
    for h in (5, 20, 50):
        ds = restrict_to_universe(build_dataset(prices, [h]), universe, [h])
        sub = dataset_for_horizon(ds, h, "relative")
        preds, folds = walk_forward_predictions(sub)
        assert folds, h
        days = pd.Index(np.sort(prices["trade_date"].unique()))
        for f in folds:
            assert pd.Timestamp(f["trainMaxTargetDate"]) < pd.Timestamp(f["testStart"]), (h, f)
            # 같은 말을 거래일로: 학습 최대 날짜 위치 + h <= 테스트 첫 날짜 위치보다 앞
            assert days.get_loc(pd.Timestamp(f["trainMaxDate"])) + h < days.get_loc(pd.Timestamp(f["testStart"])), (h, f)
        # 예측은 각 날짜에 한 폴드에서만 나온다 (테스트 구간 겹침 없음)
        assert preds.groupby("trade_date")["fold"].nunique().max() == 1
    print("evaluate_baseline self-check passed")
