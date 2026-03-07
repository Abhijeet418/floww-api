package com.floww.exchange.controller;

import com.floww.exchange.model.dto.*;
import com.floww.exchange.model.enums.AppStatus;
import com.floww.exchange.service.AppRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin endpoints for managing registered applications.
 * Protected by AdminTokenFilter (X-Admin-Token header).
 */
@RestController
@RequestMapping("/admin/apps")
@RequiredArgsConstructor
public class AppAdminController {

    private final AppRegistrationService registrationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AppSummary>>> listApps(
            @RequestParam(required = false) String status) {
        List<AppSummary> apps;
        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            apps = registrationService.listByStatus(AppStatus.valueOf(status));
        } else {
            apps = registrationService.listAll();
        }
        return ResponseEntity.ok(ApiResponse.ok(apps));
    }

    @PatchMapping("/{appId}/review")
    public ResponseEntity<ApiResponse<AppSummary>> reviewApp(
            @PathVariable UUID appId,
            @Valid @RequestBody ReviewAppRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(registrationService.review(appId, request)));
    }

    @PostMapping("/{appId}/regenerate-key")
    public ResponseEntity<ApiResponse<RegisterAppResponse>> regenerateKey(
            @PathVariable UUID appId) {
        return ResponseEntity.ok(ApiResponse.ok(registrationService.regenerateKey(appId)));
    }
}
