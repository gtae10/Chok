package com.project.Chok.repository;

import com.project.Chok.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    @Query("SELECT p FROM PriceHistory p WHERE p.ticker = :ticker ORDER BY p.tradeDate ASC")
    List<PriceHistory> findByTickerOrderByTradeDateAsc(@Param("ticker") String ticker);

    @Query("SELECT p FROM PriceHistory p WHERE p.ticker = :ticker AND p.tradeDate >= :fromDate ORDER BY p.tradeDate ASC")
    List<PriceHistory> findByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);
}