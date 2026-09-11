package com.project.Chok.repository;

import com.project.Chok.domain.Recommendation;
import org.springframework.data.domain.Pageable;
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

    // 스냅샷 선정일 이후의 지표 추이 조회용 - 선정일부터 오늘까지 시간순으로 가져온다.
    @Query("SELECT r FROM Recommendation r WHERE r.ticker = :ticker AND r.recDate >= :fromDate ORDER BY r.recDate ASC")
    List<Recommendation> findByTickerAndRecDateFromOrderByRecDateAsc(
            @Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);

    // 페이지네이션 버전 - 데이터가 몇 년치 쌓여도 상세페이지에서 한 번에 다 안 불러오게
    @Query("SELECT r FROM Recommendation r WHERE r.ticker = :ticker ORDER BY r.recDate DESC")
    List<Recommendation> findHistoryByTicker(@Param("ticker") String ticker, Pageable pageable);

    @Query("SELECT COUNT(r) FROM Recommendation r WHERE r.ticker = :ticker")
    long countByTicker(@Param("ticker") String ticker);
}