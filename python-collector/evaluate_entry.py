"""
evaluate_entry.py — "언제 살까" 진입 규칙 백테스트. 판정 기준은 evaluate_tags.py(C1 v1)를 그대로 쓴다.

규칙 4개와 가설(+ = 신호 뒤 시장 평균보다 오른다)은 이 파일을 돌리기 전에 고정한다. 결과를 보고 임계값을 바꾸지 않는다.
바꾸려면 새 규칙으로 추가하고 다중비교(규칙 개수)를 감안해 해석한다.
  PULLBACK     : 장기 상승 중(종가 > MA200)인데 20일 고점 대비 -10% 이하로 눌림
  OVERSOLD     : 종가가 MA20보다 -10% 이하 (추세 조건 없음, PULLBACK의 대조군)
  BREAKOUT_52W : 종가가 직전 252일 최고가 이상 (신고가 돌파)
  VOL_SURGE_UP : 거래량이 직전 20일 평균의 3배 이상이면서 당일 +3% 이상 상승

실행: python evaluate_entry.py     결과: model/entry_evaluation_result.json
"""
import json
import logging

import numpy as np
import pandas as pd

import evaluate_tags as et
from train_model import load_pit_price_history

log = logging.getLogger(__name__)

ENTRY_SIGN = {"PULLBACK": +1, "OVERSOLD": +1, "BREAKOUT_52W": +1, "VOL_SURGE_UP": +1}
RESULT_PATH = "model/entry_evaluation_result.json"


def entry_masks(df: pd.DataFrame) -> dict:
    """df: ticker/trade_date 정렬된 close_price, volume. 종목별 rolling이라 groupby로 계산."""
    g = df.groupby("ticker")["close_price"]
    ma20 = g.transform(lambda s: s.rolling(20).mean())
    ma200 = g.transform(lambda s: s.rolling(200).mean())
    high20 = g.transform(lambda s: s.rolling(20).max())
    prior_high252 = g.transform(lambda s: s.shift(1).rolling(252).max())
    vol_avg20 = df.groupby("ticker")["volume"].transform(lambda s: s.shift(1).rolling(20).mean())
    ret1 = g.pct_change()
    c = df["close_price"]
    return {
        "PULLBACK": (c > ma200) & (c / high20 - 1 <= -0.10),
        "OVERSOLD": c / ma20 - 1 <= -0.10,
        "BREAKOUT_52W": c >= prior_high252,
        "VOL_SURGE_UP": (df["volume"] >= 3 * vol_avg20) & (ret1 >= 0.03),
    }


def main():
    price_df, universe_df = load_pit_price_history()
    df = price_df.sort_values(["ticker", "trade_date"]).reset_index(drop=True)
    masks = entry_masks(df)
    df = df.merge(universe_df.assign(in_uni=True), on=["ticker", "trade_date"], how="left")
    in_uni = df["in_uni"].fillna(False).astype(bool)
    tag_rows = pd.concat(
        [df.loc[(m.fillna(False) & in_uni).values, ["ticker", "trade_date"]].assign(tag=name)
         for name, m in masks.items()], ignore_index=True)
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    universe = universe_df.groupby("trade_date")["ticker"].apply(set).to_dict()
    log.info("entry backtest: %s ~ %s, 신호 %d건", close.index.min().date(), close.index.max().date(), len(tag_rows))

    et.EXPECTED_SIGN = ENTRY_SIGN  # summarize/print_table이 모듈 전역을 읽는다
    result = {h: et.summarize(tag_rows, et.excess_returns(close, universe, h), close.index, h) for h in et.HORIZONS}
    et.print_table("entry", result)
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)


if __name__ == "__main__":
    main()
