package com.floww.exchange.service;

import com.floww.exchange.config.ExchangeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates API keys and hashes them for storage.
 *
 * Key format:  flw_<base64url-random-bytes>
 * Storage:     SHA-256 hex hash of the full key
 *
 * The raw key is returned ONCE to the user at registration.
 * Only the hash is stored — if the key is lost, a new one must be generated.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ExchangeProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateKey() {
        byte[] bytes = new byte[properties.getApiKeyLength()];
        secureRandom.nextBytes(bytes);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return properties.getApiKeyPrefix() + random;
    }

    public String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
