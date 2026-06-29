package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
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
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import com.moodride.routeworker.service.OsrmTripClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

@Service
public class RoutePlanner {

    private static final Logger logger = LoggerFactory.getLogger(RoutePlanner.class);
    private static final int DEFAULT_H3_RESOLUTION = H3Utils.DEFAULT_RESOLUTION;
    private static final int SAMPLE_NEIGHBOR_EXPANSION_RING = 2;
    private static final double FLAT_SCENIC_SCORE_EPSILON = 0.0001;
    private static final double DEFAULT_SCENIC_FALLBACK = 0.35;
    private static final int ROUTE_OPTION_COUNT = 3;
    private static final double MAX_EFFECTIVE_DURATION_OVERRUN_RATIO = 1.15;
    private static final double DIVERSE_ROUTE_SIMILARITY_THRESHOLD = 0.72;
    private static final double ROUTE_OPTION_MIN_SEPARATION_KM = 0.35;
    private static final double SHORTER_PROFILE_TARGET_RATIO = 0.78;
    private static final double SHORTER_PROFILE_MIN_USEFUL_RATIO = 0.60;
    private static final double MIN_USEFUL_DURATION_RATIO = 0.55;
    private static final int MAX_CORRIDOR_SIGNATURE_SAMPLES = 96;
    private static final int MAX_GEOMETRY_SEPARATION_SAMPLES = 80;
    private static final int MIN_VIBE_AVAILABILITY_TILES = 3;
    private static final int VIBE_AVAILABILITY_TOP_N = 12;
    private static final double MIN_VIBE_BEST_FIT_SCORE = 0.32;
    private static final double MIN_VIBE_AVG_FIT_SCORE = 0.26;
    private static final int TARGET_ANCHOR_LIMIT = 14;
    private static final int TARGET_ANCHOR_PAIR_LIMIT = 24;
    private static final double TARGET_ANCHOR_MIN_SEPARATION_KM = 2.0;
    private static final String HYBRID_OSRM_V2 = "hybrid_osrm_v2";
    private static final double V2_LANDSCAPE_WEIGHT = 0.38;
    private static final double V2_VIBE_FIT_WEIGHT = 0.24;
    private static final double V2_DRIVE_QUALITY_WEIGHT = 0.14;
    private static final double V2_ROUTE_SHAPE_WEIGHT = 0.10;
    private static final double V2_SCENIC_MOMENTS_WEIGHT = 0.14;
    private static final double V2_URBAN_PENALTY_WEIGHT = 0.10;
    private static final double V2_START_END_PENALTY_WEIGHT = 0.06;
    private static final double V2_STRATEGY_MISMATCH_PENALTY_WEIGHT = 0.08;
    private static final double V2_BACKTRACKING_PENALTY_WEIGHT = 0.08;
    private static final double MOST_SCENIC_BACKTRACKING_PROFILE_WEIGHT = 0.18;
    private static final double BALANCED_BACKTRACKING_PROFILE_WEIGHT = 0.12;
    private static final double SHORTER_BACKTRACKING_PROFILE_WEIGHT = 0.07;
    private static final double V2_CONTINUITY_THRESHOLD = 0.45;
    private static final int V2_EDGE_SAMPLE_COUNT = 4;
    private static final int ROUTE_CRAFT_H3_RESOLUTION = DEFAULT_H3_RESOLUTION + 1;
    private static final int ROUTE_CRAFT_SAMPLE_METERS = 350;
    private static final int ROUTE_CRAFT_NEAR_DUPLICATE_WINDOW = 4;
    private static final double ROUTE_CRAFT_LEG_SEPARATION_GOOD_KM = 0.85;
    private static final double ROUTE_CRAFT_NEAR_DUPLICATE_KM = 0.12;
    private static final int STRATEGY_ANCHOR_LIMIT = 12;
    private static final int STRATEGY_PAIR_LIMIT = 18;
    private static final double STRATEGY_ANCHOR_MIN_SEPARATION_KM = 1.6;
    private static final double STRICT_MOUNTAIN_STRATEGY_MIN_FIT = 0.32;
    private static final double STRICT_MOUNTAIN_MIN_CURVE_ELEVATION_SHARE = 0.28;
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

    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final RouteWeightCalibrationRepository routeWeightCalibrationRepository;
    private final RouteDurationCalibrationRepository routeDurationCalibrationRepository;
    private final OsrmTripClient osrmTripClient;
    private final ApplicationConfiguration config;
    private final ObjectMapper objectMapper;
    private final ScenicScoreCalculator scenicScoreCalculator;

    public RoutePlanner(ScenicScoreTileRepository scenicScoreTileRepository,
                        RouteWeightCalibrationRepository routeWeightCalibrationRepository,
                        RouteDurationCalibrationRepository routeDurationCalibrationRepository,
                        OsrmTripClient osrmTripClient,
                        ApplicationConfiguration config,
                        ObjectMapper objectMapper,
                        ScenicScoreCalculator scenicScoreCalculator) {
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.routeWeightCalibrationRepository = routeWeightCalibrationRepository;
        this.routeDurationCalibrationRepository = routeDurationCalibrationRepository;
        this.osrmTripClient = osrmTripClient;
        this.config = config;
        this.objectMapper = objectMapper;
        this.scenicScoreCalculator = scenicScoreCalculator;
    }

    public RouteCandidate generateRoute(RouteJob job) {
        return generateRouteOptions(job).getFirst();
    }

    public List<RouteCandidate> generateRouteOptions(RouteJob job) {
        RoadNode start = new RoadNode(job.getStartLatitude(), job.getStartLongitude());
        List<String> requestVibes = resolveJobVibes(job);
        VibeCatalog.BlendedVibeProfile vibeProfile = VibeCatalog.blendProfiles(requestVibes);
        PreferenceWeights preferences = resolvePreferenceWeights(requestVibes, job.getPreferenceVector());
        GeometryStrategy geometryStrategy = resolveGeometryStrategy(vibeProfile);
        DurationCalibrationHint durationCalibration = resolveDurationCalibrationHint(start, job, geometryStrategy);

        List<TileCandidate> scoredTiles = scoreNearbyTiles(
            start,
            job.getTimeBudgetMinutes(),
            preferences,
            vibeProfile,
            durationCalibration
        );
        validateVibeAvailability(job, requestVibes, vibeProfile, scoredTiles);
        List<List<RoadNode>> waypointVariants = buildHybridV2WaypointRings(
            start,
            scoredTiles,
            job.getTimeBudgetMinutes(),
            vibeProfile,
            geometryStrategy,
            durationCalibration
        );
        List<RouteCandidate> hybridCandidates = collectHybridCandidates(job, waypointVariants, preferences, vibeProfile, geometryStrategy);
        if (hybridCandidates.isEmpty() || !hasDiverseCandidatePool(hybridCandidates, job.getTimeBudgetMinutes())) {
            List<List<RoadNode>> syntheticVariants = buildSyntheticWaypointRings(start, job.getTimeBudgetMinutes());
            List<RouteCandidate> syntheticCandidates = collectHybridCandidates(job, syntheticVariants, preferences, vibeProfile, geometryStrategy);
            if (hybridCandidates.isEmpty()) {
                hybridCandidates = syntheticCandidates;
            } else if (!syntheticCandidates.isEmpty()) {
                hybridCandidates = combineCandidates(hybridCandidates, syntheticCandidates);
            }
            if (!syntheticCandidates.isEmpty()) {
                logger.info("Hybrid routing used synthetic waypoint variants for job {}", job.getId());
            }
        }
        if (hybridCandidates.size() < ROUTE_OPTION_COUNT || needsBudgetRescue(hybridCandidates, job.getTimeBudgetMinutes())) {
            List<List<RoadNode>> budgetRescueVariants = buildBudgetRescueWaypointRings(start, job.getTimeBudgetMinutes());
            List<RouteCandidate> budgetRescueCandidates = collectHybridCandidates(job, budgetRescueVariants, preferences, vibeProfile, geometryStrategy);
            if (!budgetRescueCandidates.isEmpty()) {
                hybridCandidates = combineCandidates(hybridCandidates, budgetRescueCandidates);
                logger.info("Hybrid routing used budget-rescue waypoint variants for job {}", job.getId());
            }
        }
        if (!hybridCandidates.isEmpty()) {
            List<RouteCandidate> differentiated = differentiateFlatScenicScores(hybridCandidates, job.getTimeBudgetMinutes());
            List<RouteCandidate> candidatePool = contractCandidatePool(differentiated, vibeProfile, geometryStrategy, requestVibes, job);
            List<RouteCandidate> selected = selectRouteOptions(candidatePool, job.getTimeBudgetMinutes());
            if (selected.size() >= ROUTE_OPTION_COUNT && minRouteSeparationKm(selected) < ROUTE_OPTION_MIN_SEPARATION_KM) {
                List<RouteCandidate> rescueCandidates = collectHybridCandidates(
                    job,
                    buildDiversityRescueWaypointRings(start, job.getTimeBudgetMinutes()),
                    preferences,
                    vibeProfile,
                    geometryStrategy
                );
                if (!rescueCandidates.isEmpty()) {
                    List<RouteCandidate> expanded = differentiateFlatScenicScores(
                        combineCandidates(differentiated, rescueCandidates),
                        job.getTimeBudgetMinutes()
                    );
                    List<RouteCandidate> expandedPool = contractCandidatePool(expanded, vibeProfile, geometryStrategy, requestVibes, job);
                    selected = selectRouteOptions(expandedPool, job.getTimeBudgetMinutes());
                    logger.info("Hybrid routing used diversity-rescue waypoint variants for job {}", job.getId());
                }
            }
            if (requiresStrictContractOptions(vibeProfile, geometryStrategy) && selected.size() < ROUTE_OPTION_COUNT) {
                throw noStrongStrategyRoute(requestVibes, job);
            }
            return selected;
        }

        int maxAllowedMinutes = maxAllowedMinutes(job.getTimeBudgetMinutes());
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
        int maxAllowedMinutes = maxAllowedMinutes(job.getTimeBudgetMinutes());

        for (List<RoadNode> variant : waypointVariants) {
            var result = osrmTripClient.requestRoundTrip(variant, job.getRouteMode());
            if (result.isEmpty()) {
                continue;
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
            RouteCandidate candidate = new RouteCandidate(
                trip.path(),
                routeScore.finalScore(),
                trip.totalDistanceKm(),
                trip.durationMinutes(),
                HYBRID_OSRM_V2,
                null,
                scoreBreakdown
            );
            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RouteCandidate> deduplicated = deduplicateCandidates(candidates);
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

    private int maxAllowedMinutes(int targetMinutes) {
        int safeTarget = Math.max(1, targetMinutes);
        double configuredRatio = Math.max(1.0, config.getMaxDurationOverrunRatio());
        double effectiveRatio = Math.min(MAX_EFFECTIVE_DURATION_OVERRUN_RATIO, configuredRatio);
        int ratioCap = (int) Math.ceil(safeTarget * effectiveRatio);
        int absoluteSlack = Math.max(3, Math.min(10, (int) Math.ceil(safeTarget * 0.15)));
        return Math.max(safeTarget, Math.min(ratioCap, safeTarget + absoluteSlack));
    }

    private int minUsefulMinutes(int targetMinutes) {
        return (int) Math.ceil(Math.max(10.0, Math.max(1, targetMinutes) * MIN_USEFUL_DURATION_RATIO));
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

    private List<RouteCandidate> combineCandidates(List<RouteCandidate> left, List<RouteCandidate> right) {
        if ((left == null || left.isEmpty()) && (right == null || right.isEmpty())) {
            return List.of();
        }
        List<RouteCandidate> combined = new ArrayList<>();
        if (left != null) {
            combined.addAll(left);
        }
        if (right != null) {
            combined.addAll(right);
        }
        return deduplicateCandidates(combined);
    }

    private List<RouteCandidate> selectRouteOptions(List<RouteCandidate> candidates, int targetMinutes) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RouteCandidate> selected = new ArrayList<>();

        addIfPresent(selected, pickBestCandidate(candidates, candidate -> mostScenicProfileScore(candidate, targetMinutes), selected, targetMinutes, false));
        addIfPresent(selected, pickBestCandidate(candidates, candidate -> balancedProfileScore(candidate, targetMinutes), selected, targetMinutes, true));
        addIfPresent(selected, pickBestCandidate(candidates, shorterProfileScorer(candidates, targetMinutes), selected, targetMinutes, true));

        if (selected.size() < 3) {
            addIfPresent(selected, pickBestCandidate(candidates, candidate -> balancedProfileScore(candidate, targetMinutes), selected, targetMinutes, false));
            addIfPresent(selected, pickBestCandidate(candidates, shorterProfileScorer(candidates, targetMinutes), selected, targetMinutes, false));
        }

        if (selected.size() < ROUTE_OPTION_COUNT) {
            candidates.stream()
                .filter(candidate -> selected.stream().noneMatch(existing -> candidateSignature(existing).equals(candidateSignature(candidate))))
                .sorted(Comparator.comparingDouble((RouteCandidate candidate) -> fallbackOptionScore(candidate, selected, targetMinutes)).reversed())
                .limit(ROUTE_OPTION_COUNT - selected.size())
                .forEach(selected::add);
        }

        return List.copyOf(selected);
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
            return containsAny(profileIds,
                "countryside",
                "sunday_cruise",
                "quiet",
                "minimal_traffic",
                "clear_my_head"
            );
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
            return isStrictLowPressureCandidate(candidate);
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
                + "-minute budget. Try a larger time budget, a less urban start point, or Scenic/Open Roads."
        );
    }

    private double breakdownValue(Map<String, Double> breakdown, String key, double fallback) {
        if (breakdown == null || !breakdown.containsKey(key) || breakdown.get(key) == null) {
            return fallback;
        }
        return breakdown.get(key);
    }

    private boolean needsBudgetRescue(List<RouteCandidate> candidates, int targetMinutes) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }

        int minUsefulMinutes = minUsefulMinutes(targetMinutes);
        long usefulCandidates = candidates.stream()
            .filter(candidate -> candidate.getEstimatedMinutes() >= minUsefulMinutes)
            .count();
        int maxDuration = candidates.stream()
            .mapToInt(RouteCandidate::getEstimatedMinutes)
            .max()
            .orElse(0);
        int minDuration = candidates.stream()
            .mapToInt(RouteCandidate::getEstimatedMinutes)
            .min()
            .orElse(0);

        return usefulCandidates < ROUTE_OPTION_COUNT
            || maxDuration < minUsefulMinutes
            || (maxDuration - minDuration) < minDurationSeparationMinutes(targetMinutes);
    }

    private boolean hasDiverseCandidatePool(List<RouteCandidate> candidates, int targetMinutes) {
        if (candidates == null || candidates.size() < ROUTE_OPTION_COUNT) {
            return false;
        }

        List<RouteCandidate> diverse = new ArrayList<>();
        candidates.stream()
            .sorted(Comparator.comparingDouble((RouteCandidate candidate) -> mostScenicProfileScore(candidate, targetMinutes)).reversed())
            .forEach(candidate -> {
                boolean duplicate = diverse.stream()
                    .anyMatch(existing -> candidateSignature(existing).equals(candidateSignature(candidate)));
                if (!duplicate && isMeaningfullyDifferent(candidate, diverse, targetMinutes)) {
                    diverse.add(candidate);
                }
            });
        return diverse.size() >= ROUTE_OPTION_COUNT;
    }

    private RouteCandidate pickBestCandidate(List<RouteCandidate> candidates,
                                             ToDoubleFunction<RouteCandidate> scorer,
                                             List<RouteCandidate> selected,
                                             int targetMinutes,
                                             boolean requireDiversity) {
        return candidates.stream()
            .filter(candidate -> selected.stream().noneMatch(existing -> candidateSignature(existing).equals(candidateSignature(candidate))))
            .filter(candidate -> !requireDiversity || isMeaningfullyDifferent(candidate, selected, targetMinutes))
            .max(Comparator.comparingDouble(candidate -> scorer.applyAsDouble(candidate) - diversityPenalty(candidate, selected, targetMinutes)))
            .orElse(null);
    }

    private void addIfPresent(List<RouteCandidate> selected, RouteCandidate candidate) {
        if (candidate != null && selected.stream().noneMatch(existing -> candidateSignature(existing).equals(candidateSignature(candidate)))) {
            selected.add(candidate);
        }
    }

    private double mostScenicProfileScore(RouteCandidate candidate, int targetMinutes) {
        return (candidate.getTotalScenicScore() * 0.74)
            + (budgetFitScore(candidate, targetMinutes) * 0.08)
            + (budgetUtilizationScore(candidate, targetMinutes) * 0.18)
            - (candidateBacktrackingPenalty(candidate) * MOST_SCENIC_BACKTRACKING_PROFILE_WEIGHT);
    }

    private double balancedProfileScore(RouteCandidate candidate, int targetMinutes) {
        return (candidate.getTotalScenicScore() * 0.46)
            + (budgetFitScore(candidate, targetMinutes) * 0.34)
            + (budgetUtilizationScore(candidate, targetMinutes) * 0.12)
            + (estimateCurvatureScore(candidate.getWaypoints()) * 0.08)
            - (candidateBacktrackingPenalty(candidate) * BALANCED_BACKTRACKING_PROFILE_WEIGHT);
    }

    private ToDoubleFunction<RouteCandidate> shorterProfileScorer(List<RouteCandidate> candidates, int targetMinutes) {
        double maxDistance = Math.max(0.1, candidates.stream().mapToDouble(RouteCandidate::getTotalDistanceKm).max().orElse(0.1));
        return candidate -> {
            double shorterDistance = 1.0 - clamp01(candidate.getTotalDistanceKm() / maxDistance);
            double usefulShorterFit = shorterProfileBudgetFitScore(candidate, targetMinutes);
            return (usefulShorterFit * 0.46)
                + (candidate.getTotalScenicScore() * 0.28)
                + (budgetFitScore(candidate, targetMinutes) * 0.14)
                + (shorterDistance * 0.08)
                + (estimateCurvatureScore(candidate.getWaypoints()) * 0.04)
                - (candidateBacktrackingPenalty(candidate) * SHORTER_BACKTRACKING_PROFILE_WEIGHT);
        };
    }

    private double candidateBacktrackingPenalty(RouteCandidate candidate) {
        return breakdownValue(candidate.getScoreBreakdown(), "backtracking_penalty", 0.0);
    }

    private double shorterProfileBudgetFitScore(RouteCandidate candidate, int targetMinutes) {
        double safeTarget = Math.max(1.0, targetMinutes);
        double ratio = candidate.getEstimatedMinutes() / safeTarget;
        double targetFit = 1.0 - clamp01(Math.abs(ratio - SHORTER_PROFILE_TARGET_RATIO) / 0.28);
        if (ratio < SHORTER_PROFILE_MIN_USEFUL_RATIO) {
            targetFit *= clamp01(ratio / SHORTER_PROFILE_MIN_USEFUL_RATIO) * 0.55;
        }
        if (ratio > 1.0) {
            targetFit *= Math.max(0.35, 1.0 - ((ratio - 1.0) * 2.0));
        }
        return clamp01(targetFit);
    }

    private double budgetFitScore(RouteCandidate candidate, int targetMinutes) {
        int maxAllowedMinutes = maxAllowedMinutes(targetMinutes);
        int gap = candidate.getEstimatedMinutes() <= targetMinutes
            ? targetMinutes - candidate.getEstimatedMinutes()
            : (candidate.getEstimatedMinutes() - targetMinutes) * 2;
        return clamp01(1.0 - (gap / (double) Math.max(1, maxAllowedMinutes)));
    }

    private double budgetUtilizationScore(RouteCandidate candidate, int targetMinutes) {
        return clamp01(candidate.getEstimatedMinutes() / (double) Math.max(1, targetMinutes));
    }

    private double diversityPenalty(RouteCandidate candidate, List<RouteCandidate> selected, int targetMinutes) {
        double penalty = 0.0;
        for (RouteCandidate existing : selected) {
            double separationKm = geometrySeparationKm(candidate, existing);
            if (separationKm < ROUTE_OPTION_MIN_SEPARATION_KM) {
                penalty = Math.max(penalty, (ROUTE_OPTION_MIN_SEPARATION_KM - separationKm) * 0.65);
            }

            double similarity = routeSimilarity(candidate, existing);
            if (similarity > DIVERSE_ROUTE_SIMILARITY_THRESHOLD) {
                penalty = Math.max(penalty, 0.28 + (similarity - DIVERSE_ROUTE_SIMILARITY_THRESHOLD));
            }

            int durationGap = Math.abs(candidate.getEstimatedMinutes() - existing.getEstimatedMinutes());
            double distanceGap = Math.abs(candidate.getTotalDistanceKm() - existing.getTotalDistanceKm());
            if (similarity > 0.50
                && durationGap < minDurationSeparationMinutes(targetMinutes)
                && distanceGap < minDistanceSeparationKm(candidate, existing)) {
                penalty = Math.max(penalty, 0.18);
            }
        }
        return penalty;
    }

    private double fallbackOptionScore(RouteCandidate candidate, List<RouteCandidate> selected, int targetMinutes) {
        double minSeparationKm = selected.isEmpty()
            ? ROUTE_OPTION_MIN_SEPARATION_KM
            : selected.stream()
                .mapToDouble(existing -> geometrySeparationKm(candidate, existing))
                .min()
                .orElse(0.0);
        double separationScore = clamp01(minSeparationKm / ROUTE_OPTION_MIN_SEPARATION_KM);
        return mostScenicProfileScore(candidate, targetMinutes)
            + (separationScore * 0.30)
            + (budgetFitScore(candidate, targetMinutes) * 0.10)
            - diversityPenalty(candidate, selected, targetMinutes);
    }

    private boolean isMeaningfullyDifferent(RouteCandidate candidate, List<RouteCandidate> selected, int targetMinutes) {
        for (RouteCandidate existing : selected) {
            if (geometrySeparationKm(candidate, existing) < ROUTE_OPTION_MIN_SEPARATION_KM) {
                return false;
            }

            double similarity = routeSimilarity(candidate, existing);
            if (similarity > DIVERSE_ROUTE_SIMILARITY_THRESHOLD) {
                return false;
            }

            int durationGap = Math.abs(candidate.getEstimatedMinutes() - existing.getEstimatedMinutes());
            double distanceGap = Math.abs(candidate.getTotalDistanceKm() - existing.getTotalDistanceKm());
            if (similarity > 0.50
                && durationGap < minDurationSeparationMinutes(targetMinutes)
                && distanceGap < minDistanceSeparationKm(candidate, existing)) {
                return false;
            }
        }
        return true;
    }

    private double minRouteSeparationKm(List<RouteCandidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return ROUTE_OPTION_MIN_SEPARATION_KM;
        }

        double minSeparationKm = Double.POSITIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                minSeparationKm = Math.min(minSeparationKm, geometrySeparationKm(candidates.get(i), candidates.get(j)));
            }
        }
        return Double.isInfinite(minSeparationKm) ? ROUTE_OPTION_MIN_SEPARATION_KM : minSeparationKm;
    }

    private double geometrySeparationKm(RouteCandidate left, RouteCandidate right) {
        List<RoadNode> leftSamples = sampledGeometryPoints(left.getWaypoints());
        List<RoadNode> rightSamples = sampledGeometryPoints(right.getWaypoints());
        if (leftSamples.isEmpty() || rightSamples.isEmpty()) {
            return ROUTE_OPTION_MIN_SEPARATION_KM;
        }

        double forward = averageNearestDistanceKm(leftSamples, rightSamples);
        double reverse = averageNearestDistanceKm(rightSamples, leftSamples);
        return (forward + reverse) / 2.0;
    }

    private List<RoadNode> sampledGeometryPoints(List<RoadNode> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        if (points.size() <= MAX_GEOMETRY_SEPARATION_SAMPLES) {
            return points;
        }

        List<RoadNode> sampled = new ArrayList<>(MAX_GEOMETRY_SEPARATION_SAMPLES);
        int lastIndex = points.size() - 1;
        for (int i = 0; i < MAX_GEOMETRY_SEPARATION_SAMPLES; i++) {
            int index = (int) Math.round((i * lastIndex) / (double) Math.max(1, MAX_GEOMETRY_SEPARATION_SAMPLES - 1));
            sampled.add(points.get(index));
        }
        return sampled;
    }

    private double averageNearestDistanceKm(List<RoadNode> fromPoints, List<RoadNode> toPoints) {
        double sum = 0.0;
        for (RoadNode point : fromPoints) {
            double minDistanceKm = Double.POSITIVE_INFINITY;
            for (RoadNode candidate : toPoints) {
                minDistanceKm = Math.min(minDistanceKm, distanceKm(point, candidate));
            }
            if (!Double.isInfinite(minDistanceKm)) {
                sum += minDistanceKm;
            }
        }
        return sum / Math.max(1, fromPoints.size());
    }

    private int minDurationSeparationMinutes(int targetMinutes) {
        return Math.max(4, (int) Math.ceil(targetMinutes * 0.08));
    }

    private double minDistanceSeparationKm(RouteCandidate left, RouteCandidate right) {
        return Math.max(2.0, Math.min(left.getTotalDistanceKm(), right.getTotalDistanceKm()) * 0.08);
    }

    private double routeSimilarity(RouteCandidate left, RouteCandidate right) {
        Set<String> leftCells = corridorSignature(left);
        Set<String> rightCells = corridorSignature(right);
        if (leftCells.isEmpty() || rightCells.isEmpty()) {
            return 0.0;
        }

        int intersection = 0;
        for (String cell : leftCells) {
            if (rightCells.contains(cell)) {
                intersection++;
            }
        }
        int union = leftCells.size() + rightCells.size() - intersection;
        if (union <= 0) {
            return 0.0;
        }
        return intersection / (double) union;
    }

    private Set<String> corridorSignature(RouteCandidate candidate) {
        List<RoadNode> path = candidate.getWaypoints();
        if (path.isEmpty()) {
            return Set.of();
        }

        Set<String> cells = new HashSet<>();
        int step = Math.max(1, (int) Math.ceil(path.size() / (double) MAX_CORRIDOR_SIGNATURE_SAMPLES));
        for (int i = 0; i < path.size(); i += step) {
            cells.add(coarseCoordinateKey(path.get(i)));
        }
        cells.add(coarseCoordinateKey(path.getLast()));
        return cells;
    }

    private String coarseCoordinateKey(RoadNode node) {
        return String.format(Locale.ROOT, "%.3f,%.3f", node.getLatitude(), node.getLongitude());
    }

    private List<RouteCandidate> differentiateFlatScenicScores(List<RouteCandidate> candidates, int targetMinutes) {
        if (candidates.size() < 2) {
            return candidates;
        }

        double minScenic = candidates.stream()
            .mapToDouble(RouteCandidate::getTotalScenicScore)
            .min()
            .orElse(0.0);
        double maxScenic = candidates.stream()
            .mapToDouble(RouteCandidate::getTotalScenicScore)
            .max()
            .orElse(0.0);
        if ((maxScenic - minScenic) > FLAT_SCENIC_SCORE_EPSILON) {
            return candidates;
        }

        double minDistance = candidates.stream()
            .mapToDouble(RouteCandidate::getTotalDistanceKm)
            .min()
            .orElse(0.0);
        double maxDistance = candidates.stream()
            .mapToDouble(RouteCandidate::getTotalDistanceKm)
            .max()
            .orElse(minDistance);

        List<RouteCandidate> adjusted = new ArrayList<>(candidates.size());
        for (RouteCandidate candidate : candidates) {
            double fallbackScore = estimateFallbackScenicScore(candidate, minDistance, maxDistance, targetMinutes);
            Map<String, Double> adjustedBreakdown = new LinkedHashMap<>(candidate.getScoreBreakdown());
            adjustedBreakdown.put("final_score", fallbackScore);
            adjustedBreakdown.put("fallback_selection_score", fallbackScore);
            adjusted.add(new RouteCandidate(
                candidate.getWaypoints(),
                fallbackScore,
                candidate.getTotalDistanceKm(),
                candidate.getEstimatedMinutes(),
                candidate.getAlgorithmVersion(),
                candidate.getBeamCandidates(),
                adjustedBreakdown
            ));
        }

        logger.info(
            "Scenic score fallback differentiation applied for {} candidate(s) at target {} minutes",
            adjusted.size(),
            targetMinutes
        );
        return List.copyOf(adjusted);
    }

    private double estimateFallbackScenicScore(RouteCandidate candidate,
                                               double minDistanceKm,
                                               double maxDistanceKm,
                                               int targetMinutes) {
        double distanceComponent = normalizeRange(candidate.getTotalDistanceKm(), minDistanceKm, maxDistanceKm);
        double curvatureComponent = estimateCurvatureScore(candidate.getWaypoints());
        double timeFitComponent = clamp01(
            1.0 - (Math.abs(candidate.getEstimatedMinutes() - targetMinutes) / Math.max(1.0, (double) targetMinutes))
        );
        double hashJitter = (Math.abs(candidateSignature(candidate).hashCode()) % 1000) / 1_000_000.0;

        return clamp01(
            0.25
                + (distanceComponent * 0.40)
                + (curvatureComponent * 0.25)
                + (timeFitComponent * 0.10)
                + hashJitter
        );
    }

    private double normalizeRange(double value, double min, double max) {
        if ((max - min) <= FLAT_SCENIC_SCORE_EPSILON) {
            return 0.5;
        }
        return clamp01((value - min) / (max - min));
    }

    private double estimateCurvatureScore(List<RoadNode> path) {
        if (path == null || path.size() < 3) {
            return 0.0;
        }

        double totalTurn = 0.0;
        int turnSamples = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            double previousBearing = bearingDegrees(path.get(i - 1), path.get(i));
            double nextBearing = bearingDegrees(path.get(i), path.get(i + 1));
            double delta = Math.abs(nextBearing - previousBearing);
            if (delta > 180.0) {
                delta = 360.0 - delta;
            }
            totalTurn += delta;
            turnSamples++;
        }

        if (turnSamples == 0) {
            return 0.0;
        }
        double averageTurn = totalTurn / turnSamples;
        return clamp01(averageTurn / 90.0);
    }

    private List<RouteCandidate> deduplicateCandidates(List<RouteCandidate> candidates) {
        Map<String, RouteCandidate> unique = new LinkedHashMap<>();
        for (RouteCandidate candidate : candidates) {
            String signature = candidateSignature(candidate);
            RouteCandidate existing = unique.get(signature);
            if (existing == null || shouldReplace(existing, candidate)) {
                unique.put(signature, candidate);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean shouldReplace(RouteCandidate existing, RouteCandidate replacement) {
        if (replacement.getTotalScenicScore() > existing.getTotalScenicScore() + 0.0001) {
            return true;
        }
        if (Math.abs(replacement.getTotalScenicScore() - existing.getTotalScenicScore()) <= 0.0001) {
            if (replacement.getEstimatedMinutes() != existing.getEstimatedMinutes()) {
                return replacement.getEstimatedMinutes() < existing.getEstimatedMinutes();
            }
            return replacement.getTotalDistanceKm() < existing.getTotalDistanceKm();
        }
        return false;
    }

    private String candidateSignature(RouteCandidate candidate) {
        return candidate.getWaypoints().stream()
            .map(node -> String.format(Locale.ROOT, "%.5f,%.5f", node.getLatitude(), node.getLongitude()))
            .collect(Collectors.joining("|"));
    }

    private List<TileCandidate> scoreNearbyTiles(RoadNode start,
                                                 int timeBudgetMinutes,
                                                 PreferenceWeights preferences,
                                                 VibeCatalog.BlendedVibeProfile vibeProfile,
                                                 DurationCalibrationHint durationCalibration) {
        int ringSize = determineRingSize(timeBudgetMinutes, vibeProfile, durationCalibration);
        int configuredResolution = Math.max(0, config.getH3Resolution());
        List<ScenicScoreTile> nearbyTiles = findTilesNearStart(start, ringSize, configuredResolution);

        if (nearbyTiles.isEmpty() && configuredResolution != DEFAULT_H3_RESOLUTION) {
            nearbyTiles = findTilesNearStart(start, ringSize, DEFAULT_H3_RESOLUTION);
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

        if (nearbyTiles.isEmpty()) {
            return List.of();
        }

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile, durationCalibration);
        return nearbyTiles.stream()
            .filter(tile -> tile.getGeometry() != null && !tile.getGeometry().isEmpty())
            .map(tile -> {
                RoadNode tileCenter = new RoadNode(
                    tile.getGeometry().getCentroid().getY(),
                    tile.getGeometry().getCentroid().getX()
                );
                double distanceKm = distanceKm(start, tileCenter);
                double score = scoreTile(tile, preferences);
                double selectionScore = tileSelectionScore(tile, vibeProfile, score, distanceKm, targetRadiusKm);
                return new TileCandidate(tile, tileCenter, score, selectionScore, distanceKm);
            })
            .sorted(Comparator.comparingDouble(TileCandidate::selectionScore).reversed())
            .limit(Math.max(20, config.getTileSelectionLimit()))
            .toList();
    }

    private void validateVibeAvailability(RouteJob job,
                                          List<String> vibes,
                                          VibeCatalog.BlendedVibeProfile vibeProfile,
                                          List<TileCandidate> scoredTiles) {
        if (scoredTiles == null || scoredTiles.size() < MIN_VIBE_AVAILABILITY_TILES) {
            throw new NoFeasibleRouteException(
                "No scenic data found near this start for " + VibeCatalog.displayList(vibes)
                    + " within your " + job.getTimeBudgetMinutes()
                    + "-minute budget. Try a broader vibe like Scenic or Relaxing, or choose a nearby start point."
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
                    + "-minute budget. Try Scenic, Relaxing, Countryside, or increase the time budget."
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
        String centerCell = H3Utils.getH3Index(start.getLatitude(), start.getLongitude(), resolution);
        List<String> nearbyCells = H3Utils.getKRing(centerCell, ringSize);
        if (nearbyCells.isEmpty()) {
            return List.of();
        }
        return scenicScoreTileRepository.findByH3IndexIn(nearbyCells);
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
        variants.addAll(buildWaypointRings(start, scoredTiles, timeBudgetMinutes, vibeProfile, durationCalibration));

        List<List<RoadNode>> intentVariants = buildTargetAnchorWaypointRings(
            start,
            scoredTiles,
            timeBudgetMinutes,
            vibeProfile,
            durationCalibration
        );
        variants.addAll(intentVariants);
        List<List<RoadNode>> strategyVariants = buildStrategyWaypointRings(
            start,
            scoredTiles,
            timeBudgetMinutes,
            vibeProfile,
            geometryStrategy,
            durationCalibration
        );
        variants.addAll(strategyVariants);

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
        double photoPeak = Math.max(components.water(), Math.max(components.elevation(), components.poi()));
        return clamp01(switch (geometryStrategy) {
            case WATER_FOLLOWING -> (components.water() * 0.58)
                + (components.greenery() * 0.12)
                + (vibeFit * 0.20)
                + (lowUrban * 0.10);
            case OPEN_SPACE_ESCAPE -> (openSpaceScore(tile, components) * 0.55)
                + (components.solitude() * 0.20)
                + (lowUrban * 0.15)
                + (lowRoadDensity * 0.10);
            case QUIET_LOW_PRESSURE -> (components.solitude() * 0.48)
                + (components.greenery() * 0.20)
                + (lowUrban * 0.22)
                + (clamp01(tile.getDarknessScore()) * 0.10);
            case PHOTO_PEAKS -> (photoPeak * 0.48)
                + (components.poi() * 0.18)
                + (vibeFit * 0.22)
                + (candidate.scenicScore() * 0.12);
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
        if (containsAny(profileIds, "photo_worthy", "photo_run", "date_night", "hidden_gems")) {
            return GeometryStrategy.PHOTO_PEAKS;
        }
        if (containsAny(profileIds, "coastal", "riverside", "golden_hour", "sunset", "sunrise")) {
            return GeometryStrategy.WATER_FOLLOWING;
        }
        if (containsAny(profileIds, "open_roads")) {
            return GeometryStrategy.OPEN_SPACE_ESCAPE;
        }
        if (containsAny(profileIds, "quiet", "minimal_traffic", "clear_my_head", "relaxing", "smooth_cruise", "countryside", "forest", "nature_escape", "scenic_reset", "sunday_cruise")) {
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

    private double bearingDegrees(RoadNode from, RoadNode to) {
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double dLon = Math.toRadians(to.getLongitude() - from.getLongitude());

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
            - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
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

    private double normalizeLongitude(double longitude) {
        double normalized = longitude;
        while (normalized > 180.0) {
            normalized -= 360.0;
        }
        while (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized;
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

        RouteCraftMetrics routeCraftMetrics = computeRouteCraftMetrics(path);
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
            double photoSignal = Math.max(
                Math.max(components.water(), components.elevation()),
                Math.max(components.poi(), scenicPoi)
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

            waterShare += gradedMembership(components.water(), 0.38, 0.70);
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

    private RouteCraftMetrics computeRouteCraftMetrics(List<RoadNode> path) {
        if (path == null || path.size() < 4) {
            return new RouteCraftMetrics(0.0, 0.0, 1.0, 0.0, 0.0);
        }

        List<RoadNode> samples = samplePath(path, ROUTE_CRAFT_SAMPLE_METERS);
        if (samples.size() < 4) {
            samples = path;
        }

        List<String> compressedCells = compressedRouteCraftCells(samples);
        double repeatedCellShare = repeatedCorridorCellShare(compressedCells);
        double reverseOverlapShare = reverseOverlapShare(compressedCells);
        double legSeparationScore = legSeparationScore(samples);
        double nearDuplicateRisk = selfIntersectionOrNearDuplicateRisk(samples);
        double backtrackingPenalty = clamp01(
            (repeatedCellShare * 0.34)
                + (reverseOverlapShare * 0.30)
                + ((1.0 - legSeparationScore) * 0.24)
                + (nearDuplicateRisk * 0.12)
        );

        return new RouteCraftMetrics(
            repeatedCellShare,
            reverseOverlapShare,
            legSeparationScore,
            nearDuplicateRisk,
            backtrackingPenalty
        );
    }

    private List<String> compressedRouteCraftCells(List<RoadNode> samples) {
        List<String> cells = new ArrayList<>();
        String previous = null;
        for (RoadNode sample : samples) {
            String cell = H3Utils.getH3Index(sample.getLatitude(), sample.getLongitude(), ROUTE_CRAFT_H3_RESOLUTION);
            if (!cell.equals(previous)) {
                cells.add(cell);
                previous = cell;
            }
        }
        return cells;
    }

    private double repeatedCorridorCellShare(List<String> compressedCells) {
        if (compressedCells == null || compressedCells.size() < 2) {
            return 0.0;
        }
        Set<String> uniqueCells = new HashSet<>(compressedCells);
        return clamp01((compressedCells.size() - uniqueCells.size()) / (double) compressedCells.size());
    }

    private double reverseOverlapShare(List<String> compressedCells) {
        if (compressedCells == null || compressedCells.size() < 3) {
            return 0.0;
        }

        int edgeCount = 0;
        Set<String> uniqueUndirectedEdges = new HashSet<>();
        for (int i = 1; i < compressedCells.size(); i++) {
            String left = compressedCells.get(i - 1);
            String right = compressedCells.get(i);
            if (left.equals(right)) {
                continue;
            }
            edgeCount++;
            uniqueUndirectedEdges.add(undirectedEdgeKey(left, right));
        }
        if (edgeCount == 0) {
            return 0.0;
        }
        return clamp01((edgeCount - uniqueUndirectedEdges.size()) / (double) edgeCount);
    }

    private String undirectedEdgeKey(String left, String right) {
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    private double legSeparationScore(List<RoadNode> samples) {
        if (samples == null || samples.size() < 9) {
            return 1.0;
        }

        int size = samples.size();
        int firstEnd = Math.max(2, size / 3);
        int middleEnd = Math.max(firstEnd + 2, (size * 2) / 3);
        List<RoadNode> firstLeg = trimmedLeg(samples, 0, firstEnd, true, false);
        List<RoadNode> middleLeg = trimmedLeg(samples, firstEnd, middleEnd, false, false);
        List<RoadNode> finalLeg = trimmedLeg(samples, middleEnd, size, false, true);
        if (firstLeg.isEmpty() || middleLeg.isEmpty() || finalLeg.isEmpty()) {
            return 1.0;
        }

        double firstToMiddle = symmetricLegSeparationScore(firstLeg, middleLeg);
        double middleToFinal = symmetricLegSeparationScore(middleLeg, finalLeg);
        double firstToFinal = symmetricLegSeparationScore(firstLeg, finalLeg);
        return clamp01(Math.min(firstToFinal, Math.min(firstToMiddle, middleToFinal)));
    }

    private List<RoadNode> trimmedLeg(List<RoadNode> samples, int fromInclusive, int toExclusive, boolean trimStart, boolean trimEnd) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(samples.size(), Math.max(from, toExclusive));
        int length = to - from;
        if (length <= 0) {
            return List.of();
        }
        int trim = Math.max(1, length / 5);
        if (trimStart && length > 3) {
            from = Math.min(to, from + trim);
        }
        if (trimEnd && (to - from) > 3) {
            to = Math.max(from, to - trim);
        }
        return from >= to ? List.of() : new ArrayList<>(samples.subList(from, to));
    }

    private double symmetricLegSeparationScore(List<RoadNode> left, List<RoadNode> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 1.0;
        }
        return (averageNearestSeparationScore(left, right) + averageNearestSeparationScore(right, left)) / 2.0;
    }

    private double averageNearestSeparationScore(List<RoadNode> fromPoints, List<RoadNode> toPoints) {
        double sum = 0.0;
        for (RoadNode point : fromPoints) {
            double minDistanceKm = Double.POSITIVE_INFINITY;
            for (RoadNode candidate : toPoints) {
                minDistanceKm = Math.min(minDistanceKm, distanceKm(point, candidate));
            }
            if (!Double.isInfinite(minDistanceKm)) {
                sum += clamp01(minDistanceKm / ROUTE_CRAFT_LEG_SEPARATION_GOOD_KM);
            }
        }
        return sum / Math.max(1, fromPoints.size());
    }

    private double selfIntersectionOrNearDuplicateRisk(List<RoadNode> samples) {
        if (samples == null || samples.size() < 8) {
            return 0.0;
        }

        int riskyPoints = 0;
        for (int i = 0; i < samples.size(); i++) {
            boolean risky = false;
            for (int j = i + ROUTE_CRAFT_NEAR_DUPLICATE_WINDOW + 1; j < samples.size(); j++) {
                if (isExpectedLoopClosure(i, j, samples.size())) {
                    continue;
                }
                if (distanceKm(samples.get(i), samples.get(j)) <= ROUTE_CRAFT_NEAR_DUPLICATE_KM) {
                    risky = true;
                    break;
                }
            }
            if (risky) {
                riskyPoints++;
            }
        }
        return clamp01(riskyPoints / (double) samples.size());
    }

    private boolean isExpectedLoopClosure(int leftIndex, int rightIndex, int sampleCount) {
        return leftIndex <= ROUTE_CRAFT_NEAR_DUPLICATE_WINDOW
            && rightIndex >= sampleCount - ROUTE_CRAFT_NEAR_DUPLICATE_WINDOW - 1;
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
        List<ScenicScoreTile> fetchedTiles = scenicScoreTileRepository.findByH3IndexIn(h3Indexes);
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

    private List<RoadNode> samplePath(List<RoadNode> path, int sampleMeters) {
        List<RoadNode> samples = new ArrayList<>();
        samples.add(path.getFirst());

        double accumulatedMeters = 0.0;
        for (int i = 1; i < path.size(); i++) {
            RoadNode previous = path.get(i - 1);
            RoadNode current = path.get(i);

            accumulatedMeters += distanceKm(previous, current) * 1000.0;
            if (accumulatedMeters >= sampleMeters) {
                samples.add(current);
                accumulatedMeters = 0.0;
            }
        }

        RoadNode lastPoint = path.getLast();
        RoadNode lastSample = samples.getLast();
        if (!lastPoint.equals(lastSample)) {
            samples.add(lastPoint);
        }

        return samples;
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

    private String normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            return VibeCatalog.defaultVibe();
        }
        String normalized = VibeCatalog.normalize(vibe);
        return normalized.isBlank() ? VibeCatalog.defaultVibe() : normalized;
    }

    private double scoreTile(ScenicScoreTile tile, PreferenceWeights preferences) {
        return scenicScoreCalculator.scoreTile(tile, preferences);
    }

    private double distanceKm(RoadNode from, RoadNode to) {
        final double earthRadiusKm = 6371.0;
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.getLongitude() - from.getLongitude());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CorridorTileCoverage(List<ScenicScoreTile> orderedTiles) {
    }

    private record RouteScoreResult(double finalScore, Map<String, Double> breakdown) {
    }

    private record RouteCraftMetrics(double repeatedCorridorCellShare,
                                     double reverseOverlapShare,
                                     double legSeparationScore,
                                     double selfIntersectionOrNearDuplicateScore,
                                     double backtrackingPenalty) {
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

    private record TileCandidate(ScenicScoreTile tile,
                                 RoadNode center,
                                 double scenicScore,
                                 double selectionScore,
                                 double distanceKm) {
    }
}
