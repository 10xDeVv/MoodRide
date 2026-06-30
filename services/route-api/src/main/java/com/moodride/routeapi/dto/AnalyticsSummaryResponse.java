package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.List;

public record AnalyticsSummaryResponse(
    Instant from,
    Instant to,
    long totalEvents,
    long generateClicks,
    long submittedRoutes,
    long completedRoutes,
    long failedRoutes,
    long vibeUnavailableRoutes,
    long startDriveClicks,
    long navigationOpens,
    long planNewRouteClicks,
    double routeSuccessRate,
    double averageGenerationMs,
    double averageRouteOptions,
    double averageScenicScore,
    List<AnalyticsCountResponse> topVibes,
    List<AnalyticsCountResponse> selectedProfiles,
    List<AnalyticsCountResponse> routeModes
) {
}
