package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
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

    // DART 공시 최신 접수일 (태그 DART_BUYBACK/DART_DILUTION, PROJECT_PLAN D21) - collect.py가 최근 60일을 조회해 갱신.
    // checked = 마지막으로 조회에 성공한 날(KST). 접수일 null + checked 있음 = 확인했고 사건 없음
    @Column(name = "dart_buyback_dt")
    private LocalDate dartBuybackDt;
    @Column(name = "dart_dilution_dt")
    private LocalDate dartDilutionDt;
    @Column(name = "dart_checked_dt")
    private LocalDate dartCheckedDt;

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

    public LocalDate getDartBuybackDt() { return dartBuybackDt; }
    public void setDartBuybackDt(LocalDate dartBuybackDt) { this.dartBuybackDt = dartBuybackDt; }
    public LocalDate getDartDilutionDt() { return dartDilutionDt; }
    public void setDartDilutionDt(LocalDate dartDilutionDt) { this.dartDilutionDt = dartDilutionDt; }
    public LocalDate getDartCheckedDt() { return dartCheckedDt; }
    public void setDartCheckedDt(LocalDate dartCheckedDt) { this.dartCheckedDt = dartCheckedDt; }
}