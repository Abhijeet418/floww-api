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
import com.lmax.disruptor.RingBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order gateway — the entry point for all order placement and cancellation.
 *
 * Hot path (placeOrder) is fully non-blocking with respect to Postgres:
 *   1. Market hours check — MarketSessionService (in-memory)
 *   2. Rate limiting     — RateLimitService (Redis)
 *   3. Deduplication     — in-memory ConcurrentHashMap (no DB query)
 *   4. Ticker validation — TickerCache (in-memory, refreshed every 60s)
 *   5. Price validation  — TickerCache.getRefPrice() (no DB query)
 *   6. Sequence number   — OrderSequencer (AtomicLong, no DB lock)
 *   7. Publish            — LMAX Disruptor RingBuffer (submicrosecond)
 *   8. Return HTTP 200   — immediately, ~0.1ms total latency
 *
 * The DB write (order → Postgres) is performed asynchronously by
 * OrderPersistenceHandler on the Disruptor consumer thread, off the hot path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderGatewayService {

    private final OrderRepository orderRepository;
    private final DisruptorConfig disruptorConfig;
    private final RateLimitService rateLimitService;
    private final ExchangeProperties exchangeProperties;
    private final MarketSessionService marketSessionService;
    private final TickerCache tickerCache;
    private final OrderSequencer orderSequencer;

    // In-memory dedup: "appId:clientOrderId" → submission timestamp.
    // Prevents duplicate orders without a Postgres round-trip.
    // Entries are evicted by the scheduled cleanup every 5 minutes.
    private final Map<String, Long> recentOrders = new ConcurrentHashMap<>();

    // --- Hot Path ---

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

        // 3. Idempotency — in-memory dedup, no DB query
        String dedupKey = appId + ":" + request.getClientOrderId();
        if (recentOrders.putIfAbsent(dedupKey, System.currentTimeMillis()) != null) {
            throw new DuplicateOrderException("Duplicate clientOrderId: " + request.getClientOrderId());
        }

        // 4. Ticker validation — in-memory cache, no DB
        Ticker ticker = tickerCache.get(request.getTicker()).orElseGet(() -> {
            recentOrders.remove(dedupKey);
            throw new ResourceNotFoundException("Ticker not found: " + request.getTicker());
        });
        if (tickerCache.isHalted(request.getTicker())) {
            recentOrders.remove(dedupKey);
            throw new OrderRejectedException("Ticker " + request.getTicker() + " is halted");
        }

        // 5. Price/qty sanity — uses cached LTP, no DB
        validateOrderSanity(request, ticker);

        // 6. Generate IDs in memory — no DB sequence lock
        UUID newOrderId = UUID.randomUUID();
        long seq = orderSequencer.next(request.getTicker());

        // 7. Track open orders (Redis)
        rateLimitService.incrementOpenOrders(appId);

        // 8. Publish to Disruptor — submits to MatchingEngine AND OrderPersistenceHandler
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

        // Return immediately — Postgres write happens in OrderPersistenceHandler
        return OrderResponse.builder()
                .orderId(newOrderId)
                .clientOrderId(request.getClientOrderId())
                .ticker(request.getTicker())
                .side(request.getSide())
                .orderType(request.getType())
                .price(request.getPrice())
                .qty(request.getQty())
                .filledQty(0)
                .status(OrderStatus.OPEN)
                .sequenceNumber(seq)
                .createdAt(Instant.now())
                .build();
    }

    // --- Cancel (not a hot path, ~1000/min limit — DB reads are fine here) ---

    @Transactional
    public OrderResponse cancelOrder(UUID appId, UUID orderId) {
        rateLimitService.checkCancelRate(appId);

        // Atomically claim the cancel in a single UPDATE — prevents concurrent
        // double-cancel races where two threads both read status=OPEN before either commits.
        int updated = orderRepository.cancelIfEligible(orderId, appId);
        if (updated == 0) {
            // Determine the right error message by reading the order (already at DB boundary)
            ExchangeOrder order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
            if (!order.getAppId().equals(appId))
                throw new OrderRejectedException("Not your order");
            throw new OrderRejectedException("Cannot cancel order in status: " + order.getStatus());
        }

        // Re-fetch the now-CANCELLED order so we can build the response and publish to Disruptor.
        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

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

        rateLimitService.decrementOpenOrders(appId);
        return toOrderResponse(order);
    }

    public OrderResponse getOrder(UUID appId, UUID orderId) {
        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getAppId().equals(appId))
            throw new OrderRejectedException("Not your order");
        return toOrderResponse(order);
    }

    // --- Helpers ---

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

    /** Evict dedup entries older than 24 hours every 5 minutes. */
    @Scheduled(fixedRate = 300_000)
    public void evictStaleDedup() {
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        Iterator<Map.Entry<String, Long>> it = recentOrders.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) { it.remove(); removed++; }
        }
        if (removed > 0) log.debug("Evicted {} stale dedup entries", removed);
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
