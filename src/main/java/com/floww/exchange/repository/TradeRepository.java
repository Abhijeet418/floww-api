package com.floww.exchange.repository;

import com.floww.exchange.model.entity.Trade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TradeRepository extends JpaRepository<Trade, UUID> {
    List<Trade> findByTickerOrderByTradedAtDesc(String ticker, Pageable pageable);

    @Query("SELECT t.price FROM Trade t WHERE t.ticker = :ticker ORDER BY t.tradedAt DESC LIMIT 1")
    Long findLastTradedPrice(String ticker);
}
