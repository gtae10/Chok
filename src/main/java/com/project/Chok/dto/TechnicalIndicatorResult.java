package com.project.Chok.dto;

public class TechnicalIndicatorResult {

    private Double ma5;
    private Double ma20;
    private Double ma60;
    private Double rsi;
    private Double macd;
    private Double macdSignal;
    private Double macdHistogram;
    private Double bbUpper;
    private Double bbLower;
    private Double bbPercentB;
    private Double volumeRatio;
    private String obvTrend;
    private Double technicalScore;
    private Double riseProbability;
    private String probabilitySource; // "MODEL" | "HEURISTIC"
    private String technicalReason;

    public TechnicalIndicatorResult(Double ma5, Double ma20, Double ma60,
                                    Double rsi, Double macd, Double macdSignal,
                                    Double macdHistogram, Double bbUpper, Double bbLower,
                                    Double bbPercentB, Double volumeRatio, String obvTrend,
                                    Double technicalScore, Double riseProbability,
                                    String probabilitySource, String technicalReason) {
        this.ma5 = ma5;
        this.ma20 = ma20;
        this.ma60 = ma60;
        this.rsi = rsi;
        this.macd = macd;
        this.macdSignal = macdSignal;
        this.macdHistogram = macdHistogram;
        this.bbUpper = bbUpper;
        this.bbLower = bbLower;
        this.bbPercentB = bbPercentB;
        this.volumeRatio = volumeRatio;
        this.obvTrend = obvTrend;
        this.technicalScore = technicalScore;
        this.riseProbability = riseProbability;
        this.probabilitySource = probabilitySource;
        this.technicalReason = technicalReason;
    }

    // 데이터 부족 시 기본값 반환
    public static TechnicalIndicatorResult insufficient() {
        return new TechnicalIndicatorResult(
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                50.0, 50.0, "HEURISTIC",
                "가격 데이터 부족으로 중립 처리"
        );
    }

    public Double getMa5() { return ma5; }
    public Double getMa20() { return ma20; }
    public Double getMa60() { return ma60; }
    public Double getRsi() { return rsi; }
    public Double getMacd() { return macd; }
    public Double getMacdSignal() { return macdSignal; }
    public Double getMacdHistogram() { return macdHistogram; }
    public Double getBbUpper() { return bbUpper; }
    public Double getBbLower() { return bbLower; }
    public Double getBbPercentB() { return bbPercentB; }
    public Double getVolumeRatio() { return volumeRatio; }
    public String getObvTrend() { return obvTrend; }
    public Double getTechnicalScore() { return technicalScore; }
    public Double getRiseProbability() { return riseProbability; }
    public String getProbabilitySource() { return probabilitySource; }
    public String getTechnicalReason() { return technicalReason; }
}
