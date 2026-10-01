package com.project.Chok.repository;

import com.project.Chok.domain.TagSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TagSnapshotRepository extends JpaRepository<TagSnapshot, Long> {

    @Query("SELECT t FROM TagSnapshot t WHERE t.snapDate = :date")
    List<TagSnapshot> findBySnapDate(@Param("date") LocalDate date);

    @Query("SELECT t FROM TagSnapshot t WHERE t.ticker = :ticker AND t.snapDate = " +
           "(SELECT MAX(t2.snapDate) FROM TagSnapshot t2)")
    List<TagSnapshot> findLatestByTicker(@Param("ticker") String ticker);

    @Query("SELECT MAX(t.snapDate) FROM TagSnapshot t")
    LocalDate findLatestSnapDate();

    @Query("SELECT MIN(t.snapDate) FROM TagSnapshot t")
    LocalDate findFirstSnapDate();

    @Query("SELECT MAX(t.snapDate) FROM TagSnapshot t WHERE t.snapDate < :date")
    LocalDate findSnapDateBefore(@Param("date") LocalDate date);

    @Query("SELECT COUNT(DISTINCT t.snapDate) FROM TagSnapshot t")
    long countSnapDates();

    /** 태그별 누적 부여 건수 (행: [tag, count]) */
    @Query("SELECT t.tag, COUNT(t) FROM TagSnapshot t GROUP BY t.tag")
    List<Object[]> countByTag();

    @Modifying
    @Query("DELETE FROM TagSnapshot t WHERE t.snapDate = :date")
    void deleteBySnapDate(@Param("date") LocalDate date);
}
