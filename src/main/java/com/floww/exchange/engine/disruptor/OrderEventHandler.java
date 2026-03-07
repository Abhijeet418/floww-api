package com.floww.exchange.engine.disruptor;

import com.floww.exchange.engine.MatchingEngine;
import com.floww.exchange.model.event.OrderEvent;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Disruptor consumer — receives OrderEventHolders from the ring buffer
 * and dispatches them to the MatchingEngine.
 *
 * Runs on a single Disruptor thread, so the MatchingEngine's onOrderEvent
 * is called sequentially (no locking needed on OrderBook).
 */
@Component
@Slf4j
public class OrderEventHandler implements EventHandler<OrderEventHolder> {

    private final MatchingEngine matchingEngine;

    public OrderEventHandler(@Lazy MatchingEngine matchingEngine) {
        this.matchingEngine = matchingEngine;
    }

    @Override
    public void onEvent(OrderEventHolder holder, long sequence, boolean endOfBatch) {
        try {
            OrderEvent event = OrderEvent.builder()
                    .action(holder.action == OrderEventHolder.Action.PLACE
                            ? OrderEvent.Action.PLACE : OrderEvent.Action.CANCEL)
                    .orderId(holder.orderId)
                    .clientOrderId(holder.clientOrderId)
                    .appId(holder.appId)
                    .traderId(holder.traderId)
                    .ticker(holder.ticker)
                    .side(holder.side)
                    .orderType(holder.orderType)
                    .price(holder.price)
                    .qty(holder.qty)
                    .sequenceNumber(holder.sequenceNumber)
                    .timestamp(holder.timestamp)
                    .build();

            matchingEngine.onOrderEvent(event);
        } catch (Exception e) {
            log.error("MatchingEngine error on seq={}: {}", sequence, e.getMessage(), e);
        }
        // NOTE: holder.clear() is intentionally removed.
        // The Disruptor .then() clearing handler in DisruptorConfig runs after
        // ALL parallel consumers (OrderEventHandler + OrderPersistenceHandler)
        // have finished, ensuring OrderPersistenceHandler reads a valid slot.
    }
}
