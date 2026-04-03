package com.moodride.datamodels;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.time.Instant;

/**
 * JPA entity representing scenic score for an H3 hexagonal tile.
 * Scores are computed weekly by scenic-scoring-service batch job.
 */
@Entity
@Table(name = "scenic_score_tiles", indexes = {
    @Index(name = "idx_scenic_h3", columnList = "h3Index", unique = true),
    @Index(name = "idx_scenic_score", columnList = "scenicScore"),
    @Index(name = "idx_scenic_geom", columnList = "geometry")
})
public class ScenicScoreTile {

    @Id
    @Column(length = 15)
    private String h3Index;  // H3 hex index (resolution 9: ~105m average)

    @Column(name = "geometry", columnDefinition = "geometry(POLYGON, 4326)", nullable = false)
    private Polygon geometry;  // PostGIS polygon for the hex

    @Column(nullable = false)
    private double scenicScore;  // Combined scenic score (0.0 - 1.0)

    // Individual scoring components (each 0.0 - 1.0)
    @Column(nullable = false)
    private double waterProximity;  // Coastal/lake proximity

    @Column(nullable = false)
    private double elevationVariance;  // Terrain complexity

    @Column(nullable = false)
    private double naturalLandUse;  // Forest/park coverage

    @Column(nullable = false)
    private double roadDensity;  // Road network complexity

    @Column(nullable = false)
    private double poiDensity;  // Points of interest

    @Column(nullable = false)
    private double visualComplexity;  // Landscape visual appeal

    @Column(nullable = false)
    private Instant lastScored;

    @Column(length = 50)
    private String scoringVersion;  // Algorithm version used

    // Constructors
    public ScenicScoreTile() {}

    public ScenicScoreTile(String h3Index, Polygon geometry) {
        this.h3Index = h3Index;
        this.geometry = geometry;
        this.lastScored = Instant.now();
    }

    /**
     * Calculates the combined scenic score from individual components.
     * Weights are based on user preference research and can be tuned.
     */
    public void calculateScenicScore() {
        this.scenicScore = (
            waterProximity * 0.25 +
            elevationVariance * 0.20 +
            naturalLandUse * 0.20 +
            roadDensity * 0.10 +
            poiDensity * 0.15 +
            visualComplexity * 0.10
        );
    }

    // Getters and setters
    public String getH3Index() { return h3Index; }
    public void setH3Index(String h3Index) { this.h3Index = h3Index; }

    public Polygon getGeometry() { return geometry; }
    public void setGeometry(Polygon geometry) { this.geometry = geometry; }

    public double getScenicScore() { return scenicScore; }
    public void setScenicScore(double scenicScore) { this.scenicScore = scenicScore; }

    public double getWaterProximity() { return waterProximity; }
    public void setWaterProximity(double waterProximity) { this.waterProximity = waterProximity; }

    public double getElevationVariance() { return elevationVariance; }
    public void setElevationVariance(double elevationVariance) { this.elevationVariance = elevationVariance; }

    public double getNaturalLandUse() { return naturalLandUse; }
    public void setNaturalLandUse(double naturalLandUse) { this.naturalLandUse = naturalLandUse; }

    public double getRoadDensity() { return roadDensity; }
    public void setRoadDensity(double roadDensity) { this.roadDensity = roadDensity; }

    public double getPoiDensity() { return poiDensity; }
    public void setPoiDensity(double poiDensity) { this.poiDensity = poiDensity; }

    public double getVisualComplexity() { return visualComplexity; }
    public void setVisualComplexity(double visualComplexity) { this.visualComplexity = visualComplexity; }

    public Instant getLastScored() { return lastScored; }
    public void setLastScored(Instant lastScored) { this.lastScored = lastScored; }

    public String getScoringVersion() { return scoringVersion; }
    public void setScoringVersion(String scoringVersion) { this.scoringVersion = scoringVersion; }
}