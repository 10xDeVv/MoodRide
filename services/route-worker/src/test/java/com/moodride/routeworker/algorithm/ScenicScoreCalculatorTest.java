package com.moodride.routeworker.algorithm;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.ComponentScores;
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
    void legacyComponentZeroFallsBackToLegacySignal() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setScoringVersion("2.9-protected-areas-enrichment");
        tile.setWaterScore(0.0);
        tile.setWaterProximity(0.9);

        ComponentScores scores = calculator.componentScores(tile);

        assertThat(scores.water()).isEqualTo(0.9);
    }
}
