package com.moodride.ingestionservice.processor;

import com.moodride.datamodels.ScenicScoreTile;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

/**
 * Computes scenic scores for H3 hexagonal tiles.
 * Integrates multiple data sources to produce a composite scenic quality score.
 *
 * Scoring formula (weights based on user preference research):
 * - Water Proximity (25%): Coastal/lake presence
 * - Elevation Variance (20%): Terrain complexity
 * - Natural Land Use (20%): Forest/park coverage
 * - Road Density (10%): Road network complexity
 * - POI Density (15%): Points of interest
 * - Visual Complexity (10%): Landscape visual appeal
 */
@Component
public class ScenicScoringProcessor {

    /**
     * Computes a comprehensive scenic score for a tile.
     * Each component is normalized to 0.0-1.0 range.
     */
    public ScenicScoreTile computeScenicScore(
            String h3Index,
            Polygon tileGeometry,
            double waterProximity,
            double elevationVariance,
            double naturalLandUse,
            double roadDensity,
            double trafficSignalScore,
            double poiDensity,
            double visualComplexity) {

        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setGeometry(tileGeometry);

        // Normalize all components to 0.0-1.0
        tile.setWaterProximity(Math.max(0.0, Math.min(1.0, waterProximity)));
        tile.setElevationVariance(Math.max(0.0, Math.min(1.0, elevationVariance)));
        tile.setNaturalLandUse(Math.max(0.0, Math.min(1.0, naturalLandUse)));
        tile.setRoadDensity(Math.max(0.0, Math.min(1.0, roadDensity)));
        tile.setPoiDensity(Math.max(0.0, Math.min(1.0, poiDensity)));
        tile.setTrafficSignalScore(Math.max(0.0, Math.min(1.0, trafficSignalScore)));
        tile.setVisualComplexity(Math.max(0.0, Math.min(1.0, visualComplexity)));
        tile.syncComponentScoresFromLegacySignals();

        // Calculate weighted scenic score
        tile.calculateScenicScore();
        tile.setScoringVersion("1.0");

        return tile;
    }

    /**
     * Scores water proximity: How close is the tile to water bodies?
     * Range: 0.0 (landlocked) to 1.0 (waterfront)
     *
     * Uses Natural Earth water bodies + OSM water polygons.
     */
    public double scoreWaterProximity(Polygon tile, double minDistanceToWater) {
        // Distance is in meters; scale to 0-1 score
        // 0m (on water) = 1.0, 5km+ = 0.0
        if (minDistanceToWater < 0) return 1.0;
        if (minDistanceToWater > 5000) return 0.0;
        return 1.0 - (minDistanceToWater / 5000.0);
    }

    /**
     * Scores elevation variance: How much terrain variation in the tile?
     * Range: 0.0 (flat) to 1.0 (mountainous)
     *
     * Uses OpenTopoData elevation API.
     */
    public double scoreElevationVariance(double minElevation, double maxElevation) {
        double elevRange = maxElevation - minElevation;

        // Normalize elevation range to 0-1 score
        // 0m (flat) = 0.0, 2000m+ (mountainous) = 1.0
        if (elevRange < 0) return 0.0;
        if (elevRange > 2000) return 1.0;
        return elevRange / 2000.0;
    }

    /**
     * Scores natural land use: What percentage of tile is forest/park/natural?
     * Range: 0.0 (100% urban) to 1.0 (100% natural)
     *
     * Uses NLCD land use classification (resolution: 30m pixels).
     */
    public double scoreNaturalLandUse(double naturalPixelPercentage) {
        // Percentage of pixels classified as natural (11-95 in NLCD)
        if (naturalPixelPercentage < 0) return 0.0;
        if (naturalPixelPercentage > 100) return 1.0;
        return naturalPixelPercentage / 100.0;
    }

    /**
     * Scores road density: How complex is the road network?
     * Range: 0.0 (no roads) to 1.0 (grid of highways)
     *
     * Computed from OSM road segments.
     */
    public double scoreRoadDensity(int roadCount, double tileAreaSqKm) {
        if (tileAreaSqKm <= 0 || roadCount == 0) return 0.0;

        // Road density: number of road segments per sq km
        double density = roadCount / tileAreaSqKm;

        // Normalize: 0 roads = 0.0, 100+ segments/sq km = 1.0
        if (density > 100) return 1.0;
        return Math.min(1.0, density / 100.0);
    }

    /**
     * Scores POI density: How many points of interest (scenic overlooks, landmarks)?
     * Range: 0.0 (no POIs) to 1.0 (many POIs)
     *
     * Uses OSM POI data filtered for scenic categories.
     */
    public double scorePoiDensity(int poiCount, double tileAreaSqKm) {
        if (tileAreaSqKm <= 0) return 0.0;

        // POI density per sq km
        double density = poiCount / tileAreaSqKm;

        // Normalize: 0 = 0.0, 10+ POIs/sq km = 1.0
        if (density > 10) return 1.0;
        return Math.min(1.0, density / 10.0);
    }

    /**
     * Scores visual complexity: How visually interesting is the landscape?
     * Range: 0.0 (monotonous) to 1.0 (highly varied)
     *
     * Computed from combination of elevation variance and land use diversity.
     */
    public double scoreVisualComplexity(double elevationVariance, double landUseDiversity) {
        // Simple weighted combination
        return (elevationVariance * 0.6) + (landUseDiversity * 0.4);
    }

    /**
     * Computes land use diversity: How many different types of land use are present?
     * Range: 0.0 (homogeneous) to 1.0 (highly diverse)
     *
     * Uses Shannon diversity index on NLCD classes.
     */
    public double computeLandUseDiversity(int[] nlcdClassPixels) {
        if (nlcdClassPixels == null || nlcdClassPixels.length == 0) return 0.0;

        int totalPixels = 0;
        for (int pixels : nlcdClassPixels) {
            totalPixels += pixels;
        }

        if (totalPixels == 0) return 0.0;

        // Shannon diversity index
        double entropy = 0.0;
        for (int pixels : nlcdClassPixels) {
            if (pixels > 0) {
                double p = (double) pixels / totalPixels;
                entropy -= p * Math.log(p);
            }
        }

        // Normalize to 0-1 (max entropy for n classes = ln(n))
        double maxEntropy = Math.log(nlcdClassPixels.length);
        return entropy / maxEntropy;
    }
}

