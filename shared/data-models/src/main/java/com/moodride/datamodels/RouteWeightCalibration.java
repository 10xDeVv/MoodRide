package com.moodride.datamodels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "route_weight_calibrations")
public class RouteWeightCalibration {

    @Id
    @Column(length = 32, nullable = false)
    private String vibe;

    @Column(name = "water_multiplier", nullable = false)
    private double waterMultiplier = 1.0;

    @Column(name = "greenery_multiplier", nullable = false)
    private double greeneryMultiplier = 1.0;

    @Column(name = "elevation_multiplier", nullable = false)
    private double elevationMultiplier = 1.0;

    @Column(name = "solitude_multiplier", nullable = false)
    private double solitudeMultiplier = 1.0;

    @Column(name = "curves_multiplier", nullable = false)
    private double curvesMultiplier = 1.0;

    @Column(name = "poi_multiplier", nullable = false)
    private double poiMultiplier = 1.0;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public RouteWeightCalibration() {
    }

    public RouteWeightCalibration(String vibe) {
        this.vibe = vibe;
    }

    public String getVibe() {
        return vibe;
    }

    public void setVibe(String vibe) {
        this.vibe = vibe;
    }

    public double getWaterMultiplier() {
        return waterMultiplier;
    }

    public void setWaterMultiplier(double waterMultiplier) {
        this.waterMultiplier = waterMultiplier;
    }

    public double getGreeneryMultiplier() {
        return greeneryMultiplier;
    }

    public void setGreeneryMultiplier(double greeneryMultiplier) {
        this.greeneryMultiplier = greeneryMultiplier;
    }

    public double getElevationMultiplier() {
        return elevationMultiplier;
    }

    public void setElevationMultiplier(double elevationMultiplier) {
        this.elevationMultiplier = elevationMultiplier;
    }

    public double getSolitudeMultiplier() {
        return solitudeMultiplier;
    }

    public void setSolitudeMultiplier(double solitudeMultiplier) {
        this.solitudeMultiplier = solitudeMultiplier;
    }

    public double getCurvesMultiplier() {
        return curvesMultiplier;
    }

    public void setCurvesMultiplier(double curvesMultiplier) {
        this.curvesMultiplier = curvesMultiplier;
    }

    public double getPoiMultiplier() {
        return poiMultiplier;
    }

    public void setPoiMultiplier(double poiMultiplier) {
        this.poiMultiplier = poiMultiplier;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

