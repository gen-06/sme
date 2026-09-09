package com.creditscore.platform.common;

import java.util.Map;

/**
 * Resolves a market's settlement currency from its ISO 3166-1 alpha-2 country code.
 * This is the single place that knows the country-to-currency mapping, which is what
 * keeps currency handling out of the normalization/scoring layers and makes adding a
 * new market a config change here, not a code change downstream.
 */
public final class CountryCurrencyResolver {

    private static final Map<String, String> COUNTRY_TO_CURRENCY = Map.of(
            "KE", "KES",
            "NG", "NGN",
            "GH", "GHS",
            "UG", "UGX",
            "TZ", "TZS",
            "ZA", "ZAR",
            "US", "USD"
    );

    private CountryCurrencyResolver() {
    }

    public static String resolve(String isoCountryCode) {
        String currency = COUNTRY_TO_CURRENCY.get(isoCountryCode);
        if (currency == null) {
            throw new IllegalArgumentException("No currency mapping configured for country: " + isoCountryCode);
        }
        return currency;
    }
}
