package com.moodride.routeworker.graph;

public class RoadSegmentEdge {
    private final long roadSegmentId;
    private final double lengthMeters;
    private final double scenicScore;
    private final String roadType;
    
    public RoadSegmentEdge(long id, double lengthMeters, double scenicScore, String roadType) {
        this.roadSegmentId = id;
        this.lengthMeters = lengthMeters;
        this.scenicScore = scenicScore;
        this.roadType = roadType;
    }
    
    public long getRoadSegmentId() {
        return roadSegmentId;
    }
    
    public double getLengthMeters() {
        return lengthMeters;
    }
    
    public double getScenicScore() {
        return scenicScore;
    }
    
    public String getRoadType() {
        return roadType;
    }
    
    // Edge weight: scenic score normalized (higher is better)
    // For pathfinding, we use 1 - scenicScore so lower is better
    public double getWeight() {
        return (1.0 - scenicScore) + (lengthMeters / 100000.0);
    }
}
