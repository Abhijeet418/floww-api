package com.floww.exchange.service;

import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final ExchangeProperties props;

    /**
     * Two-tier order rate check:
     *  1. Burst cap  — max orderBurstCapacity (50) orders in any single second.
     *  2. Rolling avg — max ordersPerSecond (20) × orderBurstWindowSeconds (10)
     *                   = 200 orders per 10-second window.
     */
    public void checkOrderRate(UUID appId) {
        // — Burst: per-second bucket —
        long epoch = System.currentTimeMillis() / 1000;
        String burstKey = "ratelimit:orders:burst:" + appId + ":" + epoch;
        Long burstCount = redisTemplate.opsForValue().increment(burstKey);
        if (burstCount != null && burstCount == 1) redisTemplate.expire(burstKey, Duration.ofSeconds(2));
        if (burstCount != null && burstCount > props.getRateLimit().getOrderBurstCapacity()) {
            throw new RateLimitExceededException(
                "Burst limit exceeded: max " + props.getRateLimit().getOrderBurstCapacity() + " orders/sec");
        }

        // — Rolling average: fixed 10-second window bucket —
        long epoch10 = System.currentTimeMillis() / (props.getRateLimit().getOrderBurstWindowSeconds() * 1000L);
        String rollingKey = "ratelimit:orders:rolling:" + appId + ":" + epoch10;
        Long rollingCount = redisTemplate.opsForValue().increment(rollingKey);
        if (rollingCount != null && rollingCount == 1) {
            redisTemplate.expire(rollingKey, Duration.ofSeconds(props.getRateLimit().getOrderBurstWindowSeconds() * 2L));
        }
        int maxRolling = props.getRateLimit().getOrdersPerSecond() * props.getRateLimit().getOrderBurstWindowSeconds();
        if (rollingCount != null && rollingCount > maxRolling) {
            throw new RateLimitExceededException(
                "Rolling average limit exceeded: max " + props.getRateLimit().getOrdersPerSecond() + " orders/sec average");
        }
    }

    /**
     * Global per-app rate cap: max globalRequestsPerSecond (100) across all endpoints.
     * Called by ApiKeyFilter after authentication.
     */
    public void checkGlobalRate(UUID appId) {
        long epoch = System.currentTimeMillis() / 1000;
        String key = "ratelimit:global:" + appId + ":" + epoch;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) redisTemplate.expire(key, Duration.ofSeconds(2));
        if (count != null && count > props.getRateLimit().getGlobalRequestsPerSecond()) {
            throw new RateLimitExceededException(
                "Global rate limit exceeded: max " + props.getRateLimit().getGlobalRequestsPerSecond() + " req/sec per app");
        }
    }

    /**
     * Anti-DDoS guard for POST /api/apps/register.
     * Allows at most registrationsPerIpPerDay (1) registration attempt per IP per calendar day.
     */
    public void checkRegistrationRateByIp(String ip) {
        long epochDay = System.currentTimeMillis() / 86_400_000L;
        String key = "ratelimit:register:ip:" + ip + ":" + epochDay;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) redisTemplate.expire(key, Duration.ofDays(2));
        if (count != null && count > props.getRateLimit().getRegistrationsPerIpPerDay()) {
            throw new RateLimitExceededException(
                "Registration rate limit exceeded: max " + props.getRateLimit().getRegistrationsPerIpPerDay()
                + " registration(s) per IP per day");
        }
    }

    public void checkOpenOrderLimit(UUID appId) {
        String key = "ratelimit:open:" + appId;
        String val = redisTemplate.opsForValue().get(key);
        long count = val != null ? Long.parseLong(val) : 0;
        if (count >= props.getRateLimit().getMaxOpenOrders()) {
            throw new RateLimitExceededException("Max open orders exceeded: " + props.getRateLimit().getMaxOpenOrders());
        }
    }

    public void incrementOpenOrders(UUID appId) {
        redisTemplate.opsForValue().increment("ratelimit:open:" + appId);
    }

    public void decrementOpenOrders(UUID appId) {
        String key = "ratelimit:open:" + appId;
        Long val = redisTemplate.opsForValue().decrement(key);
        if (val != null && val < 0) redisTemplate.opsForValue().set(key, "0");
    }

    public void checkCancelRate(UUID appId) {
        long epochMinute = System.currentTimeMillis() / 60000;
        String key = "ratelimit:cancel:" + appId + ":" + epochMinute;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) redisTemplate.expire(key, Duration.ofSeconds(120));
        if (count != null && count > props.getRateLimit().getCancellationsPerMinute()) {
            throw new RateLimitExceededException("Cancellation rate limit exceeded");
        }
    }
}
