package com.moodride.routeworker.algorithm;

import com.moodride.routeworker.graph.RoadNode;
import java.util.*;

public class RouteCandidate implements Comparable<RouteCandidate> {
    private final List<RoadNode> waypoints;
    private final double totalScenicScore;
    private final double totalDistanceKm;
    private final int estimatedMinutes;
    private final String algorithmVersion;
    private final Integer beamCandidates;
    
    public RouteCandidate(List<RoadNode> waypoints, double scenicScore, 
                         double distanceKm, int minutes) {
        this(waypoints, scenicScore, distanceKm, minutes, "unknown", null);
    }

    public RouteCandidate(List<RoadNode> waypoints, double scenicScore,
                         double distanceKm, int minutes,
                         String algorithmVersion, Integer beamCandidates) {
        this.waypoints = new ArrayList<>(waypoints);
        this.totalScenicScore = scenicScore;
        this.totalDistanceKm = distanceKm;
        this.estimatedMinutes = minutes;
        this.algorithmVersion = algorithmVersion;
        this.beamCandidates = beamCandidates;
    }
    
    public List<RoadNode> getWaypoints() {
        return new ArrayList<>(waypoints);
    }
    
    public double getTotalScenicScore() {
        return totalScenicScore;
    }
    
    public double getTotalDistanceKm() {
        return totalDistanceKm;
    }
    
    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public Integer getBeamCandidates() {
        return beamCandidates;
    }
    
    @Override
    public int compareTo(RouteCandidate other) {
        return Double.compare(other.totalScenicScore, this.totalScenicScore);
    }
    
    @Override
    public String toString() {
        return String.format("Route(score=%.3f, distance=%.1f km, time=%d min)",
            totalScenicScore, totalDistanceKm, estimatedMinutes);
    }
}
