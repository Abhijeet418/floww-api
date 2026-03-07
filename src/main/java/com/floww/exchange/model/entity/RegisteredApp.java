package com.floww.exchange.model.entity;

import com.floww.exchange.model.enums.AppStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * RegisteredApp — read-only from Exchange's perspective.
 * The floww-registry service owns writes to this table.
 * Exchange only reads it at startup to warm the Redis cache.
 */
@Entity
@Table(name = "registered_app")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RegisteredApp {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID appId;

    @Column(name = "name", nullable = false, unique = true)
    private String appName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    @Column(name = "api_key_hash", unique = true)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AppStatus status;

    @Column(name = "rate_limit", nullable = false)
    private int rateLimit;

    @Column(name = "admin_notes", columnDefinition = "text")
    private String adminNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
