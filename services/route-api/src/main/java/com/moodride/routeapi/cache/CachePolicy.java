package com.moodride.routeapi.cache;

import java.time.Duration;
import java.util.Map;

public final class CachePolicy {

    public static final Duration ROUTE_DETAILS_V2_TTL = Duration.ofHours(24);
    public static final Duration SCENIC_TILES_TTL = Duration.ofDays(8);
    public static final Duration ROAD_SEGMENTS_TTL = Duration.ofDays(7);
    public static final Duration REGIONAL_POPULARITY_TTL = Duration.ofHours(24);

    private CachePolicy() {
    }

    public static Map<String, String> ttlSummary() {
        return Map.of(
                "routeDetailsV2Ttl", "24h",
                "scenicTilesTtl", "8d",
                "roadSegmentsTtl", "7d",
                "regionalPopularityTtl", "24h"
        );
    }
}

