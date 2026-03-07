package com.floww.exchange.service;

import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.enums.TickerStatus;
import com.floww.exchange.repository.TickerRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ticker and LTP cache.
 *
 * Eliminates per-order DB queries for ticker validation and price deviation checks.
 * Loaded on startup from Postgres; refreshed every 60s to pick up admin changes
 * (new tickers, halts, etc.).
 *
 * LTP updates are pushed here from TradePersistenceHandler on every matched trade,
 * replacing the hot-path tradeRepository.findLastTradedPrice() call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TickerCache {

    private final TickerRepository tickerRepository;

    private final Map<String, Ticker> tickers = new ConcurrentHashMap<>();
    private final Map<String, Long>   ltpCache = new ConcurrentHashMap<>();

    @PostConstruct
    @Scheduled(fixedRate = 60_000)
    public void refresh() {
        tickerRepository.findAll().forEach(t -> tickers.put(t.getSymbol(), t));
        log.info("TickerCache refreshed — {} tickers loaded", tickers.size());
    }

    public Optional<Ticker> get(String symbol) {
        return Optional.ofNullable(tickers.get(symbol));
    }

    public boolean isHalted(String symbol) {
        Ticker t = tickers.get(symbol);
        return t != null && t.getStatus() == TickerStatus.HALTED;
    }

    /**
     * Called by TradePersistenceHandler on every matched trade.
     * Keeps the LTP current in memory so the validation path never hits Postgres.
     */
    public void updateLtp(String symbol, long price) {
        ltpCache.put(symbol, price);
    }

    /**
     * Returns the best available reference price for deviation checks:
     * LTP if a trade has occurred, otherwise the session open price from the DB cache.
     */
    public Long getRefPrice(String symbol) {
        Long ltp = ltpCache.get(symbol);
        if (ltp != null) return ltp;
        Ticker t = tickers.get(symbol);
        return t != null ? t.getSessionOpenPrice() : null;
    }
}
