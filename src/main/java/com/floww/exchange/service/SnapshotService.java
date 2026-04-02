package com.floww.exchange.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.exchange.model.dto.OrderBookSnapshot;
import com.floww.exchange.engine.MatchingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotService {

    private final MatchingEngine matchingEngine;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private final Set<String> knownTickers = ConcurrentHashMap.newKeySet();

    public void registerTicker(String ticker) {
        knownTickers.add(ticker);
    }

    public Set<String> getKnownTickers() {
        return Set.copyOf(knownTickers);
    }

    @Scheduled(fixedRateString = "${floww.exchange.snapshot.interval-seconds:5}000")
    public void snapshotAll() {
        for (String ticker : knownTickers) {
            try {
                OrderBookSnapshot snapshot = matchingEngine.getSnapshot(ticker);
                if (snapshot != null) {
                    redisTemplate.opsForValue().set("snapshot:" + ticker, snapshot);
                }
            } catch (Exception e) {
                log.warn("Snapshot failed for {}: {}", ticker, e.getMessage());
            }
        }
    }

    public OrderBookSnapshot getSnapshot(String ticker) {
        // Try Redis cache first
        Object cached = redisTemplate.opsForValue().get("snapshot:" + ticker);
        if (cached instanceof OrderBookSnapshot s) return s;

        // Try converting from linked hash map (Redis deserialization)
        if (cached != null) {
            try {
                return objectMapper.convertValue(cached, OrderBookSnapshot.class);
            } catch (Exception e) {
                log.warn("Failed to convert cached snapshot for {}", ticker);
            }
        }

        // Fall back to engine
        return matchingEngine.getSnapshot(ticker);
    }
}
