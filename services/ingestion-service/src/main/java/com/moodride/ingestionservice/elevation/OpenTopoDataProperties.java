package com.moodride.ingestionservice.elevation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moodride.elevation.opentopodata")
public class OpenTopoDataProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:5000";
    private String dataset = "etopo1";
    private int requestBatchSize = 64;
    private int segmentBatchSize = 500;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 8000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public int getRequestBatchSize() {
        return requestBatchSize;
    }

    public void setRequestBatchSize(int requestBatchSize) {
        this.requestBatchSize = requestBatchSize;
    }

    public int getSegmentBatchSize() {
        return segmentBatchSize;
    }

    public void setSegmentBatchSize(int segmentBatchSize) {
        this.segmentBatchSize = segmentBatchSize;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}

