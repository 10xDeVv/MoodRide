package com.moodride.routeworker.algorithm;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.ComponentScores;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenicScoreCalculatorTest {

    private final ScenicScoreCalculator calculator = new ScenicScoreCalculator();

    @Test
    void v30ComponentZeroIsAuthoritativeAndDoesNotFallbackToLegacySignal() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.0-overture-lightpollution-enrichment");
        tile.setWaterScore(0.0);
        tile.setWaterProximity(0.9);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isZero();
    }

    @Test
    void v31ComponentZeroIsAuthoritativeAndDoesNotFallbackToLegacySignal() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.1-darkness-urban-penalty-calibration");
        tile.setWaterScore(0.0);
        tile.setWaterProximity(0.9);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isZero();
    }

    @Test
    void legacyComponentZeroFallsBackToLegacySignal() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("2.9-protected-areas-enrichment");
        tile.setWaterScore(0.0);
        tile.setWaterProximity(0.9);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isEqualTo(0.9);
    }

    @Test
    void roadStressReducesEnrichedSolitudeAndTileScore() {
        ScenicScoreTile calmTile = enrichedTileWithRoadStress(0.0);
        ScenicScoreTile stressfulTile = enrichedTileWithRoadStress(1.0);

        ComponentScores calmScores = calculator.componentScores(calmTile);
        ComponentScores stressfulScores = calculator.componentScores(stressfulTile);

        assertThat(stressfulScores.solitude()).isLessThan(calmScores.solitude());
        assertThat(calculator.scoreTile(stressfulTile, balancedWeights()))
            .isLessThan(calculator.scoreTile(calmTile, balancedWeights()));
    }

    @Test
    void waterVisibilityCanLiftEnrichedWaterScoreBeyondProximity() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.3-water-visibility-calibration");
        tile.setWaterScore(0.10);
        tile.setWaterProximity(0.10);
        tile.setWaterVisibilityScore(0.90);
        tile.setCoastalRoadScore(0.70);
        tile.setWaterCrossingScore(0.50);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isGreaterThan(0.70);
    }

    @Test
    void treeCanopyCanLiftEnrichedGreeneryScore() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.4-tree-canopy-calibration");
        tile.setGreenScore(0.20);
        tile.setNaturalLandUse(0.20);
        tile.setTreeCanopyScore(0.90);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.greenery()).isGreaterThan(0.40);
    }

    @Test
    void zeroTreeCanopyPreservesEnrichedGreeneryScore() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.4-tree-canopy-calibration");
        tile.setGreenScore(0.42);
        tile.setNaturalLandUse(0.90);
        tile.setTreeCanopyScore(0.0);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.greenery()).isEqualTo(0.42);
    }

    @Test
    void scenicPoiCanLiftEnrichedPoiScore() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.5-scenic-poi-calibration");
        tile.setPoiScore(0.12);
        tile.setPoiDensity(0.12);
        tile.setOverturePoiScore(0.20);
        tile.setScenicPoiScore(0.82);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.poi()).isEqualTo(0.82);
    }

    @Test
    void viewpointCanLiftEnrichedPoiScore() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.6-viewpoint-calibration");
        tile.setPoiScore(0.12);
        tile.setPoiDensity(0.12);
        tile.setScenicPoiScore(0.40);
        tile.setViewpointScore(0.88);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.poi()).isEqualTo(0.88);
    }

    @Test
    void bridgeCoastalCanLiftEnrichedWaterScore() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.7-bridge-coastal-calibration");
        tile.setWaterScore(0.10);
        tile.setWaterProximity(0.10);
        tile.setWaterVisibilityScore(0.10);
        tile.setCoastalRoadScore(0.10);
        tile.setWaterCrossingScore(0.10);
        tile.setBridgeCoastalScore(0.76);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isEqualTo(0.76);
    }

    private static ScenicScoreTile enrichedTileWithRoadStress(double roadStressScore) {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("3.2-road-stress-calibration");
        tile.setWaterScore(0.5);
        tile.setGreenScore(0.5);
        tile.setElevationScore(0.5);
        tile.setSolitudeScore(0.7);
        tile.setCurveScore(0.5);
        tile.setPoiScore(0.2);
        tile.setBuildingDensityScore(0.1);
        tile.setDarknessScore(0.8);
        tile.setUrbanPenaltyScore(0.1);
        tile.setRoadStressScore(roadStressScore);
        return tile;
    }

    private static PreferenceWeights balancedWeights() {
        return new PreferenceWeights(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }
}
