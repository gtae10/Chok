package com.project.Chok.repository;

import com.project.Chok.domain.PerformanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PerformanceSnapshotRepository extends JpaRepository<PerformanceSnapshot, Long> {

    @Query("SELECT s FROM PerformanceSnapshot s WHERE s.ticker = :ticker AND s.snapshotDate = :snapshotDate")
    Optional<PerformanceSnapshot> findByTickerAndSnapshotDate(
            @Param("ticker") String ticker, @Param("snapshotDate") LocalDate snapshotDate);

    @Query("SELECT s FROM PerformanceSnapshot s ORDER BY s.snapshotDate DESC, s.rank ASC")
    List<PerformanceSnapshot> findAllOrderBySnapshotDateDescRankAsc();

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM PerformanceSnapshot s WHERE s.snapshotDate = :snapshotDate AND s.ticker NOT IN :tickers")
    int deleteBySnapshotDateAndTickerNotIn(@Param("snapshotDate") LocalDate snapshotDate, @Param("tickers") List<String> tickers);
}
