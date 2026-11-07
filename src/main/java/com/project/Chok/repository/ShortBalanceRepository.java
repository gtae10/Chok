package com.project.Chok.repository;

import com.project.Chok.domain.ShortBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ShortBalanceRepository extends JpaRepository<ShortBalance, Long> {

    Optional<ShortBalance> findByTickerAndTradeDate(String ticker, LocalDate tradeDate);
}
