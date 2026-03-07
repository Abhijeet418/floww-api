package com.floww.exchange.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataRetentionService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs every 4 hours to prune stale data and prevent the EC2 disk from filling up.
     * Retains active 'OPEN' orders, but deletes completed flow older than 24 hours.
     */
    @Scheduled(cron = "0 0 */4 * * *") // Run at minute 0 past every 4th hour
    public void pruneHistoricalData() {
        log.info("🧹 [GARBAGE COLLECTION] Starting database pruning...");
        long startTime = System.currentTimeMillis();

        try {
            // 1. Prune Orders (Keep 'OPEN' orders forever, delete dead orders older than 24h)
            int deletedOrders = jdbcTemplate.update(
                "DELETE FROM exchange_order WHERE status IN ('FILLED', 'CANCELLED', 'REJECTED') " +
                "AND updated_at < NOW() - INTERVAL '1 day'"
            );

            // 2. Prune Historical Trades (Older than 24h)
            // (Note: TimescaleDB candles already hold the historical price data)
            int deletedTrades = jdbcTemplate.update(
                "DELETE FROM trade WHERE traded_at < NOW() - INTERVAL '1 day'"
            );

            // 3. Prune Webhook Deliveries (Older than 24h)
            int deletedWebhooks = jdbcTemplate.update(
                "DELETE FROM webhook_delivery WHERE created_at < NOW() - INTERVAL '1 day'"
            );

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ [GARBAGE COLLECTION] Pruned {} orders, {} trades, {} webhooks in {}ms", 
                     deletedOrders, deletedTrades, deletedWebhooks, elapsed);

        } catch (Exception e) {
            log.error("❌ [GARBAGE COLLECTION] Failed to prune database: {}", e.getMessage());
        }
    }
}
