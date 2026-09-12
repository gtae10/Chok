package com.project.Chok.dto;

public class SentimentResult {

    private double score;   // -1.0 ~ 1.0
    private String label;   // POSITIVE / NEUTRAL / NEGATIVE
    private String summary;
    private boolean fallback; // true면 실제 LLM 판단이 아니라 오류 시 중립 기본값으로 대체된 것

    public SentimentResult(double score, String label, String summary) {
        this(score, label, summary, false);
    }

    private SentimentResult(double score, String label, String summary, boolean fallback) {
        this.score = score;
        this.label = label;
        this.summary = summary;
        this.fallback = fallback;
    }

    /** LLM 호출/파싱 실패 시 사용하는 중립 폴백. score=0.0은 실제 판단이 아니라 대체값이므로
     * {@link #isFallback()}로 이 결과를 사용한 통계/저장 로직에서 구분해야 한다. */
    public static SentimentResult neutral(String reason) {
        return new SentimentResult(0.0, "NEUTRAL", reason, true);
    }

    public double getScore() { return score; }
    public String getLabel() { return label; }
    public String getSummary() { return summary; }
    public boolean isFallback() { return fallback; }
}