"""
sentiment_consistency_check.py
- NewsDeduplicationService(Java)와 동일한 문자 2-gram Jaccard 유사도로 "같은 사건"
  헤드라인을 묶고, 그 안에서 LLM이 매긴 sentiment_score가 얼마나 갈렸는지 통계 낸다.
- "API 호출 오류" 폴백(진짜 LLM 판단이 아닌 기본값 0.0)은 제외한다 - 섞이면 인위적으로
  "일관성 있음"처럼 보이는 착시가 생긴다.
- 재사용 가능: 그냥 다시 실행하면 그 시점 데이터로 재검증.
"""
import re
import mysql.connector
import pandas as pd
import numpy as np

import config

NGRAM_SIZE = 2
SIMILARITY_THRESHOLD = 0.25  # chok.news.dedup-similarity-threshold와 동일


def normalize(headline: str) -> str:
    """NewsDeduplicationService.normalize()와 동일 - 공백/쉼표/마침표 제거, 소문자화."""
    return re.sub(r"[\s,.]", "", headline).lower()


def ngrams(s: str) -> set:
    if len(s) < NGRAM_SIZE:
        return {s} if s else set()
    return {s[i:i + NGRAM_SIZE] for i in range(len(s) - NGRAM_SIZE + 1)}


def jaccard_similarity(a: str, b: str) -> float:
    """NewsDeduplicationService.jaccardSimilarity()와 동일."""
    grams_a, grams_b = ngrams(a), ngrams(b)
    if not grams_a and not grams_b:
        return 1.0 if a == b else 0.0
    union = grams_a | grams_b
    if not union:
        return 0.0
    return len(grams_a & grams_b) / len(union)


def cluster_same_event(df: pd.DataFrame) -> list:
    """같은 ticker, 같은 news_date 안에서 jaccard >= threshold인 헤드라인을 묶는다
    (NewsDeduplicationService가 실제로 dedup을 적용하는 범위와 동일 - 같은 수집 배치 내)."""
    clusters = []
    for (ticker, date), group in df.groupby(["ticker", "news_date"]):
        rows = group.to_dict("records")
        normalized = [normalize(r["headline"]) for r in rows]
        n = len(rows)
        parent = list(range(n))

        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        def union(x, y):
            px, py = find(x), find(y)
            if px != py:
                parent[px] = py

        for i in range(n):
            for j in range(i + 1, n):
                if jaccard_similarity(normalized[i], normalized[j]) >= SIMILARITY_THRESHOLD:
                    union(i, j)

        groups = {}
        for i in range(n):
            groups.setdefault(find(i), []).append(rows[i])
        for members in groups.values():
            if len(members) >= 2:
                clusters.append(members)
    return clusters


def main():
    conn = mysql.connector.connect(
        host=config.DB_HOST, port=config.DB_PORT, database=config.DB_NAME,
        user=config.DB_USER, password=config.DB_PASSWORD, charset="utf8mb4"
    )
    df = pd.read_sql(
        """SELECT ticker, news_date, headline, sentiment_score, summary
           FROM news_sentiment
           WHERE summary NOT LIKE '%API 호출 오류%'""",
        conn
    )
    conn.close()

    excluded = pd.read_sql  # noqa - just to keep import usage clear
    print(f"분석 대상(API 오류 폴백 제외): {len(df)}건")

    clusters = cluster_same_event(df)
    print(f"근접중복(같은 사건)으로 묶인 클러스터 수: {len(clusters)}개\n")

    if not clusters:
        print("클러스터가 없어 일관성 통계를 낼 수 없습니다.")
        return

    stats = []
    for members in clusters:
        scores = [m["sentiment_score"] for m in members]
        stats.append({
            "ticker": members[0]["ticker"],
            "date": members[0]["news_date"],
            "size": len(members),
            "scores": scores,
            "std": float(np.std(scores)),
            "range": float(max(scores) - min(scores)),
            "headlines": [m["headline"] for m in members],
        })

    stats_df = pd.DataFrame(stats)
    print("=== 클러스터별 점수 편차 요약 ===")
    print(f"평균 표준편차: {stats_df['std'].mean():.3f}")
    print(f"평균 범위(max-min): {stats_df['range'].mean():.3f}")
    print(f"범위가 0.3 이상(뚜렷한 불일치)인 클러스터: {(stats_df['range'] >= 0.3).sum()} / {len(stats_df)}")
    print(f"범위가 0인(완전 일치) 클러스터: {(stats_df['range'] == 0).sum()} / {len(stats_df)}")

    print("\n=== 편차가 가장 큰 클러스터 top 5 ===")
    top5 = stats_df.sort_values("range", ascending=False).head(5)
    for _, row in top5.iterrows():
        print(f"- {row['ticker']} {row['date']} (범위={row['range']:.2f}, scores={row['scores']})")
        for h in row["headlines"]:
            print(f"    · {h}")

    stats_df.drop(columns=["headlines"]).to_csv("sentiment_consistency_report.csv", index=False, encoding="utf-8-sig")
    print("\n상세 결과 저장: sentiment_consistency_report.csv")


if __name__ == "__main__":
    main()
