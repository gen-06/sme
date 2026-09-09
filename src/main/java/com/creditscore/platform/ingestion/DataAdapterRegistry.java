package com.creditscore.platform.ingestion;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DataAdapterRegistry {

    private final Map<AdapterType, DataAdapter> adaptersByType;

    public DataAdapterRegistry(List<DataAdapter> adapters) {
        this.adaptersByType = adapters.stream()
                .collect(Collectors.toMap(DataAdapter::getType, Function.identity()));
    }

    public DataAdapter resolve(AdapterType type) {
        DataAdapter adapter = adaptersByType.get(type);
        if (adapter == null) {
            throw new NoSuchElementException("No DataAdapter registered for type: " + type);
        }
        return adapter;
    }
}
