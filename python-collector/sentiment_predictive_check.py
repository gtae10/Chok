"""
sentiment_predictive_check.py
- "감성점수가 실제로 이후 주가 움직임과 상관관계가 있는가"를 추적하는 장치.
- 뉴스는 과거 특정 날짜 걸로 소급 수집이 불가능한 구조(NewsCollectorService가 항상
  "현재 시점" 최신 뉴스만 가져옴)라서, 이 검증은 오직 지금부터 매일 쌓이는 실시간
  recommendations.sentiment_score로만 가능하다. 즉 이 스크립트를 오늘 돌려도 의미
  있는 결과는 안 나오고, 시간이 지나 표본이 쌓인 뒤 다시 돌려야 값어치가 있다.
  (재사용 목적: 그냥 나중에 이 파일을 다시 실행만 하면 됨)

- 버킷: 긍정(sentiment_score > 0.3) / 중립(-0.1 ~ 0.1) / 부정(sentiment_score < -0.3)
- 각 버킷에 속한 (ticker, rec_date)가 이후 5/10/20 거래일 뒤 실제로 어떤 수익률을
  보였는지 계산.
"""
import mysql.connector
import pandas as pd
import numpy as np

import config
from backtest import load_price_data  # 기존 가격 로딩 로직 재사용 (DRY)

HORIZONS = [5, 10, 20]
BUCKETS = [
    ("긍정", lambda s: s > 0.3),
    ("중립", lambda s: -0.1 <= s <= 0.1),
    ("부정", lambda s: s < -0.3),
]


# 2026-06-27~07-23 수집분은 감성분석 API 호출이 전부 실패해 sentiment_score가
# 항상 0.0(중립)으로 폴백된 기간이었다(news_sentiment.summary='API 호출 오류' 확인됨,
# 09-04 이후로는 정상). 이 구간을 섞으면 "중립"이 실제 판단이 아니라 오류 폴백이라
# 중립 버킷 통계가 오염되므로 제외한다.
SENTIMENT_PIPELINE_FIXED_FROM = "2026-09-04"


def load_recommendations() -> pd.DataFrame:
    conn = mysql.connector.connect(
        host=config.DB_HOST, port=config.DB_PORT, database=config.DB_NAME,
        user=config.DB_USER, password=config.DB_PASSWORD, charset="utf8mb4"
    )
    df = pd.read_sql(
        """SELECT ticker, rec_date, sentiment_score FROM recommendations
           WHERE sentiment_score IS NOT NULL AND rec_date >= %s""",
        conn, params=(SENTIMENT_PIPELINE_FIXED_FROM,)
    )
    conn.close()
    df["rec_date"] = pd.to_datetime(df["rec_date"])
    return df


def forward_return(price_data, ticker, rec_date, horizon):
    if ticker not in price_data:
        return None
    dates = price_data[ticker].index
    on_or_after = dates[dates >= rec_date]
    if on_or_after.empty:
        return None
    entry_date = on_or_after[0]
    future_candidates = dates[dates > entry_date]
    if len(future_candidates) < horizon:
        return None
    exit_date = future_candidates[horizon - 1]
    entry_price = price_data[ticker].loc[entry_date, "close_price"]
    exit_price = price_data[ticker].loc[exit_date, "close_price"]
    return (exit_price - entry_price) / entry_price * 100


def main():
    rec = load_recommendations()
    price_data = load_price_data()

    print(f"전체 recommendations 행 수: {len(rec)} (rec_date 범위 {rec['rec_date'].min().date()} ~ {rec['rec_date'].max().date()})")

    print("\n=== 버킷별 표본 수 (전체 기간) ===")
    for name, cond in BUCKETS:
        mask = rec["sentiment_score"].apply(cond)
        print(f"{name}: {mask.sum()}건")

    print("\n=== 버킷/기간별 수익률 (표본 부족 시 '샘플 부족'으로 표시) ===")
    MIN_SAMPLES = 20  # 이 미만이면 통계적으로 논할 수준이 아님 (표본 부족으로 표시)
    results = []
    for name, cond in BUCKETS:
        subset = rec[rec["sentiment_score"].apply(cond)]
        for h in HORIZONS:
            returns = []
            for _, row in subset.iterrows():
                r = forward_return(price_data, row["ticker"], row["rec_date"], h)
                if r is not None:
                    returns.append(r)
            n = len(returns)
            if n == 0:
                avg, med = None, None
            else:
                avg, med = float(np.mean(returns)), float(np.median(returns))
            status = "샘플 부족" if n < MIN_SAMPLES else "유효"
            results.append({"bucket": name, "horizon_days": h, "valid_samples": n,
                             "avg_return(%)": avg, "median_return(%)": med, "status": status})
            avg_str = f"{avg:.2f}" if avg is not None else "-"
            print(f"[{name}][{h}일] 유효표본={n} 평균수익률={avg_str}% ({status})")

    pd.DataFrame(results).to_csv("sentiment_predictive_report.csv", index=False, encoding="utf-8-sig")
    print("\n상세 결과 저장: sentiment_predictive_report.csv")

    # 지금 기준 실제로 "의미 있는"(중립이 아닌) 감성점수가 며칠치 쌓였는지
    non_neutral = rec[(rec["sentiment_score"] > 0.1) | (rec["sentiment_score"] < -0.1)]
    valid_days = sorted(non_neutral["rec_date"].dt.date.unique())
    print(f"\n=== 참고: 의미 있는(비중립) sentiment_score가 존재하는 날짜 ===")
    print(f"{len(valid_days)}일: {[str(d) for d in valid_days]}")
    print("이 스크립트는 나중에(예: 표본이 쌓인 뒤) 그대로 재실행하면 됩니다: python sentiment_predictive_check.py")


if __name__ == "__main__":
    main()
