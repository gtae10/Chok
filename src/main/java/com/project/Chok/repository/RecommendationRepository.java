package com.project.Chok.repository;

import com.project.Chok.domain.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    @Query("SELECT MAX(r.recDate) FROM Recommendation r")
    LocalDate findLatestRecDate();

    @Query("SELECT r FROM Recommendation r WHERE r.recDate = :recDate ORDER BY r.finalScore DESC")
    List<Recommendation> findByRecDateOrderByFinalScoreDesc(@Param("recDate") LocalDate recDate);

    @Query("SELECT r FROM Recommendation r WHERE r.ticker = :ticker ORDER BY r.recDate DESC")
    List<Recommendation> findHistoryByTicker(@Param("ticker") String ticker);
}