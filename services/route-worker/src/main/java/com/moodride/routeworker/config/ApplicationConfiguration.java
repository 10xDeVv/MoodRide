package com.moodride.routeworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moodride.algorithm")
public class ApplicationConfiguration {
    
    private int beamWidth = 10;
    private int maxIterations = 1000;
    private int timeoutMinutes = 5;
    
    public int getBeamWidth() {
        return beamWidth;
    }
    
    public void setBeamWidth(int beamWidth) {
        this.beamWidth = beamWidth;
    }
    
    public int getMaxIterations() {
        return maxIterations;
    }
    
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }
    
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }
    
    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }
}
