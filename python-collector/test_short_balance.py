"""공매도 특징 시차 + 수집 날짜 범위 점검 (네트워크/DB 없음) - 실행: pytest test_short_balance.py 또는 python test_short_balance.py"""
import datetime

import numpy as np
import pandas as pd

from collect import SHORT_MAX_BACKFILL_DAYS, short_fetch_dates
from train_model import SHORT_FEATURE, compute_short_feature


def test_short_feature_uses_value_two_trading_days_earlier():
    # 종목 A: 1/3(금) -> 1/6(월)처럼 주말을 건너뛰어도 달력일이 아니라 거래일 두 칸 전 값을 써야 한다.
    # 1/7은 공매도 데이터가 빠진 날 - 그래도 거래일 한 칸으로 센다. 종목 B 값이 A로 새면 안 된다.
    a_dates = pd.to_datetime(["2025-01-02", "2025-01-03", "2025-01-06", "2025-01-07", "2025-01-08", "2025-01-09"])
    b_dates = pd.to_datetime(["2025-01-02", "2025-01-03", "2025-01-06"])
    price = pd.DataFrame({"ticker": ["A"] * 6 + ["B"] * 3, "trade_date": list(a_dates) + list(b_dates)})
    short = pd.DataFrame({
        "ticker": ["A", "A", "A", "A", "A", "B", "B", "B"],
        "trade_date": list(a_dates[[0, 1, 2, 4, 5]]) + list(b_dates),
        "short_ratio": [1.0, 2.0, 3.0, 5.0, 6.0, 10.0, 20.0, 30.0],
    })
    got = compute_short_feature(price, short).set_index(["ticker", "trade_date"])[SHORT_FEATURE]

    a = got.loc["A"].tolist()
    assert np.isnan(a[0]) and np.isnan(a[1]), a
    assert a[2:5] == [1.0, 2.0, 3.0] and np.isnan(a[5]), a  # 1/9 = 1/7 값(없음) -> NaN
    b = got.loc["B"].tolist()
    assert np.isnan(b[0]) and np.isnan(b[1]) and b[2] == 10.0, b


def test_short_fetch_dates_starts_after_last_saved_skips_weekends_and_caps():
    d = datetime.date
    # 11/04(수) 저장됨 -> 11/05(목) ~ 11/09(월), 주말 11/07·08 제외
    assert short_fetch_dates(d(2026, 11, 4), d(2026, 11, 9)) == [d(2026, 11, 5), d(2026, 11, 6), d(2026, 11, 9)]
    assert short_fetch_dates(d(2026, 11, 9), d(2026, 11, 9)) == []  # 이미 최신
    # 아주 오래 비었거나 테이블이 비어 있으면 SHORT_MAX_BACKFILL_DAYS일 전까지만
    today = d(2026, 11, 9)
    floor = today - datetime.timedelta(days=SHORT_MAX_BACKFILL_DAYS)
    for last in (d(2025, 1, 1), None):
        got = short_fetch_dates(last, today)
        assert got[0] >= floor and got[-1] == today and all(x.weekday() < 5 for x in got), got
        assert len(got) == sum((floor + datetime.timedelta(days=i)).weekday() < 5 for i in range(SHORT_MAX_BACKFILL_DAYS + 1))


if __name__ == "__main__":
    test_short_feature_uses_value_two_trading_days_earlier()
    test_short_fetch_dates_starts_after_last_saved_skips_weekends_and_caps()
    print("short balance self-check passed")
