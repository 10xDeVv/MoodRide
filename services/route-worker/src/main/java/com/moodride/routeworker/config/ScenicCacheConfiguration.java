package com.moodride.routeworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moodride.cache")
public class ScenicCacheConfiguration {

    public static final String DEFAULT_SCENIC_SCORING_VERSION = "3.7-bridge-coastal-calibration";
    public static final String DEFAULT_ROAD_DATASET_REVISION = "local-dev";
    public static final String DEFAULT_ROAD_ANCHOR_CACHE_SCHEMA = "v1";

    private String scenicScoringVersion = DEFAULT_SCENIC_SCORING_VERSION;
    private String roadDatasetRevision = DEFAULT_ROAD_DATASET_REVISION;
    private String roadAnchorCacheSchema = DEFAULT_ROAD_ANCHOR_CACHE_SCHEMA;

    public String getScenicScoringVersion() {
        return scenicScoringVersion;
    }

    public void setScenicScoringVersion(String scenicScoringVersion) {
        this.scenicScoringVersion = requiredIdentity(
            scenicScoringVersion,
            "moodride.cache.scenic-scoring-version"
        );
    }

    public String getRoadDatasetRevision() {
        return roadDatasetRevision;
    }

    public void setRoadDatasetRevision(String roadDatasetRevision) {
        this.roadDatasetRevision = requiredIdentity(
            roadDatasetRevision,
            "moodride.cache.road-dataset-revision"
        );
    }

    public String getRoadAnchorCacheSchema() {
        return roadAnchorCacheSchema;
    }

    public void setRoadAnchorCacheSchema(String roadAnchorCacheSchema) {
        this.roadAnchorCacheSchema = requiredIdentity(
            roadAnchorCacheSchema,
            "moodride.cache.road-anchor-cache-schema"
        );
    }

    private static String requiredIdentity(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        String identity = value.strip();
        if (!identity.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                propertyName + " must contain only letters, digits, '.', '_', or '-'"
            );
        }
        return identity;
    }
}
