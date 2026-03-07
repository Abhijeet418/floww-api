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
import lombok.RequiredArgsConstructor;
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

/**
 * Webhook dispatch — consumes trades from the Disruptor and delivers
 * webhook notifications to registered apps asynchronously.
 */
@Component
@RequiredArgsConstructor
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
        // Deliver a tailored payload to each side — no counterparty identity exposed.
        dispatch(holder, holder.buyerAppId,  holder.buyOrderId,  "BUY");
        dispatch(holder, holder.sellerAppId, holder.sellOrderId, "SELL");
    }

    private void dispatch(TradeEventHolder holder, UUID appId, UUID ownOrderId, String side) {
        if (simulatorAppIds.contains(appId)) return;
        try {
            Optional<RegisteredApp> appOpt = appRepository.findById(appId);
            if (appOpt.isEmpty() || appOpt.get().getWebhookUrl() == null) return;

            RegisteredApp app = appOpt.get();

            WebhookTradePayload payload = WebhookTradePayload.builder()
                    .tradeId(holder.tradeId)
                    .ticker(holder.ticker)
                    .price(holder.price)
                    .qty(holder.qty)
                    .orderId(ownOrderId)
                    .side(side)
                    .tradedAt(holder.tradedAt)
                    .build();

            WebhookDelivery delivery = WebhookDelivery.builder()
                    .appId(appId).tradeId(holder.tradeId)
                    .payload(objectMapper.writeValueAsString(payload)).status("PENDING")
                    .attempts(0).nextRetry(Instant.now())
                    .build();
            deliveryRepository.save(delivery);

            attemptDelivery(delivery, app.getWebhookUrl());
        } catch (Exception e) {
            log.warn("Webhook setup failed for app {}: {}", appId, e.getMessage());
        }
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
}
