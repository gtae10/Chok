"""
backfill_short_balance.py — 1회용. experiment_flow_short.py가 받아둔 data/krx/short_{ticker}.pkl을
short_balance 테이블에 넣는다 (이후로는 collect.py의 fetch_short_balance가 매일 최근분을 채운다).
실행: python backfill_short_balance.py
"""
import glob
import os

import pandas as pd

from db import upsert_short_rows

CACHE_DIR = os.path.join("data", "krx")
CHUNK = 5000

if __name__ == "__main__":
    rows = []
    for path in sorted(glob.glob(os.path.join(CACHE_DIR, "short_*.pkl"))):
        ticker = os.path.basename(path)[len("short_"):-len(".pkl")]
        s = pd.read_pickle(path)["short_ratio"].dropna()
        rows += [(ticker, d.strftime("%Y-%m-%d"), round(float(v), 4)) for d, v in s.items()]
    for i in range(0, len(rows), CHUNK):
        upsert_short_rows(rows[i:i + CHUNK])
    dates = [r[1] for r in rows]
    print(f"공매도 잔고 백필 완료: {len(rows)}행, 종목 {len({r[0] for r in rows})}개, "
          f"{min(dates) if dates else '-'} ~ {max(dates) if dates else '-'}")
