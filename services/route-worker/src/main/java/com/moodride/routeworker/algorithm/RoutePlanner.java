package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.RouteWeightCalibration;
import com.moodride.datamodels.scoring.ComponentScores;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.H3Utils;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNode;
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
    private final OsrmTripClient osrmTripClient;
    private final ApplicationConfiguration config;
    private final ObjectMapper objectMapper;
    private final ScenicScoreCalculator scenicScoreCalculator;

    public RoutePlanner(ScenicScoreTileRepository scenicScoreTileRepository,
                        RouteWeightCalibrationRepository routeWeightCalibrationRepository,
                        OsrmTripClient osrmTripClient,
                        ApplicationConfiguration config,
                        ObjectMapper objectMapper,
                        ScenicScoreCalculator scenicScoreCalculator) {
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.routeWeightCalibrationRepository = routeWeightCalibrationRepository;
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

        List<TileCandidate> scoredTiles = scoreNearbyTiles(start, job.getTimeBudgetMinutes(), preferences, vibeProfile);
        validateVibeAvailability(job, requestVibes, vibeProfile, scoredTiles);
        List<List<RoadNode>> waypointVariants = buildWaypointRings(start, scoredTiles, job.getTimeBudgetMinutes(), vibeProfile);
        List<RouteCandidate> hybridCandidates = collectHybridCandidates(job, waypointVariants, preferences);
        if (usesTargetAnchors(vibeProfile)) {
            List<List<RoadNode>> targetAnchorVariants = buildTargetAnchorWaypointRings(
                start,
                scoredTiles,
                job.getTimeBudgetMinutes(),
                vibeProfile
            );
            List<RouteCandidate> targetAnchorCandidates = collectHybridCandidates(job, targetAnchorVariants, preferences);
            if (!targetAnchorCandidates.isEmpty()) {
                hybridCandidates = combineCandidates(hybridCandidates, targetAnchorCandidates);
                logger.info(
                    "Hybrid routing used {} target-anchor waypoint variant(s) for job {}",
                    targetAnchorVariants.size(),
                    job.getId()
                );
            }
        }
        if (hybridCandidates.isEmpty() || !hasDiverseCandidatePool(hybridCandidates, job.getTimeBudgetMinutes())) {
            List<List<RoadNode>> syntheticVariants = buildSyntheticWaypointRings(start, job.getTimeBudgetMinutes());
            List<RouteCandidate> syntheticCandidates = collectHybridCandidates(job, syntheticVariants, preferences);
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
            List<RouteCandidate> budgetRescueCandidates = collectHybridCandidates(job, budgetRescueVariants, preferences);
            if (!budgetRescueCandidates.isEmpty()) {
                hybridCandidates = combineCandidates(hybridCandidates, budgetRescueCandidates);
                logger.info("Hybrid routing used budget-rescue waypoint variants for job {}", job.getId());
            }
        }
        if (!hybridCandidates.isEmpty()) {
            List<RouteCandidate> differentiated = differentiateFlatScenicScores(hybridCandidates, job.getTimeBudgetMinutes());
            List<RouteCandidate> selected = selectRouteOptions(differentiated, job.getTimeBudgetMinutes());
            if (selected.size() >= ROUTE_OPTION_COUNT && minRouteSeparationKm(selected) < ROUTE_OPTION_MIN_SEPARATION_KM) {
                List<RouteCandidate> rescueCandidates = collectHybridCandidates(
                    job,
                    buildDiversityRescueWaypointRings(start, job.getTimeBudgetMinutes()),
                    preferences
                );
                if (!rescueCandidates.isEmpty()) {
                    List<RouteCandidate> expanded = differentiateFlatScenicScores(
                        combineCandidates(differentiated, rescueCandidates),
                        job.getTimeBudgetMinutes()
                    );
                    selected = selectRouteOptions(expanded, job.getTimeBudgetMinutes());
                    logger.info("Hybrid routing used diversity-rescue waypoint variants for job {}", job.getId());
                }
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
                                                         PreferenceWeights preferences) {
        List<RouteCandidate> candidates = new ArrayList<>();
        int maxAllowedMinutes = maxAllowedMinutes(job.getTimeBudgetMinutes());

        for (List<RoadNode> variant : waypointVariants) {
            var result = osrmTripClient.requestRoundTrip(variant, job.getRouteMode());
            if (result.isEmpty()) {
                continue;
            }

            var trip = result.get();
            double scenicDensity = computeScenicDensity(trip.path(), preferences);
            RouteCandidate candidate = new RouteCandidate(
                trip.path(),
                scenicDensity,
                trip.totalDistanceKm(),
                trip.durationMinutes(),
                "hybrid_osrm_v1",
                null
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
            + (budgetUtilizationScore(candidate, targetMinutes) * 0.18);
    }

    private double balancedProfileScore(RouteCandidate candidate, int targetMinutes) {
        return (candidate.getTotalScenicScore() * 0.46)
            + (budgetFitScore(candidate, targetMinutes) * 0.34)
            + (budgetUtilizationScore(candidate, targetMinutes) * 0.12)
            + (estimateCurvatureScore(candidate.getWaypoints()) * 0.08);
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
                + (estimateCurvatureScore(candidate.getWaypoints()) * 0.04);
        };
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
            adjusted.add(new RouteCandidate(
                candidate.getWaypoints(),
                fallbackScore,
                candidate.getTotalDistanceKm(),
                candidate.getEstimatedMinutes(),
                candidate.getAlgorithmVersion(),
                candidate.getBeamCandidates()
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
                                                 VibeCatalog.BlendedVibeProfile vibeProfile) {
        int ringSize = determineRingSize(timeBudgetMinutes, vibeProfile);
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

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile);
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
            case "open_space" -> Math.max(weights.solitude(), weights.curves());
            default -> 0.0;
        };
    }

    private double openSpaceScore(ScenicScoreTile tile, ComponentScores components) {
        double lowRoadDensity = 1.0 - clamp01(tile.getRoadDensity());
        double lowBuildingDensity = 1.0 - clamp01(tile.getBuildingDensityScore());
        double lowUrbanPressure = 1.0 - clamp01(tile.getUrbanPenaltyScore());
        double lowPoiDensity = 1.0 - components.poi();
        return clamp01(
            (components.solitude() * 0.45)
                + (lowRoadDensity * 0.25)
                + (lowBuildingDensity * 0.15)
                + (lowUrbanPressure * 0.10)
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

    private List<ScenicScoreTile> findTilesNearStart(RoadNode start, int ringSize, int resolution) {
        String centerCell = H3Utils.getH3Index(start.getLatitude(), start.getLongitude(), resolution);
        List<String> nearbyCells = H3Utils.getKRing(centerCell, ringSize);
        if (nearbyCells.isEmpty()) {
            return List.of();
        }
        return scenicScoreTileRepository.findByH3IndexIn(nearbyCells);
    }

    private int determineRingSize(int timeBudgetMinutes) {
        return determineRingSize(timeBudgetMinutes, null);
    }

    private int determineRingSize(int timeBudgetMinutes, VibeCatalog.BlendedVibeProfile vibeProfile) {
        int dynamicRing = (int) Math.ceil(timeBudgetMinutes / 5.0);
        int min = Math.max(1, config.getTileSelectionRingMin());
        int max = Math.max(min, config.getTileSelectionRingMax());
        if (usesTargetAnchors(vibeProfile)) {
            dynamicRing = Math.max(dynamicRing, (int) Math.ceil(dynamicRing * 1.65));
        }
        return Math.max(min, Math.min(max, dynamicRing));
    }

    private List<List<RoadNode>> buildWaypointRings(RoadNode start,
                                                    List<TileCandidate> scoredTiles,
                                                    int timeBudgetMinutes,
                                                    VibeCatalog.BlendedVibeProfile vibeProfile) {
        if (scoredTiles.isEmpty()) {
            return List.of();
        }

        int sectorCount = Math.max(4, config.getSectorCount());
        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile);
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
        for (Integer waypointCount : WAYPOINT_VARIANTS) {
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
        if (scoredTiles == null || scoredTiles.isEmpty()) {
            return List.of();
        }

        double targetRadiusKm = targetWaypointRadiusKm(timeBudgetMinutes, vibeProfile);
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
        boolean outwardRouting = usesTargetAnchors(vibeProfile);
        double target = outwardRouting ? timeBudgetMinutes / 4.2 : timeBudgetMinutes / 6.5;
        return Math.max(2.5, target);
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

    private double computeScenicDensity(List<RoadNode> path, PreferenceWeights preferences) {
        if (path == null || path.size() < 2) {
            return 0.0;
        }

        List<RoadNode> samples = samplePath(path, Math.max(100, config.getCorridorSampleMeters()));
        if (samples.isEmpty()) {
            return 0.0;
        }

        int configuredResolution = Math.max(0, config.getH3Resolution());
        List<ScenicScoreTile> tiles = findTilesForSamples(samples, configuredResolution, false);
        if (tiles.isEmpty() && configuredResolution != DEFAULT_H3_RESOLUTION) {
            tiles = findTilesForSamples(samples, DEFAULT_H3_RESOLUTION, false);
            if (!tiles.isEmpty()) {
                logger.debug(
                    "No corridor tiles found at configured H3 resolution {}; scoring corridor at fallback resolution {}",
                    configuredResolution,
                    DEFAULT_H3_RESOLUTION
                );
            }
        }
        if (tiles.isEmpty()) {
            tiles = findTilesForSamples(samples, DEFAULT_H3_RESOLUTION, true);
        }
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

    private List<ScenicScoreTile> findTilesForSamples(List<RoadNode> samples,
                                                      int resolution,
                                                      boolean includeNeighborExpansion) {
        Set<String> h3Indexes = new HashSet<>();
        for (RoadNode point : samples) {
            String h3Index = H3Utils.getH3Index(point.getLatitude(), point.getLongitude(), resolution);
            h3Indexes.add(h3Index);
            if (includeNeighborExpansion) {
                h3Indexes.addAll(H3Utils.getKRing(h3Index, SAMPLE_NEIGHBOR_EXPANSION_RING));
            }
        }

        if (h3Indexes.isEmpty()) {
            return List.of();
        }
        return scenicScoreTileRepository.findByH3IndexIn(h3Indexes);
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

    private record TileCandidate(ScenicScoreTile tile,
                                 RoadNode center,
                                 double scenicScore,
                                 double selectionScore,
                                 double distanceKm) {
    }
}
