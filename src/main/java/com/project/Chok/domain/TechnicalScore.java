package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "technical_scores")
public class TechnicalScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "calc_date", nullable = false)
    private LocalDate calcDate;

    private Double ma5;
    private Double ma20;
    private Double ma60;
    private Double rsi;
    private Double macd;

    @Column(name = "macd_signal")
    private Double macdSignal;

    @Column(name = "macd_histogram")
    private Double macdHistogram;

    @Column(name = "bb_upper")
    private Double bbUpper;

    @Column(name = "bb_lower")
    private Double bbLower;

    @Column(name = "bb_percent_b")
    private Double bbPercentB;

    @Column(name = "technical_score")
    private Double technicalScore;

    @Column(name = "technical_reason", columnDefinition = "TEXT")
    private String technicalReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public LocalDate getCalcDate() { return calcDate; }
    public void setCalcDate(LocalDate calcDate) { this.calcDate = calcDate; }

    public Double getMa5() { return ma5; }
    public void setMa5(Double ma5) { this.ma5 = ma5; }

    public Double getMa20() { return ma20; }
    public void setMa20(Double ma20) { this.ma20 = ma20; }

    public Double getMa60() { return ma60; }
    public void setMa60(Double ma60) { this.ma60 = ma60; }

    public Double getRsi() { return rsi; }
    public void setRsi(Double rsi) { this.rsi = rsi; }

    public Double getMacd() { return macd; }
    public void setMacd(Double macd) { this.macd = macd; }

    public Double getMacdSignal() { return macdSignal; }
    public void setMacdSignal(Double macdSignal) { this.macdSignal = macdSignal; }

    public Double getMacdHistogram() { return macdHistogram; }
    public void setMacdHistogram(Double macdHistogram) { this.macdHistogram = macdHistogram; }

    public Double getBbUpper() { return bbUpper; }
    public void setBbUpper(Double bbUpper) { this.bbUpper = bbUpper; }

    public Double getBbLower() { return bbLower; }
    public void setBbLower(Double bbLower) { this.bbLower = bbLower; }

    public Double getBbPercentB() { return bbPercentB; }
    public void setBbPercentB(Double bbPercentB) { this.bbPercentB = bbPercentB; }

    public Double getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Double technicalScore) { this.technicalScore = technicalScore; }

    public String getTechnicalReason() { return technicalReason; }
    public void setTechnicalReason(String technicalReason) { this.technicalReason = technicalReason; }
}