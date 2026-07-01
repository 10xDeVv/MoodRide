package com.moodride.routeworker.algorithm;

import com.moodride.routeworker.graph.RoadNode;

import java.util.ArrayList;
import java.util.List;

final class RouteGeometry {

    private RouteGeometry() {
    }

    static double distanceKm(RoadNode from, RoadNode to) {
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

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static double bearingDegrees(RoadNode from, RoadNode to) {
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double dLon = Math.toRadians(to.getLongitude() - from.getLongitude());

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
            - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static double normalizeLongitude(double longitude) {
        double normalized = longitude;
        while (normalized > 180.0) {
            normalized -= 360.0;
        }
        while (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    static double estimateCurvatureScore(List<RoadNode> path) {
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

    static List<RoadNode> samplePath(List<RoadNode> path, int sampleMeters) {
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
}
