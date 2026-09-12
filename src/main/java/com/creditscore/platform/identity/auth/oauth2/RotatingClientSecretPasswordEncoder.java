package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Pattern;

/**
 * Lets {@code RegisteredClient}'s single {@code clientSecret} string carry two valid
 * secrets during a rotation's grace period ({@code Consumer.getEffectiveOauthClientSecret}
 * builds that string as {@code primary}{@link #DELIMITER}{@code previous}). {@code matches}
 * tries each delimited candidate against the delegate encoder.
 *
 * <p>{@code upgradeEncoding} always returns {@code false} — this is load-bearing, not
 * an oversight. Spring Authorization Server's {@code ClientSecretAuthenticationProvider}
 * calls {@code upgradeEncoding(registeredClient.getClientSecret())} after a successful
 * match and, if it returns {@code true}, re-encodes the presented secret alone and
 * persists it via {@code RegisteredClientRepository.save(...)} as the new (single)
 * client secret — silently collapsing a two-secret composite string down to whichever
 * secret was just presented, permanently destroying the other one before its intended
 * grace-period expiry. Verified against the actual 1.3.3 bytecode, not assumed.
 */
public class RotatingClientSecretPasswordEncoder implements PasswordEncoder {

    /**
     * Never appears inside a {@code DelegatingPasswordEncoder}-produced hash (the only
     * kind this app ever stores) — that format uses only {@code {id}} prefixes and
     * base64-alphabet/{@code $}-delimited encoded output. The single source of truth
     * for this contract; {@code Consumer.getEffectiveOauthClientSecret} references this
     * constant rather than redeclaring the literal.
     */
    public static final String DELIMITER = "|";

    private static final Pattern SPLIT_PATTERN = Pattern.compile(Pattern.quote(DELIMITER));

    private final PasswordEncoder delegate;

    public RotatingClientSecretPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        for (String candidate : SPLIT_PATTERN.split(encodedPassword)) {
            if (delegate.matches(rawPassword, candidate)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }
}
