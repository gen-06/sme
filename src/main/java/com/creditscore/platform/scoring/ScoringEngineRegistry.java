package com.creditscore.platform.scoring;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ScoringEngineRegistry {

    private final Map<String, ScoringEngine> enginesByVersion;

    public ScoringEngineRegistry(List<ScoringEngine> engines) {
        this.enginesByVersion = engines.stream()
                .collect(Collectors.toMap(ScoringEngine::getModelVersion, Function.identity()));
    }

    public ScoringEngine resolve(String modelVersion) {
        ScoringEngine engine = enginesByVersion.get(modelVersion);
        if (engine == null) {
            throw new NoSuchElementException("No ScoringEngine registered for version: " + modelVersion);
        }
        return engine;
    }
}
