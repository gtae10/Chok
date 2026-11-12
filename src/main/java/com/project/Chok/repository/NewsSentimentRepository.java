package com.project.Chok.repository;

import com.project.Chok.domain.NewsSentiment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface NewsSentimentRepository extends JpaRepository<NewsSentiment, Long> {

    @Query("SELECT n FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate ORDER BY n.newsDate DESC")
    List<NewsSentiment> findByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);

    // 감성 평균·재료성 태그 집계용 - 따라잡기 보관 기사(backfilled)는 빼서 표본 크기를 평소와 같게 유지
    @Query("SELECT n FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate AND (n.backfilled IS NULL OR n.backfilled = false)")
    List<NewsSentiment> findScoredByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);

    // 페이지네이션 버전 - 뉴스가 많이 쌓인 인기 종목이라도 한 번에 너무 많이 안 불러오게
    @Query("SELECT n FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate ORDER BY n.newsDate DESC")
    List<NewsSentiment> findByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate, Pageable pageable);

    @Query("SELECT COUNT(n) FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate")
    long countByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);

    @Query("SELECT COUNT(n) > 0 FROM NewsSentiment n WHERE n.ticker = :ticker AND n.headline = :headline AND n.newsDate = :newsDate")
    boolean existsByTickerAndHeadlineAndDate(@Param("ticker") String ticker, @Param("headline") String headline, @Param("newsDate") LocalDate newsDate);

    @Query("SELECT MAX(n.newsDate) FROM NewsSentiment n WHERE n.ticker = :ticker")
    LocalDate findLatestNewsDate(@Param("ticker") String ticker);
}
