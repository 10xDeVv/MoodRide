package com.moodride.routeworker.algorithm;

import com.moodride.datamodels.RouteDurationCalibration;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDurationCalibrationTest {

    @Test
    void observeShrinksRadiusAndWaypointCountForLongRoutes() {
        RouteDurationCalibration calibration = new RouteDurationCalibration(
            "drive",
            "region-a",
            60,
            "WATER_FOLLOWING"
        );

        calibration.observe(12.0, 6, 60, 90, Instant.parse("2026-06-08T12:00:00Z"));

        assertThat(calibration.getSampleCount()).isEqualTo(1);
        assertThat(calibration.getRadiusMultiplier()).isEqualTo(0.75);
        assertThat(calibration.getLearnedWaypointCount()).isEqualTo(4.5);
        assertThat(calibration.getAvgDurationRatio()).isEqualTo(1.5);
    }

    @Test
    void bucketMinutesRoundsToNearestFifteenMinuteBucket() {
        assertThat(RouteDurationCalibration.bucketMinutes(8)).isEqualTo(15);
        assertThat(RouteDurationCalibration.bucketMinutes(37)).isEqualTo(30);
        assertThat(RouteDurationCalibration.bucketMinutes(38)).isEqualTo(45);
    }
}
