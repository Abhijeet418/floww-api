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
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Per-IP rate limiter for all public (unauthenticated) endpoints.
 * Runs before ApiKeyFilter (Order -1) so even public paths are protected from DDoS.
 *
 * Authenticated endpoints are already rate-limited per API key in ApiKeyFilter,
 * so we skip paths that require auth (/orders).
 */
@Component
@Order(-1)
@RequiredArgsConstructor
@Slf4j
public class PublicRateLimitFilter implements Filter {

    private static final String[] PUBLIC_PREFIXES = {
            "/tickers", "/market-data", "/market-status", "/api/apps", "/actuator"
    };

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        boolean isPublic = false;
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                isPublic = true;
                break;
            }
        }

        if (isPublic) {
            String ip = extractClientIp(request);
            try {
                rateLimitService.checkPublicRate(ip);
            } catch (RateLimitExceededException e) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.error(e.getMessage(), "RATE_LIMITED")));
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
