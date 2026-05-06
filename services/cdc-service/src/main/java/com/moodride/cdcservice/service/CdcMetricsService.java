package com.moodride.cdcservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class CdcMetricsService {

    private final MeterRegistry meterRegistry;
    private final AtomicLong consumerLagMs;

    public CdcMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.consumerLagMs = new AtomicLong(0);
        Gauge.builder("moodride.cdc.consumer.lag", consumerLagMs, AtomicLong::get)
                .description("CDC consumer lag in milliseconds based on Debezium source timestamp")
                .register(meterRegistry);
    }

    public void processed(String topic) {
        Counter.builder("moodride.cdc.events.processed.total")
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();
    }

    public void invalidated(String layer, int count) {
        Counter.builder("moodride.cdc.invalidation.total")
                .tag("layer", layer)
                .register(meterRegistry)
                .increment(count);
    }

    public void duplicate(String topic) {
        Counter.builder("moodride.cdc.events.duplicate.total")
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();
    }

    public void error(String topic) {
        Counter.builder("moodride.cdc.events.error.total")
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();
    }

    public void updateLag(long lagMs) {
        consumerLagMs.set(Math.max(0, lagMs));
    }
}

