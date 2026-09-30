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

    @Modifying
    @Query("DELETE FROM TagSnapshot t WHERE t.snapDate = :date")
    void deleteBySnapDate(@Param("date") LocalDate date);
}
