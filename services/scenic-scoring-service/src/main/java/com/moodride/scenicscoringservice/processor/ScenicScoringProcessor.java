package com.moodride.scenicscoringservice.processor;

import com.moodride.datamodels.ScenicScoreTile;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

/**
 * Computes scenic scores for H3 hexagonal tiles.
 */
@Component
public class ScenicScoringProcessor {

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

        tile.setWaterProximity(clamp01(waterProximity));
        tile.setElevationVariance(clamp01(elevationVariance));
        tile.setNaturalLandUse(clamp01(naturalLandUse));
        tile.setRoadDensity(clamp01(roadDensity));
        tile.setPoiDensity(clamp01(poiDensity));
        tile.setTrafficSignalScore(clamp01(trafficSignalScore));
        tile.setVisualComplexity(clamp01(visualComplexity));
        tile.syncComponentScoresFromLegacySignals();

        tile.calculateScenicScore();
        tile.setScoringVersion("1.0");

        return tile;
    }

    public double scoreWaterProximity(Polygon tile, double minDistanceToWater) {
        if (minDistanceToWater < 0) {
            return 1.0;
        }
        if (minDistanceToWater > 5000) {
            return 0.0;
        }
        return 1.0 - (minDistanceToWater / 5000.0);
    }

    public double scoreElevationVariance(double minElevation, double maxElevation) {
        double elevRange = maxElevation - minElevation;
        if (elevRange < 0) {
            return 0.0;
        }
        if (elevRange > 2000) {
            return 1.0;
        }
        return elevRange / 2000.0;
    }

    public double scoreNaturalLandUse(double naturalPixelPercentage) {
        if (naturalPixelPercentage < 0) {
            return 0.0;
        }
        if (naturalPixelPercentage > 100) {
            return 1.0;
        }
        return naturalPixelPercentage / 100.0;
    }

    public double scoreRoadDensity(int roadCount, double tileAreaSqKm) {
        if (tileAreaSqKm <= 0 || roadCount == 0) {
            return 0.0;
        }
        double density = roadCount / tileAreaSqKm;
        if (density > 100) {
            return 1.0;
        }
        return Math.min(1.0, density / 100.0);
    }

    public double scorePoiDensity(int poiCount, double tileAreaSqKm) {
        if (tileAreaSqKm <= 0) {
            return 0.0;
        }
        double density = poiCount / tileAreaSqKm;
        if (density > 10) {
            return 1.0;
        }
        return Math.min(1.0, density / 10.0);
    }

    public double scoreVisualComplexity(double elevationVariance, double landUseDiversity) {
        return (elevationVariance * 0.6) + (landUseDiversity * 0.4);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

