package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 분석 완료 시점에 그날 종합점수 상위 10개 종목을 스냅샷으로 저장한다.
 * "그때 추천했던 종목이 지금까지 얼마나 올랐나"를 추적하기 위한 진입가 기록용 —
 * 수익률은 저장하지 않고 조회 시점에 최신가 기준으로 매번 다시 계산한다.
 */
@Entity
@Table(name = "performance_snapshots")
public class PerformanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "rank_no", nullable = false)
    private Integer rank;

    @Column(name = "technical_score")
    private Double technicalScore;

    @Column(name = "sentiment_score")
    private Double sentimentScore;

    @Column(name = "final_score")
    private Double finalScore;

    @Column(name = "rise_probability")
    private Double riseProbability;

    @Column(name = "entry_price", nullable = false)
    private Integer entryPrice;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Double getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Double technicalScore) { this.technicalScore = technicalScore; }

    public Double getSentimentScore() { return sentimentScore; }
    public void setSentimentScore(Double sentimentScore) { this.sentimentScore = sentimentScore; }

    public Double getFinalScore() { return finalScore; }
    public void setFinalScore(Double finalScore) { this.finalScore = finalScore; }

    public Double getRiseProbability() { return riseProbability; }
    public void setRiseProbability(Double riseProbability) { this.riseProbability = riseProbability; }

    public Integer getEntryPrice() { return entryPrice; }
    public void setEntryPrice(Integer entryPrice) { this.entryPrice = entryPrice; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
