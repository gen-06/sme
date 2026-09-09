package com.creditscore.platform.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic (unsalted) SHA-256 hashing for API keys. Deterministic on purpose:
 * lookup is by hash equality (an indexed column), which a per-key-salted hash (like
 * BCrypt) can't support without scanning every consumer on every request. The key
 * itself is high-entropy (see {@link ApiKeyGenerator}), so this is the standard
 * pattern for API-key auth (Stripe, GitHub tokens, etc.) rather than a password.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
