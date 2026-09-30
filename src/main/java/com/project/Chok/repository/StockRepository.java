package com.project.Chok.repository;

import com.project.Chok.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockRepository extends JpaRepository<Stock, String> {

    // 최근 수집(collect.py)이 갱신한 시총 상위 종목만 - 순위에서 밀린 종목은 시세가 더 이상
    // 갱신되지 않으므로 분석 대상에서 뺀다 (base_date = 마지막으로 상위 N에 든 날, yyyyMMdd)
    @Query("SELECT s FROM Stock s WHERE s.baseDate = (SELECT MAX(s2.baseDate) FROM Stock s2) ORDER BY s.marketCap DESC")
    List<Stock> findCurrentUniverseOrderByMarketCapDesc();

    @Query("SELECT COUNT(s) FROM Stock s WHERE s.baseDate = (SELECT MAX(s2.baseDate) FROM Stock s2)")
    long countCurrentUniverse();
}
