package com.moodride.routeapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AnalyticsEventRequest(
    @NotBlank @Size(max = 80)
    String anonymousSessionId,

    @NotBlank @Size(max = 80)
    String eventName,

    UUID jobId,
    UUID routeId,

    @Size(max = 40)
    String routeProfile,

    @Size(max = 16)
    String routeMode,

    List<@Size(max = 40) String> vibes,

    @Min(1)
    Integer timeBudgetMinutes,

    @Min(0)
    Integer routeCount,

    @Size(max = 40)
    String status,

    @Min(0)
    Long durationMs,

    Double scenicScore,

    Map<String, Object> metadata
) {
}
