package com.floww.exchange.repository;

import com.floww.exchange.model.entity.Candle;
import com.floww.exchange.model.entity.CandleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface CandleRepository extends JpaRepository<Candle, CandleId> {
    @Query("SELECT c FROM Candle c WHERE c.ticker = :ticker AND c.resolution = :resolution " +
           "AND c.bucket >= :from AND c.bucket <= :to ORDER BY c.bucket ASC")
    List<Candle> findCandles(String ticker, String resolution, Instant from, Instant to);
}
