package com.moodride.routeapi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RouteRatingRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        List<String> feedbackTags
) {
    public RouteRatingRequest(Integer rating) {
        this(rating, List.of());
    }
}
