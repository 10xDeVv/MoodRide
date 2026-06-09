package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.H3Utils;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteWeightCalibrationRepository;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import com.moodride.routeworker.service.OsrmTripClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlannerTest {

    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;

    @Mock
    private OsrmTripClient osrmTripClient;

    @Mock
    private RouteWeightCalibrationRepository routeWeightCalibrationRepository;

    @Mock
    private RouteDurationCalibrationRepository routeDurationCalibrationRepository;

    private RoutePlanner routePlanner;

    @BeforeEach
    void setUp() {
        ApplicationConfiguration config = new ApplicationConfiguration();
        config.setH3Resolution(9);
        config.setTileSelectionRingMin(1);
        config.setTileSelectionRingMax(2);
        config.setTileSelectionLimit(20);
        config.setSectorCount(6);
        config.setCorridorSampleMeters(500);
        config.setMaxDurationOverrunRatio(1.0);
        routePlanner = new RoutePlanner(
            scenicScoreTileRepository,
            routeWeightCalibrationRepository,
            routeDurationCalibrationRepository,
            osrmTripClient,
            config,
            new ObjectMapper(),
            new ScenicScoreCalculator()
        );
        when(routeWeightCalibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of());
        when(routeDurationCalibrationRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    }

    @Test
    void generateRouteRejectsMissingScenicDataForRequestedVibe() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No scenic data found near this start");
    }

    @Test
    void generateRouteRejectsOverBudgetHybridCandidatesWhenNoInBudgetOptionExists() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(22)));

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(15)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 15 minutes");
    }

    @Test
    void generateRouteThrowsWhenNoHybridCandidateCanBeProduced() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 45 minutes");
    }

    @Test
    void generateRouteOptionsReturnsThreeDistinctProfilesWhenCandidatesExist() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int durationMinutes = variant.size() * 8;
            double distanceKm = variant.size() * 2.5;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        assertThat(options.stream().map(RouteCandidate::getAlgorithmVersion).collect(Collectors.toSet()))
            .containsExactly("hybrid_osrm_v2");
        assertThat(options.getFirst().getScoreBreakdown())
            .containsKeys(
                "final_score",
                "landscape_score",
                "vibe_fit_score",
                "urban_penalty",
                "strategy_fit_score",
                "strategy_mismatch_penalty",
                "water_corridor_share",
                "requested_avg_radius_km",
                "requested_waypoint_count",
                "duration_fit_ratio",
                "duration_calibration_bucket_minutes"
            );
        assertThat(options.getFirst().getScoreBreakdown().get("geometry_strategy_code"))
            .isEqualTo(0.0);
        assertThat(options.stream().map(this::pathSignature).collect(Collectors.toSet()))
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(options.stream().map(RouteCandidate::getTotalScenicScore).collect(Collectors.toSet()))
            .hasSizeGreaterThan(1);
        double maxScenicScore = options.stream()
            .mapToDouble(RouteCandidate::getTotalScenicScore)
            .max()
            .orElse(0.0);
        assertThat(options.getFirst().getTotalScenicScore()).isEqualTo(maxScenicScore);
    }

    @Test
    void openRoadsUsesOpenSpaceGeometryStrategy() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, variant.size() * 8));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45, "open_roads"));

        assertThat(options).hasSize(3);
        assertThat(options.getFirst().getScoreBreakdown().get("geometry_strategy_code"))
            .isEqualTo(1.0);
        assertThat(options.getFirst().getScoreBreakdown())
            .containsKeys("strategy_fit_score", "strategy_mismatch_penalty", "open_space_corridor_share");
    }

    @Test
    void countrysideUsesGradedQuietStrategyFitForModerateCorridors() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(moderateQuietTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, variant.size() * 8));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45, "countryside"));
        Map<String, Double> breakdown = options.getFirst().getScoreBreakdown();

        assertThat(options).hasSize(3);
        assertThat(breakdown.get("geometry_strategy_code")).isEqualTo(3.0);
        assertThat(breakdown.get("strategy_fit_score")).isGreaterThan(0.30);
        assertThat(breakdown.get("strategy_mismatch_penalty")).isLessThan(0.50);
    }

    @Test
    void countrysideRejectsUrbanCorridorsInsteadOfPretendingTheyFit() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(urbanQuietTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, variant.size() * 8));
        });

        assertThatThrownBy(() -> routePlanner.generateRouteOptions(sampleJob(45, "countryside")))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No strong Countryside route found");
    }

    @Test
    void shorterProfilePrefersUsefulShortRouteInsteadOfTinyRescueLoop() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        int[] durations = {60, 56, 20, 47, 45, 50, 22, 48, 52, 24, 44, 40, 38, 35};
        AtomicInteger callIndex = new AtomicInteger();
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int index = callIndex.getAndIncrement();
            int durationMinutes = durations[Math.min(index, durations.length - 1)];
            double distanceKm = durationMinutes * 0.75;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(60));

        assertThat(options).hasSize(3);
        RouteCandidate shorter = options.get(2);
        assertThat(shorter.getEstimatedMinutes()).isBetween(36, 55);
    }

    @Test
    void mostScenicProfileAvoidsTinyLoopsWhenLongerUsefulOptionsExist() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(highScenicTilesAroundStart());
        int[] durations = {32, 61, 46, 35, 58, 52, 34, 63, 44, 40, 55, 48, 36, 50};
        AtomicInteger callIndex = new AtomicInteger();
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int index = callIndex.getAndIncrement();
            int durationMinutes = durations[Math.min(index, durations.length - 1)];
            double distanceKm = durationMinutes * 0.75;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(60));

        assertThat(options).hasSize(3);
        RouteCandidate mostScenic = options.getFirst();
        assertThat(mostScenic.getEstimatedMinutes()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void generateRouteRejectsWeakVibeAvailabilityWhenNearbyTilesDoNotFit() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(lowWaterTilesAroundStart());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No strong Coastal route found");
    }

    @Test
    void generateRouteFallsBackToDefaultH3ResolutionForScenicScoring() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> indexes = invocation.getArgument(0);
            boolean includesDefaultResolution = indexes.stream()
                .anyMatch(index -> H3Utils.getResolution(index) == H3Utils.DEFAULT_RESOLUTION);
            if (!includesDefaultResolution) {
                return List.of();
            }

            return highScenicTilesAroundStart();
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(25)));

        RouteCandidate candidate = routePlanner.generateRoute(sampleJob(45));

        assertThat(candidate.getTotalScenicScore()).isGreaterThan(0.30);
        verify(scenicScoreTileRepository, atLeastOnce()).findByH3IndexIn(anyCollection());
    }

    private RouteJob sampleJob(int timeBudgetMinutes) {
        return sampleJob(timeBudgetMinutes, "coastal");
    }

    private RouteJob sampleJob(int timeBudgetMinutes, String vibe) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 46.0945, -64.7809, timeBudgetMinutes, vibe);
        job.setId(UUID.randomUUID());
        return job;
    }

    private String pathSignature(RouteCandidate candidate) {
        return candidate.getWaypoints().stream()
            .map(node -> "%.4f,%.4f".formatted(node.getLatitude(), node.getLongitude()))
            .collect(Collectors.joining("|"));
    }

    private List<ScenicScoreTile> highScenicTilesAroundStart() {
        return List.of(
            scenicTile("test-1", 46.1100, -64.7800),
            scenicTile("test-2", 46.1030, -64.7500),
            scenicTile("test-3", 46.0800, -64.7420),
            scenicTile("test-4", 46.0600, -64.7700),
            scenicTile("test-5", 46.0720, -64.8120),
            scenicTile("test-6", 46.1050, -64.8250),
            scenicTile("test-7", 46.1250, -64.8000),
            scenicTile("test-8", 46.1250, -64.7500)
        );
    }

    private List<ScenicScoreTile> lowWaterTilesAroundStart() {
        return List.of(
            scenicTile("dry-1", 46.1100, -64.7800, 0.05, 0.70, 0.45, 0.70, 0.55, 0.20),
            scenicTile("dry-2", 46.1030, -64.7500, 0.07, 0.75, 0.40, 0.72, 0.55, 0.20),
            scenicTile("dry-3", 46.0800, -64.7420, 0.04, 0.65, 0.35, 0.68, 0.50, 0.20),
            scenicTile("dry-4", 46.0600, -64.7700, 0.06, 0.72, 0.45, 0.76, 0.50, 0.20)
        );
    }

    private List<ScenicScoreTile> moderateQuietTilesAroundStart() {
        return List.of(
            moderateQuietTile("quiet-1", 46.1100, -64.7800),
            moderateQuietTile("quiet-2", 46.1030, -64.7500),
            moderateQuietTile("quiet-3", 46.0800, -64.7420),
            moderateQuietTile("quiet-4", 46.0600, -64.7700),
            moderateQuietTile("quiet-5", 46.0720, -64.8120),
            moderateQuietTile("quiet-6", 46.1050, -64.8250),
            moderateQuietTile("quiet-7", 46.1250, -64.8000),
            moderateQuietTile("quiet-8", 46.1250, -64.7500)
        );
    }

    private ScenicScoreTile moderateQuietTile(String h3Index, double latitude, double longitude) {
        ScenicScoreTile tile = scenicTile(h3Index, latitude, longitude, 0.18, 0.52, 0.32, 0.54, 0.42, 0.22);
        tile.setRoadDensity(0.28);
        tile.setBuildingDensityScore(0.18);
        tile.setUrbanPenaltyScore(0.24);
        tile.setDarknessScore(0.42);
        return tile;
    }

    private List<ScenicScoreTile> urbanQuietTilesAroundStart() {
        return List.of(
            urbanQuietTile("urban-1", 46.1100, -64.7800),
            urbanQuietTile("urban-2", 46.1030, -64.7500),
            urbanQuietTile("urban-3", 46.0800, -64.7420),
            urbanQuietTile("urban-4", 46.0600, -64.7700),
            urbanQuietTile("urban-5", 46.0720, -64.8120),
            urbanQuietTile("urban-6", 46.1050, -64.8250),
            urbanQuietTile("urban-7", 46.1250, -64.8000),
            urbanQuietTile("urban-8", 46.1250, -64.7500)
        );
    }

    private ScenicScoreTile urbanQuietTile(String h3Index, double latitude, double longitude) {
        ScenicScoreTile tile = scenicTile(h3Index, latitude, longitude, 0.12, 0.70, 0.25, 0.78, 0.30, 0.20);
        tile.setRoadDensity(0.90);
        tile.setBuildingDensityScore(0.92);
        tile.setUrbanPenaltyScore(0.95);
        tile.setDarknessScore(0.10);
        return tile;
    }

    private OsrmTripClient.TripResult defaultTrip(int durationMinutes) {
        return new OsrmTripClient.TripResult(
            List.of(
                new RoadNode(46.0945, -64.7809),
                new RoadNode(46.1030, -64.7600),
                new RoadNode(46.0945, -64.7809)
            ),
            12.5,
            durationMinutes
        );
    }

    private ScenicScoreTile scenicTile(String h3Index, double latitude, double longitude) {
        return scenicTile(h3Index, latitude, longitude, 0.95, 0.90, 0.80, 0.85, 0.88, 0.75);
    }

    private ScenicScoreTile scenicTile(String h3Index,
                                       double latitude,
                                       double longitude,
                                       double waterScore,
                                       double greenScore,
                                       double elevationScore,
                                       double solitudeScore,
                                       double curveScore,
                                       double poiScore) {
        GeometryFactory geometryFactory = new GeometryFactory();
        double latOffset = 0.01;
        double lngOffset = 0.01;

        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setGeometry(geometryFactory.createPolygon(new Coordinate[] {
            new Coordinate(longitude - lngOffset, latitude - latOffset),
            new Coordinate(longitude + lngOffset, latitude - latOffset),
            new Coordinate(longitude + lngOffset, latitude + latOffset),
            new Coordinate(longitude - lngOffset, latitude + latOffset),
            new Coordinate(longitude - lngOffset, latitude - latOffset)
        }));
        tile.setScenicScore(0.90);
        tile.setWaterScore(waterScore);
        tile.setGreenScore(greenScore);
        tile.setElevationScore(elevationScore);
        tile.setSolitudeScore(solitudeScore);
        tile.setCurveScore(curveScore);
        tile.setPoiScore(poiScore);
        tile.setLastScored(Instant.now());
        return tile;
    }
}
