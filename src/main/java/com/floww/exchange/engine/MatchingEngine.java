package com.floww.exchange.engine;

import com.floww.exchange.engine.disruptor.DisruptorConfig;
import com.floww.exchange.engine.disruptor.TradeEventHolder;
import com.floww.exchange.model.dto.MarketDataEvent;
import com.floww.exchange.model.dto.OrderBookSnapshot;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.model.event.OrderEvent;
import com.floww.exchange.model.event.TradeEvent;
import com.floww.exchange.repository.OrderRepository;
import com.floww.exchange.service.RateLimitService;
import com.floww.exchange.service.SnapshotService;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matching Engine — consumes OrderEvents from the Disruptor ring buffer,
 * matches via in-memory OrderBooks, publishes TradeEvents to the trade
 * Disruptor for async persistence, OHLCV aggregation, and webhook dispatch.
 *
 * All order book state is in-memory. DB writes happen asynchronously
 * on separate Disruptor consumer threads.
 */
@Service
@Slf4j
public class MatchingEngine {

    private final RateLimitService rateLimitService;
    private final SnapshotService snapshotService;
    private final DisruptorConfig disruptorConfig;

    private final ConcurrentHashMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    public MatchingEngine(RateLimitService rateLimitService,
                          @Lazy SnapshotService snapshotService,
                          @Lazy DisruptorConfig disruptorConfig) {
        this.rateLimitService = rateLimitService;
        this.snapshotService = snapshotService;
        this.disruptorConfig = disruptorConfig;
    }

    /**
     * Called by OrderEventHandler on the Disruptor consumer thread.
     * Single-threaded — no locking needed on OrderBook.
     */
    public void onOrderEvent(OrderEvent event) {
        String ticker = event.getTicker();
        log.debug("[{}] {} orderId={} seq={}", ticker, event.getAction(), event.getOrderId(), event.getSequenceNumber());

        OrderBook book = orderBooks.computeIfAbsent(ticker, t -> {
            snapshotService.registerTicker(t);
            return new OrderBook(t);
        });

        if (event.getAction() == OrderEvent.Action.CANCEL) {
            handleCancel(book, event);
        } else {
            handlePlace(book, event);
        }
    }

    private void handlePlace(OrderBook book, OrderEvent event) {
        OrderBookEntry entry = OrderBookEntry.builder()
                .orderId(event.getOrderId()).appId(event.getAppId())
                .traderId(event.getTraderId()).ticker(event.getTicker())
                .side(event.getSide()).orderType(event.getOrderType())
                .price(event.getPrice() != null ? event.getPrice() : 0)
                .qty(event.getQty()).remainingQty(event.getQty())
                .sequenceNumber(event.getSequenceNumber())
                .timestamp(System.currentTimeMillis())
                .build();

        List<TradeEvent> trades = book.processOrder(entry);

        // Publish each trade to the trade Disruptor for async processing
        RingBuffer<TradeEventHolder> tradeRing = disruptorConfig.getTradeRingBuffer();
        for (TradeEvent trade : trades) {
            long seq = tradeRing.next();
            try {
                TradeEventHolder holder = tradeRing.get(seq);
                holder.tradeId = trade.getTradeId();
                holder.ticker = trade.getTicker();
                holder.price = trade.getPrice();
                holder.qty = trade.getQty();
                holder.buyOrderId = trade.getBuyOrderId();
                holder.sellOrderId = trade.getSellOrderId();
                holder.buyerAppId = trade.getBuyerAppId();
                holder.sellerAppId = trade.getSellerAppId();
                holder.tradedAt = trade.getTradedAt();
                // Context for async order-status update
                holder.aggressorOrderId = event.getOrderId();
                holder.aggressorRemainingQty = entry.getRemainingQty();
                holder.aggressorOriginalQty = event.getQty();
                // Maker (passive) order context
                holder.makerOrderId = trade.getMakerOrderId();
                holder.makerRemainingQty = trade.getMakerRemainingQty();
                holder.makerOriginalQty = trade.getMakerOriginalQty();
                // Market data context
                holder.lastTradedPrice = book.getLastTradedPrice();
                holder.sessionVolume = book.getSessionVolume();
                holder.bestBid = !book.getBids().isEmpty() ? book.getBids().firstKey() : null;
                holder.bestAsk = !book.getAsks().isEmpty() ? book.getAsks().firstKey() : null;
            } finally {
                tradeRing.publish(seq);
            }
        }
    }

    private void handleCancel(OrderBook book, OrderEvent event) {
        boolean cancelled = book.cancelOrder(event.getOrderId());
        if (cancelled) {
            rateLimitService.decrementOpenOrders(event.getAppId());
            log.debug("[{}] Order cancelled: {}", event.getTicker(), event.getOrderId());
        }
    }

    /**
     * Replay an order into the book without matching (for crash recovery).
     * Inserts directly at the given price level.
     */
    public void replayOrder(OrderBookEntry entry) {
        OrderBook book = orderBooks.computeIfAbsent(entry.getTicker(), t -> {
            snapshotService.registerTicker(t);
            return new OrderBook(t);
        });
        book.insertWithoutMatching(entry);
    }

    public OrderBookSnapshot getSnapshot(String ticker) {
        OrderBook book = orderBooks.get(ticker);
        return book != null ? book.toSnapshot() : null;
    }

    public OrderBook getOrderBook(String ticker) {
        return orderBooks.get(ticker);
    }
}
