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
    private static final double PARK_SCORE_MULTIPLIER = 0.30;

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
    private double trafficSignalScore = 0.5;  // Lower congestion -> higher scenic quality

    @Column(nullable = false)
    private double poiDensity;

    @Column(nullable = false)
    private double visualComplexity;

    // Explicit component scores for preference-driven routing (Execution Plan Week 1).
    @Column(name = "water_score", nullable = false)
    private double waterScore;

    @Column(name = "green_score", nullable = false)
    private double greenScore;

    @Column(name = "elevation_score", nullable = false)
    private double elevationScore;

    @Column(name = "solitude_score", nullable = false)
    private double solitudeScore;

    @Column(name = "curve_score", nullable = false)
    private double curveScore;

    @Column(name = "poi_score", nullable = false)
    private double poiScore;

    @Column(name = "park_score", nullable = false)
    private double parkScore;

    @Column(name = "overture_poi_score", nullable = false)
    private double overturePoiScore;

    @Column(name = "building_density_score", nullable = false)
    private double buildingDensityScore;

    @Column(name = "darkness_score", nullable = false)
    private double darknessScore;

    @Column(name = "urban_penalty_score", nullable = false)
    private double urbanPenaltyScore;

    @Column(name = "road_stress_score", nullable = false)
    private double roadStressScore;

    @Column(name = "water_visibility_score", nullable = false)
    private double waterVisibilityScore;

    @Column(name = "water_crossing_score", nullable = false)
    private double waterCrossingScore;

    @Column(name = "coastal_road_score", nullable = false)
    private double coastalRoadScore;

    @Column(name = "tree_canopy_score", nullable = false)
    private double treeCanopyScore;

    @Column(name = "scenic_poi_score", nullable = false)
    private double scenicPoiScore;

    @Column(name = "viewpoint_score", nullable = false)
    private double viewpointScore;

    @Column(name = "bridge_coastal_score", nullable = false)
    private double bridgeCoastalScore;

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
        double water = clamp(waterScore);
        double green = clamp(greenScore);
        double elevation = clamp(elevationScore);
        double solitude = clamp(solitudeScore);
        double curves = clamp(curveScore);
        double poi = clamp(poiScore);

        // Backward compatibility if component-score columns have not been populated yet.
        if (!hasAuthoritativeComponentScores()
            && water == 0.0 && green == 0.0 && elevation == 0.0 && solitude == 0.0 && curves == 0.0 && poi == 0.0) {
            water = clamp(waterProximity);
            green = clamp(naturalLandUse);
            elevation = clamp(elevationVariance);
            solitude = clamp((1.0 - clamp(roadDensity) + clamp(trafficSignalScore)) / 2.0);
            curves = clamp(visualComplexity);
            poi = clamp(poiDensity);
        }

        double baseScore = clamp(
            water * 0.25 +
            green * 0.22 +
            elevation * 0.15 +
            solitude * 0.12 +
            curves * 0.11 +
            poi * 0.15
        );
        this.scenicScore = applyParkBoost(baseScore);
    }

    public double applyParkBoost(double baseScore) {
        double park = clamp(parkScore);
        if (park <= 0.0) {
            return clamp(baseScore);
        }
        return clamp(baseScore * (1.0 + (park * PARK_SCORE_MULTIPLIER)));
    }

    public void syncComponentScoresFromLegacySignals() {
        this.waterScore = clamp(waterProximity);
        this.greenScore = clamp(naturalLandUse);
        this.elevationScore = clamp(elevationVariance);
        this.solitudeScore = clamp((1.0 - clamp(roadDensity) + clamp(trafficSignalScore)) / 2.0);
        this.curveScore = clamp(visualComplexity);
        this.poiScore = clamp(poiDensity);
    }

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

    public double getWaterScore() { return waterScore; }
    public void setWaterScore(double waterScore) { this.waterScore = waterScore; }

    public double getGreenScore() { return greenScore; }
    public void setGreenScore(double greenScore) { this.greenScore = greenScore; }

    public double getElevationScore() { return elevationScore; }
    public void setElevationScore(double elevationScore) { this.elevationScore = elevationScore; }

    public double getSolitudeScore() { return solitudeScore; }
    public void setSolitudeScore(double solitudeScore) { this.solitudeScore = solitudeScore; }

    public double getCurveScore() { return curveScore; }
    public void setCurveScore(double curveScore) { this.curveScore = curveScore; }

    public double getPoiScore() { return poiScore; }
    public void setPoiScore(double poiScore) { this.poiScore = poiScore; }

    public double getParkScore() { return parkScore; }
    public void setParkScore(double parkScore) { this.parkScore = parkScore; }

    public double getOverturePoiScore() { return overturePoiScore; }
    public void setOverturePoiScore(double overturePoiScore) { this.overturePoiScore = overturePoiScore; }

    public double getBuildingDensityScore() { return buildingDensityScore; }
    public void setBuildingDensityScore(double buildingDensityScore) { this.buildingDensityScore = buildingDensityScore; }

    public double getDarknessScore() { return darknessScore; }
    public void setDarknessScore(double darknessScore) { this.darknessScore = darknessScore; }

    public double getUrbanPenaltyScore() { return urbanPenaltyScore; }
    public void setUrbanPenaltyScore(double urbanPenaltyScore) { this.urbanPenaltyScore = urbanPenaltyScore; }

    public double getRoadStressScore() { return roadStressScore; }
    public void setRoadStressScore(double roadStressScore) { this.roadStressScore = roadStressScore; }

    public double getWaterVisibilityScore() { return waterVisibilityScore; }
    public void setWaterVisibilityScore(double waterVisibilityScore) { this.waterVisibilityScore = waterVisibilityScore; }

    public double getWaterCrossingScore() { return waterCrossingScore; }
    public void setWaterCrossingScore(double waterCrossingScore) { this.waterCrossingScore = waterCrossingScore; }

    public double getCoastalRoadScore() { return coastalRoadScore; }
    public void setCoastalRoadScore(double coastalRoadScore) { this.coastalRoadScore = coastalRoadScore; }

    public double getTreeCanopyScore() { return treeCanopyScore; }
    public void setTreeCanopyScore(double treeCanopyScore) { this.treeCanopyScore = treeCanopyScore; }

    public double getScenicPoiScore() { return scenicPoiScore; }
    public void setScenicPoiScore(double scenicPoiScore) { this.scenicPoiScore = scenicPoiScore; }

    public double getViewpointScore() { return viewpointScore; }
    public void setViewpointScore(double viewpointScore) { this.viewpointScore = viewpointScore; }

    public double getBridgeCoastalScore() { return bridgeCoastalScore; }
    public void setBridgeCoastalScore(double bridgeCoastalScore) { this.bridgeCoastalScore = bridgeCoastalScore; }

    public double getTrafficSignalScore() { return trafficSignalScore; }
    public void setTrafficSignalScore(double trafficSignalScore) { this.trafficSignalScore = trafficSignalScore; }

    public String getH3Index() { return h3Index; }
    public void setH3Index(String h3Index) { this.h3Index = h3Index; }

    public Polygon getGeometry() { return geometry; }
    public void setGeometry(Polygon geometry) { this.geometry = geometry; }

    public double getScenicScore() { return scenicScore; }

    public Instant getLastScored() { return lastScored; }
    public void setLastScored(Instant lastScored) { this.lastScored = lastScored; }

    public String getScoringVersion() { return scoringVersion; }
    public void setScoringVersion(String scoringVersion) { this.scoringVersion = scoringVersion; }

    private boolean hasAuthoritativeComponentScores() {
        return scoringVersion != null && scoringVersion.startsWith("3.");
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
