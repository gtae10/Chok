package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 종목별이 아닌, 그날 전체 시장에 공통으로 적용되는 거시 지표 (하루 1행).
 * python-collector/collect.py가 FinanceDataReader로 KOSPI/KOSDAQ 지수, 원/달러 환율을
 * 수집해 저장한다. train_model.py가 trade_date로 조인해 시장 전체 모멘텀 특징을 계산한다.
 */
@Entity
@Table(name = "market_indicators")
public class MarketIndicator {

    @Id
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "kospi_close")
    private Double kospiClose;

    @Column(name = "kosdaq_close")
    private Double kosdaqClose;

    @Column(name = "usd_krw_close")
    private Double usdKrwClose;

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public Double getKospiClose() { return kospiClose; }
    public void setKospiClose(Double kospiClose) { this.kospiClose = kospiClose; }

    public Double getKosdaqClose() { return kosdaqClose; }
    public void setKosdaqClose(Double kosdaqClose) { this.kosdaqClose = kosdaqClose; }

    public Double getUsdKrwClose() { return usdKrwClose; }
    public void setUsdKrwClose(Double usdKrwClose) { this.usdKrwClose = usdKrwClose; }
}
