package com.project.Chok.repository;

import com.project.Chok.domain.TechnicalScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface TechnicalScoreRepository extends JpaRepository<TechnicalScore, Long> {

    @Query("SELECT t FROM TechnicalScore t WHERE t.ticker = :ticker AND t.calcDate = :calcDate")
    Optional<TechnicalScore> findByTickerAndCalcDate(@Param("ticker") String ticker, @Param("calcDate") LocalDate calcDate);
}