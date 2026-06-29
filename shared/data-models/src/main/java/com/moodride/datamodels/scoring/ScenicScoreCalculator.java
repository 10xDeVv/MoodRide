package com.moodride.datamodels.scoring;

import com.moodride.datamodels.ScenicScoreTile;

public class ScenicScoreCalculator {

    private static final double SOLITUDE_BASE_WEIGHT = 0.60;
    private static final double SOLITUDE_BUILDING_WEIGHT = 0.25;
    private static final double SOLITUDE_DARKNESS_WEIGHT = 0.10;
    private static final double SOLITUDE_ROAD_STRESS_WEIGHT = 0.05;
    private static final double URBAN_PENALTY_WEIGHT = 0.10;
    private static final double ROAD_STRESS_PENALTY_WEIGHT = 0.06;
    private static final double GREENERY_TREE_CANOPY_WEIGHT = 0.35;

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
        if (isEnrichedDataVersion(tile)) {
            double penalty = clamp01(tile.getUrbanPenaltyScore());
            boosted = clamp01(boosted - (penalty * URBAN_PENALTY_WEIGHT));
            boosted = clamp01(boosted - (clamp01(tile.getRoadStressScore()) * ROAD_STRESS_PENALTY_WEIGHT));
        }
        return boosted;
    }

    public ComponentScores componentScores(ScenicScoreTile tile) {
        boolean enrichedDataVersion = isEnrichedDataVersion(tile);
        double water = resolveWaterScore(tile, enrichedDataVersion);
        double greenery = resolveGreeneryScore(tile, enrichedDataVersion);
        double elevation = normalizeElevation(resolveComponentScore(tile.getElevationScore(), tile.getElevationVariance(), enrichedDataVersion));
        double curves = resolveComponentScore(tile.getCurveScore(), tile.getVisualComplexity(), enrichedDataVersion);

        double baseSolitude = resolveComponentScore(
            tile.getSolitudeScore(),
            (1.0 - clamp01(tile.getRoadDensity()) + clamp01(tile.getTrafficSignalScore())) / 2.0,
            enrichedDataVersion
        );

        double solitude = baseSolitude;
        if (enrichedDataVersion) {
            double buildingDensity = clamp01(tile.getBuildingDensityScore());
            double darkness = clamp01(tile.getDarknessScore());
            double lowRoadStress = 1.0 - clamp01(tile.getRoadStressScore());
            solitude = clamp01(
                (baseSolitude * SOLITUDE_BASE_WEIGHT)
                    + ((1.0 - buildingDensity) * SOLITUDE_BUILDING_WEIGHT)
                    + (darkness * SOLITUDE_DARKNESS_WEIGHT)
                    + (lowRoadStress * SOLITUDE_ROAD_STRESS_WEIGHT)
            );
        }

        double poi = resolveComponentScore(tile.getPoiScore(), tile.getPoiDensity(), enrichedDataVersion);
        if (enrichedDataVersion) {
            poi = Math.max(poi, clamp01(tile.getOverturePoiScore()));
            poi = Math.max(poi, clamp01(tile.getScenicPoiScore()));
        }

        return new ComponentScores(water, greenery, elevation, solitude, curves, poi);
    }

    private boolean isEnrichedDataVersion(ScenicScoreTile tile) {
        String version = tile.getScoringVersion();
        return version != null && version.startsWith("3.");
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

    private double resolveWaterScore(ScenicScoreTile tile, boolean componentScoresAreAuthoritative) {
        double baseWater = resolveComponentScore(tile.getWaterScore(), tile.getWaterProximity(), componentScoresAreAuthoritative);
        if (!componentScoresAreAuthoritative) {
            return baseWater;
        }

        double visibleWater = clamp01(tile.getWaterVisibilityScore());
        double crossingWater = clamp01(tile.getWaterCrossingScore());
        double coastalRoad = clamp01(tile.getCoastalRoadScore());
        double visibilityBlend = clamp01(
            (visibleWater * 0.62)
                + (coastalRoad * 0.26)
                + (crossingWater * 0.12)
        );
        return Math.max(baseWater, visibilityBlend);
    }

    private double resolveGreeneryScore(ScenicScoreTile tile, boolean componentScoresAreAuthoritative) {
        double baseGreenery = resolveComponentScore(tile.getGreenScore(), tile.getNaturalLandUse(), componentScoresAreAuthoritative);
        if (!componentScoresAreAuthoritative) {
            return baseGreenery;
        }

        double treeCanopy = clamp01(tile.getTreeCanopyScore());
        if (treeCanopy <= 0.0) {
            return baseGreenery;
        }

        double canopyBlend = clamp01(
            (baseGreenery * (1.0 - GREENERY_TREE_CANOPY_WEIGHT))
                + (treeCanopy * GREENERY_TREE_CANOPY_WEIGHT)
        );
        return Math.max(baseGreenery, canopyBlend);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
