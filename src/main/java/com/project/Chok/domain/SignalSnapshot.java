package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * G단계 전향 검증 기록 (PROJECT_PLAN D19) - python-collector/forward_signals.py가 매일 채운다.
 * 같은 (날짜, 종목, 신호)는 처음 값을 유지한다 - recorded_at이 "그때 알던 값"의 증거.
 */
@Entity
@Table(name = "signal_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uq_signal_date_ticker_name",
                columnNames = {"trade_date", "ticker", "signal_name"}))
public class SignalSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "signal_name", nullable = false, length = 20)
    private String signalName;

    @Column(name = "signal_value", nullable = false)
    private Double value;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    public Long getId() { return id; }
    public LocalDate getTradeDate() { return tradeDate; }
    public String getTicker() { return ticker; }
    public String getSignalName() { return signalName; }
    public Double getValue() { return value; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
}
