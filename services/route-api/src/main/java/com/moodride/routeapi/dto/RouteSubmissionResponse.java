package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.UUID;

public record RouteSubmissionResponse(
        UUID jobId,
        String status,
        int estimatedCompletionSeconds,
        String statusUrl,
        String wsChannel,
        Instant queuedAt,
        int retryCount,
        int maxRetries
) {
}

