package com.moodride.routeapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moodride.cache")
public class ScenicCacheConfiguration {

    public static final String DEFAULT_SCORING_VERSION = "3.7-bridge-coastal-calibration";

    private String scenicScoringVersion = DEFAULT_SCORING_VERSION;

    public String getScenicScoringVersion() {
        return scenicScoringVersion;
    }

    public void setScenicScoringVersion(String scenicScoringVersion) {
        this.scenicScoringVersion = requiredIdentity(
            scenicScoringVersion,
            "moodride.cache.scenic-scoring-version"
        );
    }

    private static String requiredIdentity(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.strip();
    }
}
