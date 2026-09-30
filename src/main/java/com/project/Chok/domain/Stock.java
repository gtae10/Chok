package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stocks")
public class Stock {

    @Id
    @Column(length = 10)
    private String ticker;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 10)
    private String market;

    @Column(name = "market_cap", nullable = false)
    private Long marketCap;

    @Column(name = "base_date", nullable = false, length = 8)
    private String baseDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // 순발행 log(1년 전 대비 주식수) - collect.py가 marcap으로 계산해 수집 때마다 갱신 (태그 ISSUANCE_UP/BUYBACK)
    @Column(name = "issuance_252")
    private Double issuance252;

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public Long getMarketCap() { return marketCap; }
    public void setMarketCap(Long marketCap) { this.marketCap = marketCap; }

    public String getBaseDate() { return baseDate; }
    public void setBaseDate(String baseDate) { this.baseDate = baseDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Double getIssuance252() { return issuance252; }
    public void setIssuance252(Double issuance252) { this.issuance252 = issuance252; }
}