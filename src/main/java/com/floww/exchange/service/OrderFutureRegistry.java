package com.floww.exchange.service;

import com.floww.exchange.model.dto.OrderResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the HTTP thread (producer) and the matching engine thread (consumer).
 *
 * The gateway registers a CompletableFuture before publishing an event to the
 * Disruptor. The engine completes it after processing, waking the HTTP thread
 * which then returns the authoritative result to the broker.
 */
@Component
public class OrderFutureRegistry {

    private final ConcurrentHashMap<UUID, CompletableFuture<OrderResponse>> futures = new ConcurrentHashMap<>();

    /** Called by the gateway thread before publishing to the ring buffer. */
    public CompletableFuture<OrderResponse> register(UUID orderId) {
        CompletableFuture<OrderResponse> future = new CompletableFuture<>();
        futures.put(orderId, future);
        return future;
    }

    /** Called by the engine thread after processing the event. */
    public void complete(UUID orderId, OrderResponse response) {
        CompletableFuture<OrderResponse> future = futures.remove(orderId);
        if (future != null) future.complete(response);
    }

    /** Called by the engine thread when an order must be rejected. */
    public void completeExceptionally(UUID orderId, Exception ex) {
        CompletableFuture<OrderResponse> future = futures.remove(orderId);
        if (future != null) future.completeExceptionally(ex);
    }
}
