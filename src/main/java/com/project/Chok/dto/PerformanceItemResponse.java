package com.project.Chok.dto;

import com.project.Chok.domain.PerformanceSnapshot;
import com.project.Chok.domain.PriceHistory;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class PerformanceItemResponse {

    private Long id;
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
    // 선정 뒤 종가 고점 - 날짜, 선정일로부터 며칠 뒤(달력일, holdingDays와 같은 기준), 진입가 대비 수익률
    private String peakDate;
    private Long peakDays;
    private Double peakReturnRate;

    public PerformanceItemResponse(PerformanceSnapshot s, Integer currentPrice, LocalDate today, PriceHistory peak) {
        this.id = s.getId();
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
        if (peak != null) {
            this.peakDate = peak.getTradeDate().toString();
            this.peakDays = ChronoUnit.DAYS.between(s.getSnapshotDate(), peak.getTradeDate());
            this.peakReturnRate = (s.getEntryPrice() != null && s.getEntryPrice() != 0)
                    ? round4((peak.getClosePrice() - s.getEntryPrice()) / (double) s.getEntryPrice())
                    : null;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public Long getId() { return id; }
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
    public String getPeakDate() { return peakDate; }
    public Long getPeakDays() { return peakDays; }
    public Double getPeakReturnRate() { return peakReturnRate; }
}
