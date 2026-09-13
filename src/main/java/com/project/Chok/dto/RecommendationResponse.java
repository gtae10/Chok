package com.project.Chok.dto;

import com.project.Chok.domain.Recommendation;

public class RecommendationResponse {

    private String ticker;
    private String name;
    private String market;
    private Double technicalScore;
    private Double sentimentScore;
    private String sentimentDataQuality;
    private Double finalScore;
    private Double riseProbability;
    private String probabilitySource;
    private Integer probabilityHorizonDays;
    private Integer notableHorizonDays;
    private Double notableHorizonProbability;
    private String notableHorizonApproxDate;
    private Integer notableFallHorizonDays;
    private Double notableFallHorizonProbability;
    private String notableFallHorizonApproxDate;
    private String recommendation;
    private String recommendationNuance;
    private String reason;
    private String date;

    public RecommendationResponse(Recommendation r) {
        this.ticker = r.getTicker();
        this.name = r.getName();
        this.market = r.getMarket();
        this.technicalScore = r.getTechnicalScore();
        this.sentimentScore = r.getSentimentScore();
        this.sentimentDataQuality = r.getSentimentDataQuality();
        this.finalScore = r.getFinalScore();
        this.riseProbability = r.getRiseProbability();
        this.probabilitySource = r.getProbabilitySource();
        this.probabilityHorizonDays = r.getProbabilityHorizonDays();
        this.notableHorizonDays = r.getNotableHorizonDays();
        this.notableHorizonProbability = r.getNotableHorizonProbability();
        this.notableHorizonApproxDate = r.getNotableHorizonApproxDate() == null ? null : r.getNotableHorizonApproxDate().toString();
        this.notableFallHorizonDays = r.getNotableFallHorizonDays();
        this.notableFallHorizonProbability = r.getNotableFallHorizonProbability();
        this.notableFallHorizonApproxDate = r.getNotableFallHorizonApproxDate() == null ? null : r.getNotableFallHorizonApproxDate().toString();
        this.recommendation = r.getRecommendation();
        this.recommendationNuance = r.getRecommendationNuance();
        this.reason = r.getReason();
        this.date = r.getRecDate().toString();
    }

    public String getTicker() { return ticker; }
    public String getName() { return name; }
    public String getMarket() { return market; }
    public Double getTechnicalScore() { return technicalScore; }
    public Double getSentimentScore() { return sentimentScore; }
    public String getSentimentDataQuality() { return sentimentDataQuality; }
    public Double getFinalScore() { return finalScore; }
    public Double getRiseProbability() { return riseProbability; }
    public String getProbabilitySource() { return probabilitySource; }
    public Integer getProbabilityHorizonDays() { return probabilityHorizonDays; }
    public Integer getNotableHorizonDays() { return notableHorizonDays; }
    public Double getNotableHorizonProbability() { return notableHorizonProbability; }
    public String getNotableHorizonApproxDate() { return notableHorizonApproxDate; }
    public Integer getNotableFallHorizonDays() { return notableFallHorizonDays; }
    public Double getNotableFallHorizonProbability() { return notableFallHorizonProbability; }
    public String getNotableFallHorizonApproxDate() { return notableFallHorizonApproxDate; }
    public String getRecommendation() { return recommendation; }
    public String getRecommendationNuance() { return recommendationNuance; }
    public String getReason() { return reason; }
    public String getDate() { return date; }
}
