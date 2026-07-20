package com.moodride.routeworker.cache;

import java.util.Locale;
import java.util.UUID;

public final class CacheKeySchema {

    private CacheKeySchema() {
    }

    public static String routeResult(UUID routeId) {
        return "route:result:" + routeId;
    }

    public static String scenicTile(String scoringVersion, String h3Index) {
        return "scenic:tile:" + scoringVersion + ":" + h3Index;
    }

    public static String segmentMeta(String h3Index) {
        return "segment:meta:" + h3Index;
    }

    public static String roadAnchor(
        String scenicScoringVersion,
        String roadDatasetRevision,
        String anchorCacheSchema,
        String h3Index
    ) {
        return "segment:anchor:scenic=" + scenicScoringVersion
            + ":road=" + roadDatasetRevision
            + ":schema=" + anchorCacheSchema
            + ":h3=" + h3Index;
    }

    public static String regionalPopularity(String regionKey) {
        return "popular:routes:" + regionKey.toLowerCase(Locale.ROOT);
    }
}

