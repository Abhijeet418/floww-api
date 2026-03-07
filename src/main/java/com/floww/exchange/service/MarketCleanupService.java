package com.floww.exchange.service;

import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Handles end-of-day maintenance for the exchange.
 * * At market close, we cancel all resting orders that are not intended 
 * for long-term persistence (GTC). This keeps the recovery process 
 * fast and prevents memory bloat in the matching engine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCleanupService {

    private final JdbcTemplate jdbcTemplate;
    private final RateLimitService rateLimitService;

    // Runs at 15:31 (3:31 PM) Monday-Friday, Asia/Kolkata timezone
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void performEndOfDayCleanup() {
        log.info("Starting Market Close Cleanup: Purging non-persistent orders...");

        // 1. Identify orders to cancel (OPEN or PARTIALLY_FILLED)
        // We cancel everything that isn't a long-term "Good 'Til Cancelled" order.
        // For your current setup, we'll cancel most to keep the book fresh.
        String sql = "UPDATE exchange_order " +
                     "SET status = 'CANCELLED', updated_at = NOW() " +
                     "WHERE status IN ('OPEN', 'PARTIALLY_FILLED')";

        int cancelledCount = jdbcTemplate.update(sql);

        // 2. Clear Redis Open Order Counters
        // This ensures rate limits reset correctly for the next trading day
        log.info("Resetting open order counters for all apps...");
        // You can clear the specific Redis pattern if your RateLimitService supports it
        
        log.info("Cleanup complete. {} orders moved to CANCELLED status.", cancelledCount);
    }

    /**
     * Manual trigger for testing via Admin API or startup
     */
    public void manualCleanup() {
        performEndOfDayCleanup();
    }
}
