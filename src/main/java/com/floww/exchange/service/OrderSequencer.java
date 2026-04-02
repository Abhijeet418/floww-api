package com.floww.exchange.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory per-ticker sequence number generator.
 *
 * Replaces the Postgres next_sequence(ticker) row-lock on the hot path.
 * At 2,000 RPS, that lock was the primary bottleneck — all Tomcat threads
 * serialised on a single Postgres advisory lock per ticker.
 *
 * AtomicLong.incrementAndGet() is ~10ns vs ~5ms for the DB round-trip.
 *
 * On startup, initializes from MAX(sequence_number) per ticker to prevent
 * new orders from illegally jumping the queue ahead of recovered resting orders.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSequencer {

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Seed each ticker's counter from the max sequence in the DB so that
        // post-restart orders get higher sequence numbers than recovered resting orders.
        var rows = jdbcTemplate.queryForList(
                "SELECT ticker, COALESCE(MAX(sequence_number), 0) AS max_seq " +
                "FROM exchange_order GROUP BY ticker");
        for (var row : rows) {
            String ticker = (String) row.get("ticker");
            long maxSeq = ((Number) row.get("max_seq")).longValue();
            counters.put(ticker, new AtomicLong(maxSeq));
            log.info("OrderSequencer: {} initialized at sequence {}", ticker, maxSeq);
        }
    }

    public long next(String ticker) {
        return counters.computeIfAbsent(ticker, k -> new AtomicLong(0)).incrementAndGet();
    }
}
