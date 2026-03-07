package com.floww.exchange.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RegisterAppResponse {
    private UUID id;
    private String name;
    private String contactEmail;
    private String status;
    private String apiKey;   // Shown once, never stored
    private Instant createdAt;
}
