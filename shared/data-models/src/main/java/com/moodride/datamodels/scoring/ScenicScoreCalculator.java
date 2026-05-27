package com.moodride.datamodels.scoring;

import com.moodride.datamodels.ScenicScoreTile;

public class ScenicScoreCalculator {

    private static final double SOLITUDE_BASE_WEIGHT = 0.60;
    private static final double SOLITUDE_BUILDING_WEIGHT = 0.25;
    private static final double SOLITUDE_DARKNESS_WEIGHT = 0.15;
    private static final double URBAN_PENALTY_WEIGHT = 0.10;

    public double scoreTile(ScenicScoreTile tile, PreferenceWeights preferences) {
        ComponentScores components = componentScores(tile);
        double weighted = (components.water() * preferences.water())
            + (components.greenery() * preferences.greenery())
            + (components.elevation() * preferences.elevation())
            + (components.solitude() * preferences.solitude())
            + (components.curves() * preferences.curves())
            + (components.poi() * preferences.poi());

        double baseScore = clamp01(weighted / preferences.totalWeight());
        double boosted = tile.applyParkBoost(baseScore);
        if (isV30(tile)) {
            double penalty = clamp01(tile.getUrbanPenaltyScore());
            boosted = clamp01(boosted - (penalty * URBAN_PENALTY_WEIGHT));
        }
        return boosted;
    }

    public ComponentScores componentScores(ScenicScoreTile tile) {
        boolean v30 = isV30(tile);
        double water = resolveComponentScore(tile.getWaterScore(), tile.getWaterProximity(), v30);
        double greenery = resolveComponentScore(tile.getGreenScore(), tile.getNaturalLandUse(), v30);
        double elevation = normalizeElevation(resolveComponentScore(tile.getElevationScore(), tile.getElevationVariance(), v30));
        double curves = resolveComponentScore(tile.getCurveScore(), tile.getVisualComplexity(), v30);

        double baseSolitude = resolveComponentScore(
            tile.getSolitudeScore(),
            (1.0 - clamp01(tile.getRoadDensity()) + clamp01(tile.getTrafficSignalScore())) / 2.0,
            v30
        );

        double solitude = baseSolitude;
        if (v30) {
            double buildingDensity = clamp01(tile.getBuildingDensityScore());
            double darkness = clamp01(tile.getDarknessScore());
            solitude = clamp01(
                (baseSolitude * SOLITUDE_BASE_WEIGHT)
                    + ((1.0 - buildingDensity) * SOLITUDE_BUILDING_WEIGHT)
                    + (darkness * SOLITUDE_DARKNESS_WEIGHT)
            );
        }

        double poi = resolveComponentScore(tile.getPoiScore(), tile.getPoiDensity(), v30);
        if (v30) {
            poi = Math.max(poi, clamp01(tile.getOverturePoiScore()));
        }

        return new ComponentScores(water, greenery, elevation, solitude, curves, poi);
    }

    private boolean isV30(ScenicScoreTile tile) {
        String version = tile.getScoringVersion();
        return version != null && version.startsWith("3.0");
    }

    private double normalizeElevation(double value) {
        if (value <= 1.0) {
            return clamp01(value);
        }
        return clamp01(value / 40.0);
    }

    private double resolveComponentScore(double component, double legacyFallback, boolean componentScoresAreAuthoritative) {
        if (componentScoresAreAuthoritative) {
            return clamp01(component);
        }
        if (component > 0.0) {
            return clamp01(component);
        }
        return clamp01(legacyFallback);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
