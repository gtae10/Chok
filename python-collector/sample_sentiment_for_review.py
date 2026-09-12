"""
sample_sentiment_for_review.py
- news_sentiment 테이블에서 실제로 LLM이 분석한 헤드라인을 점수 구간별로 골고루 뽑아
  사람이 직접 훑어보고 "동의/비동의"를 표시할 수 있는 CSV로 만든다.
- 재사용 가능: 그냥 다시 실행하면 그 시점 기준 최신 데이터로 새 샘플을 뽑는다
  (랜덤 시드 고정이라 같은 데이터면 같은 샘플이 나옴 - 재현 가능).
- summary가 'API 호출 오류'(또는 상세 사유가 붙은 'API 호출 오류: ...')인 행은 실제 LLM
  판단이 아니라 오류 폴백값이라 사람 검토 대상에서 제외한다.
"""
import mysql.connector
import pandas as pd
import numpy as np

import config

OUTPUT_PATH = "sentiment_review_sample.csv"
SAMPLES_PER_BAND = 6
SEED = 42

BANDS = [
    ("고재료성_긍정", "sentiment_score >= 0.5"),
    ("고재료성_부정", "sentiment_score <= -0.5"),
    ("중립", "sentiment_score > -0.2 AND sentiment_score < 0.2"),
    ("애매한_중간", "(sentiment_score >= 0.2 AND sentiment_score < 0.5) OR (sentiment_score <= -0.2 AND sentiment_score > -0.5)"),
]


def get_connection():
    return mysql.connector.connect(
        host=config.DB_HOST, port=config.DB_PORT, database=config.DB_NAME,
        user=config.DB_USER, password=config.DB_PASSWORD, charset="utf8mb4"
    )


def main():
    conn = get_connection()
    rng = np.random.default_rng(SEED)

    frames = []
    for band_name, cond in BANDS:
        query = f"""
            SELECT ticker, news_date, headline, sentiment_score, sentiment_label, summary
            FROM news_sentiment
            WHERE ({cond}) AND summary NOT LIKE 'API 호출 오류%'
        """
        df = pd.read_sql(query, conn)
        if df.empty:
            print(f"[{band_name}] 해당 구간에 데이터 없음")
            continue
        n = min(SAMPLES_PER_BAND, len(df))
        sampled = df.sample(n=n, random_state=SEED)
        sampled.insert(0, "band", band_name)
        frames.append(sampled)
        print(f"[{band_name}] 전체 {len(df)}건 중 {n}건 샘플링")

    conn.close()

    result = pd.concat(frames, ignore_index=True)
    result["사람검토(동의/비동의)"] = ""
    result["검토메모"] = ""
    result.to_csv(OUTPUT_PATH, index=False, encoding="utf-8-sig")
    print(f"\n총 {len(result)}건 -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
