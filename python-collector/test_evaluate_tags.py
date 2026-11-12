"""evaluate_tags 자가점검 - 실행: python test_evaluate_tags.py"""
import numpy as np
import pandas as pd

import evaluate_tags
from evaluate_tags import (ks_trend_judge, non_overlapping_dates, summarize,
                           tags_for_day, top_share_mask)

if __name__ == "__main__":
    # TagService.topShareCutoff 테스트와 같은 사례: 값 5개 중 상위 20% = 1개(50), null은 세지 않음
    m = top_share_mask(pd.Series([10.0, np.nan, 50.0, 30.0, 20.0, 40.0]), 0.2)
    assert m.tolist() == [False, False, True, False, False, False], m.tolist()

    # TagServiceTest와 같은 규칙: 10종목 중 모멘텀 상위 20% = T9,T10 / 저변동성 하위 20% = T1,T2
    day = pd.DataFrame({"ticker": [f"T{i}" for i in range(1, 11)],
                        "momentum12m": np.arange(1.0, 11), "vol60": np.arange(1.0, 11),
                        "issuance252": [0.05, -0.05] + [0.0] * 8, "gap52": [0.0] + [-0.01] * 9})
    got = tags_for_day(day).groupby("tag")["ticker"].apply(sorted).to_dict()
    assert got == {"MOMENTUM_TOP20": ["T10", "T9"], "LOW_VOL20": ["T1", "T2"],
                   "ISSUANCE_UP": ["T1"], "BUYBACK": ["T2"], "BREAKOUT_52W": ["T1"]}, got

    days = pd.bdate_range("2026-01-01", periods=30)
    # 매일 기록돼도 h=5면 0,5,10...번째 날만 창으로 센다
    assert non_overlapping_dates(days, days, 5) == list(days[::5])

    # 창 6개 모두 가설(-) 방향으로 -3% -> 5일 기준(|x|>=0.5%) 통과, 창 5개면 "창 부족"
    tag_rows = pd.DataFrame({"trade_date": days[::5], "ticker": "A", "tag": "ISSUANCE_UP"})
    excess = pd.DataFrame({"trade_date": days[::5], "ticker": "A", "excess": -0.03})
    assert summarize(tag_rows, excess, days, 5)["ISSUANCE_UP"]["verdict"] == "통과"
    assert summarize(tag_rows.iloc[:5], excess, days, 5)["ISSUANCE_UP"]["verdict"] == "창 부족"
    # 가설과 반대 방향(+3%)이면 크기와 무관하게 불통과
    assert summarize(tag_rows, excess.assign(excess=0.03), days, 5)["ISSUANCE_UP"]["verdict"] == "불통과"
    # KS_TREND 실전: 시작일 전 200일 + 이후 60일 꾸준히 상승 -> 매일 신호, 20일 창은 6개 미만이라 판정 불가
    kd = pd.bdate_range(end="2026-11-10", periods=200).append(pd.bdate_range("2026-11-11", periods=60))
    k = ks_trend_judge(pd.Series(np.linspace(100, 200, len(kd)), index=kd))
    assert (k["signalDays"], k["horizons"][20]["windows"], k["horizons"][20]["verdict"], k["horizons"][50]["verdict"],
            "BREAKOUT_52W" in evaluate_tags.EXPECTED_SIGN) == (60, 2, "판정 불가", "판정 불가", True), k

    # --live-only/집계 실패: 새로 계산한 키만 덮고 backtest·이전 live는 남긴다
    print("evaluate_tags self-check passed")
