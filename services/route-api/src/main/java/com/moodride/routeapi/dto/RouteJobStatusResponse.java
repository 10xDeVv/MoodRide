package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteJobStatusResponse(
        UUID jobId,
        String status,
        UUID routeId,
        String routeUrl,
        List<RouteOptionResponse> routeOptions,
        Instant primaryReadyAt,
        long stateRevision,
        long optionRevision,
        int optionCount,
        boolean optionsComplete,
        String reason,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant failedAt,
        Integer estimatedRemainingSeconds,
        int retryCount,
        int maxRetries,
        String routeMode,
        String failureCode,
        String userMessage,
        List<String> suggestedVibes,
        List<String> suggestedActions
) {
}

