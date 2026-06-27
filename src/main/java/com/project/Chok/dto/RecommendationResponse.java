package com.project.Chok.dto;

import com.project.Chok.domain.Recommendation;

public class RecommendationResponse {

    private String ticker;
    private String name;
    private String market;
    private Double technicalScore;
    private Double sentimentScore;
    private Double finalScore;
    private String recommendation;
    private String reason;
    private String date;

    public RecommendationResponse(Recommendation r) {
        this.ticker = r.getTicker();
        this.name = r.getName();
        this.market = r.getMarket();
        this.technicalScore = r.getTechnicalScore();
        this.sentimentScore = r.getSentimentScore();
        this.finalScore = r.getFinalScore();
        this.recommendation = r.getRecommendation();
        this.reason = r.getReason();
        this.date = r.getRecDate().toString();
    }

    public String getTicker() { return ticker; }
    public String getName() { return name; }
    public String getMarket() { return market; }
    public Double getTechnicalScore() { return technicalScore; }
    public Double getSentimentScore() { return sentimentScore; }
    public Double getFinalScore() { return finalScore; }
    public String getRecommendation() { return recommendation; }
    public String getReason() { return reason; }
    public String getDate() { return date; }
}