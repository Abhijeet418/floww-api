package com.floww.exchange.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AppSummary {
    private UUID id;
    private String name;
    private String contactEmail;
    private String description;
    private String webhookUrl;
    private String status;
    private int rateLimit;
    private String adminNotes;
    private Instant createdAt;
    private Instant updatedAt;
}
