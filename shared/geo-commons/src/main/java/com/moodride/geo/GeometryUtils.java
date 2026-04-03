package com.moodride.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/**
 * Geospatial distance calculations and utilities.
 */
public class GeometryUtils {
    
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;
    
    /**
     * Calculate Haversine distance between two points in meters.
     * This is the great-circle distance on Earth's surface.
     */
    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * DEGREES_TO_RADIANS;
        double dLon = (lon2 - lon1) * DEGREES_TO_RADIANS;
        
        double lat1Rad = lat1 * DEGREES_TO_RADIANS;
        double lat2Rad = lat2 * DEGREES_TO_RADIANS;
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.sin(dLon / 2) * Math.sin(dLon / 2) * 
                   Math.cos(lat1Rad) * Math.cos(lat2Rad);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_METERS * c;
    }
    
    /**
     * Calculate Haversine distance between two JTS Points in meters.
     */
    public static double haversineDistance(Point p1, Point p2) {
        return haversineDistance(p1.getY(), p1.getX(), p2.getY(), p2.getX());
    }
    
    /**
     * Calculate curvature score for a road segment (0.0 = straight, 1.0 = very curvy).
     * Uses the ratio of actual path length to straight-line distance.
     */
    public static double calculateCurvature(LineString lineString) {
        if (lineString.getNumPoints() < 2) {
            return 0.0;
        }
        
        // Get start and end points
        Coordinate start = lineString.getCoordinateN(0);
        Coordinate end = lineString.getCoordinateN(lineString.getNumPoints() - 1);
        
        // Calculate straight-line distance
        double straightLineDistance = haversineDistance(
            start.y, start.x, end.y, end.x
        );
        
        if (straightLineDistance < 1.0) {
            return 0.0; // Too short to measure curvature
        }
        
        // Calculate actual path length
        double pathLength = 0.0;
        for (int i = 0; i < lineString.getNumPoints() - 1; i++) {
            Coordinate c1 = lineString.getCoordinateN(i);
            Coordinate c2 = lineString.getCoordinateN(i + 1);
            pathLength += haversineDistance(c1.y, c1.x, c2.y, c2.x);
        }
        
        // Curvature ratio: (path length - straight distance) / straight distance
        double curvatureRatio = (pathLength - straightLineDistance) / straightLineDistance;
        
        // Normalize to 0.0-1.0 range (assuming max curvature ratio of 0.5 = very curvy)
        return Math.min(1.0, curvatureRatio / 0.5);
    }
    
    /**
     * Calculate bearing (compass direction) from point 1 to point 2 in degrees (0-360).
     * 0° = North, 90° = East, 180° = South, 270° = West
     */
    public static double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = lat1 * DEGREES_TO_RADIANS;
        double lat2Rad = lat2 * DEGREES_TO_RADIANS;
        double dLon = (lon2 - lon1) * DEGREES_TO_RADIANS;
        
        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                   Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);
        
        double bearingRad = Math.atan2(y, x);
        double bearingDeg = bearingRad / DEGREES_TO_RADIANS;
        
        // Normalize to 0-360
        return (bearingDeg + 360) % 360;
    }
    
    /**
     * Calculate the destination point given a start point, bearing, and distance.
     * Used for bounding box calculations.
     */
    public static Coordinate destinationPoint(double lat, double lon, double bearingDegrees, double distanceMeters) {
        double bearingRad = bearingDegrees * DEGREES_TO_RADIANS;
        double angularDistance = distanceMeters / EARTH_RADIUS_METERS;
        double latRad = lat * DEGREES_TO_RADIANS;
        double lonRad = lon * DEGREES_TO_RADIANS;
        
        double destLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(angularDistance) +
            Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearingRad)
        );
        
        double destLonRad = lonRad + Math.atan2(
            Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(latRad),
            Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(destLatRad)
        );
        
        return new Coordinate(
            destLonRad / DEGREES_TO_RADIANS,
            destLatRad / DEGREES_TO_RADIANS
        );
    }
    
    /**
     * Calculate bounding box (min/max lat/lng) for a given center point and radius.
     * Returns [minLon, minLat, maxLon, maxLat]
     */
    public static double[] getBoundingBox(double lat, double lon, double radiusMeters) {
        // Calculate corners using bearing
        Coordinate north = destinationPoint(lat, lon, 0, radiusMeters);
        Coordinate south = destinationPoint(lat, lon, 180, radiusMeters);
        Coordinate east = destinationPoint(lat, lon, 90, radiusMeters);
        Coordinate west = destinationPoint(lat, lon, 270, radiusMeters);
        
        return new double[] {
            west.x,  // minLon
            south.y, // minLat
            east.x,  // maxLon
            north.y  // maxLat
        };
    }
}
