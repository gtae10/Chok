"""
backtest.py
- 매달 첫 거래일에 종합점수 상위 N종목을 매수해서 HOLD_DAYS 보유했을 때의 수익률을 시뮬레이션.
- v1: 거래량 지표 도입 이전 공식 (MA35% + RSI25% + MACD25% + BB15%)
- v2: 거래량 지표 도입 이후 공식 (MA30% + RSI20% + MACD20% + BB15% + Volume15%)
      -> Java TechnicalAnalysisService의 scoreVolume()/obvTrend()와 동일한 로직으로 맞춤
- 같은 기간/같은 리밸런싱 시점에 v1, v2를 각각 돌려서 승률/평균수익률/변동성을 나란히 비교한다.
  v2가 v1보다 유의미하게 낫지 않다면, 거래량 지표를 넣은 게 실제로는 도움이 안 된다는 뜻.
"""
import logging
import mysql.connector
import pandas as pd
import numpy as np
from typing import Dict

import config

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# 설정
TOP_N = 5
HOLD_DAYS = 30
INITIAL_CAPITAL = 1_000_000


def get_connection():
    return mysql.connector.connect(
        host=config.DB_HOST, port=config.DB_PORT, database=config.DB_NAME,
        user=config.DB_USER, password=config.DB_PASSWORD, charset="utf8mb4"
    )


def load_price_data() -> Dict[str, pd.DataFrame]:
    """전체 종목의 가격+거래량 데이터를 딕셔너리로 로드"""
    conn = get_connection()
    query = """
        SELECT p.ticker, s.name, p.trade_date, p.close_price, p.volume
        FROM price_history p
        JOIN stocks s ON p.ticker = s.ticker
        ORDER BY p.ticker, p.trade_date
    """
    df = pd.read_sql(query, conn)
    conn.close()

    df["trade_date"] = pd.to_datetime(df["trade_date"])
    result = {}
    for ticker, group in df.groupby("ticker"):
        result[ticker] = group.set_index("trade_date").sort_index()
    return result


# v1: 거래량 지표 도입 이전 공식

def calc_score_v1(closes: pd.Series, volumes: pd.Series = None) -> float:
    if len(closes) < 60:
        return 50.0

    price = closes.iloc[-1]
    ma5, ma20, ma60 = closes.iloc[-5:].mean(), closes.iloc[-20:].mean(), closes.iloc[-60:].mean()

    if ma5 > ma20 > ma60:
        ma_score = 80
    elif ma5 < ma20 < ma60:
        ma_score = 20
    elif ma5 > ma20:
        ma_score = 60
    else:
        ma_score = 40
    ma_score += 10 if price > ma5 else -10
    ma_score = max(0, min(100, ma_score))

    delta = closes.diff().iloc[-14:]
    gain = delta.clip(lower=0).mean()
    loss = (-delta.clip(upper=0)).mean()
    rsi = 100 - (100 / (1 + gain / loss)) if loss != 0 else 100
    if rsi <= 30:
        rsi_score = 75
    elif rsi >= 70:
        rsi_score = 30
    elif rsi >= 50:
        rsi_score = 60
    else:
        rsi_score = 45

    ema12 = closes.ewm(span=12, adjust=False).mean().iloc[-1]
    ema26 = closes.ewm(span=26, adjust=False).mean().iloc[-1]
    macd = ema12 - ema26
    signal = closes.ewm(span=9, adjust=False).mean().iloc[-1]
    macd_score = 75 if macd > signal else 30

    sma20 = closes.iloc[-20:].mean()
    std20 = closes.iloc[-20:].std()
    bb_upper, bb_lower = sma20 + 2 * std20, sma20 - 2 * std20
    pct_b = (price - bb_lower) / (bb_upper - bb_lower) if (bb_upper - bb_lower) != 0 else 0.5
    if pct_b <= 0.2:
        bb_score = 70
    elif pct_b >= 0.8:
        bb_score = 35
    else:
        bb_score = 50

    return (ma_score * 0.35) + (rsi_score * 0.25) + (macd_score * 0.25) + (bb_score * 0.15)


# v2: 거래량 지표 포함 (Java TechnicalAnalysisService와 동일 로직)

def _score_volume(closes: pd.Series, volumes: pd.Series) -> float:
    current_volume = volumes.iloc[-1]
    avg_volume20 = volumes.iloc[-20:].mean()
    volume_ratio = current_volume / avg_volume20 if avg_volume20 > 0 else 1.0

    up_day = closes.iloc[-1] > closes.iloc[-2]
    down_day = closes.iloc[-1] < closes.iloc[-2]

    if up_day and volume_ratio >= 1.5:
        score = 80
    elif up_day and volume_ratio >= 1.0:
        score = 62
    elif down_day and volume_ratio >= 1.5:
        score = 20
    elif down_day and volume_ratio >= 1.0:
        score = 38
    else:
        score = 50

    diffs = closes.diff()
    signed_volume = volumes.where(diffs > 0, -volumes)
    signed_volume = signed_volume.where(diffs != 0, 0)
    obv = signed_volume.cumsum()
    if len(obv) > 5:
        delta = obv.iloc[-1] - obv.iloc[-6]
        scale = abs(obv.iloc[-1]) + 1.0
        rel = delta / scale
        if rel > 0.02:
            score += 5
        elif rel < -0.02:
            score -= 5

    return max(0, min(100, score))


def calc_score_v2(closes: pd.Series, volumes: pd.Series) -> float:
    if len(closes) < 60 or volumes is None or len(volumes) < 20:
        return 50.0

    price = closes.iloc[-1]
    ma5, ma20, ma60 = closes.iloc[-5:].mean(), closes.iloc[-20:].mean(), closes.iloc[-60:].mean()

    if ma5 > ma20 > ma60:
        ma_score = 80
    elif ma5 < ma20 < ma60:
        ma_score = 20
    elif ma5 > ma20:
        ma_score = 60
    else:
        ma_score = 40
    ma_score += 10 if price > ma5 else -10
    ma_score = max(0, min(100, ma_score))

    delta = closes.diff().iloc[-14:]
    gain = delta.clip(lower=0).mean()
    loss = (-delta.clip(upper=0)).mean()
    rsi = 100 - (100 / (1 + gain / loss)) if loss != 0 else 100
    if rsi <= 30:
        rsi_score = 75
    elif rsi >= 70:
        rsi_score = 30
    elif rsi >= 50:
        rsi_score = 60
    else:
        rsi_score = 45

    ema12 = closes.ewm(span=12, adjust=False).mean().iloc[-1]
    ema26 = closes.ewm(span=26, adjust=False).mean().iloc[-1]
    macd = ema12 - ema26
    signal = closes.ewm(span=9, adjust=False).mean().iloc[-1]
    macd_score = 75 if macd > signal else 30

    sma20 = closes.iloc[-20:].mean()
    std20 = closes.iloc[-20:].std()
    bb_upper, bb_lower = sma20 + 2 * std20, sma20 - 2 * std20
    pct_b = (price - bb_lower) / (bb_upper - bb_lower) if (bb_upper - bb_lower) != 0 else 0.5
    if pct_b <= 0.2:
        bb_score = 70
    elif pct_b >= 0.8:
        bb_score = 35
    else:
        bb_score = 50

    volume_score = _score_volume(closes, volumes)

    return (ma_score * 0.30) + (rsi_score * 0.20) + (macd_score * 0.20) \
        + (bb_score * 0.15) + (volume_score * 0.15)


# 백테스팅 (점수 함수를 인자로 받아 v1/v2 공통으로 재사용)

def run_backtest(price_data: Dict[str, pd.DataFrame], score_fn) -> pd.DataFrame:
    all_dates = sorted(set(date for df in price_data.values() for date in df.index))

    if len(all_dates) < HOLD_DAYS + 60:
        logger.error("데이터가 부족합니다. 최소 %d일 이상 필요합니다.", HOLD_DAYS + 60)
        return pd.DataFrame()

    rebalance_dates = []
    prev_month = None
    for date in all_dates:
        if date.month != prev_month:
            rebalance_dates.append(date)
            prev_month = date.month

    results = []
    for entry_date in rebalance_dates:
        exit_candidates = [d for d in all_dates if d > entry_date]
        if len(exit_candidates) < HOLD_DAYS:
            break
        exit_date = exit_candidates[HOLD_DAYS - 1]

        scores = []
        for ticker, df in price_data.items():
            history = df[df.index <= entry_date]
            if len(history) < 60:
                continue
            score = score_fn(history["close_price"], history["volume"])
            scores.append({
                "ticker": ticker, "name": history["name"].iloc[0],
                "score": score, "entry_price": history["close_price"].iloc[-1],
            })

        if not scores:
            continue

        top = sorted(scores, key=lambda x: x["score"], reverse=True)[:TOP_N]
        for stock in top:
            df = price_data[stock["ticker"]]
            exit_prices = df[df.index >= exit_date]["close_price"]
            if exit_prices.empty:
                continue
            exit_price = exit_prices.iloc[0]
            returns = (exit_price - stock["entry_price"]) / stock["entry_price"] * 100
            results.append({
                "entry_date": entry_date.strftime("%Y-%m-%d"),
                "exit_date": exit_date.strftime("%Y-%m-%d"),
                "ticker": stock["ticker"], "name": stock["name"],
                "score": round(stock["score"], 1),
                "returns(%)": round(returns, 2), "win": returns > 0,
            })

    return pd.DataFrame(results)


def summarize(df: pd.DataFrame, label: str) -> dict:
    if df.empty:
        return {"label": label, "trades": 0}
    return {
        "label": label,
        "trades": len(df),
        "win_rate(%)": round(df["win"].mean() * 100, 1),
        "avg_return(%)": round(df["returns(%)"].mean(), 2),
        "median_return(%)": round(df["returns(%)"].median(), 2),
        "std_return(%)": round(df["returns(%)"].std(), 2),
        "min_return(%)": round(df["returns(%)"].min(), 2),
        "max_return(%)": round(df["returns(%)"].max(), 2),
    }


def main():
    logger.info("백테스팅 시작...")
    price_data = load_price_data()
    logger.info("로드된 종목 수: %d개", len(price_data))

    logger.info("v1 (거래량 지표 이전) 백테스트 실행 중...")
    df_v1 = run_backtest(price_data, calc_score_v1)
    logger.info("v2 (거래량 지표 포함) 백테스트 실행 중...")
    df_v2 = run_backtest(price_data, calc_score_v2)

    if df_v1.empty and df_v2.empty:
        logger.warning("결과가 없습니다. 데이터가 충분한지 확인해주세요 (최소 %d영업일 필요).", HOLD_DAYS + 60)
        return

    df_v1.to_csv("backtest_result_v1.csv", index=False, encoding="utf-8-sig")
    df_v2.to_csv("backtest_result_v2.csv", index=False, encoding="utf-8-sig")

    summary_v1 = summarize(df_v1, "v1_no_volume")
    summary_v2 = summarize(df_v2, "v2_with_volume")

    print("\n" + "=" * 64)
    print("v1(거래량 제외) vs v2(거래량 포함) 백테스트 비교")
    print("=" * 64)
    comparison = pd.DataFrame([summary_v1, summary_v2]).set_index("label")
    print(comparison.to_string())
    comparison.to_csv("backtest_comparison.csv", encoding="utf-8-sig")

    print("\n해석 가이드:")
    print("- win_rate/avg_return이 v2 > v1 이면 거래량 지표가 실제로 도움된 것")
    print("- std_return(변동성)이 v2 < v1 이면 더 안정적인 선택을 했다는 뜻")
    print("- 거래 횟수(trades)가 적으면(예: 10회 미만) 우연일 가능성이 크니 기간을 늘려서 재검증 필요")
    print("=" * 64)
    print("\n상세 결과: backtest_result_v1.csv, backtest_result_v2.csv")
    print("비교 요약: backtest_comparison.csv")


if __name__ == "__main__":
    main()
