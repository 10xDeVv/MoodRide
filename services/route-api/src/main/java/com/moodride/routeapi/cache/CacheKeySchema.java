package com.moodride.routeapi.cache;

import java.util.Locale;
import java.util.UUID;

public final class CacheKeySchema {

    private CacheKeySchema() {
    }

    public static String routeResult(UUID routeId) {
        return "route:result:" + routeId;
    }

    public static String scenicTile(String h3Index) {
        return "scenic:tile:" + h3Index;
    }

    public static String segmentMeta(String h3Index) {
        return "segment:meta:" + h3Index;
    }

    public static String regionalPopularity(String regionKey) {
        return "popular:routes:" + regionKey.toLowerCase(Locale.ROOT);
    }
}

