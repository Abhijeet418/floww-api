package com.floww.exchange.service;

import com.floww.exchange.model.entity.RegisteredApp;
import com.floww.exchange.model.enums.AppStatus;
import com.floww.exchange.repository.RegisteredAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hydrates the Redis cache with API keys from the PostgreSQL database on startup.
 * Ensures the "Cache-Only Auth" filter doesn't blindly reject valid requests 
 * if the Redis container was flushed or restarted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheWarmer implements CommandLineRunner {

    private final RegisteredAppRepository appRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void run(String... args) {
        log.info("🔥 [BOOT] Starting Cache Warmer: Syncing ACTIVE apps from DB to Redis...");

        // Fetch all active apps (using the existing repository method)
        List<RegisteredApp> activeApps = appRepository.findByStatusOrderByCreatedAtDesc(AppStatus.ACTIVE);
        int count = 0;

        for (RegisteredApp app : activeApps) {
            if (app.getApiKeyHash() != null) {
                // 1. Sync App Status
                redisTemplate.opsForValue().set("app:status:" + app.getAppId(), app.getStatus().name());
                
                // 2. Sync API Key Hash
                redisTemplate.opsForValue().set("apikey:" + app.getApiKeyHash(), app.getAppId().toString());
                
                count++;
            }
        }

        log.info("✅ [BOOT] Cache Warmer finished! Hydrated Redis with {} active API keys.", count);
    }
}
