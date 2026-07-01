package com.moodride.routeworker.algorithm;

import com.moodride.geo.H3Utils;
import com.moodride.routeworker.graph.RoadNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.moodride.routeworker.algorithm.RouteGeometry.clamp01;
import static com.moodride.routeworker.algorithm.RouteGeometry.distanceKm;
import static com.moodride.routeworker.algorithm.RouteGeometry.samplePath;

final class RouteCraftAnalyzer {

    private static final int ROUTE_CRAFT_H3_RESOLUTION = H3Utils.DEFAULT_RESOLUTION + 1;
    private static final int ROUTE_CRAFT_SAMPLE_METERS = 350;
    private static final int ROUTE_CRAFT_NEAR_DUPLICATE_WINDOW = 4;
    private static final double ROUTE_CRAFT_LEG_SEPARATION_GOOD_KM = 0.85;
    private static final double ROUTE_CRAFT_NEAR_DUPLICATE_KM = 0.12;

    RouteCraftMetrics analyze(List<RoadNode> path) {
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
}

record RouteCraftMetrics(double repeatedCorridorCellShare,
                         double reverseOverlapShare,
                         double legSeparationScore,
                         double selfIntersectionOrNearDuplicateScore,
                         double backtrackingPenalty) {

    boolean active() {
        return repeatedCorridorCellShare > 0.0001
            || reverseOverlapShare > 0.0001
            || selfIntersectionOrNearDuplicateScore > 0.0001
            || legSeparationScore < 0.9999
            || backtrackingPenalty > 0.0001;
    }
}
