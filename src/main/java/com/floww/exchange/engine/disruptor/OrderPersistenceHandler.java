package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.entity.ExchangeOrder;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.repository.OrderBulkRepository;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    // Local to this single consumer thread — no synchronization needed
    private final List<ExchangeOrder> batch = new ArrayList<>(MAX_BATCH);

    @Override
    public void onEvent(OrderEventHolder holder, long sequence, boolean endOfBatch) {
        // Only persist PLACE events; CANCEL is handled synchronously in cancelOrder()
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
        }

        if (endOfBatch || batch.size() >= MAX_BATCH) {
            flush();
        }
    }

    private void flush() {
        if (batch.isEmpty()) return;
        try {
            orderBulkRepository.saveAllIgnoringDuplicates(batch);
        } catch (Exception e) {
            log.error("Batch order save failed ({} orders): {}", batch.size(), e.getMessage(), e);
        } finally {
            batch.clear();
        }
    }
}
