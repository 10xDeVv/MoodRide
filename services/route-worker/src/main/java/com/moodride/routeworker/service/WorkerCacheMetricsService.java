package com.moodride.routeworker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class WorkerCacheMetricsService {

    private final MeterRegistry meterRegistry;

    public WorkerCacheMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void invalidate(String layer, int count) {
        Counter.builder("moodride.cache.invalidate.total")
                .tag("service", "route-worker")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment(count);
    }

    public void warmSuccess(String layer) {
        Counter.builder("moodride.cache.warm.total")
                .tag("service", "route-worker")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment();
    }
}

