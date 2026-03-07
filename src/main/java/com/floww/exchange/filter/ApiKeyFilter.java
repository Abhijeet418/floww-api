package com.floww.exchange.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.exchange.exception.RateLimitExceededException;
import com.floww.exchange.model.dto.ApiResponse;
import com.floww.exchange.service.RateLimitService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * API Key Authentication Filter.
 *
 * Extracts X-API-KEY header, hashes it with SHA-256, looks up the appId in Redis.
 * If found and status == APPROVED, sets the appId as a request attribute.
 *
 * Public endpoints (tickers, market-data, actuator, admin) bypass this filter.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class ApiKeyFilter implements Filter {

    public static final String APP_ID_ATTRIBUTE = "floww.appId";

    private static final String[] PUBLIC_PREFIXES = {
            "/tickers", "/market-data", "/market-status", "/actuator", "/admin", "/api/apps"
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // Skip auth for public endpoints
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                chain.doFilter(req, res);
                return;
            }
        }

        String apiKey = request.getHeader("X-API-KEY");
        if (apiKey == null || apiKey.isBlank()) {
            writeError(response, 401, "Missing X-API-KEY header", "UNAUTHORIZED");
            return;
        }

        // SHA-256 hash for Redis lookup (fast, deterministic)
        String keyHash = sha256(apiKey);
        String appIdStr = redisTemplate.opsForValue().get("apikey:" + keyHash);

        if (appIdStr == null) {
            writeError(response, 401, "Invalid API key", "UNAUTHORIZED");
            return;
        }

        // Check app status in Redis
        String status = redisTemplate.opsForValue().get("app:status:" + appIdStr);
        if (!"ACTIVE".equals(status)) {
            writeError(response, 403, "App not active (status: " + status + ")", "FORBIDDEN");
            return;
        }

        UUID appId = UUID.fromString(appIdStr);
        try {
            rateLimitService.checkGlobalRate(appId);
        } catch (RateLimitExceededException e) {
            writeError(response, 429, e.getMessage(), "RATE_LIMITED");
            return;
        }
        request.setAttribute(APP_ID_ATTRIBUTE, appId);
        chain.doFilter(req, res);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void writeError(HttpServletResponse response, int status, String msg, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(msg, code)));
    }
}
