package com.floww.exchange.engine;

import com.floww.exchange.model.dto.MarketDataEvent;
import com.floww.exchange.model.dto.OrderBookSnapshot;
import com.floww.exchange.model.enums.OrderSide;
import com.floww.exchange.model.enums.OrderType;
import com.floww.exchange.model.event.TradeEvent;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;

/**
 * Order Book — price-time priority matching for a single ticker.
 *
 * Uses TreeMap<Long, ArrayDeque<OrderBookEntry>>:
 *   - Bids: reversed (highest price first)
 *   - Asks: natural (lowest price first)
 *   - Within same price: FIFO via ArrayDeque (time priority)
 *
 * All prices are in paise/cents (long). No floating point.
 */
@Slf4j
public class OrderBook {

    private final String ticker;

    // Bids: highest price first (reversed)
    @Getter private final TreeMap<Long, ArrayDeque<OrderBookEntry>> bids = new TreeMap<>(Comparator.reverseOrder());
    // Asks: lowest price first (natural)
    @Getter private final TreeMap<Long, ArrayDeque<OrderBookEntry>> asks = new TreeMap<>();
    // O(1) lookup for cancellations
    private final HashMap<UUID, OrderBookEntry> orderIndex = new HashMap<>();

    // Thread-safe snapshot: updated on the engine thread after mutations,
    // read by the scheduled SnapshotService thread (volatile ensures visibility).
    private volatile OrderBookSnapshot cachedSnapshot;

    @Getter private long lastTradedPrice = 0;
    @Getter private long sessionVolume = 0;

    // Circuit breaker: triggers when a fill deviates >20% from the reference price
    private static final long CB_DENOMINATOR = 5; // 1/5 = 20%
    @Getter private long referencePrice = 0;
    @Getter @Setter private boolean circuitBreakerTriggered = false;

    public OrderBook(String ticker) {
        this.ticker = ticker;
    }

    /**
     * Sets the fixed reference price for circuit breaker checks.
     * Typically the session open price from the DB.
     * If not set, the first trade price auto-sets it.
     */
    public void setReferencePrice(long price) {
        if (price > 0) this.referencePrice = price;
    }

    /**
     * Process an incoming order. Returns list of trades generated.
     */
    public List<TradeEvent> processOrder(OrderBookEntry entry) {
        List<TradeEvent> trades;
        if (entry.getOrderType() == OrderType.MARKET) {
            trades = matchMarketOrder(entry);
        } else {
            trades = matchLimitOrder(entry);
        }
        refreshSnapshot();
        return trades;
    }

    /**
     * Cancel and return the entry, or null if not found.
     * Returning the entry lets the engine build the response and complete the future.
     */
    public OrderBookEntry cancelOrder(UUID orderId) {
        OrderBookEntry entry = orderIndex.remove(orderId);
        if (entry == null) return null;

        TreeMap<Long, ArrayDeque<OrderBookEntry>> book = entry.getSide() == OrderSide.BUY ? bids : asks;
        ArrayDeque<OrderBookEntry> level = book.get(entry.getPrice());
        if (level != null) {
            level.remove(entry);
            if (level.isEmpty()) book.remove(entry.getPrice());
        }
        refreshSnapshot();
        return entry;
    }

    private List<TradeEvent> matchMarketOrder(OrderBookEntry aggressor) {
        List<TradeEvent> trades = new ArrayList<>();
        TreeMap<Long, ArrayDeque<OrderBookEntry>> oppBook = aggressor.getSide() == OrderSide.BUY ? asks : bids;

        while (aggressor.getRemainingQty() > 0 && !oppBook.isEmpty()) {
            Map.Entry<Long, ArrayDeque<OrderBookEntry>> bestLevel = oppBook.firstEntry();
            ArrayDeque<OrderBookEntry> queue = bestLevel.getValue();
            OrderBookEntry resting = queue.peekFirst();

            long fillQty = Math.min(aggressor.getRemainingQty(), resting.getRemainingQty());
            long fillPrice = resting.getPrice(); // Execute at resting order's price

            trades.add(createTrade(aggressor, resting, fillPrice, fillQty));

            aggressor.setRemainingQty(aggressor.getRemainingQty() - fillQty);
            resting.setRemainingQty(resting.getRemainingQty() - fillQty);

            if (resting.getRemainingQty() == 0) {
                queue.pollFirst();
                orderIndex.remove(resting.getOrderId());
                if (queue.isEmpty()) oppBook.remove(bestLevel.getKey());
            }

            lastTradedPrice = fillPrice;
            sessionVolume += fillQty;

            if (checkCircuitBreaker(fillPrice)) break;
        }
        // Any remaining qty for a market order is discarded (no resting for market)
        return trades;
    }

    private List<TradeEvent> matchLimitOrder(OrderBookEntry aggressor) {
        List<TradeEvent> trades = new ArrayList<>();
        TreeMap<Long, ArrayDeque<OrderBookEntry>> oppBook = aggressor.getSide() == OrderSide.BUY ? asks : bids;

        while (aggressor.getRemainingQty() > 0 && !oppBook.isEmpty()) {
            Map.Entry<Long, ArrayDeque<OrderBookEntry>> bestLevel = oppBook.firstEntry();
            long bestPrice = bestLevel.getKey();

            // Check if prices cross
            boolean crosses = aggressor.getSide() == OrderSide.BUY
                    ? aggressor.getPrice() >= bestPrice
                    : aggressor.getPrice() <= bestPrice;
            if (!crosses) break;

            ArrayDeque<OrderBookEntry> queue = bestLevel.getValue();
            OrderBookEntry resting = queue.peekFirst();

            long fillQty = Math.min(aggressor.getRemainingQty(), resting.getRemainingQty());
            long fillPrice = resting.getPrice();

            trades.add(createTrade(aggressor, resting, fillPrice, fillQty));

            aggressor.setRemainingQty(aggressor.getRemainingQty() - fillQty);
            resting.setRemainingQty(resting.getRemainingQty() - fillQty);

            if (resting.getRemainingQty() == 0) {
                queue.pollFirst();
                orderIndex.remove(resting.getOrderId());
                if (queue.isEmpty()) oppBook.remove(bestLevel.getKey());
            }

            lastTradedPrice = fillPrice;
            sessionVolume += fillQty;

            if (checkCircuitBreaker(fillPrice)) break;
        }

        // Rest any unfilled qty
        if (aggressor.getRemainingQty() > 0) {
            addToBook(aggressor);
        }

        return trades;
    }

    private void addToBook(OrderBookEntry entry) {
        TreeMap<Long, ArrayDeque<OrderBookEntry>> book = entry.getSide() == OrderSide.BUY ? bids : asks;
        book.computeIfAbsent(entry.getPrice(), k -> new ArrayDeque<>()).addLast(entry);
        orderIndex.put(entry.getOrderId(), entry);
    }

    /**
     * Checks if the fill price breaches the 20% circuit breaker threshold.
     * The first trade of the session auto-sets the reference if not already configured.
     */
    private boolean checkCircuitBreaker(long fillPrice) {
        if (referencePrice == 0) {
            referencePrice = fillPrice;
            return false;
        }
        // Rearranged to avoid overflow: |fill - ref| > ref / denom  (instead of |fill - ref| * denom > ref)
        if (Math.abs(fillPrice - referencePrice) > referencePrice / CB_DENOMINATOR) {
            circuitBreakerTriggered = true;
            return true;
        }
        return false;
    }

    private TradeEvent createTrade(OrderBookEntry aggressor, OrderBookEntry resting, long price, long qty) {
        UUID buyOrderId = aggressor.getSide() == OrderSide.BUY ? aggressor.getOrderId() : resting.getOrderId();
        UUID sellOrderId = aggressor.getSide() == OrderSide.SELL ? aggressor.getOrderId() : resting.getOrderId();
        UUID buyerAppId = aggressor.getSide() == OrderSide.BUY ? aggressor.getAppId() : resting.getAppId();
        UUID sellerAppId = aggressor.getSide() == OrderSide.SELL ? aggressor.getAppId() : resting.getAppId();

        return TradeEvent.builder()
                .tradeId(UUID.randomUUID())
                .ticker(ticker).price(price).qty(qty)
                .buyOrderId(buyOrderId).sellOrderId(sellOrderId)
                .buyerAppId(buyerAppId).sellerAppId(sellerAppId)
                .tradedAt(Instant.now())
                // Maker (passive/resting) order context for async status updates
                .makerOrderId(resting.getOrderId())
                .makerOriginalQty(resting.getQty())
                .makerRemainingQty(resting.getRemainingQty() - qty)
                .build();
    }

    /**
     * Builds a snapshot from the current book state.
     * MUST only be called from the engine thread (single-writer).
     */
    private void refreshSnapshot() {
        List<OrderBookSnapshot.PriceLevel> bidLevels = new ArrayList<>();
        for (var entry : bids.entrySet()) {
            long totalQty = entry.getValue().stream().mapToLong(OrderBookEntry::getRemainingQty).sum();
            bidLevels.add(OrderBookSnapshot.PriceLevel.builder()
                    .price(entry.getKey()).qty(totalQty).orderCount(entry.getValue().size()).build());
            if (bidLevels.size() >= 20) break;
        }

        List<OrderBookSnapshot.PriceLevel> askLevels = new ArrayList<>();
        for (var entry : asks.entrySet()) {
            long totalQty = entry.getValue().stream().mapToLong(OrderBookEntry::getRemainingQty).sum();
            askLevels.add(OrderBookSnapshot.PriceLevel.builder()
                    .price(entry.getKey()).qty(totalQty).orderCount(entry.getValue().size()).build());
            if (askLevels.size() >= 20) break;
        }

        cachedSnapshot = OrderBookSnapshot.builder()
                .ticker(ticker).bids(bidLevels).asks(askLevels)
                .timestamp(Instant.now()).exchangeType("SIMULATED")
                .build();
    }

    /**
     * Returns the last snapshot built on the engine thread.
     * Thread-safe — reads a volatile reference to an immutable object.
     */
    public OrderBookSnapshot toSnapshot() {
        return cachedSnapshot;
    }

    public MarketDataEvent toMarketData() {
        return MarketDataEvent.builder()
                .type("SNAPSHOT").ticker(ticker)
                .ltp(lastTradedPrice > 0 ? lastTradedPrice : null)
                .volume(sessionVolume)
                .bestBid(!bids.isEmpty() ? bids.firstKey() : null)
                .bestAsk(!asks.isEmpty() ? asks.firstKey() : null)
                .build();
    }

    /**
     * Insert an order directly into the book without matching.
     * Used for crash recovery — replays resting orders from DB.
     */
    public void insertWithoutMatching(OrderBookEntry entry) {
        addToBook(entry);
        refreshSnapshot();
        log.debug("[{}] Recovered order {} @ {} qty={}", ticker, entry.getOrderId(), entry.getPrice(), entry.getRemainingQty());
    }

    /** Returns all resting order entries (for cleanup/purge operations). */
    public List<OrderBookEntry> getAllRestingOrders() {
        return new ArrayList<>(orderIndex.values());
    }
}
