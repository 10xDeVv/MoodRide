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

    @Size(max = 120)
    String anonymousClientId,

    @NotBlank @Size(max = 80)
    String eventName,

    UUID jobId,
    UUID routeId,

    @Size(max = 40)
    String routeProfile,

    @Size(max = 16)
    String routeMode,

    @Size(max = 3)
    List<@NotBlank @Size(max = 40) String> vibes,

    @Min(1)
    Integer timeBudgetMinutes,

    @Size(max = 40)
    String regionKey,

    @Min(0)
    Integer routeCount,

    @Size(max = 40)
    String status,

    @Min(0)
    Long durationMs,

    Double scenicScore,

    @Size(max = 20)
    Map<String, Object> metadata
) {
}
