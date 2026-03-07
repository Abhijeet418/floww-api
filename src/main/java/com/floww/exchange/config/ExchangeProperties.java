package com.floww.exchange.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "floww.exchange")
@Data
public class ExchangeProperties {
    private String type = "SIMULATED";
    private String adminToken;
    private String apiKeyPrefix = "flw_";
    private int apiKeyLength = 48;
    private RateLimit rateLimit = new RateLimit();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private OrderValidation orderValidation = new OrderValidation();
    private Webhook webhook = new Webhook();
    private Snapshot snapshot = new Snapshot();
    private MarketHours marketHours = new MarketHours();

    @Data public static class MarketHours {
        private String openTime = "09:15";
        private String closeTime = "15:30";
        private String timezone = "Asia/Kolkata";
    }

    @Data public static class RateLimit {
        private int ordersPerSecond = 20;
        private int orderBurstCapacity = 50;
        private int orderBurstWindowSeconds = 10;
        private int globalRequestsPerSecond = 100;
        private int registrationsPerIpPerDay = 1;
        private int maxOpenOrders = 100000;
        private int cancellationsPerMinute = 1000;
    }
    @Data public static class CircuitBreaker {
        private int thresholdPercent = 20;
        private int haltDurationMinutes = 15;
    }
    @Data public static class OrderValidation {
        private int maxPriceDeviationPercent = 80;
    }
    @Data public static class Webhook {
        private int maxRetries = 5;
        private long initialBackoffMs = 1000;
        /** SHA-256 hashes of API keys that should never receive webhook deliveries (e.g. internal simulators). */
        private java.util.List<String> simulatorKeyHashes = new java.util.ArrayList<>();
    }
    @Data public static class Snapshot {
        private int intervalSeconds = 5;
    }
}
