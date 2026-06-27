package com.project.Chok.dto;

public class SentimentResult {

    private double score;   // -1.0 ~ 1.0
    private String label;   // POSITIVE / NEUTRAL / NEGATIVE
    private String summary;

    public SentimentResult(double score, String label, String summary) {
        this.score = score;
        this.label = label;
        this.summary = summary;
    }

    public static SentimentResult neutral(String reason) {
        return new SentimentResult(0.0, "NEUTRAL", reason);
    }

    public double getScore() { return score; }
    public String getLabel() { return label; }
    public String getSummary() { return summary; }
}