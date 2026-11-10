package com.project.Chok.dto;

import com.project.Chok.domain.PerformanceSnapshot;
import com.project.Chok.domain.PriceHistory;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
    // 첫 선정일 뒤 같은 종목이 다시 선정된 날짜들(YYYY-MM-DD, 오름차순). 횟수 = size
    private List<String> reselectDates;

    /**
     * entryPrice: 선정일 확정 종가 (PerformanceTrackingService가 price_history에서 다시 읽어 넘긴다)
     * rank: 그날 최종 추천 순위 (저장된 rank는 하루 여러 번 분석하면 겹칠 수 있어 서비스가 다시 매긴다)
     */
    public PerformanceItemResponse(PerformanceSnapshot s, int rank, List<String> reselectDates,
                                   Integer entryPrice, Integer currentPrice, LocalDate today, PriceHistory peak) {
        this.id = s.getId();
        this.ticker = s.getTicker();
        this.name = s.getName();
        this.snapshotDate = s.getSnapshotDate().toString();
        this.rank = rank;
        this.reselectDates = reselectDates;
        this.technicalScore = s.getTechnicalScore();
        this.sentimentScore = s.getSentimentScore();
        this.finalScore = s.getFinalScore();
        this.riseProbability = s.getRiseProbability();
        this.entryPrice = entryPrice;
        this.currentPrice = currentPrice;
        this.returnRate = (currentPrice != null && entryPrice != null && entryPrice != 0)
                ? round4((currentPrice - entryPrice) / (double) entryPrice)
                : null;
        this.holdingDays = ChronoUnit.DAYS.between(s.getSnapshotDate(), today);
        if (peak != null) {
            this.peakDate = peak.getTradeDate().toString();
            this.peakDays = ChronoUnit.DAYS.between(s.getSnapshotDate(), peak.getTradeDate());
            this.peakReturnRate = (entryPrice != null && entryPrice != 0)
                    ? round4((peak.getClosePrice() - entryPrice) / (double) entryPrice)
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
    public List<String> getReselectDates() { return reselectDates; }
}
