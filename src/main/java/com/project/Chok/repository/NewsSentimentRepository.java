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

    // 페이지네이션 버전 - 뉴스가 많이 쌓인 인기 종목이라도 한 번에 너무 많이 안 불러오게
    @Query("SELECT n FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate ORDER BY n.newsDate DESC")
    List<NewsSentiment> findByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate, Pageable pageable);

    @Query("SELECT COUNT(n) FROM NewsSentiment n WHERE n.ticker = :ticker AND n.newsDate >= :fromDate")
    long countByTickerSince(@Param("ticker") String ticker, @Param("fromDate") LocalDate fromDate);

    @Query("SELECT COUNT(n) > 0 FROM NewsSentiment n WHERE n.ticker = :ticker AND n.headline = :headline AND n.newsDate = :newsDate")
    boolean existsByTickerAndHeadlineAndDate(@Param("ticker") String ticker, @Param("headline") String headline, @Param("newsDate") LocalDate newsDate);
}