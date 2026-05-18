package com.moodride.routeapi.dto;

import java.util.List;
import java.util.Map;

public record RouteOptionExplanationResponse(
        Map<String, Double> componentAverages,
        Map<String, Double> baselineAverages,
        Map<String, Double> componentLifts,
        Map<String, Double> componentWeights,
        Map<String, Double> weightedContributions,
        List<String> leadingComponents,
        String summary,
        int sampleTileCount,
        int baselineTileCount
) {
}
