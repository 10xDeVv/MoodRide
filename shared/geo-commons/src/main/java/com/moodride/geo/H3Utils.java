package com.moodride.geo;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.io.IOException;
import java.util.List;

/**
 * H3 hexagonal indexing utilities for geospatial operations.
 * Uses Uber's H3 library for hierarchical spatial indexing.
 */
public class H3Utils {
    
    private static final H3Core h3;
    private static final GeometryFactory geometryFactory = new GeometryFactory();
    
    // H3 resolution 7 provides ~5.16 km² hexagons
    public static final int DEFAULT_RESOLUTION = 7;
    
    static {
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize H3Core", e);
        }
    }
    
    /**
     * Get H3 index for a lat/lng coordinate at the default resolution (7).
     */
    public static String getH3Index(double lat, double lng) {
        return getH3Index(lat, lng, DEFAULT_RESOLUTION);
    }
    
    /**
     * Get H3 index for a lat/lng coordinate at a specific resolution.
     */
    public static String getH3Index(double lat, double lng, int resolution) {
        return Long.toHexString(h3.latLngToCell(lat, lng, resolution));
    }
    
    /**
     * Get H3 index from a JTS Point geometry.
     */
    public static String getH3Index(Point point) {
        return getH3Index(point.getY(), point.getX(), DEFAULT_RESOLUTION);
    }
    
    /**
     * Get the center lat/lng of an H3 cell.
     */
    public static LatLng getCellCenter(String h3Index) {
        return h3.cellToLatLng(h3Index);
    }
    
    /**
     * Get all neighboring H3 cells (6 neighbors for a hexagon).
     */
    public static List<String> getNeighbors(String h3Index) {
        return h3.gridDisk(h3Index, 1);
    }
    
    /**
     * Get all cells within k rings of the given cell.
     * k=1 returns the cell and its 6 neighbors (7 total)
     * k=2 returns the cell, 6 neighbors, and their neighbors (19 total)
     */
    public static List<String> getKRing(String h3Index, int k) {
        return h3.gridDisk(h3Index, k);
    }
    
    /**
     * Convert H3 index to JTS Point geometry (cell center).
     */
    public static Point h3ToPoint(String h3Index) {
        LatLng center = getCellCenter(h3Index);
        return geometryFactory.createPoint(new Coordinate(center.lng, center.lat));
    }
    
    /**
     * Check if an H3 index is valid.
     */
    public static boolean isValidH3Index(String h3Index) {
        return h3.isValidCell(h3Index);
    }
    
    /**
     * Get the resolution of an H3 index.
     */
    public static int getResolution(String h3Index) {
        return h3.getResolution(h3Index);
    }
    
    /**
     * Get the parent cell at a coarser resolution.
     */
    public static String getParent(String h3Index, int parentResolution) {
        long cellId = Long.parseLong(h3Index, 16);
        return Long.toHexString(h3.cellToParent(cellId, parentResolution));
    }
    
    /**
     * Get all child cells at a finer resolution.
     */
    public static List<String> getChildren(String h3Index, int childResolution) {
        return h3.cellToChildren(h3Index, childResolution);
    }
}
