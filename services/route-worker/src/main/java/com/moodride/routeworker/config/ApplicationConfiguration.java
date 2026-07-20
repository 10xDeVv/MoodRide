package com.moodride.routeworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moodride.algorithm")
public class ApplicationConfiguration implements InitializingBean {
    public static final String COMPILED_PROFILE = "hybrid_osrm_v2";
    public static final String COMPILED_MODE = "drive";

    private String profile = COMPILED_PROFILE;
    private String mode = COMPILED_MODE;
    private int h3Resolution = 7;
    private int tileSelectionRingMin = 4;
    private int tileSelectionRingMax = 22;
    private int tileSelectionLimit = 120;
    private int sectorCount = 8;
    private int corridorSampleMeters = 500;
    private double maxDurationOverrunRatio = 1.15;
    private int anchoredTileSelectionLimit = 48;
    private int maxOsrmRequestsPerJob = 48;
    private int osrmRequestParallelism = 6;
    private boolean osrmEarlyStopEnabled = true;
    private int osrmEarlyStopMinRequests = 24;
    private int osrmEarlyStopMinCandidates = 12;


    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getH3Resolution() {
        return h3Resolution;
    }

    public void setH3Resolution(int h3Resolution) {
        this.h3Resolution = h3Resolution;
    }

    public int getTileSelectionRingMin() {
        return tileSelectionRingMin;
    }

    public void setTileSelectionRingMin(int tileSelectionRingMin) {
        this.tileSelectionRingMin = tileSelectionRingMin;
    }

    public int getTileSelectionRingMax() {
        return tileSelectionRingMax;
    }

    public void setTileSelectionRingMax(int tileSelectionRingMax) {
        this.tileSelectionRingMax = tileSelectionRingMax;
    }

    public int getTileSelectionLimit() {
        return tileSelectionLimit;
    }

    public void setTileSelectionLimit(int tileSelectionLimit) {
        this.tileSelectionLimit = tileSelectionLimit;
    }

    public int getSectorCount() {
        return sectorCount;
    }

    public void setSectorCount(int sectorCount) {
        this.sectorCount = sectorCount;
    }

    public int getCorridorSampleMeters() {
        return corridorSampleMeters;
    }

    public void setCorridorSampleMeters(int corridorSampleMeters) {
        this.corridorSampleMeters = corridorSampleMeters;
    }
    public int getAnchoredTileSelectionLimit() {
        return anchoredTileSelectionLimit;
    }

    public void setAnchoredTileSelectionLimit(int anchoredTileSelectionLimit) {
        this.anchoredTileSelectionLimit = anchoredTileSelectionLimit;
    }

    public double getMaxDurationOverrunRatio() {
        return maxDurationOverrunRatio;
    }

    public void setMaxDurationOverrunRatio(double maxDurationOverrunRatio) {
        this.maxDurationOverrunRatio = maxDurationOverrunRatio;
    }

    public int getMaxOsrmRequestsPerJob() {
        return maxOsrmRequestsPerJob;
    }

    public void setMaxOsrmRequestsPerJob(int maxOsrmRequestsPerJob) {
        this.maxOsrmRequestsPerJob = maxOsrmRequestsPerJob;
    }

    public int getOsrmRequestParallelism() {
        return osrmRequestParallelism;
    }

    public void setOsrmRequestParallelism(int osrmRequestParallelism) {
        this.osrmRequestParallelism = osrmRequestParallelism;
    }

    public boolean isOsrmEarlyStopEnabled() {
        return osrmEarlyStopEnabled;
    }

    public void setOsrmEarlyStopEnabled(boolean osrmEarlyStopEnabled) {
        this.osrmEarlyStopEnabled = osrmEarlyStopEnabled;
    }

    public int getOsrmEarlyStopMinRequests() {
        return osrmEarlyStopMinRequests;
    }

    public void setOsrmEarlyStopMinRequests(int osrmEarlyStopMinRequests) {
        this.osrmEarlyStopMinRequests = osrmEarlyStopMinRequests;
    }

    public int getOsrmEarlyStopMinCandidates() {
        return osrmEarlyStopMinCandidates;
    }

    public void setOsrmEarlyStopMinCandidates(int osrmEarlyStopMinCandidates) {
        this.osrmEarlyStopMinCandidates = osrmEarlyStopMinCandidates;
    }
    @Override
    public void afterPropertiesSet() {
        if (!COMPILED_PROFILE.equals(profile)) {
            throw new IllegalStateException(
                "Unsupported moodride.algorithm.profile '" + profile
                    + "'; this route-worker binary supports only '" + COMPILED_PROFILE + "'"
            );
        }
        if (!COMPILED_MODE.equals(mode)) {
            throw new IllegalStateException(
                "Unsupported moodride.algorithm.mode '" + mode
                    + "'; this route-worker binary supports only '" + COMPILED_MODE + "'"
            );
        }
    }
}
