package com.creditscore.platform.identity.auth.oauth2;

import java.security.SecureRandom;
import java.util.Base64;

public final class OAuth2ClientCredentialGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth2ClientCredentialGenerator() {
    }

    public static String generateClientId() {
        return "client_" + randomToken();
    }

    public static String generateClientSecret() {
        return "secret_" + randomToken();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
