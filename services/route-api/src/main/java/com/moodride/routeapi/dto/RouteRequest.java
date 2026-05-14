package com.moodride.routeapi.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RouteRequest(
    UUID userId,
    @JsonProperty("lat")
    @JsonAlias({"startLatitude"})
    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    Double lat,
    @JsonProperty("lng")
    @JsonAlias({"startLongitude"})
    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    Double lng,
    @Min(15)
    @Max(240)
    int timeBudgetMinutes,
    List<String> vibes,
    @JsonAlias({"vibe"})
    String vibe,
    Map<String, Object> preferenceVector,
    @JsonAlias({"mode", "travelMode"})
    String routeMode
) {
    public RouteRequest(
        UUID userId,
        Double lat,
        Double lng,
        int timeBudgetMinutes,
        List<String> vibes,
        String vibe,
        Map<String, Object> preferenceVector
    ) {
        this(userId, lat, lng, timeBudgetMinutes, vibes, vibe, preferenceVector, null);
    }

    public List<String> resolvedVibes() {
        if (vibes != null && !vibes.isEmpty()) {
            return vibes;
        }
        if (vibe != null && !vibe.isBlank()) {
            return List.of(vibe);
        }
        return List.of("countryside");
    }
}
