package com.floww.exchange.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.model.dto.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Admin Token Authentication Filter.
 *
 * Protects all /admin/* endpoints with a shared admin token.
 * The token is passed via the X-Admin-Token header.
 */
@Component
@Order(0) // Run before ApiKeyFilter
@RequiredArgsConstructor
@Slf4j
public class AdminTokenFilter implements Filter {

    private final ExchangeProperties exchangeProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // Only protect /admin/* paths
        if (!path.startsWith("/admin")) {
            chain.doFilter(req, res);
            return;
        }

        String token = request.getHeader("X-Admin-Token");
        String expected = exchangeProperties.getAdminToken();

        if (expected == null || expected.isBlank()) {
            log.warn("Admin token not configured — denying request to {}", path);
            writeError(response, 500, "Admin token not configured");
            return;
        }

        if (token == null || !token.equals(expected)) {
            writeError(response, 401, "Invalid or missing admin token");
            return;
        }

        chain.doFilter(req, res);
    }

    private void writeError(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(msg, "UNAUTHORIZED")));
    }
}
