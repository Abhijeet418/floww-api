package com.floww.exchange.service;

import com.floww.exchange.model.entity.RegisteredApp;
import com.floww.exchange.model.enums.AppStatus;
import com.floww.exchange.repository.RegisteredAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Syncs app status from PostgreSQL into Redis periodically.
 * The Registry service writes to the DB; this service reads.
 * The API key filter reads from Redis on every request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppStatusSyncService {

    private final RegisteredAppRepository appRepository;
    private final StringRedisTemplate redisTemplate;

    @PostConstruct
    public void initialSync() {
        syncAppStatuses();
    }

    /**
     * Every 5 seconds, re-sync all app statuses from DB to Redis.
     * This picks up new approvals/suspensions made by the Registry service.
     */
    @Scheduled(fixedRate = 5000)
    public void syncAppStatuses() {
        List<RegisteredApp> allApps = appRepository.findAll();
        for (RegisteredApp app : allApps) {
            redisTemplate.opsForValue().set("app:status:" + app.getAppId(), app.getStatus().name());

            // Also sync API key hash → appId mapping for the filter
            if (app.getApiKeyHash() != null && app.getStatus() == AppStatus.ACTIVE) {
                redisTemplate.opsForValue().set("apikey:" + app.getApiKeyHash(), app.getAppId().toString());
            }
        }
        log.debug("Synced {} app statuses to Redis", allApps.size());
    }
}
