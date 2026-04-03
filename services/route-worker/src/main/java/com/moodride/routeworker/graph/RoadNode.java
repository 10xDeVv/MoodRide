package com.moodride.routeworker.graph;

import java.util.Objects;

public class RoadNode {
    private final double latitude;
    private final double longitude;
    
    public RoadNode(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
    
    public double getLatitude() {
        return latitude;
    }
    
    public double getLongitude() {
        return longitude;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadNode roadNode = (RoadNode) o;
        return Double.compare(roadNode.latitude, latitude) == 0 &&
               Double.compare(roadNode.longitude, longitude) == 0;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }
    
    @Override
    public String toString() {
        return String.format("Node(%.6f,%.6f)", latitude, longitude);
    }
}
