package com.floww.exchange.repository;

import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.enums.TickerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TickerRepository extends JpaRepository<Ticker, UUID> {
    Optional<Ticker> findBySymbol(String symbol);
    List<Ticker> findByStatus(TickerStatus status);
    boolean existsBySymbol(String symbol);
}
