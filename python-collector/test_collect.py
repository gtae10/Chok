"""collect.py 자가점검 - 실행: python test_collect.py"""
import datetime

import numpy as np
import pandas as pd

from collect import KST, compute_issuance, confirmed_until


def frame(code, marcap):
    d = pd.bdate_range("2025-01-01", periods=len(marcap))
    return pd.DataFrame({"Date": d, "Code": code, "Marcap": marcap, "ChangesRatio": 0.0})


if __name__ == "__main__":
    # 순발행: 가격 그대로 시총 +10% = 증자 / 액면분할은 시총·수정종가가 연속이라 0 / 이력 252영업일 미만 제외
    issued = frame("A", np.r_[np.full(150, 100.0), np.full(150, 110.0)])
    split = frame("B", np.full(300, 100.0))
    short = frame("C", np.full(100, 100.0))
    got = compute_issuance(pd.concat([issued, split, short]), {"A", "B", "C"})
    assert abs(got["A"] - np.log(1.1)) < 1e-9, got
    assert abs(got["B"]) < 1e-9, got
    assert "C" not in got, got

    # 확정 종가: 16시 전 수집이면 오늘 행 제외, 16시 이후면 포함. UTC 시각이 들어와도 한국 시각으로 판단
    at = lambda h, m=0: datetime.datetime(2026, 9, 30, h, m, tzinfo=KST)
    assert confirmed_until(at(12)) == pd.Timestamp("2026-09-30")          # 장중: 9/29까지
    assert confirmed_until(at(15, 59)) == pd.Timestamp("2026-09-30")
    assert confirmed_until(at(16)) == pd.Timestamp("2026-10-01")          # 마감 뒤: 9/30 포함
    utc_noon = datetime.datetime(2026, 9, 30, 3, 0, tzinfo=datetime.timezone.utc)  # = KST 12:00
    assert confirmed_until(utc_noon.astimezone(KST)) == pd.Timestamp("2026-09-30")
    print("collect self-check passed")
