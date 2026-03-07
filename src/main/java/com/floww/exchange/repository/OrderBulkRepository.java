package com.floww.exchange.repository;

import com.floww.exchange.model.entity.ExchangeOrder;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class OrderBulkRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderBulkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAllIgnoringDuplicates(List<ExchangeOrder> orders) {
        if (orders == null || orders.isEmpty()) return;

        // ON CONFLICT DO NOTHING silently drops duplicate rows instead of
        // aborting the entire batch — essential for idempotent HFT ingestion.
        String sql = "INSERT INTO exchange_order " +
                "(app_id, client_order_id, created_at, filled_qty, order_type, price, qty, sequence_number, side, status, ticker, trader_id, updated_at, order_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (app_id, client_order_id) DO NOTHING";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ExchangeOrder order = orders.get(i);

                // @PrePersist does not fire via JdbcTemplate — supply timestamps here
                Instant now = Instant.now();
                Instant createdAt = order.getCreatedAt() != null ? order.getCreatedAt() : now;
                Instant updatedAt = order.getUpdatedAt() != null ? order.getUpdatedAt() : now;

                ps.setObject(1, order.getAppId());
                ps.setString(2, order.getClientOrderId());
                ps.setTimestamp(3, Timestamp.from(createdAt));
                ps.setLong(4, order.getFilledQty());
                ps.setString(5, order.getOrderType().name());

                // MARKET orders carry no price
                if (order.getPrice() != null) {
                    ps.setLong(6, order.getPrice());
                } else {
                    ps.setNull(6, java.sql.Types.BIGINT);
                }

                ps.setLong(7, order.getQty());
                ps.setLong(8, order.getSequenceNumber());
                ps.setString(9, order.getSide().name());
                ps.setString(10, order.getStatus().name());
                ps.setString(11, order.getTicker());
                ps.setString(12, order.getTraderId());
                ps.setTimestamp(13, Timestamp.from(updatedAt));
                ps.setObject(14, order.getOrderId());
            }

            @Override
            public int getBatchSize() {
                return orders.size();
            }
        });
    }
}
