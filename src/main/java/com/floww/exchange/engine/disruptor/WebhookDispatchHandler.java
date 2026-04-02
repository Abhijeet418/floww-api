package com.floww.exchange.engine.disruptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.model.dto.WebhookTradePayload;
import com.floww.exchange.model.entity.RegisteredApp;
import com.floww.exchange.model.entity.WebhookDelivery;
import com.floww.exchange.repository.RegisteredAppRepository;
import com.floww.exchange.repository.WebhookDeliveryRepository;
import com.lmax.disruptor.EventHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Webhook dispatch — consumes trades from the Disruptor and delivers
 * webhook notifications to registered apps asynchronously.
 */
@Component
@Slf4j
public class WebhookDispatchHandler implements EventHandler<TradeEventHolder> {

    private final RegisteredAppRepository appRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ExchangeProperties exchangeProperties;
    private final StringRedisTemplate redisTemplate;

    // Resolved at startup: appIds of simulator bots that must not receive webhooks.
    private final Set<UUID> simulatorAppIds = new HashSet<>();

    // Offload blocking DB work off the Disruptor thread to prevent backpressure
    // that cascades into blocking all Tomcat threads under high trade throughput.
    private final ExecutorService webhookExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookDispatchHandler(RegisteredAppRepository appRepository,
                                  WebhookDeliveryRepository deliveryRepository,
                                  WebClient webClient,
                                  ObjectMapper objectMapper,
                                  ExchangeProperties exchangeProperties,
                                  StringRedisTemplate redisTemplate) {
        this.appRepository = appRepository;
        this.deliveryRepository = deliveryRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.exchangeProperties = exchangeProperties;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void resolveSimulatorAppIds() {
        for (String keyHash : exchangeProperties.getWebhook().getSimulatorKeyHashes()) {
            String appId = redisTemplate.opsForValue().get("apikey:" + keyHash);
            if (appId != null) {
                simulatorAppIds.add(UUID.fromString(appId));
                log.info("Webhook blacklist: resolved simulator appId {}", appId);
            } else {
                log.warn("Webhook blacklist: no appId found in Redis for key hash {}", keyHash);
            }
        }
    }

    @Override
    public void onEvent(TradeEventHolder holder, long sequence, boolean endOfBatch) {
        // Skip cancellation-only events (no trade data)
        if (holder.tradeId == null) return;
        // Snapshot data from the ring buffer slot BEFORE returning —
        // the Disruptor will reuse this slot once all handlers return.
        var snapshot = new TradeSnapshot(
                holder.tradeId, holder.ticker, holder.price, holder.qty,
                holder.buyerAppId, holder.buyOrderId,
                holder.sellerAppId, holder.sellOrderId,
                holder.tradedAt);

        webhookExecutor.submit(() -> {
            dispatch(snapshot, snapshot.buyerAppId,  snapshot.buyOrderId,  "BUY");
            dispatch(snapshot, snapshot.sellerAppId, snapshot.sellOrderId, "SELL");
        });
    }

    private void dispatch(TradeSnapshot trade, UUID appId, UUID ownOrderId, String side) {
        if (simulatorAppIds.contains(appId)) return;
        try {
            Optional<RegisteredApp> appOpt = appRepository.findById(appId);
            if (appOpt.isEmpty() || appOpt.get().getWebhookUrl() == null) return;

            RegisteredApp app = appOpt.get();

            WebhookTradePayload payload = WebhookTradePayload.builder()
                    .tradeId(trade.tradeId)
                    .ticker(trade.ticker)
                    .price(trade.price)
                    .qty(trade.qty)
                    .orderId(ownOrderId)
                    .side(side)
                    .tradedAt(trade.tradedAt)
                    .build();

            WebhookDelivery delivery = WebhookDelivery.builder()
                    .appId(appId).tradeId(trade.tradeId)
                    .payload(objectMapper.writeValueAsString(payload)).status("PENDING")
                    .attempts(0).nextRetry(Instant.now())
                    .build();
            deliveryRepository.save(delivery);

            attemptDelivery(delivery, app.getWebhookUrl());
        } catch (Exception e) {
            log.warn("Webhook setup failed for app {}: {}", appId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        webhookExecutor.shutdown();
        try { webhookExecutor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    @Scheduled(fixedRate = 5000)
    public void retryPending() {
        List<WebhookDelivery> pending = deliveryRepository
                .findByStatusAndNextRetryBefore("PENDING", Instant.now());
        for (WebhookDelivery d : pending) {
            Optional<RegisteredApp> app = appRepository.findById(d.getAppId());
            if (app.isEmpty() || app.get().getWebhookUrl() == null) continue;
            attemptDelivery(d, app.get().getWebhookUrl());
        }
    }

    private void attemptDelivery(WebhookDelivery delivery, String url) {
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttempt(Instant.now());

        webClient.post().uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(delivery.getPayload())
                .retrieve().toBodilessEntity()
                .subscribe(
                        response -> {
                            delivery.setStatus("DELIVERED");
                            deliveryRepository.save(delivery);
                        },
                        error -> {
                            if (delivery.getAttempts() >= 5) {
                                delivery.setStatus("FAILED");
                            } else {
                                long backoff = (long) Math.pow(2, delivery.getAttempts()) * 1000;
                                delivery.setNextRetry(Instant.now().plusMillis(backoff));
                            }
                            deliveryRepository.save(delivery);
                        }
                );
    }

    private record TradeSnapshot(
            UUID tradeId, String ticker, long price, long qty,
            UUID buyerAppId, UUID buyOrderId,
            UUID sellerAppId, UUID sellOrderId,
            Instant tradedAt) {}
}
