package com.creditscore.platform.normalization;

import com.creditscore.platform.ingestion.AdapterType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class NormalizerRegistry {

    private final Map<AdapterType, TransactionNormalizer> normalizersByType;

    public NormalizerRegistry(List<TransactionNormalizer> normalizers) {
        this.normalizersByType = normalizers.stream()
                .collect(Collectors.toMap(TransactionNormalizer::supports, Function.identity()));
    }

    public TransactionNormalizer resolve(AdapterType type) {
        TransactionNormalizer normalizer = normalizersByType.get(type);
        if (normalizer == null) {
            throw new NoSuchElementException("No TransactionNormalizer registered for type: " + type);
        }
        return normalizer;
    }
}
