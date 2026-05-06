package com.moodride.routeworker.cache;

import java.time.Duration;

public final class CachePolicy {

    public static final Duration ROUTE_RESULTS_TTL = Duration.ofHours(24);
    public static final Duration SCENIC_TILES_TTL = Duration.ofDays(8);
    public static final Duration ROAD_SEGMENTS_TTL = Duration.ofDays(7);
    public static final Duration REGIONAL_POPULARITY_TTL = Duration.ofHours(24);

    private CachePolicy() {
    }
}

