package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendations")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "rec_date", nullable = false)
    private LocalDate recDate;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 10)
    private String market;

    @Column(name = "technical_score")
    private Double technicalScore;

    @Column(name = "sentiment_score")
    private Double sentimentScore;

    @Column(name = "final_score")
    private Double finalScore;

    @Column(name = "rise_probability")
    private Double riseProbability;

    @Column(name = "probability_source", length = 10)
    private String probabilitySource;

    @Column(name = "probability_horizon_days")
    private Integer probabilityHorizonDays;

    @Column(length = 20)
    private String recommendation;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public LocalDate getRecDate() { return recDate; }
    public void setRecDate(LocalDate recDate) { this.recDate = recDate; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public Double getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Double technicalScore) { this.technicalScore = technicalScore; }

    public Double getSentimentScore() { return sentimentScore; }
    public void setSentimentScore(Double sentimentScore) { this.sentimentScore = sentimentScore; }

    public Double getFinalScore() { return finalScore; }
    public void setFinalScore(Double finalScore) { this.finalScore = finalScore; }

    public Double getRiseProbability() { return riseProbability; }
    public void setRiseProbability(Double riseProbability) { this.riseProbability = riseProbability; }

    public String getProbabilitySource() { return probabilitySource; }
    public void setProbabilitySource(String probabilitySource) { this.probabilitySource = probabilitySource; }

    public Integer getProbabilityHorizonDays() { return probabilityHorizonDays; }
    public void setProbabilityHorizonDays(Integer probabilityHorizonDays) { this.probabilityHorizonDays = probabilityHorizonDays; }

    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}