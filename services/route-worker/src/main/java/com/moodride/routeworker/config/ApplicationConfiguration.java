package com.moodride.routeworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moodride.algorithm")
public class ApplicationConfiguration {
    private int h3Resolution = 7;
    private int tileSelectionRingMin = 4;
    private int tileSelectionRingMax = 22;
    private int tileSelectionLimit = 120;
    private int sectorCount = 8;
    private int corridorSampleMeters = 500;
    private double maxDurationOverrunRatio = 1.15;
    private int anchoredTileSelectionLimit = 48;
    private int maxOsrmRequestsPerJob = 48;


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
}
