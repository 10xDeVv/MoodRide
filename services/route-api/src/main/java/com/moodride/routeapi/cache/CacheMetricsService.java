package com.moodride.routeapi.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class CacheMetricsService {

    private final MeterRegistry meterRegistry;

    public CacheMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void warmSuccess(String layer, int count) {
        Counter.builder("moodride.cache.warm.total")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment(count);
    }

    public void invalidate(String layer, int count) {
        Counter.builder("moodride.cache.invalidate.total")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment(count);
    }

    public void warmFailure(String layer) {
        Counter.builder("moodride.cache.warm.errors")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment();
    }
}

