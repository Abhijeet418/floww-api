package com.floww.exchange.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.model.dto.MarketDataEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    private final ObjectMapper objectMapper;
    private final MarketSessionService marketSessionService;
    private final StringRedisTemplate redisTemplate;
    private final ExchangeProperties exchangeProperties;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, MarketDataEvent> latestData = new ConcurrentHashMap<>();

    // Ring buffer for replay (per ticker, last ~1000 events)
    // ConcurrentLinkedDeque avoids the O(n) array copy per mutation of CopyOnWriteArrayList.
    private final Map<String, Deque<MarketDataEvent>> eventBuffer = new ConcurrentHashMap<>();
    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Connection count per client key (appId when authenticated, remote IP otherwise)
    private final Map<String, AtomicInteger> connectionCount = new ConcurrentHashMap<>();

    /**
     * Resolves a stable client key from an optional API key header.
     * Authenticated clients are keyed by their appId (UUID string).
     * Unauthenticated clients are keyed by remote IP.
     */
    public String resolveClientKey(String apiKey, String remoteAddr) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String sha256 = java.util.HexFormat.of().formatHex(hash);
                String appId = redisTemplate.opsForValue().get("apikey:" + sha256);
                if (appId != null) return "app:" + appId;
            } catch (Exception ignored) { }
        }
        return "ip:" + remoteAddr;
    }

    public SseEmitter subscribe(String ticker, String lastEventId, String clientKey) {
        int maxConnections = exchangeProperties.getRateLimit().getSseMaxConnectionsPerClient();
        AtomicInteger count = connectionCount.computeIfAbsent(clientKey, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > maxConnections) {
            count.decrementAndGet();
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new IllegalStateException(
                    "SSE connection limit reached (max " + maxConnections + ")"));
            return rejected;
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.computeIfAbsent(ticker, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            removeEmitter(ticker, emitter);
            connectionCount.computeIfPresent(clientKey, (k, c) -> {
                c.decrementAndGet();
                return c;
            });
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send market status immediately on connect
        try {
            var status = marketSessionService.getStatus();
            emitter.send(SseEmitter.event()
                    .name("market-status")
                    .data(objectMapper.writeValueAsString(status)));
        } catch (Exception e) {
            log.warn("Failed to send market-status on subscribe: {}", e.getMessage());
        }

        // Replay from lastEventId if provided
        if (lastEventId != null) {
            try {
                long fromId = Long.parseLong(lastEventId);
                Deque<MarketDataEvent> buffer = eventBuffer.get(ticker);
                if (buffer != null) {
                    // This is a simplified replay — just send latest
                    MarketDataEvent latest = latestData.get(ticker);
                    if (latest != null) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .id(String.valueOf(eventIdCounter.get()))
                                    .name("market-data")
                                    .data(objectMapper.writeValueAsString(latest)));
                        } catch (Exception e) { /* ignore */ }
                    }
                }
            } catch (NumberFormatException e) { /* ignore invalid id */ }
        }

        return emitter;
    }

    public void broadcast(String ticker, MarketDataEvent event) {
        latestData.put(ticker, event);
        long eventId = eventIdCounter.incrementAndGet();

        // Add to ring buffer (ConcurrentLinkedDeque — O(1) addLast/removeFirst, no array copies)
        Deque<MarketDataEvent> buffer = eventBuffer.computeIfAbsent(ticker, k -> new ConcurrentLinkedDeque<>());
        buffer.addLast(event);
        // Trim from the head — each removeFirst is O(1), no array reallocation
        while (buffer.size() > 1000) buffer.pollFirst();

        List<SseEmitter> tickerEmitters = emitters.get(ticker);
        if (tickerEmitters == null || tickerEmitters.isEmpty()) return;

        String eventJson;
        try {
            eventJson = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return;
        }

        for (SseEmitter emitter : tickerEmitters) {
            sseExecutor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(eventId))
                            .name("market-data")
                            .data(eventJson));
                } catch (Exception e) {
                    removeEmitter(ticker, emitter);
                }
            });
        }
    }

    public MarketDataEvent getLatest(String ticker) {
        return latestData.get(ticker);
    }

    /**
     * Every 30 seconds, broadcast market-status to all connected SSE clients.
     * This ensures clients detect open↔closed transitions promptly even if
     * no trades are happening.
     */
    @Scheduled(fixedRate = 30000)
    public void broadcastMarketStatus() {
        if (emitters.isEmpty()) return;

        var status = marketSessionService.getStatus();
        String json;
        try {
            json = objectMapper.writeValueAsString(status);
        } catch (Exception e) {
            return;
        }

        for (var entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("market-status")
                            .data(json));
                } catch (Exception e) {
                    removeEmitter(entry.getKey(), emitter);
                }
            }
        }
    }

    private void removeEmitter(String ticker, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(ticker);
        if (list != null) list.remove(emitter);
    }

    /**
     * Prune empty emitter lists and zero-count connection entries every 60s
     * to prevent unbounded map growth from disconnected clients.
     */
    @Scheduled(fixedRate = 60000)
    public void pruneStaleEntries() {
        emitters.entrySet().removeIf(e -> e.getValue().isEmpty());
        connectionCount.entrySet().removeIf(e -> e.getValue().get() <= 0);
    }
}
