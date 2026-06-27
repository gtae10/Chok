package com.project.Chok.repository;

import com.project.Chok.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockRepository extends JpaRepository<Stock, String> {

    @Query("SELECT s FROM Stock s ORDER BY s.marketCap DESC")
    List<Stock> findAllOrderByMarketCapDesc();
}