package com.floww.exchange.service;

import com.floww.exchange.model.dto.*;
import com.floww.exchange.model.entity.RegisteredApp;
import com.floww.exchange.model.enums.AppStatus;
import com.floww.exchange.repository.RegisteredAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Handles app registration, status checks, and admin review.
 * Previously split between floww-registry and floww-exchange,
 * now consolidated here since the Next.js frontend proxies to this service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppRegistrationService {

    private final RegisteredAppRepository repository;
    private final ApiKeyService apiKeyService;
    private final StringRedisTemplate redisTemplate;

    /* ── Registration ─────────────────────────────────────── */

    @Transactional
    public RegisterAppResponse register(RegisterAppRequest request) {
        if (repository.existsByAppName(request.getName().trim())) {
            throw new IllegalArgumentException("An application with this name already exists");
        }

        String rawKey = apiKeyService.generateKey();
        String keyHash = apiKeyService.hashKey(rawKey);

        String webhookUrl = request.getWebhookUrl() != null ? request.getWebhookUrl().trim() : null;
        if (webhookUrl != null) validateWebhookUrl(webhookUrl);

        RegisteredApp app = RegisteredApp.builder()
                .appName(request.getName().trim())
                .contactEmail(request.getContactEmail().trim())
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .webhookUrl(webhookUrl)
                .apiKeyHash(keyHash)
                .status(AppStatus.PENDING_REVIEW)
                .rateLimit(50)
                .build();

        RegisteredApp saved = repository.save(app);
        syncToRedis(saved);

        log.info("Registered new app: {} ({})", saved.getAppName(), saved.getAppId());

        return RegisterAppResponse.builder()
                .id(saved.getAppId())
                .name(saved.getAppName())
                .contactEmail(saved.getContactEmail())
                .status(saved.getStatus().name())
                .apiKey(rawKey)
                .createdAt(saved.getCreatedAt())
                .build();
    }

    /* ── Status check by API key ─────────────────────────── */

    @Transactional(readOnly = true)
    public AppSummary checkStatus(String rawApiKey) {
        String hash = apiKeyService.hashKey(rawApiKey.trim());
        RegisteredApp app = repository.findByApiKeyHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("No application found for this API key"));
        return toSummary(app);
    }

    /* ── Admin: list ──────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<AppSummary> listAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppSummary> listByStatus(AppStatus status) {
        return repository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toSummary)
                .toList();
    }

    /* ── Admin: review ────────────────────────────────────── */

    @Transactional
    public AppSummary review(UUID appId, ReviewAppRequest request) {
        RegisteredApp app = repository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        if (request.getStatus() == AppStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Cannot set status back to PENDING_REVIEW");
        }

        app.setStatus(request.getStatus());
        if (request.getRateLimit() != null) {
            app.setRateLimit(request.getRateLimit());
        }
        if (request.getAdminNotes() != null) {
            app.setAdminNotes(request.getAdminNotes().isBlank() ? null : request.getAdminNotes());
        }

        RegisteredApp saved = repository.save(app);
        syncToRedis(saved);

        log.info("Reviewed app {} → {}", saved.getAppName(), saved.getStatus());
        return toSummary(saved);
    }

    /* ── Admin: regenerate key ────────────────────────────── */

    @Transactional
    public RegisterAppResponse regenerateKey(UUID appId) {
        RegisteredApp app = repository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        // Revoke old key from Redis
        if (app.getApiKeyHash() != null) {
            redisTemplate.delete("apikey:" + app.getApiKeyHash());
        }

        String rawKey = apiKeyService.generateKey();
        String keyHash = apiKeyService.hashKey(rawKey);
        app.setApiKeyHash(keyHash);

        RegisteredApp saved = repository.save(app);
        syncToRedis(saved);

        log.info("Regenerated API key for app: {}", saved.getAppName());

        return RegisterAppResponse.builder()
                .id(saved.getAppId())
                .name(saved.getAppName())
                .contactEmail(saved.getContactEmail())
                .status(saved.getStatus().name())
                .apiKey(rawKey)
                .createdAt(saved.getCreatedAt())
                .build();
    }

    /* ── Helpers ──────────────────────────────────────────── */

    private void syncToRedis(RegisteredApp app) {
        redisTemplate.opsForValue().set("app:status:" + app.getAppId(), app.getStatus().name());
        if (app.getApiKeyHash() != null && app.getStatus() == AppStatus.ACTIVE) {
            redisTemplate.opsForValue().set("apikey:" + app.getApiKeyHash(), app.getAppId().toString());
        }
        // If suspended/rejected, remove API key mapping
        if (app.getApiKeyHash() != null && app.getStatus() != AppStatus.ACTIVE) {
            redisTemplate.delete("apikey:" + app.getApiKeyHash());
        }
    }

    private AppSummary toSummary(RegisteredApp app) {
        return AppSummary.builder()
                .id(app.getAppId())
                .name(app.getAppName())
                .contactEmail(app.getContactEmail())
                .description(app.getDescription())
                .webhookUrl(app.getWebhookUrl())
                .status(app.getStatus().name())
                .rateLimit(app.getRateLimit())
                .adminNotes(app.getAdminNotes())
                .createdAt(app.getCreatedAt())
                .updatedAt(app.getUpdatedAt())
                .build();
    }

    /**
     * Rejects webhook URLs that could be used for SSRF:
     *  - Must be http or https scheme
     *  - Host must not resolve to loopback, link-local, or private (RFC-1918) addresses
     */
    private void validateWebhookUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid webhook URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Webhook URL must use http or https scheme");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must contain a valid host");
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("Webhook URL must not point to an internal address");
            }
        } catch (java.net.UnknownHostException e) {
            // DNS failure at registration time — reject rather than silently accept
            throw new IllegalArgumentException("Webhook URL host could not be resolved: " + host);
        }
    }
}
