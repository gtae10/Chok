package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 종목별 공매도 잔고 비중(%) - python-collector가 매일 채운다.
 * KRX는 2거래일 늦게 공개한다 - 모델 특징은 가격 마지막 날의 2거래일 전 행을 쓴다
 * (TechnicalAnalysisService.shortRatioFeature).
 */
@Entity
@Table(name = "short_balance",
        uniqueConstraints = @UniqueConstraint(name = "uq_short_ticker_date", columnNames = {"ticker", "trade_date"}))
public class ShortBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "short_ratio", nullable = false)
    private Double shortRatio;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public Double getShortRatio() { return shortRatio; }
    public void setShortRatio(Double shortRatio) { this.shortRatio = shortRatio; }
}
