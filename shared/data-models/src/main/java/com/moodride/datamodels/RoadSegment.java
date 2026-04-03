package com.moodride.datamodels;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import java.time.Instant;

/**
 * JPA entity representing a road segment in the road network.
 * Each segment connects two intersections and has associated scenic scoring.
 */
@Entity
@Table(name = "road_segments", indexes = {
    @Index(name = "idx_road_geom", columnList = "geometry"),
    @Index(name = "idx_road_h3_tile", columnList = "h3TileIndex")
})
public class RoadSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long osmWayId;  // Original OSM way ID

    @Column(name = "geometry", columnDefinition = "geometry(LINESTRING, 4326)", nullable = false)
    private LineString geometry;  // PostGIS geometry

    @Column(nullable = false, length = 15)
    private String h3TileIndex;  // H3 hex index (resolution 9)

    @Column(nullable = false)
    private double lengthMeters;

    @Column(nullable = false)
    private int speedLimitKmh;

    @Column(length = 50)
    private String roadType;  // "highway", "arterial", "residential", etc.

    @Column(length = 100)
    private String surface;  // "paved", "unpaved", "gravel", etc.

    @Column(nullable = false)
    private double curvature;  // Road curvature metric (0.0 - 1.0)

    @Column(nullable = false)
    private double elevationChange;  // Elevation change in meters

    @Column(nullable = false)
    private Instant lastUpdated;

    // Constructors
    public RoadSegment() {}

    public RoadSegment(Long osmWayId, LineString geometry, String h3TileIndex, 
                      double lengthMeters, int speedLimitKmh) {
        this.osmWayId = osmWayId;
        this.geometry = geometry;
        this.h3TileIndex = h3TileIndex;
        this.lengthMeters = lengthMeters;
        this.speedLimitKmh = speedLimitKmh;
        this.lastUpdated = Instant.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOsmWayId() { return osmWayId; }
    public void setOsmWayId(Long osmWayId) { this.osmWayId = osmWayId; }

    public LineString getGeometry() { return geometry; }
    public void setGeometry(LineString geometry) { this.geometry = geometry; }

    public String getH3TileIndex() { return h3TileIndex; }
    public void setH3TileIndex(String h3TileIndex) { this.h3TileIndex = h3TileIndex; }

    public double getLengthMeters() { return lengthMeters; }
    public void setLengthMeters(double lengthMeters) { this.lengthMeters = lengthMeters; }

    public int getSpeedLimitKmh() { return speedLimitKmh; }
    public void setSpeedLimitKmh(int speedLimitKmh) { this.speedLimitKmh = speedLimitKmh; }

    public String getRoadType() { return roadType; }
    public void setRoadType(String roadType) { this.roadType = roadType; }

    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }

    public double getCurvature() { return curvature; }
    public void setCurvature(double curvature) { this.curvature = curvature; }

    public double getElevationChange() { return elevationChange; }
    public void setElevationChange(double elevationChange) { this.elevationChange = elevationChange; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
}