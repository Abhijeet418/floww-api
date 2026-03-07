package com.floww.exchange.controller;

import com.floww.exchange.model.dto.*;
import com.floww.exchange.service.AppRegistrationService;
import com.floww.exchange.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public-facing registration endpoints.
 * Called by the Next.js registry frontend (no DB on frontend side).
 */
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class AppRegistrationController {

    private final AppRegistrationService registrationService;
    private final RateLimitService rateLimitService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterAppResponse>> register(
            @Valid @RequestBody RegisterAppRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkRegistrationRateByIp(extractClientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(registrationService.register(request)));
    }

    /**
     * Extracts the real client IP, honouring X-Forwarded-For set by a trusted upstream proxy.
     * The first entry in XFF is the originating client; we fall back to the direct remote address.
     */
    private String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<AppSummary>> checkStatus(
            @RequestBody AppStatusCheckRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(registrationService.checkStatus(request.getApiKey())));
    }
}
