package com.floww.exchange.service;

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
 * Sequences restart on application restart, which is acceptable — they
 * are used only for order book ordering, not as durable external IDs.
 */
@Component
public class OrderSequencer {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public long next(String ticker) {
        return counters.computeIfAbsent(ticker, k -> new AtomicLong(0)).incrementAndGet();
    }
}
