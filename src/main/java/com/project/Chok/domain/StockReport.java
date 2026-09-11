package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 종목별 AI 종합 리포트 캐시. 매일 전종목 재생성 비용을 피하기 위해 종목당 최신 1건만 보관하고,
 * 점수가 이전 생성 시점 대비 유의미하게 안 바뀌었으면 재생성 없이 그대로 재사용한다
 * (StockReportService.hasChangedMeaningfully() 참고). 종목 상세페이지를 열 때만 생성되는
 * 지연(lazy) 방식이라, 실제로 조회되지 않는 종목은 아예 생성되지 않는다.
 */
@Entity
@Table(name = "stock_reports")
public class StockReport {

    @Id
    @Column(length = 10)
    private String ticker;

    @Column(columnDefinition = "TEXT")
    private String reportText;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "based_on_date")
    private LocalDate basedOnDate;

    @Column(name = "based_on_final_score")
    private Double basedOnFinalScore;

    @Column(name = "based_on_recommendation", length = 20)
    private String basedOnRecommendation;

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getReportText() { return reportText; }
    public void setReportText(String reportText) { this.reportText = reportText; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDate getBasedOnDate() { return basedOnDate; }
    public void setBasedOnDate(LocalDate basedOnDate) { this.basedOnDate = basedOnDate; }

    public Double getBasedOnFinalScore() { return basedOnFinalScore; }
    public void setBasedOnFinalScore(Double basedOnFinalScore) { this.basedOnFinalScore = basedOnFinalScore; }

    public String getBasedOnRecommendation() { return basedOnRecommendation; }
    public void setBasedOnRecommendation(String basedOnRecommendation) { this.basedOnRecommendation = basedOnRecommendation; }
}
