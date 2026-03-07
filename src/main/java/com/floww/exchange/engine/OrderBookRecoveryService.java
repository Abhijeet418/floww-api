package com.floww.exchange.engine;

import com.floww.exchange.model.entity.ExchangeOrder;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Crash recovery — on startup, replays open/partially-filled orders from Postgres
 * back into the in-memory OrderBooks so the matching engine can resume.
 *
 * Orders are inserted directly (without matching) since they were already
 * resting in the book before the crash. Sequence number ordering is preserved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookRecoveryService {

    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;

    @PostConstruct
    public void recover() {
        List<OrderStatus> recoverableStatuses = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

        // Query all orders that were resting in books before crash
        List<ExchangeOrder> openOrders = orderRepository.findByStatusInOrderBySequenceNumber(recoverableStatuses);

        if (openOrders.isEmpty()) {
            log.info("Order book recovery: no open orders to recover");
            return;
        }

        int recovered = 0;
        for (ExchangeOrder order : openOrders) {
            try {
                // Market orders never rest in the book, skip them
                if (order.getOrderType() == com.floww.exchange.model.enums.OrderType.MARKET) continue;

                long remainingQty = order.getQty() - order.getFilledQty();
                if (remainingQty <= 0) continue;

                OrderBookEntry entry = OrderBookEntry.builder()
                        .orderId(order.getOrderId())
                        .appId(order.getAppId())
                        .traderId(order.getTraderId())
                        .ticker(order.getTicker())
                        .side(order.getSide())
                        .orderType(order.getOrderType())
                        .price(order.getPrice() != null ? order.getPrice() : 0)
                        .qty(order.getQty())
                        .remainingQty(remainingQty)
                        .sequenceNumber(order.getSequenceNumber() != null ? order.getSequenceNumber() : 0)
                        .timestamp(order.getCreatedAt() != null ? order.getCreatedAt().toEpochMilli() : System.currentTimeMillis())
                        .build();

                matchingEngine.replayOrder(entry);
                recovered++;
            } catch (Exception e) {
                log.warn("Failed to recover order {}: {}", order.getOrderId(), e.getMessage());
            }
        }

        log.info("Order book recovery complete: {} orders replayed from DB", recovered);
    }
}
