package com.project.Chok.dto;

import com.project.Chok.domain.PriceHistory;

public class PriceHistoryResponse {

    private String date;
    private Integer open;
    private Integer high;
    private Integer low;
    private Integer close;
    private Long volume;

    public PriceHistoryResponse(PriceHistory p) {
        this.date = p.getTradeDate().toString();
        this.open = p.getOpenPrice();
        this.high = p.getHighPrice();
        this.low = p.getLowPrice();
        this.close = p.getClosePrice();
        this.volume = p.getVolume();
    }

    public String getDate() { return date; }
    public Integer getOpen() { return open; }
    public Integer getHigh() { return high; }
    public Integer getLow() { return low; }
    public Integer getClose() { return close; }
    public Long getVolume() { return volume; }
}