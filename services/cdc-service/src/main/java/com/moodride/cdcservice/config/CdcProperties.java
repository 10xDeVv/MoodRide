package com.moodride.cdcservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moodride.cdc")
public class CdcProperties {

    private Topics topics = new Topics();
    private int idempotencyTtlSeconds = 86400;

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public int getIdempotencyTtlSeconds() {
        return idempotencyTtlSeconds;
    }

    public void setIdempotencyTtlSeconds(int idempotencyTtlSeconds) {
        this.idempotencyTtlSeconds = idempotencyTtlSeconds;
    }

    public static class Topics {
        private String scenicTile = "moodride.cdc.scenic_score_tiles";
        private String roadSegment = "moodride.cdc.road_segments";
        private String recomputeRequest = "scenic.recompute.requests";
        private String forwardedTileUpdate = "scenic-tile-updates";

        public String getScenicTile() {
            return scenicTile;
        }

        public void setScenicTile(String scenicTile) {
            this.scenicTile = scenicTile;
        }

        public String getRoadSegment() {
            return roadSegment;
        }

        public void setRoadSegment(String roadSegment) {
            this.roadSegment = roadSegment;
        }

        public String getRecomputeRequest() {
            return recomputeRequest;
        }

        public void setRecomputeRequest(String recomputeRequest) {
            this.recomputeRequest = recomputeRequest;
        }

        public String getForwardedTileUpdate() {
            return forwardedTileUpdate;
        }

        public void setForwardedTileUpdate(String forwardedTileUpdate) {
            this.forwardedTileUpdate = forwardedTileUpdate;
        }
    }
}

