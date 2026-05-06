package com.moodride.cdcservice.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CdcControlService {

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastProcessedAt = new AtomicReference<>();

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void markProcessed() {
        lastProcessedAt.set(Instant.now());
    }

    public Instant getLastProcessedAt() {
        return lastProcessedAt.get();
    }
}

