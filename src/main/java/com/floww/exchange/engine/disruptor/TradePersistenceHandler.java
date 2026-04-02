package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.entity.Trade;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.repository.OrderRepository;
import com.floww.exchange.repository.TradeRepository;
import com.floww.exchange.service.RateLimitService;
import com.floww.exchange.service.TickerCache;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Async trade persistence — batches DB inserts using the Disruptor's endOfBatch flag.
 *
 * Accumulates trades and order-status updates, then flushes in one saveAll() call
 * per batch. At peak throughput this collapses hundreds of single-row INSERTs into
 * a handful of bulk operations, preventing HikariCP pool exhaustion.
 *
 * Also keeps TickerCache LTP current so the hot path never queries Postgres.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TradePersistenceHandler implements EventHandler<TradeEventHolder> {

    private static final int MAX_BATCH = 100;

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final RateLimitService rateLimitService;
    private final TickerCache tickerCache;

    // Local to this single consumer thread — no synchronization needed
    private final List<Trade>        tradeBatch  = new ArrayList<>(MAX_BATCH);
    private final List<OrderUpdate>  orderBatch  = new ArrayList<>(MAX_BATCH);
    private final List<CancelledOrder> cancelBatch = new ArrayList<>();

    @Override
    public void onEvent(TradeEventHolder holder, long sequence, boolean endOfBatch) {
        // Handle unfilled market order cancellation signals
        if (holder.cancelledOrderId != null && holder.tradeId == null) {
            cancelBatch.add(new CancelledOrder(
                    holder.cancelledOrderId,
                    holder.cancelledOrderAppId,
                    holder.cancelledOrderOriginalQty,
                    holder.cancelledOrderFilledQty));
            if (endOfBatch || cancelBatch.size() >= MAX_BATCH) {
                flush();
            }
            return;
        }

        // Skip empty events (e.g. pure cancel signals already handled)
        if (holder.tradeId == null) return;

        // 1. Snapshot trade data out of the ring buffer slot
        tradeBatch.add(Trade.builder()
                .tradeId(holder.tradeId)
                .ticker(holder.ticker)
                .price(holder.price)
                .qty(holder.qty)
                .buyOrderId(holder.buyOrderId)
                .sellOrderId(holder.sellOrderId)
                .buyerAppId(holder.buyerAppId)
                .sellerAppId(holder.sellerAppId)
                .tradedAt(holder.tradedAt)
                .build());

        // 2. Update LTP in memory immediately (no DB, essentially free)
        tickerCache.updateLtp(holder.ticker, holder.price);

        // 3. Snapshot order update intent
        if (holder.aggressorOrderId != null) {
            orderBatch.add(new OrderUpdate(
                    holder.aggressorOrderId,
                    holder.aggressorRemainingQty,
                    holder.aggressorOriginalQty));
        }
        if (holder.makerOrderId != null) {
            orderBatch.add(new OrderUpdate(
                    holder.makerOrderId,
                    holder.makerRemainingQty,
                    holder.makerOriginalQty));
        }

        if (endOfBatch || tradeBatch.size() >= MAX_BATCH) {
            flush();
        }
    }

    private void flush() {
        if (!tradeBatch.isEmpty()) {
            try {
                tradeRepository.saveAll(tradeBatch);
            } catch (Exception e) {
                log.error("Batch trade save failed ({} trades): {}", tradeBatch.size(), e.getMessage(), e);
            } finally {
                tradeBatch.clear();
            }
        }

        if (!orderBatch.isEmpty()) {
            try {
                List<UUID> ids = orderBatch.stream().map(u -> u.orderId).toList();
                List<com.floww.exchange.model.entity.ExchangeOrder> orders =
                        orderRepository.findAllById(ids);
                if (orders.size() < ids.size()) {
                    log.warn("findAllById returned {}/{} orders — {} missing from DB",
                            orders.size(), ids.size(), ids.size() - orders.size());
                }
                for (com.floww.exchange.model.entity.ExchangeOrder order : orders) {
                    OrderUpdate upd = orderBatch.stream()
                            .filter(u -> u.orderId.equals(order.getOrderId()))
                            .findFirst().orElse(null);
                    if (upd == null) continue;
                    order.setFilledQty(upd.originalQty - upd.remainingQty);
                    if (upd.remainingQty == 0) {
                        order.setStatus(OrderStatus.FILLED);
                        rateLimitService.decrementOpenOrders(order.getAppId());
                    } else if (upd.remainingQty < upd.originalQty) {
                        order.setStatus(OrderStatus.PARTIALLY_FILLED);
                    }
                }
                orderRepository.saveAll(orders);
            } catch (Exception e) {
                log.error("Batch order-status update failed: {}", e.getMessage(), e);
            } finally {
                orderBatch.clear();
            }
        }

        if (!cancelBatch.isEmpty()) {
            try {
                List<UUID> cancelIds = cancelBatch.stream().map(c -> c.orderId).toList();
                List<com.floww.exchange.model.entity.ExchangeOrder> orders =
                        orderRepository.findAllById(cancelIds);
                if (orders.size() < cancelIds.size()) {
                    log.warn("Cancel findAllById returned {}/{} orders",
                            orders.size(), cancelIds.size());
                }
                for (com.floww.exchange.model.entity.ExchangeOrder order : orders) {
                    CancelledOrder cancel = cancelBatch.stream()
                            .filter(c -> c.orderId.equals(order.getOrderId()))
                            .findFirst().orElse(null);
                    if (cancel == null) continue;
                    order.setFilledQty(cancel.filledQty);
                    order.setStatus(OrderStatus.CANCELLED);
                    rateLimitService.decrementOpenOrders(cancel.appId);
                }
                orderRepository.saveAll(orders);
            } catch (Exception e) {
                log.error("Batch market-order cancel update failed: {}", e.getMessage(), e);
            } finally {
                cancelBatch.clear();
            }
        }
    }

    private record OrderUpdate(UUID orderId, long remainingQty, long originalQty) {}
    private record CancelledOrder(UUID orderId, UUID appId, long originalQty, long filledQty) {}
}
