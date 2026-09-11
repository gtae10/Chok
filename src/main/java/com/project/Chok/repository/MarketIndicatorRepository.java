package com.project.Chok.repository;

import com.project.Chok.domain.MarketIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MarketIndicatorRepository extends JpaRepository<MarketIndicator, LocalDate> {

    @Query("SELECT m FROM MarketIndicator m WHERE m.tradeDate <= :asOf ORDER BY m.tradeDate DESC")
    List<MarketIndicator> findRecentUpTo(@Param("asOf") LocalDate asOf);
}
