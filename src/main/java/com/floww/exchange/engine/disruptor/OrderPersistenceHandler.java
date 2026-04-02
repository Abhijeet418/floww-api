package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.entity.ExchangeOrder;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.repository.OrderBulkRepository;
import com.floww.exchange.repository.OrderRepository;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Async order persistence — batches DB inserts using the Disruptor's endOfBatch flag.
 *
 * Instead of 1 INSERT per order (2,000 DB transactions/sec at peak),
 * we accumulate up to 100 orders and flush in a single saveAll() call.
 * At 2,000 RPS this reduces DB transactions from ~2,000/sec to ~20/sec.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderPersistenceHandler implements EventHandler<OrderEventHolder> {

    private static final int MAX_BATCH = 100;

    private final OrderBulkRepository orderBulkRepository;
    private final OrderRepository orderRepository;

    // Local to this single consumer thread — no synchronization needed
    private final List<ExchangeOrder> batch = new ArrayList<>(MAX_BATCH);
    private final List<CancelRequest> cancelBatch = new ArrayList<>(MAX_BATCH);

    @Override
    public void onEvent(OrderEventHolder holder, long sequence, boolean endOfBatch) {
        if (holder.action == OrderEventHolder.Action.PLACE) {
            // Copy data out of the holder NOW — the slot will be cleared after this call
            batch.add(ExchangeOrder.builder()
                    .orderId(holder.orderId)
                    .clientOrderId(holder.clientOrderId)
                    .appId(holder.appId)
                    .traderId(holder.traderId)
                    .ticker(holder.ticker)
                    .side(holder.side)
                    .orderType(holder.orderType)
                    .price(holder.price)
                    .qty(holder.qty)
                    .filledQty(0)
                    .status(OrderStatus.OPEN)
                    .sequenceNumber(holder.sequenceNumber)
                    .build());
        } else if (holder.action == OrderEventHolder.Action.CANCEL) {
            cancelBatch.add(new CancelRequest(holder.orderId, holder.appId));
        }

        if (endOfBatch || batch.size() >= MAX_BATCH || cancelBatch.size() >= MAX_BATCH) {
            flush();
        }
    }

    private void flush() {
        if (!batch.isEmpty()) {
            try {
                orderBulkRepository.saveAllIgnoringDuplicates(batch);
            } catch (Exception e) {
                log.error("Batch order save failed ({} orders): {}", batch.size(), e.getMessage(), e);
            } finally {
                batch.clear();
            }
        }

        if (!cancelBatch.isEmpty()) {
            try {
                for (CancelRequest cancel : cancelBatch) {
                    orderRepository.cancelIfEligible(cancel.orderId, cancel.appId);
                }
            } catch (Exception e) {
                log.error("Batch cancel persist failed ({} cancels): {}", cancelBatch.size(), e.getMessage(), e);
            } finally {
                cancelBatch.clear();
            }
        }
    }

    private record CancelRequest(UUID orderId, UUID appId) {}
}
