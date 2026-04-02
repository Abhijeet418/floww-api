package com.floww.exchange.service;

import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.engine.disruptor.DisruptorConfig;
import com.floww.exchange.engine.disruptor.OrderEventHolder;
import com.floww.exchange.exception.DuplicateOrderException;
import com.floww.exchange.exception.OrderRejectedException;
import com.floww.exchange.exception.ResourceNotFoundException;
import com.floww.exchange.model.dto.OrderResponse;
import com.floww.exchange.model.dto.PlaceOrderRequest;
import com.floww.exchange.model.entity.ExchangeOrder;
import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.model.enums.OrderType;
import com.floww.exchange.repository.OrderRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Order gateway — the entry point for all order placement and cancellation.
 *
 * Implements the Ingress/Egress CompletableFuture pattern:
 *   1. Gateway validates, publishes to the Disruptor, and WAITS on a future.
 *   2. The MatchingEngine processes the event and completes the future.
 *   3. The HTTP thread wakes up and returns the authoritative engine result.
 *
 * This eliminates the "HTTP 200 Lie" — every response reflects the exact
 * state computed by the single-threaded engine.
 */
@Service
@Slf4j
public class OrderGatewayService {

    private final OrderRepository orderRepository;
    private final DisruptorConfig disruptorConfig;
    private final RateLimitService rateLimitService;
    private final ExchangeProperties exchangeProperties;
    private final MarketSessionService marketSessionService;
    private final TickerCache tickerCache;
    private final OrderSequencer orderSequencer;
    private final OrderFutureRegistry futureRegistry;

    // In-memory dedup: Caffeine cache with TTL eviction and bounded size.
    private final Cache<String, Boolean> recentOrders;

    public OrderGatewayService(OrderRepository orderRepository,
                               DisruptorConfig disruptorConfig,
                               RateLimitService rateLimitService,
                               ExchangeProperties exchangeProperties,
                               MarketSessionService marketSessionService,
                               TickerCache tickerCache,
                               OrderSequencer orderSequencer,
                               OrderFutureRegistry futureRegistry) {
        this.orderRepository = orderRepository;
        this.disruptorConfig = disruptorConfig;
        this.rateLimitService = rateLimitService;
        this.exchangeProperties = exchangeProperties;
        this.marketSessionService = marketSessionService;
        this.tickerCache = tickerCache;
        this.orderSequencer = orderSequencer;
        this.futureRegistry = futureRegistry;
        this.recentOrders = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(100_000)
                .build();
    }

    // --- Place Order (waits for engine verdict) ---

    public OrderResponse placeOrder(UUID appId, PlaceOrderRequest request) {

        // 1. Market hours — pure in-memory
        if (!marketSessionService.isMarketOpen()) {
            throw new OrderRejectedException("Market is closed. Trading hours: "
                    + marketSessionService.getStatus().openTime() + " – "
                    + marketSessionService.getStatus().closeTime() + " "
                    + marketSessionService.getStatus().timezone());
        }

        // 2. Rate limits — Redis, not Postgres
        rateLimitService.checkOrderRate(appId);
        rateLimitService.checkOpenOrderLimit(appId);

        // 3. Idempotency — Caffeine cache, bounded + TTL-evicted
        String dedupKey = appId + ":" + request.getClientOrderId();
        if (recentOrders.asMap().putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            throw new DuplicateOrderException("Duplicate clientOrderId: " + request.getClientOrderId());
        }

        // 4. Ticker validation — in-memory cache, no DB
        Ticker ticker = tickerCache.get(request.getTicker()).orElseGet(() -> {
            recentOrders.invalidate(dedupKey);
            throw new ResourceNotFoundException("Ticker not found: " + request.getTicker());
        });
        if (tickerCache.isHalted(request.getTicker())) {
            recentOrders.invalidate(dedupKey);
            throw new OrderRejectedException("Ticker " + request.getTicker() + " is halted");
        }

        // 5. Price/qty sanity — uses cached LTP, no DB
        validateOrderSanity(request, ticker);

        // 6. Generate IDs in memory — no DB sequence lock
        UUID newOrderId = UUID.randomUUID();
        long seq = orderSequencer.next(request.getTicker());

        // 7. Track open orders (Redis) — engine will decrement if rejected
        rateLimitService.incrementOpenOrders(appId);

        // 8. Register future BEFORE publishing — engine may complete it instantly
        CompletableFuture<OrderResponse> future = futureRegistry.register(newOrderId);

        // 9. Publish to Disruptor
        RingBuffer<OrderEventHolder> ringBuffer = disruptorConfig.getOrderRingBuffer();
        long ringSeq = ringBuffer.next();
        try {
            OrderEventHolder holder = ringBuffer.get(ringSeq);
            holder.action        = OrderEventHolder.Action.PLACE;
            holder.orderId       = newOrderId;
            holder.clientOrderId = request.getClientOrderId();
            holder.appId         = appId;
            holder.traderId      = request.getTraderId();
            holder.ticker        = request.getTicker();
            holder.side          = request.getSide();
            holder.orderType     = request.getType();
            holder.price         = request.getPrice();
            holder.qty           = request.getQty();
            holder.sequenceNumber = seq;
            holder.timestamp     = Instant.now();
        } finally {
            ringBuffer.publish(ringSeq);
        }

        // 10. WAIT for the engine to process and return the authoritative result
        return awaitEngineResult(future, newOrderId);
    }

    // --- Cancel Order (waits for engine verdict) ---

    public OrderResponse cancelOrder(UUID appId, UUID orderId) {
        rateLimitService.checkCancelRate(appId);

        // Read-only DB lookup to get order metadata (NOT a status update).
        // The engine is the sole authority for the actual cancel.
        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getAppId().equals(appId))
            throw new OrderRejectedException("Not your order");
        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.PARTIALLY_FILLED)
            throw new OrderRejectedException("Cannot cancel order in status: " + order.getStatus());

        // Register future BEFORE publishing
        CompletableFuture<OrderResponse> future = futureRegistry.register(orderId);

        // Publish CANCEL to Disruptor — engine is the sole writer
        RingBuffer<OrderEventHolder> ringBuffer = disruptorConfig.getOrderRingBuffer();
        long sequence = ringBuffer.next();
        try {
            OrderEventHolder holder = ringBuffer.get(sequence);
            holder.action    = OrderEventHolder.Action.CANCEL;
            holder.orderId   = orderId;
            holder.ticker    = order.getTicker();
            holder.appId     = appId;
            holder.timestamp = Instant.now();
        } finally {
            ringBuffer.publish(sequence);
        }

        // Wait for engine to confirm the cancel
        OrderResponse response = awaitEngineResult(future, orderId);
        if (response == null) {
            // Order was not in the engine book. This happens when:
            // 1. Market orders that were auto-cancelled (never rest in book) but DB still shows OPEN
            //    due to a race between OrderPersistenceHandler INSERT and TradePersistenceHandler UPDATE
            // 2. Exchange restart lost in-memory book state for orders stuck as OPEN in DB
            // 3. Order was filled between DB read and engine processing
            ExchangeOrder current = orderRepository.findById(orderId).orElse(null);
            if (current != null && (current.getStatus() == OrderStatus.OPEN || current.getStatus() == OrderStatus.PARTIALLY_FILLED)) {
                // Order is still OPEN/PARTIALLY_FILLED in DB but NOT in the engine book.
                // Force-cancel it directly — the engine has already discarded it.
                log.warn("Force-cancelling orphaned order not in engine book: id={} type={} status={}",
                        orderId, current.getOrderType(), current.getStatus());
                current.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(current);
                rateLimitService.decrementOpenOrders(appId);
                return toOrderResponse(current);
            }
            if (current != null) {
                throw new OrderRejectedException("Cannot cancel order in status: " + current.getStatus());
            }
            throw new OrderRejectedException("Order no longer exists in the engine book");
        }
        return response;
    }

    public OrderResponse getOrder(UUID appId, UUID orderId) {
        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getAppId().equals(appId))
            throw new OrderRejectedException("Not your order");
        return toOrderResponse(order);
    }

    // --- Helpers ---

    private OrderResponse awaitEngineResult(CompletableFuture<OrderResponse> future, UUID orderId) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Engine didn't respond in time — clean up the dangling future
            futureRegistry.complete(orderId, null);
            throw new OrderRejectedException("Engine did not respond within timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OrderRejectedException ore) throw ore;
            throw new OrderRejectedException("Order processing failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderRejectedException("Order processing interrupted");
        }
    }

    private void validateOrderSanity(PlaceOrderRequest req, Ticker ticker) {
        if (req.getType() == OrderType.LIMIT || req.getType() == OrderType.STOP) {
            if (req.getPrice() == null || req.getPrice() <= 0)
                throw new OrderRejectedException("Price required for " + req.getType() + " orders");

            Long refPrice = tickerCache.getRefPrice(req.getTicker());
            if (refPrice != null && refPrice > 0) {
                double deviation = Math.abs((double)(req.getPrice() - refPrice) / refPrice) * 100;
                if (deviation > exchangeProperties.getOrderValidation().getMaxPriceDeviationPercent())
                    throw new OrderRejectedException("Price deviates " + String.format("%.1f", deviation) +
                            "% from reference " + refPrice + " (max " +
                            exchangeProperties.getOrderValidation().getMaxPriceDeviationPercent() + "%)");
            }
        }
        if (req.getQty() <= 0) throw new OrderRejectedException("Quantity must be positive");
    }

    private OrderResponse toOrderResponse(ExchangeOrder o) {
        return OrderResponse.builder()
                .orderId(o.getOrderId()).clientOrderId(o.getClientOrderId())
                .ticker(o.getTicker()).side(o.getSide()).orderType(o.getOrderType())
                .price(o.getPrice()).qty(o.getQty()).filledQty(o.getFilledQty())
                .status(o.getStatus()).sequenceNumber(o.getSequenceNumber())
                .createdAt(o.getCreatedAt()).build();
    }
}
