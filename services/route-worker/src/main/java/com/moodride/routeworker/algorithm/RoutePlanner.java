package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.uber.h3core.util.LatLng;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.RouteDurationCalibration;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.RouteWeightCalibration;
import com.moodride.datamodels.scoring.ComponentScores;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.H3Utils;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.repository.RouteWeightCalibrationRepository;
import com.moodride.routeworker.service.OsrmTripClient;
import com.moodride.routeworker.service.RouteGenerationMetricsService;
import com.moodride.routeworker.service.RoadSegmentAnchorService;
import com.moodride.routeworker.service.ScenicTileLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.moodride.routeworker.algorithm.RouteGeometry.bearingDegrees;
import static com.moodride.routeworker.algorithm.RouteGeometry.clamp;
import static com.moodride.routeworker.algorithm.RouteGeometry.clamp01;
import static com.moodride.routeworker.algorithm.RouteGeometry.distanceKm;
import static com.moodride.routeworker.algorithm.RouteGeometry.estimateCurvatureScore;
import static com.moodride.routeworker.algorithm.RouteGeometry.normalizeLongitude;
import static com.moodride.routeworker.algorithm.RouteGeometry.samplePath;

@Service
public class RoutePlanner {

    private static final Logger logger = LoggerFactory.getLogger(RoutePlanner.class);
    private static final int DEFAULT_H3_RESOLUTION = H3Utils.DEFAULT_RESOLUTION;
    private static final int SAMPLE_NEIGHBOR_EXPANSION_RING = 2;
    private static final double DEFAULT_SCENIC_FALLBACK = 0.35;
    private static final int ROUTE_OPTION_COUNT = 3;
    private static final int MIN_VIBE_AVAILABILITY_TILES = 3;
    private static final int VIBE_AVAILABILITY_TOP_N = 12;
    private static final double MIN_VIBE_BEST_FIT_SCORE = 0.32;
    private static final double MIN_VIBE_AVG_FIT_SCORE = 0.26;
    private static final int TARGET_ANCHOR_LIMIT = 14;
    private static final int TARGET_ANCHOR_PAIR_LIMIT = 24;
    private static final double TARGET_ANCHOR_MIN_SEPARATION_KM = 2.0;
    private static final double V2_LANDSCAPE_WEIGHT = 0.38;
    private static final double V2_VIBE_FIT_WEIGHT = 0.24;
    private static final double V2_DRIVE_QUALITY_WEIGHT = 0.14;
    private static final double V2_ROUTE_SHAPE_WEIGHT = 0.10;
    private static final double V2_SCENIC_MOMENTS_WEIGHT = 0.14;
    private static final double V2_URBAN_PENALTY_WEIGHT = 0.10;
    private static final double V2_START_END_PENALTY_WEIGHT = 0.06;
    private static final double V2_STRATEGY_MISMATCH_PENALTY_WEIGHT = 0.08;
    private static final double V2_BACKTRACKING_PENALTY_WEIGHT = 0.08;
    private static final double V2_CONTINUITY_THRESHOLD = 0.45;
    private static final int V2_EDGE_SAMPLE_COUNT = 4;
    private static final int STRATEGY_ANCHOR_LIMIT = 12;
    private static final int STRATEGY_PAIR_LIMIT = 18;
    private static final double STRATEGY_ANCHOR_MIN_SEPARATION_KM = 1.6;
    private static final double STRICT_MOUNTAIN_STRATEGY_MIN_FIT = 0.32;
    private static final double STRICT_MOUNTAIN_MIN_CURVE_ELEVATION_SHARE = 0.28;
    private static final double STRICT_OPEN_SPACE_STRATEGY_MIN_FIT = 0.24;
    private static final double STRICT_OPEN_SPACE_MAX_URBAN_PRESSURE = 0.72;
    private static final double STRICT_OPEN_SPACE_MIN_OPEN_SHARE = 0.18;
    private static final double STRICT_LOW_PRESSURE_STRATEGY_MIN_FIT = 0.30;
    private static final double STRICT_LOW_PRESSURE_MAX_URBAN_PRESSURE = 0.58;
    private static final double STRICT_LOW_PRESSURE_MIN_QUIET_SHARE = 0.32;
    private static final int DURATION_CALIBRATION_H3_RESOLUTION = 5;
    private static final int MIN_DURATION_CALIBRATION_SAMPLES = 3;
    private static final double MIN_RADIUS_MULTIPLIER = 0.75;
    private static final double MAX_RADIUS_MULTIPLIER = 1.25;
    private static final int MIN_LEARNED_WAYPOINT_COUNT = 2;
    private static final int MAX_LEARNED_WAYPOINT_COUNT = 10;

    private static final List<Integer> WAYPOINT_VARIANTS = List.of(8, 6, 4);
    private static final Map<String, String> PREFERENCE_KEY_ALIASES = Map.of(
        "water", "water",
        "greenery", "greenery",
        "green", "greenery",
        "elevation", "elevation",
        "solitude", "solitude",
        "curves", "curves",
        "curve", "curves",
        "poi", "poi"
    );

    private final ScenicTileLookupService scenicTileLookupService;
    private final RoadSegmentAnchorService roadSegmentAnchorService;
    private final RouteWeightCalibrationRepository routeWeightCalibrationRepository;
    private final RouteDurationCalibrationRepository routeDurationCalibrationRepository;
    private final OsrmTripClient osrmTripClient;
    private final ApplicationConfiguration config;
    private final ObjectMapper objectMapper;
    private final ScenicScoreCalculator scenicScoreCalculator;
    private final RouteOptionSelector routeOptionSelector;
    private final RouteCraftAnalyzer routeCraftAnalyzer;
    private final RouteGenerationMetricsService routeGenerationMetricsService;

    public RoutePlanner(ScenicTileLookupService scenicTileLookupService,
                        RoadSegmentAnchorService roadSegmentAnchorService,
                        RouteWeightCalibrationRepository routeWeightCalibrationRepository,
                        RouteDurationCalibrationRepository routeDurationCalibrationRepository,
                        OsrmTripClient osrmTripClient,
                        ApplicationConfiguration config,
                        ObjectMapper objectMapper,
                        ScenicScoreCalculator scenicScoreCalculator,
                        RouteGenerationMetricsService routeGenerationMetricsService) {
        this.scenicTileLookupService = scenicTileLookupService;
        this.roadSegmentAnchorService = roadSegmentAnchorService;
        this.routeWeightCalibrationRepository = routeWeightCalibrationRepository;
        this.routeDurationCalibrationRepository = routeDurationCalibrationRepository;
        this.osrmTripClient = osrmTripClient;
        this.config = config;
        this.objectMapper = objectMapper;
        this.scenicScoreCalculator = scenicScoreCalculator;
        this.routeOptionSelector = new RouteOptionSelector(config.getMaxDurationOverrunRatio());
        this.routeCraftAnalyzer = new RouteCraftAnalyzer();
        this.routeGenerationMetricsService = routeGenerationMetricsService;
    }

    public RouteCandidate generateRoute(RouteJob job) {
        return generateRouteOptions(job).getFirst();
    }

    private void validateConfiguredRouteMode(RouteJob job) {
        String requestedMode = job.getRouteMode().apiValue();
        String configuredMode = config.getMode();
        if (!requestedMode.equals(configuredMode)) {
            throw new IllegalArgumentException(
                "Route job mode '" + requestedMode
                    + "' does not match configured worker mode '" + configuredMode + "'"
            );
        }
    }

    public List<RouteCandidate> generateRouteOptions(RouteJob job) {
        validateConfiguredRouteMode(job);
        long generationStartedNanos = System.nanoTime();
        RoadNode start = new RoadNode(job.getStartLatitude(), job.getStartLongitude());
        List<String> requestVibes = resolveJobVibes(job);
        VibeCatalog.BlendedVibeProfile vibeProfile = VibeCatalog.blendProfiles(requestVibes);
        PreferenceWeights preferences = resolvePreferenceWeights(requestVibes, job.getPreferenceVector());
        GeometryStrategy geometryStrategy = resolveGeometryStrategy(vibeProfile);
        DurationCalibrationHint durationCalibration = resolveDurationCalibrationHint(start, job, geometryStrategy);

        long stageStartedNanos = System.nanoTime();
        List<TileCandidate> scoredTiles = scoreNearbyTiles(
            job.getId(),
            start,
            job.getTimeBudgetMinutes(),
            preferences,
            vibeProfile,
            durationCalibration,
            job.getRouteMode(),
            geometryStrategy
        );
        long tileScoringMs = elapsedMillis(stageStartedNanos);
        validateVibeAvailability(job, requestVibes, vibeProfile, scoredTiles);

        stageStartedNanos = System.nanoTime();
        List<List<RoadNode>> waypointVariants = buildHybridV2WaypointRings(
            start,
            scoredTiles,
            job.getTimeBudgetMinutes(),
            vibeProfile,
            geometryStrategy,
            durationCalibration
        );
        long variantBuildMs = elapsedMillis(stageStartedNanos);

        stageStartedNanos = System.nanoTime();
        List<RouteCandidate> hybridCandidates = collectHybridCandidates(job, waypointVariants, preferences, vibeProfile, geometryStrategy);
        long primaryOsrmMs = elapsedMillis(stageStartedNanos);
        long rescueMs = 0L;

        if (hybridCandidates.isEmpty() || !routeOptionSelector.hasDiverseCandidatePool(hybridCandidates, job.getTimeBudgetMinutes())) {
            stageStartedNanos = System.nanoTime();
            List<List<RoadNode>> syntheticVariants = buildSyntheticWaypointRings(start, job.getTimeBudgetMinutes());
            List<RouteCandidate> syntheticCandidates = collectHybridCandidates(job, syntheticVariants, preferences, vibeProfile, geometryStrategy);
            rescueMs += elapsedMillis(stageStartedNanos);
            if (hybridCandidates.isEmpty()) {
                hybridCandidates = syntheticCandidates;
            } else if (!syntheticCandidates.isEmpty()) {
                hybridCandidates = routeOptionSelector.combineCandidates(hybridCandidates, syntheticCandidates);
            }
            if (!syntheticCandidates.isEmpty()) {
                logger.info("Hybrid routing used synthetic waypoint variants for job {}", job.getId());
            }
        }
        if (hybridCandidates.size() < ROUTE_OPTION_COUNT || routeOptionSelector.needsBudgetRescue(hybridCandidates, job.getTimeBudgetMinutes())) {
            stageStartedNanos = System.nanoTime();
            List<List<RoadNode>> budgetRescueVariants = buildBudgetRescueWaypointRings(start, job.getTimeBudgetMinutes());
            List<RouteCandidate> budgetRescueCandidates = collectHybridCandidates(job, budgetRescueVariants, preferences, vibeProfile, geometryStrategy);
            rescueMs += elapsedMillis(stageStartedNanos);
            if (!budgetRescueCandidates.isEmpty()) {
                hybridCandidates = routeOptionSelector.combineCandidates(hybridCandidates, budgetRescueCandidates);
                logger.info("Hybrid routing used budget-rescue waypoint variants for job {}", job.getId());
            }
        }
        if (!hybridCandidates.isEmpty()) {
            stageStartedNanos = System.nanoTime();
            List<RouteCandidate> differentiated = routeOptionSelector.differentiateFlatScenicScores(hybridCandidates, job.getTimeBudgetMinutes());
            List<RouteCandidate> candidatePool = contractCandidatePool(differentiated, vibeProfile, geometryStrategy, requestVibes, job);
            List<RouteCandidate> selected = routeOptionSelector.selectRouteOptions(candidatePool, job.getTimeBudgetMinutes(), primaryRouteScorer(geometryStrategy, job.getTimeBudgetMinutes()));
            if (selected.size() >= ROUTE_OPTION_COUNT && routeOptionSelector.minRouteSeparationKm(selected) < RouteOptionSelector.ROUTE_OPTION_MIN_SEPARATION_KM) {
                long diversityStartedNanos = System.nanoTime();
                List<RouteCandidate> rescueCandidates = collectHybridCandidates(
                    job,
                    buildDiversityRescueWaypointRings(start, job.getTimeBudgetMinutes()),
                    preferences,
                    vibeProfile,
                    geometryStrategy
                );
                rescueMs += elapsedMillis(diversityStartedNanos);
                if (!rescueCandidates.isEmpty()) {
                    List<RouteCandidate> expanded = routeOptionSelector.differentiateFlatScenicScores(
                        routeOptionSelector.combineCandidates(differentiated, rescueCandidates),
                        job.getTimeBudgetMinutes()
                    );
                    List<RouteCandidate> expandedPool = contractCandidatePool(expanded, vibeProfile, geometryStrategy, requestVibes, job);
                    selected = routeOptionSelector.selectRouteOptions(expandedPool, job.getTimeBudgetMinutes(), primaryRouteScorer(geometryStrategy, job.getTimeBudgetMinutes()));
                    logger.info("Hybrid routing used diversity-rescue waypoint variants for job {}", job.getId());
                }
            }
            long selectionMs = elapsedMillis(stageStartedNanos);
            long totalMs = elapsedMillis(generationStartedNanos);
            String outcome = requiresStrictContractOptions(vibeProfile, geometryStrategy) && selected.size() < ROUTE_OPTION_COUNT
                ? "no_feasible_route"
                : "success";
            routeGenerationMetricsService.record(new RouteGenerationMetricsService.RouteGenerationMetrics(
                job.getRouteMode(),
                geometryStrategy,
                outcome,
                totalMs,
                tileScoringMs,
                variantBuildMs,
                primaryOsrmMs,
                rescueMs,
                selectionMs,
                scoredTiles.size(),
                waypointVariants.size(),
                hybridCandidates.size(),
                selected.size()
            ));
            logger.info(
                "Route job {} generation timings totalMs={} tileScoringMs={} variantBuildMs={} primaryOsrmMs={} rescueMs={} selectionMs={} scoredTiles={} waypointVariants={} candidates={} selected={} strategy={}",
                job.getId(),
                totalMs,
                tileScoringMs,
                variantBuildMs,
                primaryOsrmMs,
                rescueMs,
                selectionMs,
                scoredTiles.size(),
                waypointVariants.size(),
                hybridCandidates.size(),
                selected.size(),
                geometryStrategy
            );
            if ("no_feasible_route".equals(outcome)) {
                throw noStrongStrategyRoute(requestVibes, job);
            }
            return selected;
        }

        long totalMs = elapsedMillis(generationStartedNanos);
        routeGenerationMetricsService.record(new RouteGenerationMetricsService.RouteGenerationMetrics(
            job.getRouteMode(),
            geometryStrategy,
            "no_feasible_route",
            totalMs,
            tileScoringMs,
            variantBuildMs,
            primaryOsrmMs,
            rescueMs,
            0L,
            scoredTiles.size(),
            waypointVariants.size(),
            0,
            0
        ));
        logger.info(
            "Route job {} generation timings totalMs={} tileScoringMs={} variantBuildMs={} primaryOsrmMs={} rescueMs={} selectionMs=0 scoredTiles={} waypointVariants={} candidates=0 selected=0 strategy={}",
            job.getId(),
            elapsedMillis(generationStartedNanos),
            tileScoringMs,
            variantBuildMs,
            primaryOsrmMs,
            rescueMs,
            scoredTiles.size(),
            waypointVariants.size(),
            geometryStrategy
        );
        int maxAllowedMinutes = routeOptionSelector.maxAllowedMinutes(job.getTimeBudgetMinutes());
        throw new NoFeasibleRouteException(
            "No feasible route found within " + maxAllowedMinutes
                + " minutes for requested " + job.getTimeBudgetMinutes()
                + "-minute budget. Try a larger time budget or a different start area."
        );
    }

    private List<RouteCandidate> collectHybridCandidates(RouteJob job,
                                                         List<List<RoadNode>> waypointVariants,
                                                         PreferenceWeights preferences,
                                                         VibeCatalog.BlendedVibeProfile vibeProfile,
                                                         GeometryStrategy geometryStrategy) {
        List<RouteCandidate> candidates = new ArrayList<>();
        int maxAllowedMinutes = routeOptionSelector.maxAllowedMinutes(job.getTimeBudgetMinutes());
        int requestLimit = Math.max(ROUTE_OPTION_COUNT, config.getMaxOsrmRequestsPerJob());
        int parallelism = Math.max(1, Math.min(requestLimit, config.getOsrmRequestParallelism()));
        AtomicInteger attemptedRequests = new AtomicInteger();
        long evaluationStartedNanos = System.nanoTime();
        boolean earlyStopped = false;

        if (parallelism == 1) {
            for (List<RoadNode> variant : waypointVariants) {
                if (attemptedRequests.get() >= requestLimit) {
                    break;
                }
                attemptedRequests.incrementAndGet();
                RouteCandidate candidate = solveVariant(job, variant, preferences, vibeProfile, geometryStrategy);
                if (candidate != null) {
                    candidates.add(candidate);
                }
                if (shouldStopOsrmEarly(candidates, attemptedRequests.get(), job.getTimeBudgetMinutes(), vibeProfile, geometryStrategy)) {
                    earlyStopped = true;
                    break;
                }
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(parallelism);
            try {
                int variantIndex = 0;
                while (variantIndex < waypointVariants.size() && attemptedRequests.get() < requestLimit) {
                    int remainingRequestBudget = requestLimit - attemptedRequests.get();
                    int batchSize = Math.min(parallelism, Math.min(remainingRequestBudget, waypointVariants.size() - variantIndex));
                    List<CompletableFuture<RouteCandidate>> futures = new ArrayList<>(batchSize);
                    for (int i = 0; i < batchSize; i++) {
                        List<RoadNode> variant = waypointVariants.get(variantIndex++);
                        attemptedRequests.incrementAndGet();
                        futures.add(CompletableFuture.supplyAsync(
                            () -> solveVariant(job, variant, preferences, vibeProfile, geometryStrategy),
                            executor
                        ));
                    }
                    for (CompletableFuture<RouteCandidate> future : futures) {
                        RouteCandidate candidate = future.join();
                        if (candidate != null) {
                            candidates.add(candidate);
                        }
                    }
                    if (shouldStopOsrmEarly(candidates, attemptedRequests.get(), job.getTimeBudgetMinutes(), vibeProfile, geometryStrategy)) {
                        earlyStopped = true;
                        break;
                    }
                }
            } finally {
                executor.shutdown();
            }
        }

        long evaluationMs = elapsedMillis(evaluationStartedNanos);
        if (waypointVariants.size() > attemptedRequests.get()) {
            logger.info(
                "Route job {} stopped OSRM evaluation at {} of {} waypoint variant(s) parallelism={} earlyStopped={} elapsedMs={}",
                job.getId(),
                attemptedRequests.get(),
                waypointVariants.size(),
                parallelism,
                earlyStopped,
                evaluationMs
            );
        } else {
            logger.info(
                "Route job {} evaluated {} OSRM waypoint variant(s) parallelism={} earlyStopped={} elapsedMs={}",
                job.getId(),
                attemptedRequests.get(),
                parallelism,
                earlyStopped,
                evaluationMs
            );
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RouteCandidate> deduplicated = routeOptionSelector.deduplicateCandidates(candidates);
        List<RouteCandidate> inBudget = deduplicated.stream()
            .filter(candidate -> candidate.getEstimatedMinutes() <= maxAllowedMinutes)
            .toList();
        if (!inBudget.isEmpty()) {
            return inBudget;
        }

        RouteCandidate shortestOverBudget = deduplicated.stream()
            .min(Comparator.comparingInt(RouteCandidate::getEstimatedMinutes))
            .orElse(null);
        if (shortestOverBudget != null) {
            logger.warn(
                "Hybrid routing discarded {} over-budget candidate(s) for job {} (allowed {} min, shortest {} min)",
                deduplicated.size(),
                job.getId(),
                maxAllowedMinutes,
                shortestOverBudget.getEstimatedMinutes()
            );
        }

        return List.of();
    }

    private RouteCandidate solveVariant(RouteJob job,
                                        List<RoadNode> variant,
                                        PreferenceWeights preferences,
                                        VibeCatalog.BlendedVibeProfile vibeProfile,
                                        GeometryStrategy geometryStrategy) {
        var result = osrmTripClient.requestRoundTrip(variant, job.getRouteMode());
        if (result.isEmpty()) {
            return null;
        }

        var trip = result.get();
        RouteScoreResult routeScore = computeHybridV2RouteScore(
            trip.path(),
            preferences,
            vibeProfile,
            job.getTimeBudgetMinutes(),
            trip.durationMinutes(),
            geometryStrategy
        );
        Map<String, Double> scoreBreakdown = withDurationCalibrationBreakdown(
            routeScore.breakdown(),
            variant,
            startNode(job),
            job.getTimeBudgetMinutes(),
            trip.durationMinutes()
        );
        return new RouteCandidate(
            trip.path(),
            routeScore.finalScore(),
            trip.totalDistanceKm(),
            trip.durationMinutes(),
            config.getProfile(),
            null,
            scoreBreakdown
        );
    }

    private boolean shouldStopOsrmEarly(List<RouteCandidate> candidates,
                                        int attemptedRequests,
                                        int targetMinutes,
                                        VibeCatalog.BlendedVibeProfile vibeProfile,
                                        GeometryStrategy geometryStrategy) {
        int minRequests = Math.max(ROUTE_OPTION_COUNT, config.getOsrmEarlyStopMinRequests());
        if (geometryStrategy != GeometryStrategy.BALANCED_VARIETY) {
            minRequests = Math.max(minRequests, 24);
        }
        int minCandidates = Math.max(ROUTE_OPTION_COUNT, config.getOsrmEarlyStopMinCandidates());
        if (!config.isOsrmEarlyStopEnabled()
            || attemptedRequests < minRequests
            || candidates.size() < minCandidates) {
            return false;
        }

        int maxAllowedMinutes = routeOptionSelector.maxAllowedMinutes(targetMinutes);
        List<RouteCandidate> deduplicated = routeOptionSelector.deduplicateCandidates(candidates);
        List<RouteCandidate> eligibleCandidates = deduplicated.stream()
            .filter(candidate -> candidate.getEstimatedMinutes() <= maxAllowedMinutes)
            .filter(candidate -> !requiresStrictContractOptions(vibeProfile, geometryStrategy)
                || satisfiesStrictContract(candidate, vibeProfile, geometryStrategy))
            .toList();
        if (eligibleCandidates.size() < ROUTE_OPTION_COUNT || routeOptionSelector.needsBudgetRescue(eligibleCandidates, targetMinutes)) {
            return false;
        }

        List<RouteCandidate> selected = routeOptionSelector.selectRouteOptions(eligibleCandidates, targetMinutes, primaryRouteScorer(geometryStrategy, targetMinutes));
        return selected.size() >= ROUTE_OPTION_COUNT
            && routeOptionSelector.minRouteSeparationKm(selected) >= RouteOptionSelector.ROUTE_OPTION_MIN_SEPARATION_KM;
    }

    private ToDoubleFunction<RouteCandidate> primaryRouteScorer(GeometryStrategy geometryStrategy, int targetMinutes) {
        GeometryStrategy activeStrategy = geometryStrategy == null ? GeometryStrategy.BALANCED_VARIETY : geometryStrategy;
        if (activeStrategy == GeometryStrategy.BALANCED_VARIETY) {
            return candidate -> candidate.getTotalScenicScore();
        }
        return candidate -> {
            Map<String, Double> breakdown = candidate.getScoreBreakdown();
            double strategyFit = breakdownValue(breakdown, "strategy_fit_score", 0.0);
            double mismatchPenalty = breakdownValue(breakdown, "strategy_mismatch_penalty", 0.0);
            double budgetFit = 1.0 - clamp01(Math.abs(candidate.getEstimatedMinutes() - targetMinutes) / (double) Math.max(1, targetMinutes));
            double durationUse = clamp01(candidate.getEstimatedMinutes() / (double) Math.max(1, targetMinutes));
            return (strategyFit * 0.42)
                + (candidate.getTotalScenicScore() * 0.32)
                + (budgetFit * 0.14)
                + (durationUse * 0.08)
                - (mismatchPenalty * 0.18);
        };
    }

    private DurationCalibrationHint resolveDurationCalibrationHint(RoadNode start,
                                                                   RouteJob job,
                                                                   GeometryStrategy geometryStrategy) {
        if (routeDurationCalibrationRepository == null || start == null || job == null) {
            return DurationCalibrationHint.empty();
        }

        String routeMode = job.getRouteMode().apiValue();
        String regionKey = durationCalibrationRegionKey(start);
        int timeBudgetBucket = RouteDurationCalibration.bucketMinutes(job.getTimeBudgetMinutes());
        String strategy = geometryStrategyName(geometryStrategy);
        String calibrationId = RouteDurationCalibration.idFor(routeMode, regionKey, timeBudgetBucket, strategy);
        return routeDurationCalibrationRepository.findById(calibrationId)
            .filter(calibration -> calibration.getSampleCount() >= MIN_DURATION_CALIBRATION_SAMPLES)
            .map(calibration -> new DurationCalibrationHint(
                clamp(calibration.getRadiusMultiplier(), MIN_RADIUS_MULTIPLIER, MAX_RADIUS_MULTIPLIER),
                clamp(calibration.getLearnedWaypointCount(), MIN_LEARNED_WAYPOINT_COUNT, MAX_LEARNED_WAYPOINT_COUNT),
                calibration.getSampleCount()
            ))
            .orElse(DurationCalibrationHint.empty());
    }

    private String durationCalibrationRegionKey(RoadNode start) {
        return H3Utils.getH3Index(
            start.getLatitude(),
            start.getLongitude(),
            DURATION_CALIBRATION_H3_RESOLUTION
        );
    }

    private String geometryStrategyName(GeometryStrategy geometryStrategy) {
        GeometryStrategy activeStrategy = geometryStrategy == null ? GeometryStrategy.BALANCED_VARIETY : geometryStrategy;
        return activeStrategy.name();
    }

    private List<RouteCandidate> contractCandidatePool(List<RouteCandidate> candidates,
                                                       VibeCatalog.BlendedVibeProfile vibeProfile,
                                                       GeometryStrategy geometryStrategy,
                                                       List<String> requestVibes,
                                                       RouteJob job) {
        if (!requiresStrictContractOptions(vibeProfile, geometryStrategy)) {
            return candidates;
        }

        List<RouteCandidate> qualified = candidates.stream()
            .filter(candidate -> satisfiesStrictContract(candidate, vibeProfile, geometryStrategy))
            .toList();
        if (qualified.size() >= ROUTE_OPTION_COUNT) {
            return qualified;
        }

        throw noStrongStrategyRoute(requestVibes, job);
    }

    private boolean requiresStrictContractOptions(VibeCatalog.BlendedVibeProfile vibeProfile,
                                                  GeometryStrategy geometryStrategy) {
        if (vibeProfile == null) {
            return false;
        }
        Set<String> profileIds = vibeProfile.profiles().stream()
            .map(VibeCatalog.VibeProfile::id)
            .collect(Collectors.toSet());
        if (geometryStrategy == GeometryStrategy.CURVY_ELEVATION) {
            return containsAny(profileIds, "mountain", "winding_roads", "adventure");
        }
        if (geometryStrategy == GeometryStrategy.OPEN_SPACE_ESCAPE) {
            return containsAny(profileIds, "open_roads");
        }
        if (geometryStrategy == GeometryStrategy.QUIET_LOW_PRESSURE) {
            return containsAny(profileIds, "countryside", "relaxing", "nature_escape");
        }
        return false;
    }

    private boolean satisfiesStrictContract(RouteCandidate candidate,
                                            VibeCatalog.BlendedVibeProfile vibeProfile,
                                            GeometryStrategy geometryStrategy) {
        if (geometryStrategy == GeometryStrategy.CURVY_ELEVATION) {
            return isStrictMountainCandidate(candidate);
        }
        if (geometryStrategy == GeometryStrategy.OPEN_SPACE_ESCAPE) {
            return isStrictOpenSpaceCandidate(candidate);
        }
        if (geometryStrategy == GeometryStrategy.QUIET_LOW_PRESSURE && requiresStrictContractOptions(vibeProfile, geometryStrategy)) {
            return isStrictLowPressureCandidate(candidate);
        }
        return true;
    }

    private boolean isStrictMountainCandidate(RouteCandidate candidate) {
        Map<String, Double> breakdown = candidate.getScoreBreakdown();
        double strategyFit = breakdownValue(breakdown, "strategy_fit_score", 0.0);
        double curveElevationShare = breakdownValue(breakdown, "curve_elevation_corridor_share", 0.0);
        return strategyFit >= STRICT_MOUNTAIN_STRATEGY_MIN_FIT
            && curveElevationShare >= STRICT_MOUNTAIN_MIN_CURVE_ELEVATION_SHARE;
    }

    private boolean isStrictOpenSpaceCandidate(RouteCandidate candidate) {
        Map<String, Double> breakdown = candidate.getScoreBreakdown();
        double strategyFit = breakdownValue(breakdown, "strategy_fit_score", 0.0);
        double urbanPressure = breakdownValue(
            breakdown,
            "corridor_urban_pressure",
            breakdownValue(breakdown, "urban_penalty", 1.0)
        );
        double openShare = breakdownValue(breakdown, "open_space_corridor_share", 0.0);
        return strategyFit >= STRICT_OPEN_SPACE_STRATEGY_MIN_FIT
            && urbanPressure <= STRICT_OPEN_SPACE_MAX_URBAN_PRESSURE
            && openShare >= STRICT_OPEN_SPACE_MIN_OPEN_SHARE;
    }

    private boolean isStrictLowPressureCandidate(RouteCandidate candidate) {
        Map<String, Double> breakdown = candidate.getScoreBreakdown();
        double strategyFit = breakdownValue(breakdown, "strategy_fit_score", 0.0);
        double urbanPressure = breakdownValue(
            breakdown,
            "corridor_urban_pressure",
            breakdownValue(breakdown, "urban_penalty", 1.0)
        );
        double quietShare = breakdownValue(breakdown, "quiet_corridor_share", 0.0);
        return strategyFit >= STRICT_LOW_PRESSURE_STRATEGY_MIN_FIT
            && urbanPressure <= STRICT_LOW_PRESSURE_MAX_URBAN_PRESSURE
            && quietShare >= STRICT_LOW_PRESSURE_MIN_QUIET_SHARE;
    }

    private NoFeasibleRouteException noStrongStrategyRoute(List<String> requestVibes, RouteJob job) {
        return new NoFeasibleRouteException(
            "No strong " + VibeCatalog.displayList(requestVibes)
                + " route found near this start within your " + job.getTimeBudgetMinutes()
                + "-minute budget. Try a larger time budget, a less urban start point, or Country/Open Road."
        );
    }

    private double breakdownValue(Map<String, Double> breakdown, String key, double fallback) {
        if (breakdown == null || !breakdown.containsKey(key) || breakdown.get(key) == null) {
            return fallback;
        }
        return breakdown.get(key);
    }

    private List<TileCandidate> scoreNearbyTiles(UUID jobId,
                                                 RoadNode start,
                                                 int timeBudgetMinutes,
                                                 PreferenceWeights preferences,
                                                 VibeCatalog.BlendedVibeProfile vibeProfile,
                                                 DurationCalibrationHint durationCalibration,
                                                 RouteMode routeMode,
                                                 GeometryStrategy geometryStrategy) {
        int ringSize = determineRingSize(timeBudgetMinutes, vibeProfile, durationCalibration);
        int configuredResolution = Math.max(0, config.getH3Resolution());
        TileLookupResult tileLookup = findTilesNearStartTimed(start, ringSize, configuredResolution);
        List<ScenicScoreTile> nearbyTiles = tileLookup.tiles();
        long h3CellBuildMs = tileLookup.h3CellBuildMs();
        long scenicTileLookupMs = tileLookup.lookupMs();

        if (nearbyTiles.isEmpty() && configuredResolution != DEFAULT_H3_RESOLUTION) {
            TileLookupResult fallbackLookup = findTilesNearStartTimed(start, ringSize, DEFAULT_H3_RESOLUTION);
            nearbyTiles = fallbackLookup.tiles();
            h3CellBuildMs += fallbackLookup.h3CellBuildMs();
            scenicTileLookupMs += fallbackLookup.lookupMs();
            if (!nearbyTiles.isEmpty()) {
                logger.info(
                    "No scenic tiles found at configured H3 resolution {} for start ({}, {}); falling back to resolution {}",
                    configuredResolution,
                    start.getLatitude(),
                    start.getLongitude(),
                    DEFAULT_H3_RESOLUTION
                );
            }
        }

        routeGenerationMetricsService.recordStage("tile_scoring.h3_cell_build", h3CellBuildMs, routeMode, geometryStrategy, "attempt");
        routeGenerationMetricsService.recordStage("tile_scoring.scenic_tile_lookup", scenicTileLookupMs, routeMode, geometryStrategy, "attempt");

        if (nearbyTiles.isEmpty()) {
            routeGenerationMetricsService.recordStage("tile_scoring.pre_anchor_rank", 0L, routeMode, geometryStrategy, "attempt");
            routeGenerationMetricsService.recordStage("tile_scoring.road_anchor_lookup", 0L, routeMode, geometryStrategy, "attempt");
            routeGenerationMetricsService.recordStage("tile_scoring.final_rank", 0L, routeMode, geometryStrategy, "attempt");
            return List.of();
        }

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, durationCalibration);
        int anchoredLimit = Math.max(
            20,
            Math.min(config.getTileSelectionLimit(), Math.max(1, config.getAnchoredTileSelectionLimit()))
        );
        long substageStartedNanos = System.nanoTime();
        List<PreAnchorTileCandidate> preselectedTiles = nearbyTiles.stream()
            .map(tile -> {
                RoadNode tileCenter = tileCenter(tile, start);
                double distanceKm = distanceKm(start, tileCenter);
                double score = scoreTile(tile, preferences);
                double selectionScore = tileSelectionScore(tile, vibeProfile, score, distanceKm, targetRadiusKm);
                return new PreAnchorTileCandidate(tile, tileCenter, score, selectionScore);
            })
            .sorted(Comparator.comparingDouble(PreAnchorTileCandidate::selectionScore).reversed())
            .limit(anchoredLimit)
            .toList();
        long preAnchorRankMs = elapsedMillis(substageStartedNanos);
        routeGenerationMetricsService.recordStage("tile_scoring.pre_anchor_rank", preAnchorRankMs, routeMode, geometryStrategy, "attempt");

        substageStartedNanos = System.nanoTime();
        List<TileCandidate> anchoredTiles = preselectedTiles.stream()
            .map(candidate -> {
                RoadNode anchor = roadSegmentAnchorService.anchorFor(candidate.tile(), candidate.center());
                double distanceKm = distanceKm(start, anchor);
                double selectionScore = tileSelectionScore(candidate.tile(), vibeProfile, candidate.scenicScore(), distanceKm, targetRadiusKm);
                return new TileCandidate(candidate.tile(), anchor, candidate.scenicScore(), selectionScore, distanceKm);
            })
            .toList();
        long roadAnchorLookupMs = elapsedMillis(substageStartedNanos);
        routeGenerationMetricsService.recordStage("tile_scoring.road_anchor_lookup", roadAnchorLookupMs, routeMode, geometryStrategy, "attempt");

        substageStartedNanos = System.nanoTime();
        List<TileCandidate> scoredTiles = anchoredTiles.stream()
            .sorted(Comparator.comparingDouble(TileCandidate::selectionScore).reversed())
            .toList();
        long finalRankMs = elapsedMillis(substageStartedNanos);
        routeGenerationMetricsService.recordStage("tile_scoring.final_rank", finalRankMs, routeMode, geometryStrategy, "attempt");
        logger.info(
            "Route job {} tile scoring timings h3CellBuildMs={} scenicTileLookupMs={} preAnchorRankMs={} roadAnchorLookupMs={} finalRankMs={} nearbyTiles={} preselectedTiles={} scoredTiles={} strategy={}",
            jobId,
            h3CellBuildMs,
            scenicTileLookupMs,
            preAnchorRankMs,
            roadAnchorLookupMs,
            finalRankMs,
            nearbyTiles.size(),
            preselectedTiles.size(),
            scoredTiles.size(),
            geometryStrategy
        );
        return scoredTiles;
    }

    private void validateVibeAvailability(RouteJob job,
                                          List<String> vibes,
                                          VibeCatalog.BlendedVibeProfile vibeProfile,
                                          List<TileCandidate> scoredTiles) {
        if (scoredTiles == null || scoredTiles.size() < MIN_VIBE_AVAILABILITY_TILES) {
            throw new NoFeasibleRouteException(
                "No scenic data found near this start for " + VibeCatalog.displayList(vibes)
                    + " within your " + job.getTimeBudgetMinutes()
                    + "-minute budget. Try Country or Relaxing, or choose a nearby start point."
            );
        }

        List<Double> fitScores = scoredTiles.stream()
            .map(candidate -> vibeFitScore(candidate.tile(), vibeProfile))
            .sorted(Comparator.reverseOrder())
            .toList();
        double bestFit = fitScores.getFirst();
        double avgTopFit = fitScores.stream()
            .limit(VIBE_AVAILABILITY_TOP_N)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        double minBestFit = Math.max(MIN_VIBE_BEST_FIT_SCORE, vibeProfile.minBestFit());
        double minAverageFit = Math.max(MIN_VIBE_AVG_FIT_SCORE, vibeProfile.minAverageFit());
        if (bestFit < minBestFit || avgTopFit < minAverageFit) {
            throw new NoFeasibleRouteException(
                "No strong " + VibeCatalog.displayList(vibes)
                    + " route found near this start within your " + job.getTimeBudgetMinutes()
                    + "-minute budget. Try Country, Relaxing, or increase the time budget."
            );
        }
    }

    private double vibeFitScore(ScenicScoreTile tile, VibeCatalog.BlendedVibeProfile vibeProfile) {
        VibeCatalog.BlendedVibeProfile activeProfile = vibeProfile == null
            ? VibeCatalog.blendProfiles(List.of(VibeCatalog.defaultVibe()))
            : vibeProfile;
        return activeProfile.profiles().stream()
            .mapToDouble(profile -> singleVibeFitScore(tile, profile))
            .average()
            .orElse(DEFAULT_SCENIC_FALLBACK);
    }

    private double singleVibeFitScore(ScenicScoreTile tile, VibeCatalog.VibeProfile profile) {
        ComponentScores components = componentScores(tile);
        double targetScore = targetComponentScore(tile, components, profile);
        double antiPenalty = antiComponentPenalty(tile, components, profile.antiComponents());
        double penaltyWeight = profile.strictIntent() ? 0.24 : 0.14;
        return clamp01(targetScore - (antiPenalty * penaltyWeight));
    }

    private double targetComponentScore(ScenicScoreTile tile, ComponentScores components, VibeCatalog.VibeProfile profile) {
        if (profile.targetComponents() == null || profile.targetComponents().isEmpty()) {
            return average(
                components.water(),
                components.greenery(),
                components.elevation(),
                components.solitude(),
                components.curves(),
                components.poi()
            );
        }

        double weighted = 0.0;
        double totalWeight = 0.0;
        for (String component : profile.targetComponents()) {
            double weight = componentWeight(profile.weights(), component);
            weighted += componentValue(tile, components, component) * weight;
            totalWeight += weight;
        }
        return totalWeight <= 0.0001 ? DEFAULT_SCENIC_FALLBACK : clamp01(weighted / totalWeight);
    }

    private ComponentScores componentScores(ScenicScoreTile tile) {
        return scenicScoreCalculator.componentScores(tile);
    }

    private double antiComponentPenalty(ScenicScoreTile tile, ComponentScores components, List<String> antiComponents) {
        if (antiComponents == null || antiComponents.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        int count = 0;
        for (String antiComponent : antiComponents) {
            sum += switch (antiComponent) {
                case "urban_penalty" -> clamp01(tile.getUrbanPenaltyScore());
                case "building_density" -> clamp01(tile.getBuildingDensityScore());
                case "road_stress" -> clamp01(tile.getRoadStressScore());
                case "poi" -> components.poi();
                case "curves" -> components.curves();
                case "road_density" -> clamp01(tile.getRoadDensity());
                default -> 0.0;
            };
            count++;
        }
        return count == 0 ? 0.0 : clamp01(sum / count);
    }

    private double componentValue(ScenicScoreTile tile, ComponentScores components, String component) {
        return switch (component) {
            case "water" -> components.water();
            case "greenery" -> components.greenery();
                case "elevation" -> components.elevation();
                case "solitude" -> components.solitude();
            case "curves" -> components.curves();
            case "poi" -> components.poi();
            case "scenic_poi" -> clamp01(tile.getScenicPoiScore());
            case "viewpoint" -> clamp01(tile.getViewpointScore());
            case "bridge_coastal" -> clamp01(tile.getBridgeCoastalScore());
            case "tree_canopy" -> clamp01(tile.getTreeCanopyScore());
            case "open_space" -> openSpaceScore(tile, components);
            default -> 0.0;
            };
    }

    private double componentWeight(VibeCatalog.ComponentWeights weights, String component) {
        return switch (component) {
            case "water" -> weights.water();
            case "greenery" -> weights.greenery();
            case "elevation" -> weights.elevation();
            case "solitude" -> weights.solitude();
            case "curves" -> weights.curves();
            case "poi" -> weights.poi();
            case "scenic_poi" -> weights.poi();
            case "viewpoint" -> Math.max(weights.poi(), weights.elevation());
            case "bridge_coastal" -> Math.max(weights.water(), weights.poi());
            case "tree_canopy" -> Math.max(weights.greenery(), weights.solitude());
            case "open_space" -> Math.max(weights.solitude(), weights.curves());
            default -> 0.0;
        };
    }

    private double openSpaceScore(ScenicScoreTile tile, ComponentScores components) {
        double lowRoadDensity = 1.0 - clamp01(tile.getRoadDensity());
        double lowBuildingDensity = 1.0 - clamp01(tile.getBuildingDensityScore());
        double lowUrbanPressure = 1.0 - clamp01(tile.getUrbanPenaltyScore());
        double lowRoadStress = 1.0 - clamp01(tile.getRoadStressScore());
        double lowPoiDensity = 1.0 - components.poi();
        return clamp01(
            (components.solitude() * 0.40)
                + (lowRoadDensity * 0.20)
                + (lowBuildingDensity * 0.15)
                + (lowUrbanPressure * 0.10)
                + (lowRoadStress * 0.10)
                + (lowPoiDensity * 0.05)
        );
    }

    private double average(double... values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += clamp01(value);
        }
        return sum / values.length;
    }

    private double averageScores(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return DEFAULT_SCENIC_FALLBACK;
        }
        return clamp01(values.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(DEFAULT_SCENIC_FALLBACK));
    }

    private double standardDeviation(List<Double> values) {
        if (values == null || values.size() < 2) {
            return 0.0;
        }
        double mean = averageScores(values);
        double variance = values.stream()
            .mapToDouble(value -> Math.pow(clamp01(value) - mean, 2))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }

    private List<ScenicScoreTile> findTilesNearStart(RoadNode start, int ringSize, int resolution) {
        return findTilesNearStartTimed(start, ringSize, resolution).tiles();
    }

    private TileLookupResult findTilesNearStartTimed(RoadNode start, int ringSize, int resolution) {
        long h3StartedNanos = System.nanoTime();
        String centerCell = H3Utils.getH3Index(start.getLatitude(), start.getLongitude(), resolution);
        List<String> nearbyCells = H3Utils.getKRing(centerCell, ringSize);
        long h3CellBuildMs = elapsedMillis(h3StartedNanos);
        if (nearbyCells.isEmpty()) {
            return new TileLookupResult(List.of(), h3CellBuildMs, 0L, 0);
        }
        long lookupStartedNanos = System.nanoTime();
        List<ScenicScoreTile> tiles = scenicTileLookupService.findByH3Indexes(nearbyCells);
        return new TileLookupResult(tiles, h3CellBuildMs, elapsedMillis(lookupStartedNanos), nearbyCells.size());
    }

    private int determineRingSize(int timeBudgetMinutes) {
        return determineRingSize(timeBudgetMinutes, null, DurationCalibrationHint.empty());
    }

    private int determineRingSize(int timeBudgetMinutes, VibeCatalog.BlendedVibeProfile vibeProfile) {
        return determineRingSize(timeBudgetMinutes, vibeProfile, DurationCalibrationHint.empty());
    }

    private int determineRingSize(int timeBudgetMinutes,
                                  VibeCatalog.BlendedVibeProfile vibeProfile,
                                  DurationCalibrationHint durationCalibration) {
        int dynamicRing = (int) Math.ceil(timeBudgetMinutes / 5.0);
        int min = Math.max(1, config.getTileSelectionRingMin());
        int max = Math.max(min, config.getTileSelectionRingMax());
        if (usesTargetAnchors(vibeProfile)) {
            dynamicRing = Math.max(dynamicRing, (int) Math.ceil(dynamicRing * 1.65));
        }
        if (durationCalibration.active()) {
            dynamicRing = Math.max(1, (int) Math.ceil(dynamicRing * durationCalibration.radiusMultiplier()));
        }
        return Math.max(min, Math.min(max, dynamicRing));
    }

    private List<List<RoadNode>> buildHybridV2WaypointRings(RoadNode start,
                                                            List<TileCandidate> scoredTiles,
                                                            int timeBudgetMinutes,
                                                            VibeCatalog.BlendedVibeProfile vibeProfile,
                                                            GeometryStrategy geometryStrategy,
                                                            DurationCalibrationHint durationCalibration) {
        List<List<RoadNode>> variants = new ArrayList<>();
        List<List<RoadNode>> baseVariants = buildWaypointRings(start, scoredTiles, timeBudgetMinutes, vibeProfile, durationCalibration);

        List<List<RoadNode>> intentVariants = buildTargetAnchorWaypointRings(
            start,
            scoredTiles,
            timeBudgetMinutes,
            vibeProfile,
            durationCalibration
        );
        List<List<RoadNode>> strategyVariants = buildStrategyWaypointRings(
            start,
            scoredTiles,
            timeBudgetMinutes,
            vibeProfile,
            geometryStrategy,
            durationCalibration
        );
        if (geometryStrategy == GeometryStrategy.BALANCED_VARIETY) {
            variants.addAll(baseVariants);
            addInterleavedVariants(variants, intentVariants, strategyVariants);
        } else {
            addInterleavedVariants(variants, strategyVariants, intentVariants);
            variants.addAll(baseVariants);
        }

        if (!intentVariants.isEmpty()) {
            logger.info(
                "Hybrid OSRM v2 added {} intent-anchor waypoint variant(s)",
                intentVariants.size()
            );
        }
        if (!strategyVariants.isEmpty()) {
            logger.info(
                "Hybrid OSRM v2 added {} {} strategy waypoint variant(s)",
                strategyVariants.size(),
                geometryStrategy
            );
        }

        return deduplicateRings(variants);
    }

    private void addInterleavedVariants(List<List<RoadNode>> target,
                                        List<List<RoadNode>> first,
                                        List<List<RoadNode>> second) {
        int maxSize = Math.max(first.size(), second.size());
        for (int i = 0; i < maxSize; i++) {
            if (i < first.size()) {
                target.add(first.get(i));
            }
            if (i < second.size()) {
                target.add(second.get(i));
            }
        }
    }

    private List<List<RoadNode>> buildStrategyWaypointRings(RoadNode start,
                                                            List<TileCandidate> scoredTiles,
                                                            int timeBudgetMinutes,
                                                            VibeCatalog.BlendedVibeProfile vibeProfile,
                                                            GeometryStrategy geometryStrategy,
                                                            DurationCalibrationHint durationCalibration) {
        if (scoredTiles == null || scoredTiles.isEmpty() || geometryStrategy == null) {
            return List.of();
        }

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, durationCalibration);
        double minRadiusKm = Math.max(2.0, targetRadiusKm * strategyMinRadiusRatio(geometryStrategy));
        double maxRadiusKm = Math.max(
            minRadiusKm + 1.0,
            Math.min(strategyMaxTimeRadiusKm(timeBudgetMinutes, geometryStrategy), targetRadiusKm * strategyMaxRadiusRatio(geometryStrategy))
        );
        List<TileCandidate> anchors = selectStrategyAnchors(
            scoredTiles,
            minRadiusKm,
            maxRadiusKm,
            candidate -> strategyAnchorScore(candidate, vibeProfile, geometryStrategy)
        );
        if (anchors.isEmpty()) {
            return List.of();
        }

        return switch (geometryStrategy) {
            case WATER_FOLLOWING -> buildWaterFollowingRings(start, anchors);
            case OPEN_SPACE_ESCAPE -> buildDirectionalStrategyRings(start, anchors, 1.18, true);
            case QUIET_LOW_PRESSURE -> buildQuietEscapeRings(start, anchors);
            case PHOTO_PEAKS -> buildPeakStrategyRings(start, anchors);
            case CURVY_ELEVATION -> buildDirectionalStrategyRings(start, anchors, 1.22, true);
            case BALANCED_VARIETY -> buildBalancedVarietyRings(start, anchors);
        };
    }

    private List<List<RoadNode>> buildWaterFollowingRings(RoadNode start, List<TileCandidate> anchors) {
        List<TileCandidate> orderedByBearing = anchors.stream()
            .sorted(Comparator.comparingDouble(anchor -> bearingDegrees(start, anchor.center())))
            .toList();
        List<List<RoadNode>> variants = new ArrayList<>();
        for (int i = 0; i < orderedByBearing.size(); i++) {
            TileCandidate first = orderedByBearing.get(i);
            variants.add(List.of(start, first.center()));
            TileCandidate second = orderedByBearing.get((i + 1) % orderedByBearing.size());
            if (!first.equals(second)) {
                variants.add(List.of(start, first.center(), second.center()));
            }
            if (orderedByBearing.size() >= 3) {
                TileCandidate third = orderedByBearing.get((i + 2) % orderedByBearing.size());
                variants.add(List.of(start, first.center(), second.center(), third.center()));
            }
        }
        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildQuietEscapeRings(RoadNode start, List<TileCandidate> anchors) {
        List<List<RoadNode>> variants = new ArrayList<>(buildDirectionalStrategyRings(start, anchors, 1.32, false));
        anchors.stream()
            .limit(STRATEGY_ANCHOR_LIMIT)
            .forEach(anchor -> {
                double bearing = bearingDegrees(start, anchor.center());
                double distanceKm = distanceKm(start, anchor.center());
                variants.add(List.of(start, anchor.center()));
                variants.add(List.of(
                    start,
                    anchor.center(),
                    offsetNode(start, Math.max(3.0, distanceKm * 1.38), bearing + 18.0)
                ));
                variants.add(List.of(
                    start,
                    offsetNode(start, Math.max(3.0, distanceKm * 0.92), bearing - 16.0),
                    anchor.center()
                ));
            });
        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildDirectionalStrategyRings(RoadNode start,
                                                               List<TileCandidate> anchors,
                                                               double radiusPush,
                                                               boolean requireSectorGap) {
        int sectorCount = Math.max(4, config.getSectorCount());
        List<List<RoadNode>> variants = new ArrayList<>();
        for (TileCandidate anchor : anchors) {
            double bearing = bearingDegrees(start, anchor.center());
            double distanceKm = distanceKm(start, anchor.center());
            variants.add(List.of(
                start,
                anchor.center(),
                offsetNode(start, Math.max(2.0, distanceKm * radiusPush), bearing + 36.0)
            ));
        }

        int pairCount = 0;
        for (int i = 0; i < anchors.size() && pairCount < STRATEGY_PAIR_LIMIT; i++) {
            TileCandidate first = anchors.get(i);
            int firstSector = calculateSector(start, first.center(), sectorCount);
            for (int j = i + 1; j < anchors.size() && pairCount < STRATEGY_PAIR_LIMIT; j++) {
                TileCandidate second = anchors.get(j);
                int secondSector = calculateSector(start, second.center(), sectorCount);
                int sectorGap = Math.abs(firstSector - secondSector);
                int circularGap = Math.min(sectorGap, sectorCount - sectorGap);
                if (requireSectorGap && circularGap < 2) {
                    continue;
                }
                variants.add(List.of(start, first.center(), second.center()));
                pairCount++;
            }
        }
        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildPeakStrategyRings(RoadNode start, List<TileCandidate> anchors) {
        List<List<RoadNode>> variants = new ArrayList<>();
        int limit = Math.min(anchors.size(), STRATEGY_ANCHOR_LIMIT);
        for (int i = 0; i < limit; i++) {
            TileCandidate anchor = anchors.get(i);
            variants.add(List.of(start, anchor.center()));
            if (i + 1 < limit) {
                variants.add(List.of(start, anchor.center(), anchors.get(i + 1).center()));
            }
        }
        if (limit >= 3) {
            variants.add(List.of(start, anchors.get(0).center(), anchors.get(1).center(), anchors.get(2).center()));
        }
        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildBalancedVarietyRings(RoadNode start, List<TileCandidate> anchors) {
        int sectorCount = Math.max(4, config.getSectorCount());
        List<TileCandidate> ordered = anchors.stream()
            .sorted(Comparator.comparingInt(anchor -> calculateSector(start, anchor.center(), sectorCount)))
            .toList();
        List<List<RoadNode>> variants = new ArrayList<>();
        for (int waypointCount : List.of(3, 4, 5)) {
            if (ordered.size() < waypointCount) {
                continue;
            }
            List<RoadNode> ring = new ArrayList<>();
            ring.add(start);
            for (int i = 0; i < waypointCount; i++) {
                int index = (int) Math.floor((double) i * ordered.size() / waypointCount);
                ring.add(ordered.get(index).center());
            }
            variants.add(List.copyOf(ring));
        }
        return deduplicateRings(variants);
    }

    private List<TileCandidate> selectStrategyAnchors(List<TileCandidate> scoredTiles,
                                                      double minRadiusKm,
                                                      double maxRadiusKm,
                                                      ToDoubleFunction<TileCandidate> scorer) {
        List<TileCandidate> anchors = new ArrayList<>();
        scoredTiles.stream()
            .filter(candidate -> candidate.distanceKm() >= minRadiusKm && candidate.distanceKm() <= maxRadiusKm)
            .sorted(Comparator.comparingDouble(scorer).reversed())
            .forEach(candidate -> {
                if (anchors.size() >= STRATEGY_ANCHOR_LIMIT) {
                    return;
                }
                boolean tooClose = anchors.stream()
                    .anyMatch(anchor -> distanceKm(anchor.center(), candidate.center()) < STRATEGY_ANCHOR_MIN_SEPARATION_KM);
                if (!tooClose) {
                    anchors.add(candidate);
                }
            });
        return List.copyOf(anchors);
    }

    private double strategyAnchorScore(TileCandidate candidate,
                                       VibeCatalog.BlendedVibeProfile vibeProfile,
                                       GeometryStrategy geometryStrategy) {
        ScenicScoreTile tile = candidate.tile();
        ComponentScores components = componentScores(tile);
        double vibeFit = vibeFitScore(tile, vibeProfile);
        double lowUrban = 1.0 - computeUrbanPressureScore(List.of(tile));
        double lowRoadDensity = 1.0 - clamp01(tile.getRoadDensity());
        double photoPeak = Math.max(
            Math.max(components.water(), components.elevation()),
            Math.max(components.poi(), clamp01(tile.getViewpointScore()))
        );
        return clamp01(switch (geometryStrategy) {
            case WATER_FOLLOWING -> (components.water() * 0.58)
                + (components.greenery() * 0.12)
                + (clamp01(tile.getBridgeCoastalScore()) * 0.08)
                + (vibeFit * 0.14)
                + (lowUrban * 0.08);
            case OPEN_SPACE_ESCAPE -> (openSpaceScore(tile, components) * 0.55)
                + (components.solitude() * 0.20)
                + (lowUrban * 0.15)
                + (lowRoadDensity * 0.10);
            case QUIET_LOW_PRESSURE -> (components.solitude() * 0.48)
                + (components.greenery() * 0.20)
                + (lowUrban * 0.22)
                + (clamp01(tile.getDarknessScore()) * 0.10);
            case PHOTO_PEAKS -> (photoPeak * 0.44)
                + (components.poi() * 0.16)
                + (clamp01(tile.getViewpointScore()) * 0.18)
                + (vibeFit * 0.14)
                + (candidate.scenicScore() * 0.08);
            case CURVY_ELEVATION -> (components.curves() * 0.45)
                + (components.elevation() * 0.30)
                + (vibeFit * 0.15)
                + (lowUrban * 0.10);
            case BALANCED_VARIETY -> (candidate.scenicScore() * 0.42)
                + (vibeFit * 0.34)
                + (landscapeVarietyScore(components) * 0.24);
        });
    }

    private double strategyMinRadiusRatio(GeometryStrategy geometryStrategy) {
        return switch (geometryStrategy) {
            case OPEN_SPACE_ESCAPE, CURVY_ELEVATION -> 0.65;
            case QUIET_LOW_PRESSURE -> 0.95;
            case PHOTO_PEAKS, WATER_FOLLOWING -> 0.45;
            case BALANCED_VARIETY -> 0.50;
        };
    }

    private double strategyMaxRadiusRatio(GeometryStrategy geometryStrategy) {
        return switch (geometryStrategy) {
            case OPEN_SPACE_ESCAPE, CURVY_ELEVATION -> 2.15;
            case QUIET_LOW_PRESSURE -> 3.20;
            case PHOTO_PEAKS -> 1.75;
            case WATER_FOLLOWING -> 1.90;
            case BALANCED_VARIETY -> 2.0;
        };
    }

    private double strategyMaxTimeRadiusKm(int timeBudgetMinutes, GeometryStrategy geometryStrategy) {
        return switch (geometryStrategy) {
            case QUIET_LOW_PRESSURE -> Math.max(8.0, timeBudgetMinutes / 1.35);
            default -> Math.max(3.0, timeBudgetMinutes / 2.1);
        };
    }

    private double landscapeVarietyScore(ComponentScores components) {
        List<Double> values = List.of(
            components.water(),
            components.greenery(),
            components.elevation(),
            components.solitude(),
            components.curves()
        );
        double mean = averageScores(values);
        double usefulSignals = values.stream()
            .filter(value -> value >= 0.35)
            .count() / (double) values.size();
        return clamp01((mean * 0.55) + (usefulSignals * 0.45));
    }

    private GeometryStrategy resolveGeometryStrategy(VibeCatalog.BlendedVibeProfile vibeProfile) {
        if (vibeProfile == null || vibeProfile.profiles().isEmpty()) {
            return GeometryStrategy.BALANCED_VARIETY;
        }

        Set<String> profileIds = vibeProfile.profiles().stream()
            .map(VibeCatalog.VibeProfile::id)
            .collect(Collectors.toSet());
        if (containsAny(profileIds, "coastal", "riverside")) {
            return GeometryStrategy.WATER_FOLLOWING;
        }
        if (containsAny(profileIds, "open_roads")) {
            return GeometryStrategy.OPEN_SPACE_ESCAPE;
        }
        if (containsAny(profileIds, "relaxing", "countryside", "nature_escape")) {
            return GeometryStrategy.QUIET_LOW_PRESSURE;
        }
        if (containsAny(profileIds, "mountain", "winding_roads", "adventure")) {
            return GeometryStrategy.CURVY_ELEVATION;
        }
        return GeometryStrategy.BALANCED_VARIETY;
    }

    private boolean containsAny(Set<String> values, String... expected) {
        if (values == null || values.isEmpty() || expected == null) {
            return false;
        }
        for (String value : expected) {
            if (values.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<List<RoadNode>> buildWaypointRings(RoadNode start,
                                                    List<TileCandidate> scoredTiles,
                                                    int timeBudgetMinutes,
                                                    VibeCatalog.BlendedVibeProfile vibeProfile) {
        return buildWaypointRings(start, scoredTiles, timeBudgetMinutes, vibeProfile, DurationCalibrationHint.empty());
    }

    private List<List<RoadNode>> buildWaypointRings(RoadNode start,
                                                    List<TileCandidate> scoredTiles,
                                                    int timeBudgetMinutes,
                                                    VibeCatalog.BlendedVibeProfile vibeProfile,
                                                    DurationCalibrationHint durationCalibration) {
        if (scoredTiles.isEmpty()) {
            return List.of();
        }

        int sectorCount = Math.max(4, config.getSectorCount());
        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, durationCalibration);
        boolean outwardRouting = usesTargetAnchors(vibeProfile);
        double minRadiusKm = targetRadiusKm * (outwardRouting ? 0.50 : 0.45);
        double maxRadiusKm = targetRadiusKm * (outwardRouting ? 1.90 : 2.0);

        Map<Integer, TileCandidate> bestBySector = new HashMap<>();
        for (TileCandidate candidate : scoredTiles) {
            if (candidate.distanceKm() < minRadiusKm || candidate.distanceKm() > maxRadiusKm) {
                continue;
            }
            int sector = calculateSector(start, candidate.center(), sectorCount);
            bestBySector.merge(sector, candidate, (left, right) -> left.selectionScore() >= right.selectionScore() ? left : right);
        }

        if (bestBySector.size() < 4) {
            for (TileCandidate candidate : scoredTiles) {
                int sector = calculateSector(start, candidate.center(), sectorCount);
                bestBySector.merge(sector, candidate, (left, right) -> left.selectionScore() >= right.selectionScore() ? left : right);
                if (bestBySector.size() >= sectorCount) {
                    break;
                }
            }
        }

        List<TileCandidate> orderedSectorTiles = bestBySector.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();

        List<List<RoadNode>> variants = new ArrayList<>();
        for (Integer waypointCount : waypointVariants(durationCalibration)) {
            if (orderedSectorTiles.size() < waypointCount) {
                continue;
            }

            for (int rotation = 0; rotation < Math.min(orderedSectorTiles.size(), waypointCount); rotation++) {
                List<RoadNode> ring = new ArrayList<>();
                ring.add(start);
                for (int i = 0; i < waypointCount; i++) {
                    int index = (rotation + (int) Math.floor((double) i * orderedSectorTiles.size() / waypointCount))
                        % orderedSectorTiles.size();
                    ring.add(orderedSectorTiles.get(index).center());
                }

                if (ring.size() >= 3) {
                    variants.add(List.copyOf(ring));
                }
            }
        }

        if (variants.isEmpty()) {
            int fallbackCount = Math.min(4, scoredTiles.size());
            if (fallbackCount >= 2) {
                List<RoadNode> fallback = new ArrayList<>();
                fallback.add(start);
                for (int i = 0; i < fallbackCount; i++) {
                    fallback.add(scoredTiles.get(i).center());
                }
                variants.add(List.copyOf(fallback));
            }
        }

        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildTargetAnchorWaypointRings(RoadNode start,
                                                               List<TileCandidate> scoredTiles,
                                                               int timeBudgetMinutes,
                                                               VibeCatalog.BlendedVibeProfile vibeProfile) {
        return buildTargetAnchorWaypointRings(start, scoredTiles, timeBudgetMinutes, vibeProfile, DurationCalibrationHint.empty());
    }

    private List<List<RoadNode>> buildTargetAnchorWaypointRings(RoadNode start,
                                                               List<TileCandidate> scoredTiles,
                                                               int timeBudgetMinutes,
                                                               VibeCatalog.BlendedVibeProfile vibeProfile,
                                                               DurationCalibrationHint durationCalibration) {
        if (scoredTiles == null || scoredTiles.isEmpty()) {
            return List.of();
        }

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, durationCalibration);
        double minRadiusKm = Math.max(3.0, targetRadiusKm * 0.55);
        double maxRadiusKm = Math.max(minRadiusKm + 1.0, Math.min(timeBudgetMinutes / 2.2, targetRadiusKm * 1.85));
        int sectorCount = Math.max(4, config.getSectorCount());
        List<TileCandidate> anchors = selectTargetAnchors(scoredTiles, minRadiusKm, maxRadiusKm);

        List<List<RoadNode>> variants = new ArrayList<>();
        for (TileCandidate anchor : anchors) {
            variants.add(List.of(start, anchor.center()));
        }

        int pairCount = 0;
        for (int i = 0; i < anchors.size() && pairCount < TARGET_ANCHOR_PAIR_LIMIT; i++) {
            TileCandidate first = anchors.get(i);
            int firstSector = calculateSector(start, first.center(), sectorCount);
            for (int j = i + 1; j < anchors.size() && pairCount < TARGET_ANCHOR_PAIR_LIMIT; j++) {
                TileCandidate second = anchors.get(j);
                if (distanceKm(first.center(), second.center()) < TARGET_ANCHOR_MIN_SEPARATION_KM) {
                    continue;
                }
                int secondSector = calculateSector(start, second.center(), sectorCount);
                int sectorGap = Math.abs(firstSector - secondSector);
                int circularGap = Math.min(sectorGap, sectorCount - sectorGap);
                if (circularGap < 2) {
                    continue;
                }
                variants.add(List.of(start, first.center(), second.center()));
                pairCount++;
            }
        }

        if (anchors.size() >= 3) {
            List<RoadNode> triangle = new ArrayList<>();
            triangle.add(start);
            anchors.stream()
                .sorted(Comparator.comparingInt(anchor -> calculateSector(start, anchor.center(), sectorCount)))
                .limit(3)
                .map(TileCandidate::center)
                .forEach(triangle::add);
            variants.add(List.copyOf(triangle));
        }

        return deduplicateRings(variants);
    }

    private List<TileCandidate> selectTargetAnchors(List<TileCandidate> scoredTiles, double minRadiusKm, double maxRadiusKm) {
        List<TileCandidate> anchors = new ArrayList<>();
        for (TileCandidate candidate : scoredTiles) {
            if (candidate.distanceKm() < minRadiusKm || candidate.distanceKm() > maxRadiusKm) {
                continue;
            }
            boolean tooClose = anchors.stream()
                .anyMatch(anchor -> distanceKm(anchor.center(), candidate.center()) < TARGET_ANCHOR_MIN_SEPARATION_KM);
            if (tooClose) {
                continue;
            }
            anchors.add(candidate);
            if (anchors.size() >= TARGET_ANCHOR_LIMIT) {
                break;
            }
        }
        return List.copyOf(anchors);
    }

    private List<List<RoadNode>> buildSyntheticWaypointRings(RoadNode start, int timeBudgetMinutes) {
        double baseRadiusKm = Math.max(2.0, Math.min(12.0, timeBudgetMinutes / 8.0));
        List<Double> radii = List.of(baseRadiusKm * 0.50, baseRadiusKm * 0.65, baseRadiusKm * 0.80, baseRadiusKm, baseRadiusKm * 1.20);
        List<Integer> waypointCounts = List.of(2, 3, 4, 6);
        List<Double> bearingOffsets = List.of(0.0, 22.5, 45.0);

        List<List<RoadNode>> variants = new ArrayList<>();
        for (double radiusKm : radii) {
            for (double bearingOffset : bearingOffsets) {
                for (int waypointCount : waypointCounts) {
                    List<RoadNode> ring = new ArrayList<>();
                    ring.add(start);
                    for (int i = 0; i < waypointCount; i++) {
                        double bearing = bearingOffset + ((360.0 / waypointCount) * i);
                        ring.add(offsetNode(start, radiusKm, bearing));
                    }
                    variants.add(List.copyOf(ring));
                }
            }
        }

        List<RoadNode> triangle = List.of(
            start,
            offsetNode(start, baseRadiusKm, 60.0),
            offsetNode(start, baseRadiusKm, 210.0)
        );
        variants.add(triangle);

        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildBudgetRescueWaypointRings(RoadNode start, int timeBudgetMinutes) {
        double baseRadiusKm = Math.max(1.8, Math.min(14.0, timeBudgetMinutes / 7.0));
        List<Double> radii = List.of(baseRadiusKm * 0.45, baseRadiusKm * 0.60, baseRadiusKm * 0.75, baseRadiusKm * 0.90, baseRadiusKm * 1.10);
        List<Integer> waypointCounts = List.of(2, 3, 4, 6);
        List<Double> bearingOffsets = List.of(0.0, 22.5, 45.0, 67.5, 90.0, 135.0);

        List<List<RoadNode>> variants = new ArrayList<>();
        for (double bearingOffset : bearingOffsets) {
            for (double radiusKm : radii) {
                for (int waypointCount : waypointCounts) {
                    List<RoadNode> ring = new ArrayList<>();
                    ring.add(start);
                    for (int i = 0; i < waypointCount; i++) {
                        double bearing = bearingOffset + ((360.0 / waypointCount) * i);
                        ring.add(offsetNode(start, radiusKm, bearing));
                    }
                    variants.add(List.copyOf(ring));
                }
            }
        }

        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> buildDiversityRescueWaypointRings(RoadNode start, int timeBudgetMinutes) {
        double baseRadiusKm = Math.max(2.5, Math.min(14.0, timeBudgetMinutes / 7.0));
        List<Double> bearings = List.of(30.0, 75.0, 120.0, 165.0, 210.0, 255.0, 300.0, 345.0);

        List<List<RoadNode>> variants = new ArrayList<>();
        for (double bearing : bearings) {
            variants.add(List.of(
                start,
                offsetNode(start, baseRadiusKm, bearing),
                offsetNode(start, baseRadiusKm * 1.15, bearing + 35.0),
                offsetNode(start, baseRadiusKm, bearing + 70.0)
            ));
        }
        return deduplicateRings(variants);
    }

    private List<List<RoadNode>> deduplicateRings(List<List<RoadNode>> rings) {
        Map<String, List<RoadNode>> unique = new LinkedHashMap<>();
        for (List<RoadNode> ring : rings) {
            String key = ring.stream()
                .map(node -> String.format(Locale.ROOT, "%.5f,%.5f", node.getLatitude(), node.getLongitude()))
                .collect(Collectors.joining("|"));
            unique.putIfAbsent(key, ring);
        }
        return List.copyOf(unique.values());
    }

    private int calculateSector(RoadNode start, RoadNode point, int sectorCount) {
        double bearing = bearingDegrees(start, point);
        double sectorWidth = 360.0 / sectorCount;
        return (int) Math.floor(bearing / sectorWidth);
    }

    private double targetWaypointRadiusKm(int timeBudgetMinutes, VibeCatalog.BlendedVibeProfile vibeProfile) {
        return targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, DurationCalibrationHint.empty());
    }

    private double targetWaypointRadiusKm(int timeBudgetMinutes,
                                          VibeCatalog.BlendedVibeProfile vibeProfile,
                                          DurationCalibrationHint durationCalibration) {
        boolean outwardRouting = usesTargetAnchors(vibeProfile);
        double target = outwardRouting ? timeBudgetMinutes / 4.2 : timeBudgetMinutes / 6.5;
        double radiusMultiplier = durationCalibration.active() ? durationCalibration.radiusMultiplier() : 1.0;
        return Math.max(2.5, target * radiusMultiplier);
    }

    private List<Integer> waypointVariants(DurationCalibrationHint durationCalibration) {
        if (!durationCalibration.active()) {
            return WAYPOINT_VARIANTS;
        }

        int learned = (int) Math.round(durationCalibration.learnedWaypointCount());
        learned = Math.max(MIN_LEARNED_WAYPOINT_COUNT, Math.min(MAX_LEARNED_WAYPOINT_COUNT, learned));
        List<Integer> variants = new ArrayList<>();
        variants.add(learned);
        variants.add(Math.max(MIN_LEARNED_WAYPOINT_COUNT, learned - 1));
        variants.add(Math.min(MAX_LEARNED_WAYPOINT_COUNT, learned + 1));
        variants.addAll(WAYPOINT_VARIANTS);
        return variants.stream()
            .distinct()
            .toList();
    }

    private boolean usesTargetAnchors(VibeCatalog.BlendedVibeProfile vibeProfile) {
        return vibeProfile != null && vibeProfile.outwardRouting() && !vibeProfile.antiComponents().isEmpty();
    }

    private double tileSelectionScore(ScenicScoreTile tile,
                                      VibeCatalog.BlendedVibeProfile vibeProfile,
                                      double scenicScore,
                                      double distanceKm,
                                      double targetRadiusKm) {
        if (vibeProfile == null) {
            return scenicScore;
        }
        double vibeFit = vibeFitScore(tile, vibeProfile);
        double radiusFit = 1.0 - Math.min(1.0, Math.abs(distanceKm - targetRadiusKm) / Math.max(1.0, targetRadiusKm));
        if (vibeProfile.strictIntent()) {
            return (scenicScore * 0.35) + (vibeFit * 0.55) + (radiusFit * 0.10);
        }
        return (scenicScore * 0.55) + (vibeFit * 0.35) + (radiusFit * 0.10);
    }

    private RoadNode offsetNode(RoadNode origin, double distanceKm, double bearingDegrees) {
        final double earthRadiusKm = 6371.0;
        double angularDistance = Math.max(0.0, distanceKm) / earthRadiusKm;
        double bearing = Math.toRadians(bearingDegrees);

        double lat1 = Math.toRadians(origin.getLatitude());
        double lon1 = Math.toRadians(origin.getLongitude());

        double sinLat1 = Math.sin(lat1);
        double cosLat1 = Math.cos(lat1);
        double sinAd = Math.sin(angularDistance);
        double cosAd = Math.cos(angularDistance);

        double lat2 = Math.asin((sinLat1 * cosAd) + (cosLat1 * sinAd * Math.cos(bearing)));
        double lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * sinAd * cosLat1,
            cosAd - (sinLat1 * Math.sin(lat2))
        );

        return new RoadNode(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)));
    }

    private RouteScoreResult computeHybridV2RouteScore(List<RoadNode> path,
                                                       PreferenceWeights preferences,
                                                       VibeCatalog.BlendedVibeProfile vibeProfile,
                                                       int targetMinutes,
                                                       int durationMinutes,
                                                       GeometryStrategy geometryStrategy) {
        if (path == null || path.size() < 2) {
            return new RouteScoreResult(0.0, Map.of("final_score", 0.0));
        }

        RouteCraftMetrics routeCraftMetrics = routeCraftAnalyzer.analyze(path);
        CorridorTileCoverage coverage = findCorridorTileCoverage(path);
        List<ScenicScoreTile> tiles = coverage.orderedTiles();
        if (tiles.isEmpty()) {
            return fallbackRouteScoreResult(path, preferences, targetMinutes, durationMinutes, geometryStrategy, routeCraftMetrics);
        }

        List<Double> landscapeScores = tiles.stream()
            .map(tile -> scoreTile(tile, preferences))
            .toList();
        double landscapeScore = averageScores(landscapeScores);
        double vibeFitScore = tiles.stream()
            .mapToDouble(tile -> vibeFitScore(tile, vibeProfile))
            .average()
            .orElse(landscapeScore);
        double driveQualityScore = computeDriveQualityScore(path, tiles);
        double routeShapeScore = computeRouteShapeScore(path, targetMinutes, durationMinutes);
        double scenicMomentsScore = computeScenicMomentsScore(landscapeScores);
        double urbanPenalty = computeUrbanPressureScore(tiles);
        double roadStressScore = computeRoadStressScore(tiles);
        double waterVisibilityScore = computeWaterVisibilityScore(tiles);
        double waterCrossingScore = computeWaterCrossingScore(tiles);
        double coastalRoadScore = computeCoastalRoadScore(tiles);
        double treeCanopyScore = computeTreeCanopyScore(tiles);
        double scenicPoiScore = computeScenicPoiScore(tiles);
        double viewpointScore = computeViewpointScore(tiles);
        double bridgeCoastalScore = computeBridgeCoastalScore(tiles);
        double edgePenalty = computeStartEndPenalty(tiles, preferences);
        StrategyCorridorMetrics strategyMetrics = computeStrategyCorridorMetrics(tiles, landscapeScores, geometryStrategy);

        double weightedScore = (landscapeScore * V2_LANDSCAPE_WEIGHT)
            + (vibeFitScore * V2_VIBE_FIT_WEIGHT)
            + (driveQualityScore * V2_DRIVE_QUALITY_WEIGHT)
            + (routeShapeScore * V2_ROUTE_SHAPE_WEIGHT)
            + (scenicMomentsScore * V2_SCENIC_MOMENTS_WEIGHT)
            - (urbanPenalty * V2_URBAN_PENALTY_WEIGHT)
            - (edgePenalty * V2_START_END_PENALTY_WEIGHT)
            - (strategyMetrics.mismatchPenalty() * V2_STRATEGY_MISMATCH_PENALTY_WEIGHT)
            - (routeCraftMetrics.backtrackingPenalty() * V2_BACKTRACKING_PENALTY_WEIGHT);

        double finalScore = clamp01(weightedScore);
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("final_score", finalScore);
        breakdown.put("landscape_score", landscapeScore);
        breakdown.put("vibe_fit_score", vibeFitScore);
        breakdown.put("drive_quality_score", driveQualityScore);
        breakdown.put("route_shape_score", routeShapeScore);
        breakdown.put("scenic_moments_score", scenicMomentsScore);
        breakdown.put("urban_penalty", urbanPenalty);
        breakdown.put("road_stress_score", roadStressScore);
        breakdown.put("water_visibility_score", waterVisibilityScore);
        breakdown.put("water_crossing_score", waterCrossingScore);
        breakdown.put("coastal_road_score", coastalRoadScore);
        breakdown.put("tree_canopy_score", treeCanopyScore);
        breakdown.put("scenic_poi_score", scenicPoiScore);
        breakdown.put("viewpoint_score", viewpointScore);
        breakdown.put("bridge_coastal_score", bridgeCoastalScore);
        breakdown.put("start_end_penalty", edgePenalty);
        breakdown.put("corridor_urban_pressure", urbanPenalty);
        breakdown.put("edge_urban_pressure", edgePenalty);
        breakdown.put("strategy_fit_score", strategyMetrics.strategyFitScore());
        breakdown.put("strategy_mismatch_penalty", strategyMetrics.mismatchPenalty());
        addRouteCraftBreakdown(breakdown, routeCraftMetrics);
        breakdown.put("water_corridor_share", strategyMetrics.waterCorridorShare());
        breakdown.put("open_space_corridor_share", strategyMetrics.openSpaceCorridorShare());
        breakdown.put("quiet_corridor_share", strategyMetrics.quietCorridorShare());
        breakdown.put("photo_peak_score", strategyMetrics.photoPeakScore());
        breakdown.put("curve_elevation_corridor_share", strategyMetrics.curveElevationCorridorShare());
        breakdown.put("corridor_tile_samples", (double) tiles.size());
        breakdown.put("target_minutes", (double) targetMinutes);
        breakdown.put("duration_minutes", (double) durationMinutes);
        breakdown.put("geometry_strategy_code", geometryStrategyCode(geometryStrategy));
        return new RouteScoreResult(finalScore, breakdown);
    }

    private RouteScoreResult fallbackRouteScoreResult(List<RoadNode> path,
                                                      PreferenceWeights preferences,
                                                      int targetMinutes,
                                                      int durationMinutes,
                                                      GeometryStrategy geometryStrategy,
                                                      RouteCraftMetrics routeCraftMetrics) {
        double fallbackScore = estimateFallbackScenicDensity(path, preferences);
        double finalScore = clamp01(fallbackScore - (routeCraftMetrics.backtrackingPenalty() * V2_BACKTRACKING_PENALTY_WEIGHT));
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("final_score", finalScore);
        breakdown.put("fallback_scenic_density", fallbackScore);
        breakdown.put("landscape_score", finalScore);
        breakdown.put("vibe_fit_score", finalScore);
        breakdown.put("drive_quality_score", estimateCurvatureScore(path));
        breakdown.put("route_shape_score", computeRouteShapeScore(path, targetMinutes, durationMinutes));
        breakdown.put("scenic_moments_score", finalScore);
        breakdown.put("urban_penalty", 0.0);
        breakdown.put("road_stress_score", 0.0);
        breakdown.put("water_visibility_score", 0.0);
        breakdown.put("water_crossing_score", 0.0);
        breakdown.put("coastal_road_score", 0.0);
        breakdown.put("tree_canopy_score", 0.0);
        breakdown.put("scenic_poi_score", 0.0);
        breakdown.put("viewpoint_score", 0.0);
        breakdown.put("bridge_coastal_score", 0.0);
        breakdown.put("start_end_penalty", 0.0);
        breakdown.put("corridor_urban_pressure", 0.0);
        breakdown.put("edge_urban_pressure", 0.0);
        breakdown.put("strategy_fit_score", fallbackScore);
        breakdown.put("strategy_mismatch_penalty", 0.0);
        addRouteCraftBreakdown(breakdown, routeCraftMetrics);
        breakdown.put("water_corridor_share", fallbackScore);
        breakdown.put("open_space_corridor_share", fallbackScore);
        breakdown.put("quiet_corridor_share", fallbackScore);
        breakdown.put("photo_peak_score", fallbackScore);
        breakdown.put("curve_elevation_corridor_share", fallbackScore);
        breakdown.put("corridor_tile_samples", 0.0);
        breakdown.put("target_minutes", (double) targetMinutes);
        breakdown.put("duration_minutes", (double) durationMinutes);
        breakdown.put("geometry_strategy_code", geometryStrategyCode(geometryStrategy));
        return new RouteScoreResult(finalScore, breakdown);
    }

    private Map<String, Double> withDurationCalibrationBreakdown(Map<String, Double> source,
                                                                 List<RoadNode> requestedWaypoints,
                                                                 RoadNode start,
                                                                 int targetMinutes,
                                                                 int durationMinutes) {
        Map<String, Double> breakdown = new LinkedHashMap<>(source == null ? Map.of() : source);
        double requestedRadiusKm = requestedAverageRadiusKm(start, requestedWaypoints);
        int requestedWaypointCount = requestedWaypointCount(requestedWaypoints);
        breakdown.put("requested_avg_radius_km", requestedRadiusKm);
        breakdown.put("requested_waypoint_count", (double) requestedWaypointCount);
        breakdown.put("duration_fit_ratio", targetMinutes <= 0 ? 1.0 : durationMinutes / (double) targetMinutes);
        breakdown.put("duration_calibration_bucket_minutes", (double) RouteDurationCalibration.bucketMinutes(targetMinutes));
        return breakdown;
    }

    private RoadNode startNode(RouteJob job) {
        return new RoadNode(job.getStartLatitude(), job.getStartLongitude());
    }

    private double requestedAverageRadiusKm(RoadNode start, List<RoadNode> requestedWaypoints) {
        if (start == null || requestedWaypoints == null || requestedWaypoints.size() < 2) {
            return 0.0;
        }
        return requestedWaypoints.stream()
            .skip(1)
            .mapToDouble(point -> distanceKm(start, point))
            .average()
            .orElse(0.0);
    }

    private int requestedWaypointCount(List<RoadNode> requestedWaypoints) {
        if (requestedWaypoints == null || requestedWaypoints.size() < 2) {
            return 0;
        }
        return requestedWaypoints.size() - 1;
    }

    private double geometryStrategyCode(GeometryStrategy geometryStrategy) {
        GeometryStrategy activeStrategy = geometryStrategy == null ? GeometryStrategy.BALANCED_VARIETY : geometryStrategy;
        return activeStrategy.ordinal();
    }

    private StrategyCorridorMetrics computeStrategyCorridorMetrics(List<ScenicScoreTile> tiles,
                                                                   List<Double> landscapeScores,
                                                                   GeometryStrategy geometryStrategy) {
        if (tiles == null || tiles.isEmpty()) {
            return new StrategyCorridorMetrics(0.0, 0.0, 0.0, 0.0, 0.0, DEFAULT_SCENIC_FALLBACK, 0.0);
        }

        double waterShare = 0.0;
        double openShare = 0.0;
        double quietShare = 0.0;
        double curveElevationShare = 0.0;
        double photoPeak = 0.0;
        List<Double> openSignals = new ArrayList<>();
        List<Double> quietSignals = new ArrayList<>();
        List<Double> curveElevationSignals = new ArrayList<>();
        for (ScenicScoreTile tile : tiles) {
            ComponentScores components = componentScores(tile);
            double urbanPressure = computeUrbanPressureScore(List.of(tile));
            double openSpace = openSpaceScore(tile, components);
            double scenicPoi = clamp01(tile.getScenicPoiScore());
            double viewpoint = clamp01(tile.getViewpointScore());
            double bridgeCoastal = clamp01(tile.getBridgeCoastalScore());
            double photoSignal = Math.max(
                Math.max(components.water(), components.elevation()),
                Math.max(Math.max(components.poi(), scenicPoi), viewpoint)
            );
            double curveElevationSignal = Math.max(components.curves(), components.elevation());

            double lowUrbanPressure = 1.0 - urbanPressure;
            double lowRoadStress = 1.0 - clamp01(tile.getRoadStressScore());
            double quietSignal = clamp01(
                (components.solitude() * 0.44)
                    + (components.greenery() * 0.18)
                    + (lowUrbanPressure * 0.20)
                    + (lowRoadStress * 0.08)
                    + (clamp01(tile.getDarknessScore()) * 0.10)
            );
            double openSignal = clamp01((openSpace * 0.68) + (lowUrbanPressure * 0.20) + (lowRoadStress * 0.12));

            double openMembership = gradedMembership(openSignal, 0.40, 0.72);
            double quietMembership = gradedMembership(quietSignal, 0.40, 0.72);
            double curveElevationMembership = gradedMembership(curveElevationSignal, 0.38, 0.70);

            waterShare += gradedMembership(Math.max(components.water(), bridgeCoastal), 0.38, 0.70);
            openShare += openMembership;
            quietShare += quietMembership;
            curveElevationShare += curveElevationMembership;
            openSignals.add(openMembership);
            quietSignals.add(quietMembership);
            curveElevationSignals.add(curveElevationMembership);
            photoPeak = Math.max(photoPeak, photoSignal);
        }

        double sampleCount = Math.max(1.0, tiles.size());
        waterShare = clamp01(waterShare / sampleCount);
        openShare = clamp01(openShare / sampleCount);
        quietShare = clamp01(quietShare / sampleCount);
        curveElevationShare = clamp01(curveElevationShare / sampleCount);
        double scenicPeak = landscapeScores == null || landscapeScores.isEmpty()
            ? DEFAULT_SCENIC_FALLBACK
            : landscapeScores.stream().mapToDouble(Double::doubleValue).max().orElse(DEFAULT_SCENIC_FALLBACK);
        photoPeak = clamp01(Math.max(photoPeak, scenicPeak));

        GeometryStrategy activeStrategy = geometryStrategy == null ? GeometryStrategy.BALANCED_VARIETY : geometryStrategy;
        double strategyFit = switch (activeStrategy) {
            case WATER_FOLLOWING -> waterShare;
            case OPEN_SPACE_ESCAPE -> corridorStretchFit(openShare, openSignals);
            case QUIET_LOW_PRESSURE -> corridorStretchFit(quietShare, quietSignals);
            case PHOTO_PEAKS -> photoPeak;
            case CURVY_ELEVATION -> corridorStretchFit(curveElevationShare, curveElevationSignals);
            case BALANCED_VARIETY -> computeBalancedStrategyFit(waterShare, openShare, quietShare, photoPeak, curveElevationShare);
        };
        double mismatchPenalty = strategyMismatchPenalty(activeStrategy, strategyFit);
        return new StrategyCorridorMetrics(
            waterShare,
            openShare,
            quietShare,
            curveElevationShare,
            photoPeak,
            clamp01(strategyFit),
            mismatchPenalty
        );
    }

    private double computeBalancedStrategyFit(double waterShare,
                                              double openShare,
                                              double quietShare,
                                              double photoPeak,
                                              double curveElevationShare) {
        double shareVariety = List.of(waterShare, openShare, quietShare, curveElevationShare).stream()
            .filter(value -> value >= 0.25)
            .count() / 4.0;
        return clamp01((shareVariety * 0.58) + (photoPeak * 0.22) + (average(waterShare, openShare, quietShare, curveElevationShare) * 0.20));
    }

    private double strategyMismatchPenalty(GeometryStrategy geometryStrategy, double strategyFit) {
        double expectedFit = switch (geometryStrategy) {
            case PHOTO_PEAKS -> 0.68;
            case BALANCED_VARIETY -> 0.38;
            case WATER_FOLLOWING, OPEN_SPACE_ESCAPE, QUIET_LOW_PRESSURE, CURVY_ELEVATION -> 0.34;
        };
        return clamp01((expectedFit - strategyFit) / Math.max(0.1, expectedFit));
    }

    private double corridorStretchFit(double averageMembership, List<Double> memberships) {
        return clamp01((averageMembership * 0.62) + (topAverage(memberships, 0.35) * 0.38));
    }

    private double topAverage(List<Double> values, double topFraction) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        int limit = Math.max(1, (int) Math.ceil(values.size() * clamp01(topFraction)));
        return values.stream()
            .sorted(Comparator.reverseOrder())
            .limit(limit)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }

    private double gradedMembership(double value, double floor, double fullCreditAt) {
        double span = Math.max(0.01, fullCreditAt - floor);
        return clamp01((value - floor) / span);
    }

    private double computeDriveQualityScore(List<RoadNode> path, List<ScenicScoreTile> tiles) {
        double curvature = estimateCurvatureScore(path);
        double lowUrbanPressure = 1.0 - computeUrbanPressureScore(tiles);
        double lowRoadStress = 1.0 - computeRoadStressScore(tiles);
        return clamp01((curvature * 0.52) + (lowUrbanPressure * 0.28) + (lowRoadStress * 0.20));
    }

    private double computeRouteShapeScore(List<RoadNode> path, int targetMinutes, int durationMinutes) {
        double safeTarget = Math.max(1.0, targetMinutes);
        double budgetFit = 1.0 - clamp01(Math.abs(durationMinutes - safeTarget) / safeTarget);
        double utilization = clamp01(durationMinutes / safeTarget);
        double closureDistanceKm = distanceKm(path.getFirst(), path.getLast());
        double closureScore = clamp01(1.0 - (closureDistanceKm / 10.0));
        return clamp01((budgetFit * 0.45) + (utilization * 0.25) + (closureScore * 0.30));
    }

    private double computeScenicMomentsScore(List<Double> landscapeScores) {
        if (landscapeScores == null || landscapeScores.isEmpty()) {
            return DEFAULT_SCENIC_FALLBACK;
        }

        double peakScore = landscapeScores.stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(DEFAULT_SCENIC_FALLBACK);
        double continuityScore = landscapeScores.stream()
            .filter(score -> score >= V2_CONTINUITY_THRESHOLD)
            .count() / (double) landscapeScores.size();
        double consistencyScore = 1.0 - clamp01(standardDeviation(landscapeScores) / 0.35);

        return clamp01((peakScore * 0.32) + (continuityScore * 0.46) + (consistencyScore * 0.22));
    }

    private void addRouteCraftBreakdown(Map<String, Double> breakdown, RouteCraftMetrics metrics) {
        breakdown.put("repeated_corridor_cell_share", metrics.repeatedCorridorCellShare());
        breakdown.put("reverse_overlap_share", metrics.reverseOverlapShare());
        breakdown.put("leg_separation_score", metrics.legSeparationScore());
        breakdown.put("self_intersection_or_near_duplicate_score", metrics.selfIntersectionOrNearDuplicateScore());
        breakdown.put("backtracking_penalty", metrics.backtrackingPenalty());
    }

    private double computeUrbanPressureScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> (clamp01(tile.getUrbanPenaltyScore()) * 0.65)
                    + (clamp01(tile.getBuildingDensityScore()) * 0.25)
                    + (clamp01(tile.getRoadDensity()) * 0.10))
                .average()
                .orElse(0.0)
        );
    }

    private double computeRoadStressScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getRoadStressScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeWaterVisibilityScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getWaterVisibilityScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeWaterCrossingScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getWaterCrossingScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeCoastalRoadScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getCoastalRoadScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeTreeCanopyScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getTreeCanopyScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeScenicPoiScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getScenicPoiScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeViewpointScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getViewpointScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeBridgeCoastalScore(List<ScenicScoreTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        return clamp01(
            tiles.stream()
                .mapToDouble(tile -> clamp01(tile.getBridgeCoastalScore()))
                .average()
                .orElse(0.0)
        );
    }

    private double computeStartEndPenalty(List<ScenicScoreTile> orderedTiles, PreferenceWeights preferences) {
        if (orderedTiles == null || orderedTiles.isEmpty()) {
            return 0.0;
        }

        List<ScenicScoreTile> edgeTiles = new ArrayList<>();
        int edgeCount = Math.min(V2_EDGE_SAMPLE_COUNT, orderedTiles.size());
        for (int i = 0; i < edgeCount; i++) {
            edgeTiles.add(orderedTiles.get(i));
        }
        int trailingStart = Math.max(edgeCount, orderedTiles.size() - edgeCount);
        for (int i = trailingStart; i < orderedTiles.size(); i++) {
            edgeTiles.add(orderedTiles.get(i));
        }

        double edgeScenic = edgeTiles.stream()
            .mapToDouble(tile -> scoreTile(tile, preferences))
            .average()
            .orElse(DEFAULT_SCENIC_FALLBACK);
        double edgeUrbanPressure = computeUrbanPressureScore(edgeTiles);
        return clamp01(((1.0 - edgeScenic) * 0.55) + (edgeUrbanPressure * 0.45));
    }

    private double computeScenicDensity(List<RoadNode> path, PreferenceWeights preferences) {
        if (path == null || path.size() < 2) {
            return 0.0;
        }

        List<ScenicScoreTile> tiles = findCorridorTileCoverage(path).orderedTiles();
        if (tiles.isEmpty()) {
            return estimateFallbackScenicDensity(path, preferences);
        }

        double weightedScoreSum = 0.0;
        int coveredTiles = 0;
        for (ScenicScoreTile tile : tiles) {
            weightedScoreSum += scoreTile(tile, preferences);
            coveredTiles++;
        }

        if (coveredTiles == 0) {
            return estimateFallbackScenicDensity(path, preferences);
        }
        return clamp01(weightedScoreSum / coveredTiles);
    }

    private CorridorTileCoverage findCorridorTileCoverage(List<RoadNode> path) {
        List<RoadNode> samples = samplePath(path, Math.max(100, config.getCorridorSampleMeters()));
        if (samples.isEmpty()) {
            return new CorridorTileCoverage(List.of());
        }

        int configuredResolution = Math.max(0, config.getH3Resolution());
        CorridorTileCoverage coverage = findTilesForSamples(samples, configuredResolution, false);
        if (coverage.orderedTiles().isEmpty() && configuredResolution != DEFAULT_H3_RESOLUTION) {
            coverage = findTilesForSamples(samples, DEFAULT_H3_RESOLUTION, false);
            if (!coverage.orderedTiles().isEmpty()) {
                logger.debug(
                    "No corridor tiles found at configured H3 resolution {}; scoring corridor at fallback resolution {}",
                    configuredResolution,
                    DEFAULT_H3_RESOLUTION
                );
            }
        }
        if (coverage.orderedTiles().isEmpty()) {
            coverage = findTilesForSamples(samples, DEFAULT_H3_RESOLUTION, true);
        }
        return coverage;
    }

    private double estimateFallbackScenicDensity(List<RoadNode> path, PreferenceWeights preferences) {
        if (path == null || path.isEmpty()) {
            return DEFAULT_SCENIC_FALLBACK;
        }

        RoadNode start = path.getFirst();
        int ringSize = Math.max(2, Math.min(config.getTileSelectionRingMax(), config.getTileSelectionRingMin() + 2));
        List<ScenicScoreTile> startTiles = findTilesNearStart(start, ringSize, DEFAULT_H3_RESOLUTION);
        if (!startTiles.isEmpty()) {
            return clamp01(
                startTiles.stream()
                    .mapToDouble(tile -> scoreTile(tile, preferences))
                    .average()
                    .orElse(DEFAULT_SCENIC_FALLBACK)
            );
        }

        double curvature = estimateCurvatureScore(path);
        double closureDistanceKm = distanceKm(path.getFirst(), path.getLast());
        double closureBonus = clamp01(1.0 - (closureDistanceKm / 15.0));
        return clamp01((DEFAULT_SCENIC_FALLBACK * 0.7) + (curvature * 0.2) + (closureBonus * 0.1));
    }

    private CorridorTileCoverage findTilesForSamples(List<RoadNode> samples,
                                                     int resolution,
                                                     boolean includeNeighborExpansion) {
        List<String> orderedSampleIndexes = new ArrayList<>();
        Set<String> h3Indexes = new HashSet<>();
        for (RoadNode point : samples) {
            String h3Index = H3Utils.getH3Index(point.getLatitude(), point.getLongitude(), resolution);
            orderedSampleIndexes.add(h3Index);
            h3Indexes.add(h3Index);
            if (includeNeighborExpansion) {
                h3Indexes.addAll(H3Utils.getKRing(h3Index, SAMPLE_NEIGHBOR_EXPANSION_RING));
            }
        }

        if (h3Indexes.isEmpty()) {
            return new CorridorTileCoverage(List.of());
        }
        List<ScenicScoreTile> fetchedTiles = scenicTileLookupService.findByH3Indexes(h3Indexes);
        if (fetchedTiles.isEmpty()) {
            return new CorridorTileCoverage(List.of());
        }

        Map<String, ScenicScoreTile> tileByH3 = fetchedTiles.stream()
            .collect(Collectors.toMap(
                ScenicScoreTile::getH3Index,
                tile -> tile,
                (left, right) -> left
            ));
        List<ScenicScoreTile> orderedTiles = orderedSampleIndexes.stream()
            .map(tileByH3::get)
            .filter(tile -> tile != null)
            .toList();

        if (orderedTiles.isEmpty()) {
            orderedTiles = fetchedTiles;
        }
        return new CorridorTileCoverage(orderedTiles);
    }

    private PreferenceWeights resolvePreferenceWeights(List<String> vibes, String rawPreferenceVectorJson) {
        PreferenceWeights defaults = blendVibeDefaults(vibes);
        Map<String, Double> overrides = parsePreferenceOverrides(rawPreferenceVectorJson);
        PreferenceWeights weighted = overrides.isEmpty() ? defaults : defaults.withOverrides(overrides);
        return applyCalibration(weighted, vibes);
    }

    private List<String> resolveJobVibes(RouteJob job) {
        List<String> parsed = parseVibesJson(job.getVibesJson());
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return List.of(normalizeVibe(job.getVibe()));
    }

    private List<String> parseVibesJson(String rawVibesJson) {
        if (rawVibesJson == null || rawVibesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(rawVibesJson, new TypeReference<>() {
            });
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String vibe : raw) {
                if (vibe == null || vibe.isBlank()) {
                    continue;
                }
                String normalizedVibe = normalizeVibe(vibe);
                if (VibeCatalog.isSupported(normalizedVibe) && seen.add(normalizedVibe)) {
                    normalized.add(normalizedVibe);
                }
            }
            return List.copyOf(normalized);
        } catch (Exception ex) {
            logger.warn("Failed to parse vibes JSON '{}': {}", rawVibesJson, ex.getMessage());
            return List.of();
        }
    }

    private PreferenceWeights blendVibeDefaults(List<String> vibes) {
        List<String> activeVibes = (vibes == null || vibes.isEmpty()) ? List.of(VibeCatalog.defaultVibe()) : vibes;
        double water = 0.0;
        double greenery = 0.0;
        double elevation = 0.0;
        double solitude = 0.0;
        double curves = 0.0;
        double poi = 0.0;

        for (String vibe : activeVibes) {
            VibeCatalog.ComponentWeights defaults = VibeCatalog.weightsFor(vibe);
            water += defaults.water();
            greenery += defaults.greenery();
            elevation += defaults.elevation();
            solitude += defaults.solitude();
            curves += defaults.curves();
            poi += defaults.poi();
        }

        double count = Math.max(1.0, activeVibes.size());
        return new PreferenceWeights(
            water / count,
            greenery / count,
            elevation / count,
            solitude / count,
            curves / count,
            poi / count
        );
    }

    private PreferenceWeights applyCalibration(PreferenceWeights weights, List<String> vibes) {
        if (vibes == null || vibes.isEmpty()) {
            return weights;
        }

        List<RouteWeightCalibration> calibrations = routeWeightCalibrationRepository.findByVibeIn(vibes);
        if (calibrations == null || calibrations.isEmpty()) {
            return weights;
        }

        double count = calibrations.size();
        double waterMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getWaterMultiplier).average().orElse(1.0);
        double greeneryMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getGreeneryMultiplier).average().orElse(1.0);
        double elevationMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getElevationMultiplier).average().orElse(1.0);
        double solitudeMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getSolitudeMultiplier).average().orElse(1.0);
        double curvesMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getCurvesMultiplier).average().orElse(1.0);
        double poiMultiplier = calibrations.stream().mapToDouble(RouteWeightCalibration::getPoiMultiplier).average().orElse(1.0);

        if (count <= 0.0) {
            return weights;
        }

        return new PreferenceWeights(
            weights.water() * waterMultiplier,
            weights.greenery() * greeneryMultiplier,
            weights.elevation() * elevationMultiplier,
            weights.solitude() * solitudeMultiplier,
            weights.curves() * curvesMultiplier,
            weights.poi() * poiMultiplier
        );
    }

    private Map<String, Double> parsePreferenceOverrides(String rawPreferenceVectorJson) {
        if (rawPreferenceVectorJson == null || rawPreferenceVectorJson.isBlank()) {
            return Map.of();
        }

        try {
            Map<String, Object> raw = objectMapper.readValue(rawPreferenceVectorJson, new TypeReference<>() {
            });
            Map<String, Double> normalized = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String key = normalizePreferenceKey(entry.getKey());
                if (key == null) {
                    continue;
                }

                Double value = normalizePreferenceValue(entry.getValue());
                if (value != null) {
                    normalized.put(key, value);
                }
            }
            return Map.copyOf(normalized);
        } catch (Exception ex) {
            logger.warn("Failed to parse preference vector JSON '{}': {}", rawPreferenceVectorJson, ex.getMessage());
            return Map.of();
        }
    }

    private String normalizePreferenceKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }

        String normalized = rawKey.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return PREFERENCE_KEY_ALIASES.get(normalized);
    }

    private Double normalizePreferenceValue(Object rawValue) {
        if (rawValue instanceof Number number) {
            return clamp01(number.doubleValue());
        }

        if (rawValue instanceof String text && !text.isBlank()) {
            try {
                return clamp01(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private RoadNode tileCenter(ScenicScoreTile tile, RoadNode fallback) {
        if (tile == null) {
            return fallback;
        }
        if (isValidH3Index(tile.getH3Index())) {
            try {
                LatLng center = H3Utils.getCellCenter(tile.getH3Index());
                return new RoadNode(center.lat, center.lng);
            } catch (RuntimeException ex) {
                logger.debug("Failed to resolve H3 center for scenic tile {}", tile.getH3Index(), ex);
            }
        }
        if (tile.getGeometry() != null && !tile.getGeometry().isEmpty()) {
            return new RoadNode(
                tile.getGeometry().getCentroid().getY(),
                tile.getGeometry().getCentroid().getX()
            );
        }
        return fallback;
    }

    private boolean isValidH3Index(String h3Index) {
        if (h3Index == null || h3Index.isBlank()) {
            return false;
        }
        try {
            return H3Utils.isValidH3Index(h3Index);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            return VibeCatalog.defaultVibe();
        }
        String normalized = VibeCatalog.normalize(vibe);
        return normalized.isBlank() ? VibeCatalog.defaultVibe() : normalized;
    }

    private long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private double scoreTile(ScenicScoreTile tile, PreferenceWeights preferences) {
        return scenicScoreCalculator.scoreTile(tile, preferences);
    }

    private record CorridorTileCoverage(List<ScenicScoreTile> orderedTiles) {
    }

    private record RouteScoreResult(double finalScore, Map<String, Double> breakdown) {
    }

    private record StrategyCorridorMetrics(double waterCorridorShare,
                                           double openSpaceCorridorShare,
                                           double quietCorridorShare,
                                           double curveElevationCorridorShare,
                                           double photoPeakScore,
                                           double strategyFitScore,
                                           double mismatchPenalty) {
    }

    private record DurationCalibrationHint(double radiusMultiplier,
                                           double learnedWaypointCount,
                                           int sampleCount) {
        private boolean active() {
            return sampleCount >= MIN_DURATION_CALIBRATION_SAMPLES;
        }

        private static DurationCalibrationHint empty() {
            return new DurationCalibrationHint(1.0, 6.0, 0);
        }
    }

    private enum GeometryStrategy {
        WATER_FOLLOWING,
        OPEN_SPACE_ESCAPE,
        PHOTO_PEAKS,
        QUIET_LOW_PRESSURE,
        CURVY_ELEVATION,
        BALANCED_VARIETY
    }

    private record TileLookupResult(List<ScenicScoreTile> tiles,
                                    long h3CellBuildMs,
                                    long lookupMs,
                                    int cellCount) {
    }

    private record PreAnchorTileCandidate(ScenicScoreTile tile,
                                          RoadNode center,
                                          double scenicScore,
                                          double selectionScore) {
    }

    private record TileCandidate(ScenicScoreTile tile,
                                 RoadNode center,
                                 double scenicScore,
                                 double selectionScore,
                                 double distanceKm) {
    }
}
