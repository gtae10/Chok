package com.project.Chok.dto;

import com.project.Chok.domain.PerformanceSnapshot;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class PerformanceItemResponse {

    private String ticker;
    private String name;
    private String snapshotDate;
    private Integer rank;
    private Double technicalScore;
    private Double sentimentScore;
    private Double finalScore;
    private Double riseProbability;
    private Integer entryPrice;
    private Integer currentPrice;
    private Double returnRate;
    private long holdingDays;

    public PerformanceItemResponse(PerformanceSnapshot s, Integer currentPrice, LocalDate today) {
        this.ticker = s.getTicker();
        this.name = s.getName();
        this.snapshotDate = s.getSnapshotDate().toString();
        this.rank = s.getRank();
        this.technicalScore = s.getTechnicalScore();
        this.sentimentScore = s.getSentimentScore();
        this.finalScore = s.getFinalScore();
        this.riseProbability = s.getRiseProbability();
        this.entryPrice = s.getEntryPrice();
        this.currentPrice = currentPrice;
        this.returnRate = (currentPrice != null && s.getEntryPrice() != null && s.getEntryPrice() != 0)
                ? round4((currentPrice - s.getEntryPrice()) / (double) s.getEntryPrice())
                : null;
        this.holdingDays = ChronoUnit.DAYS.between(s.getSnapshotDate(), today);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public String getTicker() { return ticker; }
    public String getName() { return name; }
    public String getSnapshotDate() { return snapshotDate; }
    public Integer getRank() { return rank; }
    public Double getTechnicalScore() { return technicalScore; }
    public Double getSentimentScore() { return sentimentScore; }
    public Double getFinalScore() { return finalScore; }
    public Double getRiseProbability() { return riseProbability; }
    public Integer getEntryPrice() { return entryPrice; }
    public Integer getCurrentPrice() { return currentPrice; }
    public Double getReturnRate() { return returnRate; }
    public long getHoldingDays() { return holdingDays; }
}
