package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "price_history")
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "open_price", nullable = false)
    private Integer openPrice;

    @Column(name = "high_price", nullable = false)
    private Integer highPrice;

    @Column(name = "low_price", nullable = false)
    private Integer lowPrice;

    @Column(name = "close_price", nullable = false)
    private Integer closePrice;

    @Column(nullable = false)
    private Long volume;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public Integer getOpenPrice() { return openPrice; }
    public void setOpenPrice(Integer openPrice) { this.openPrice = openPrice; }

    public Integer getHighPrice() { return highPrice; }
    public void setHighPrice(Integer highPrice) { this.highPrice = highPrice; }

    public Integer getLowPrice() { return lowPrice; }
    public void setLowPrice(Integer lowPrice) { this.lowPrice = lowPrice; }

    public Integer getClosePrice() { return closePrice; }
    public void setClosePrice(Integer closePrice) { this.closePrice = closePrice; }

    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
}