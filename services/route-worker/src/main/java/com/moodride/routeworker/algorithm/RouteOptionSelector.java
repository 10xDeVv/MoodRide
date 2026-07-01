package com.moodride.routeworker.algorithm;

import com.moodride.routeworker.graph.RoadNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.moodride.routeworker.algorithm.RouteGeometry.clamp01;
import static com.moodride.routeworker.algorithm.RouteGeometry.distanceKm;
import static com.moodride.routeworker.algorithm.RouteGeometry.estimateCurvatureScore;

final class RouteOptionSelector {

    private static final Logger logger = LoggerFactory.getLogger(RouteOptionSelector.class);

    private static final double FLAT_SCENIC_SCORE_EPSILON = 0.0001;
    private static final int ROUTE_OPTION_COUNT = 3;
    private static final double MAX_EFFECTIVE_DURATION_OVERRUN_RATIO = 1.15;
    private static final double DIVERSE_ROUTE_SIMILARITY_THRESHOLD = 0.72;
    static final double ROUTE_OPTION_MIN_SEPARATION_KM = 0.35;
    private static final double SHORTER_PROFILE_TARGET_RATIO = 0.78;
    private static final double SHORTER_PROFILE_MIN_USEFUL_RATIO = 0.60;
    private static final double MIN_USEFUL_DURATION_RATIO = 0.55;
    private static final int MAX_CORRIDOR_SIGNATURE_SAMPLES = 96;
    private static final int MAX_GEOMETRY_SEPARATION_SAMPLES = 80;
    private static final double MOST_SCENIC_BACKTRACKING_PROFILE_WEIGHT = 0.18;
    private static final double BALANCED_BACKTRACKING_PROFILE_WEIGHT = 0.12;
    private static final double SHORTER_BACKTRACKING_PROFILE_WEIGHT = 0.07;

    private final double configuredMaxDurationOverrunRatio;

    RouteOptionSelector(double configuredMaxDurationOverrunRatio) {
        this.configuredMaxDurationOverrunRatio = configuredMaxDurationOverrunRatio;
    }

    int maxAllowedMinutes(int targetMinutes) {
        int safeTarget = Math.max(1, targetMinutes);
        double configuredRatio = Math.max(1.0, configuredMaxDurationOverrunRatio);
        double effectiveRatio = Math.min(MAX_EFFECTIVE_DURATION_OVERRUN_RATIO, configuredRatio);
        int ratioCap = (int) Math.ceil(safeTarget * effectiveRatio);
        int absoluteSlack = Math.max(3, Math.min(10, (int) Math.ceil(safeTarget * 0.15)));
        return Math.max(safeTarget, Math.min(ratioCap, safeTarget + absoluteSlack));
    }

    List<RouteCandidate> combineCandidates(List<RouteCandidate> left, List<RouteCandidate> right) {
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

    List<RouteCandidate> selectRouteOptions(List<RouteCandidate> candidates, int targetMinutes) {
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

    boolean needsBudgetRescue(List<RouteCandidate> candidates, int targetMinutes) {
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

    boolean hasDiverseCandidatePool(List<RouteCandidate> candidates, int targetMinutes) {
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

    double minRouteSeparationKm(List<RouteCandidate> candidates) {
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

    List<RouteCandidate> differentiateFlatScenicScores(List<RouteCandidate> candidates, int targetMinutes) {
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

    List<RouteCandidate> deduplicateCandidates(List<RouteCandidate> candidates) {
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

    private int minUsefulMinutes(int targetMinutes) {
        return (int) Math.ceil(Math.max(10.0, Math.max(1, targetMinutes) * MIN_USEFUL_DURATION_RATIO));
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

    private double breakdownValue(Map<String, Double> breakdown, String key, double fallback) {
        if (breakdown == null || !breakdown.containsKey(key) || breakdown.get(key) == null) {
            return fallback;
        }
        return breakdown.get(key);
    }
}
