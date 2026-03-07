package com.floww.exchange.repository;

import com.floww.exchange.model.entity.ExchangeOrder;
import com.floww.exchange.model.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<ExchangeOrder, UUID> {
    Optional<ExchangeOrder> findByAppIdAndClientOrderId(UUID appId, String clientOrderId);

    @Query("SELECT COUNT(o) FROM ExchangeOrder o WHERE o.appId = :appId AND o.status IN ('OPEN','PARTIALLY_FILLED')")
    long countOpenOrders(UUID appId);

    List<ExchangeOrder> findByTickerAndStatusIn(String ticker, List<OrderStatus> statuses);

    List<ExchangeOrder> findByStatusInOrderBySequenceNumber(List<OrderStatus> statuses);

    /**
     * Atomically claims cancellation: marks the order CANCELLED only if it is
     * currently OPEN or PARTIALLY_FILLED and belongs to the given appId.
     * Returns the number of rows updated (1 = success, 0 = not eligible / not owner).
     */
    @Modifying
    @Query("UPDATE ExchangeOrder o SET o.status = com.floww.exchange.model.enums.OrderStatus.CANCELLED " +
           "WHERE o.orderId = :orderId AND o.appId = :appId " +
           "AND o.status IN (com.floww.exchange.model.enums.OrderStatus.OPEN, com.floww.exchange.model.enums.OrderStatus.PARTIALLY_FILLED)")
    int cancelIfEligible(UUID orderId, UUID appId);
}
