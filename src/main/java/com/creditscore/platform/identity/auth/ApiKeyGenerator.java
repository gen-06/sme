package com.creditscore.platform.identity.auth;

import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "csk_"; // credit-score key

    private ApiKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String displayPrefix(String rawKey) {
        return rawKey.substring(0, Math.min(12, rawKey.length()));
    }
}
