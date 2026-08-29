package com.referralhub.ingestion.adapter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Looks up the adapter for a board's {@code source} column. */
@Component
public class AdapterRegistry {

    private final Map<String, SourceAdapter> bySource;

    public AdapterRegistry(List<SourceAdapter> adapters) {
        this.bySource = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(SourceAdapter::source, Function.identity()));
    }

    public SourceAdapter require(String source) {
        SourceAdapter adapter = bySource.get(source);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for source '" + source
                    + "'. Known sources: " + bySource.keySet());
        }
        return adapter;
    }

    public java.util.Set<String> knownSources() {
        return bySource.keySet();
    }
}
