package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.H3Utils;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteWeightCalibrationRepository;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.service.RoadSegmentAnchorService;
import com.moodride.routeworker.service.ScenicTileLookupService;
import com.moodride.routeworker.service.OsrmTripClient;
import com.moodride.routeworker.service.RouteGenerationMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlannerTest {

    @Mock
    private ScenicTileLookupService scenicTileLookupService;

    @Mock
    private RoadSegmentAnchorService roadSegmentAnchorService;

    @Mock
    private OsrmTripClient osrmTripClient;

    @Mock
    private RouteWeightCalibrationRepository routeWeightCalibrationRepository;

    @Mock
    private RouteDurationCalibrationRepository routeDurationCalibrationRepository;

    private RoutePlanner routePlanner;
    private SimpleMeterRegistry meterRegistry;


    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        routePlanner = routePlanner(testConfiguration());
        lenient().when(routeWeightCalibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of());
        lenient().when(routeDurationCalibrationRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        lenient().when(roadSegmentAnchorService.anchorFor(
            org.mockito.ArgumentMatchers.any(ScenicScoreTile.class),
            org.mockito.ArgumentMatchers.any(RoadNode.class)
        )).thenAnswer(invocation -> invocation.getArgument(1));
    }

    private ApplicationConfiguration testConfiguration() {
        ApplicationConfiguration config = new ApplicationConfiguration();
        config.setH3Resolution(9);
        config.setTileSelectionRingMin(1);
        config.setTileSelectionRingMax(2);
        config.setTileSelectionLimit(20);
        config.setSectorCount(6);
        config.setCorridorSampleMeters(500);
        config.setMaxDurationOverrunRatio(1.0);
        return config;
    }

    private RoutePlanner routePlanner(ApplicationConfiguration config) {
        return new RoutePlanner(
            scenicTileLookupService,
            roadSegmentAnchorService,
            routeWeightCalibrationRepository,
            routeDurationCalibrationRepository,
            osrmTripClient,
            config,
            new ObjectMapper(),
            new ScenicScoreCalculator(),
            new RouteGenerationMetricsService(meterRegistry)
        );
    }

    private void usePlannerWithMaxOsrmRequests(int maxOsrmRequestsPerJob) {
        ApplicationConfiguration config = testConfiguration();
        config.setMaxOsrmRequestsPerJob(maxOsrmRequestsPerJob);
        routePlanner = routePlanner(config);
    }

    private void usePlannerWithOsrmLimits(int maxOsrmRequestsPerJob,
                                          int earlyStopMinRequests,
                                          int earlyStopMinCandidates) {
        ApplicationConfiguration config = testConfiguration();
        config.setMaxOsrmRequestsPerJob(maxOsrmRequestsPerJob);
        config.setOsrmEarlyStopMinRequests(earlyStopMinRequests);
        config.setOsrmEarlyStopMinCandidates(earlyStopMinCandidates);
        routePlanner = routePlanner(config);
    }

    private void usePlannerWithOsrmLimitsAndParallelism(int maxOsrmRequestsPerJob,
                                                        int earlyStopMinRequests,
                                                        int earlyStopMinCandidates,
                                                        int osrmRequestParallelism) {
        ApplicationConfiguration config = testConfiguration();
        config.setMaxOsrmRequestsPerJob(maxOsrmRequestsPerJob);
        config.setOsrmEarlyStopMinRequests(earlyStopMinRequests);
        config.setOsrmEarlyStopMinCandidates(earlyStopMinCandidates);
        config.setOsrmRequestParallelism(osrmRequestParallelism);
        routePlanner = routePlanner(config);
    }

    private void usePlannerWithOsrmParallelism(int osrmRequestParallelism) {
        ApplicationConfiguration config = testConfiguration();
        config.setOsrmRequestParallelism(osrmRequestParallelism);
        routePlanner = routePlanner(config);
    }

    @Test
    void generateRouteRejectsMissingScenicDataForRequestedVibe() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No scenic data found near this start");
    }

    @Test
    void generateRouteRejectsOverBudgetHybridCandidatesWhenNoInBudgetOptionExists() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(22)));

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(15)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 15 minutes");
    }

    @Test
    void generateRouteThrowsWhenNoHybridCandidateCanBeProduced() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 45 minutes");
    }

    @Test
    void generateRouteOptionsReturnsThreeDistinctProfilesWhenCandidatesExist() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
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
                "corridor_urban_pressure",
                "edge_urban_pressure",
                "tree_canopy_score",
                "scenic_poi_score",
                "viewpoint_score",
                "bridge_coastal_score",
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
    void generateRouteOptionsRecordsTimingHistograms() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int durationMinutes = variant.size() * 8;
            double distanceKm = variant.size() * 2.5;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        assertThat(meterRegistry.find("moodride.route.worker.generation.stage.duration")
            .tag("stage", "total")
            .tag("outcome", "success")
            .tag("route_mode", "drive")
            .timer())
            .isNotNull()
            .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        assertThat(meterRegistry.find("moodride.route.worker.generation.stage.duration")
            .tag("stage", "primary_osrm")
            .tag("outcome", "success")
            .timer())
            .isNotNull()
            .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        assertThat(meterRegistry.find("moodride.route.worker.generation.count")
            .tag("count", "candidates")
            .tag("outcome", "success")
            .summary())
            .isNotNull()
            .satisfies(summary -> assertThat(summary.count()).isEqualTo(1));
    }

    @Test
    void generateRouteOptionsCapsColdAnchorAndOsrmWork() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(manyHighScenicTilesAroundStart(80));
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int durationMinutes = variant.size() * 8;
            double distanceKm = variant.size() * 2.5;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        verify(roadSegmentAnchorService, atMost(48)).anchorFor(any(ScenicScoreTile.class), any(RoadNode.class));
        verify(osrmTripClient, atMost(48)).requestRoundTrip(anyList(), eq(RouteMode.DRIVE));
    }

    @Test
    void generateRouteOptionsReturnsThreeOptionsWhenOsrmEvaluationIsCapped() {
        usePlannerWithMaxOsrmRequests(12);
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(manyHighScenicTilesAroundStart(80));
        AtomicInteger osrmRequests = new AtomicInteger();
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int index = osrmRequests.getAndIncrement();
            int durationMinutes = 34 + (index % 9);
            double distanceKm = 12.0 + index;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        assertThat(osrmRequests.get()).isLessThanOrEqualTo(12);
        assertThat(options.stream().map(this::pathSignature).collect(Collectors.toSet()))
            .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void generateRouteOptionsStopsBeforeConfiguredCapAfterEnoughDiverseInBudgetCandidates() {
        usePlannerWithOsrmLimits(48, 12, 9);
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(manyHighScenicTilesAroundStart(80));
        AtomicInteger osrmRequests = new AtomicInteger();
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int index = osrmRequests.getAndIncrement();
            int durationMinutes = 32 + (index % 11);
            double distanceKm = 10.0 + (index * 0.75);
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        assertThat(osrmRequests.get()).isLessThan(48);
        assertThat(options.stream().map(RouteCandidate::getEstimatedMinutes))
            .allSatisfy(minutes -> assertThat(minutes).isLessThanOrEqualTo(45));
    }

    @Test
    void intentSpecificVibesCanReturnBeforeFullOsrmCapWhenStrategySearchIsEnough() {
        usePlannerWithOsrmLimitsAndParallelism(48, 24, 9, 1);
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(manyHighScenicTilesAroundStart(80));
        AtomicInteger osrmRequests = new AtomicInteger();
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int index = osrmRequests.getAndIncrement();
            int durationMinutes = 34 + (index % 9);
            double distanceKm = 11.0 + (index * 0.5);
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45, "open_roads"));

        assertThat(options).hasSize(3);
        assertThat(osrmRequests.get()).isLessThan(48);
        assertThat(options.getFirst().getScoreBreakdown().get("geometry_strategy_code"))
            .isEqualTo(1.0);
    }

    @Test
    void openRoadsReturnsThreeOptionsForModerateOpenSpaceUrbanPressureCorridors() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenAnswer(invocation -> {
            if (repositoryCalls.getAndIncrement() == 0) {
                return highOpenRoadTilesAroundStart();
            }
            return moderateOpenRoadTilesAroundStart();
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, 48));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(60, "open_roads"));

        assertThat(options).hasSize(3);
        assertThat(options).allSatisfy(option -> {
            Map<String, Double> breakdown = option.getScoreBreakdown();
            assertThat(breakdown.get("geometry_strategy_code")).isEqualTo(1.0);
            assertThat(breakdown.get("strategy_fit_score")).isGreaterThanOrEqualTo(0.24);
            assertThat(breakdown.get("open_space_corridor_share")).isGreaterThanOrEqualTo(0.18);
            assertThat(breakdown.get("corridor_urban_pressure")).isBetween(0.55, 0.72);
        });
    }


    @Test
    void countrysideUsesGradedQuietStrategyFitForModerateCorridors() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(moderateQuietTilesAroundStart());
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
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(urbanQuietTilesAroundStart());
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
    void torontoMountainMismatchRejectsWeakCurveElevationCorridors() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenAnswer(invocation -> {
            if (repositoryCalls.getAndIncrement() == 0) {
                return highMountainTilesAroundStart();
            }
            return weakMountainCorridorTiles();
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, 48));
        });

        assertThatThrownBy(() -> routePlanner.generateRouteOptions(sampleJobAt(60, "mountain", 43.6532, -79.3832)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No strong Mountain route found");
    }

    @Test
    void openRoadsRejectsUrbanPressureEvenWhenNearbyIntentTilesExist() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenAnswer(invocation -> {
            if (repositoryCalls.getAndIncrement() == 0) {
                return highOpenRoadTilesAroundStart();
            }
            return urbanQuietTilesAroundStart();
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            return Optional.of(new OsrmTripClient.TripResult(variant, variant.size() * 2.5, 48));
        });

        assertThatThrownBy(() -> routePlanner.generateRouteOptions(sampleJob(60, "open_roads")))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No strong Open Roads route found");
    }


    @Test
    void optionSelectorCanPromoteStrategyFitOverGenericScenicPrimary() throws Exception {
        RouteOptionSelector selector = new RouteOptionSelector(1.15);
        RouteCandidate genericScenic = routeCandidateWithBreakdown(
            List.of(new RoadNode(46.0945, -64.7809), new RoadNode(46.1200, -64.7809), new RoadNode(46.0945, -64.7809)),
            0.95,
            0.12,
            0.60
        );
        RouteCandidate strategyFit = routeCandidateWithBreakdown(
            List.of(new RoadNode(46.0945, -64.7809), new RoadNode(46.0945, -64.7200), new RoadNode(46.0945, -64.7809)),
            0.70,
            0.88,
            0.02
        );
        RouteCandidate alternate = routeCandidateWithBreakdown(
            List.of(new RoadNode(46.0945, -64.7809), new RoadNode(46.0600, -64.8000), new RoadNode(46.0945, -64.7809)),
            0.72,
            0.65,
            0.08
        );
        java.util.function.ToDoubleFunction<RouteCandidate> openRoadPrimaryScorer =
            primaryRouteScorerFor("OPEN_SPACE_ESCAPE", 60);

        List<RouteCandidate> options = selector.selectRouteOptions(
            List.of(genericScenic, strategyFit, alternate),
            60,
            openRoadPrimaryScorer
        );

        assertThat(options).hasSize(3);
        assertThat(genericScenic.getTotalScenicScore()).isGreaterThan(strategyFit.getTotalScenicScore());
        assertThat(strategyFit.getScoreBreakdown().get("strategy_fit_score"))
            .isGreaterThan(genericScenic.getScoreBreakdown().get("strategy_fit_score"));
        assertThat(options.getFirst()).isSameAs(strategyFit);
    }


    @Test
    void backtrackingMetricsPenalizeOutAndBackMoreThanRealLoop() throws Exception {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());

        Map<String, Double> outAndBack = scoreBreakdownForPath(outAndBackPath());
        Map<String, Double> loop = scoreBreakdownForPath(realLoopPath());

        assertThat(outAndBack.get("backtracking_penalty")).isGreaterThan(loop.get("backtracking_penalty"));
        assertThat(outAndBack.get("reverse_overlap_share")).isGreaterThan(loop.get("reverse_overlap_share"));
        assertThat(outAndBack.get("leg_separation_score")).isLessThan(loop.get("leg_separation_score"));
    }

    @Test
    void shorterProfilePrefersUsefulShortRouteInsteadOfTinyRescueLoop() {
        usePlannerWithOsrmParallelism(1);
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
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
        usePlannerWithOsrmParallelism(1);
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(highScenicTilesAroundStart());
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
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenReturn(lowWaterTilesAroundStart());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No strong Coastal route found");
    }

    @Test
    void generateRouteFallsBackToDefaultH3ResolutionForScenicScoring() {
        when(scenicTileLookupService.findByH3Indexes(anyCollection())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> indexes = invocation.getArgument(0);
            if (!includesDefaultH3Resolution(indexes)) {
                return List.of();
            }

            return highScenicTilesAroundStart();
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(25)));

        RouteCandidate candidate = routePlanner.generateRoute(sampleJob(45));

        assertThat(candidate.getTotalScenicScore()).isGreaterThan(0.30);
        verify(scenicTileLookupService, atLeastOnce()).findByH3Indexes(anyCollection());
    }

    private RouteJob sampleJob(int timeBudgetMinutes) {
        return sampleJob(timeBudgetMinutes, "coastal");
    }

    private RouteJob sampleJob(int timeBudgetMinutes, String vibe) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 46.0945, -64.7809, timeBudgetMinutes, vibe);
        job.setId(UUID.randomUUID());
        return job;
    }

    private RouteJob sampleJobAt(int timeBudgetMinutes, String vibe, double latitude, double longitude) {
        RouteJob job = new RouteJob(UUID.randomUUID(), latitude, longitude, timeBudgetMinutes, vibe);
        job.setId(UUID.randomUUID());
        return job;
    }

    private boolean includesDefaultH3Resolution(Collection<String> indexes) {
        return indexes.stream()
            .anyMatch(index -> H3Utils.getResolution(index) == H3Utils.DEFAULT_RESOLUTION);
    }

    private RouteCandidate routeCandidateWithBreakdown(List<RoadNode> path,
                                                       double scenicScore,
                                                       double strategyFit,
                                                       double strategyMismatchPenalty) {
        return new RouteCandidate(
            path,
            scenicScore,
            18.0,
            52,
            "hybrid_osrm_v2",
            null,
            Map.of(
                "strategy_fit_score", strategyFit,
                "strategy_mismatch_penalty", strategyMismatchPenalty,
                "backtracking_penalty", 0.0
            )
        );
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private java.util.function.ToDoubleFunction<RouteCandidate> primaryRouteScorerFor(String geometryStrategyName,
                                                                                      int targetMinutes) throws Exception {
        Class<?> strategyClass = Class.forName("com.moodride.routeworker.algorithm.RoutePlanner$GeometryStrategy");
        Object strategy = Enum.valueOf((Class<Enum>) strategyClass.asSubclass(Enum.class), geometryStrategyName);
        Method scorerMethod = RoutePlanner.class.getDeclaredMethod("primaryRouteScorer", strategyClass, int.class);
        scorerMethod.setAccessible(true);
        return (java.util.function.ToDoubleFunction<RouteCandidate>) scorerMethod.invoke(routePlanner, strategy, targetMinutes);
    }


    @SuppressWarnings("unchecked")
    private Map<String, Double> scoreBreakdownForPath(List<RoadNode> path) throws Exception {
        Method scoreMethod = RoutePlanner.class.getDeclaredMethod(
            "computeHybridV2RouteScore",
            List.class,
            PreferenceWeights.class,
            VibeCatalog.BlendedVibeProfile.class,
            int.class,
            int.class,
            Class.forName("com.moodride.routeworker.algorithm.RoutePlanner$GeometryStrategy")
        );
        scoreMethod.setAccessible(true);
        Object result = scoreMethod.invoke(
            routePlanner,
            path,
            new PreferenceWeights(0.65, 0.70, 0.55, 0.65, 0.50, 0.25).normalized(),
            VibeCatalog.blendProfiles(List.of("scenic")),
            60,
            45,
            null
        );
        Method breakdownMethod = result.getClass().getDeclaredMethod("breakdown");
        breakdownMethod.setAccessible(true);
        return (Map<String, Double>) breakdownMethod.invoke(result);
    }

    private List<RoadNode> outAndBackPath() {
        return List.of(
            new RoadNode(46.0945, -64.7809),
            new RoadNode(46.0990, -64.7700),
            new RoadNode(46.1030, -64.7600),
            new RoadNode(46.1080, -64.7470),
            new RoadNode(46.1120, -64.7350),
            new RoadNode(46.1180, -64.7220),
            new RoadNode(46.1230, -64.7100),
            new RoadNode(46.1180, -64.7220),
            new RoadNode(46.1120, -64.7350),
            new RoadNode(46.1080, -64.7470),
            new RoadNode(46.1030, -64.7600),
            new RoadNode(46.0990, -64.7700),
            new RoadNode(46.0945, -64.7809)
        );
    }

    private List<RoadNode> realLoopPath() {
        return List.of(
            new RoadNode(46.0945, -64.7809),
            new RoadNode(46.1010, -64.7700),
            new RoadNode(46.1080, -64.7600),
            new RoadNode(46.1160, -64.7650),
            new RoadNode(46.1220, -64.7740),
            new RoadNode(46.1210, -64.7900),
            new RoadNode(46.1150, -64.8050),
            new RoadNode(46.1060, -64.8120),
            new RoadNode(46.0980, -64.8150),
            new RoadNode(46.0940, -64.7980),
            new RoadNode(46.0945, -64.7809)
        );
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

    private List<ScenicScoreTile> manyHighScenicTilesAroundStart(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> {
                double angle = (Math.PI * 2.0 * index) / Math.max(1, count);
                double radius = 0.015 + (0.002 * (index % 12));
                return scenicTile(
                    "bulk-" + index,
                    46.0945 + (Math.sin(angle) * radius),
                    -64.7809 + (Math.cos(angle) * radius)
                );
            })
            .toList();
    }

    private List<ScenicScoreTile> moderateOpenRoadTilesAroundStart() {
        return List.of(
            moderateOpenRoadTile("moderate-open-1", 46.1100, -64.7800),
            moderateOpenRoadTile("moderate-open-2", 46.1030, -64.7500),
            moderateOpenRoadTile("moderate-open-3", 46.0800, -64.7420),
            moderateOpenRoadTile("moderate-open-4", 46.0600, -64.7700),
            moderateOpenRoadTile("moderate-open-5", 46.0720, -64.8120),
            moderateOpenRoadTile("moderate-open-6", 46.1050, -64.8250),
            moderateOpenRoadTile("moderate-open-7", 46.1250, -64.8000),
            moderateOpenRoadTile("moderate-open-8", 46.1250, -64.7500)
        );
    }

    private ScenicScoreTile moderateOpenRoadTile(String h3Index, double latitude, double longitude) {
        ScenicScoreTile tile = scenicTile(h3Index, latitude, longitude, 0.10, 0.58, 0.30, 0.62, 0.38, 0.22);
        tile.setRoadDensity(0.60);
        tile.setBuildingDensityScore(0.58);
        tile.setUrbanPenaltyScore(0.60);
        tile.setRoadStressScore(0.18);
        tile.setDarknessScore(0.42);
        return tile;
    }

    private List<ScenicScoreTile> lowWaterTilesAroundStart() {
        return List.of(
            scenicTile("dry-1", 46.1100, -64.7800, 0.05, 0.70, 0.45, 0.70, 0.55, 0.20),
            scenicTile("dry-2", 46.1030, -64.7500, 0.07, 0.75, 0.40, 0.72, 0.55, 0.20),
            scenicTile("dry-3", 46.0800, -64.7420, 0.04, 0.65, 0.35, 0.68, 0.50, 0.20),
            scenicTile("dry-4", 46.0600, -64.7700, 0.06, 0.72, 0.45, 0.76, 0.50, 0.20)
        );
    }

    private List<ScenicScoreTile> highMountainTilesAroundStart() {
        return List.of(
            scenicTile("mountain-1", 43.6700, -79.3900, 0.20, 0.70, 0.90, 0.65, 0.88, 0.15),
            scenicTile("mountain-2", 43.6600, -79.3600, 0.18, 0.68, 0.92, 0.62, 0.86, 0.14),
            scenicTile("mountain-3", 43.6400, -79.3500, 0.16, 0.66, 0.88, 0.60, 0.90, 0.12),
            scenicTile("mountain-4", 43.6250, -79.3800, 0.15, 0.72, 0.86, 0.64, 0.84, 0.16),
            scenicTile("mountain-5", 43.6400, -79.4200, 0.20, 0.74, 0.91, 0.66, 0.89, 0.12),
            scenicTile("mountain-6", 43.6750, -79.4300, 0.17, 0.70, 0.89, 0.63, 0.87, 0.14)
        );
    }

    private List<ScenicScoreTile> weakMountainCorridorTiles() {
        return List.of(
            scenicTile("flat-1", 43.6700, -79.3900, 0.26, 0.52, 0.30, 0.50, 0.24, 0.25),
            scenicTile("flat-2", 43.6600, -79.3600, 0.24, 0.48, 0.28, 0.48, 0.22, 0.24),
            scenicTile("flat-3", 43.6400, -79.3500, 0.22, 0.50, 0.31, 0.46, 0.20, 0.23),
            scenicTile("flat-4", 43.6250, -79.3800, 0.25, 0.49, 0.27, 0.47, 0.21, 0.24),
            scenicTile("flat-5", 43.6400, -79.4200, 0.23, 0.51, 0.29, 0.49, 0.23, 0.22),
            scenicTile("flat-6", 43.6750, -79.4300, 0.24, 0.50, 0.30, 0.50, 0.22, 0.23)
        );
    }

    private List<ScenicScoreTile> highOpenRoadTilesAroundStart() {
        return List.of(
            openRoadTile("open-1", 46.1100, -64.7800),
            openRoadTile("open-2", 46.1030, -64.7500),
            openRoadTile("open-3", 46.0800, -64.7420),
            openRoadTile("open-4", 46.0600, -64.7700),
            openRoadTile("open-5", 46.0720, -64.8120),
            openRoadTile("open-6", 46.1050, -64.8250),
            openRoadTile("open-7", 46.1250, -64.8000),
            openRoadTile("open-8", 46.1250, -64.7500)
        );
    }

    private ScenicScoreTile openRoadTile(String h3Index, double latitude, double longitude) {
        ScenicScoreTile tile = scenicTile(h3Index, latitude, longitude, 0.12, 0.56, 0.30, 0.92, 0.32, 0.05);
        tile.setRoadDensity(0.12);
        tile.setBuildingDensityScore(0.08);
        tile.setUrbanPenaltyScore(0.10);
        tile.setDarknessScore(0.50);
        return tile;
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
