package com.floww.exchange.repository;

import com.floww.exchange.model.entity.RegisteredApp;
import com.floww.exchange.model.enums.AppStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegisteredAppRepository extends JpaRepository<RegisteredApp, UUID> {
    List<RegisteredApp> findByStatus(AppStatus status);
    List<RegisteredApp> findByStatusOrderByCreatedAtDesc(AppStatus status);
    List<RegisteredApp> findAllByOrderByCreatedAtDesc();
    Optional<RegisteredApp> findByApiKeyHash(String apiKeyHash);
    boolean existsByAppName(String appName);
}
