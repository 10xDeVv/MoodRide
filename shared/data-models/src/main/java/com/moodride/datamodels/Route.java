package com.moodride.datamodels;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity representing a generated scenic route.
 * Contains the complete route with waypoints and metadata.
 */
@Entity
@Table(name = "routes", indexes = {
    @Index(name = "idx_route_job", columnList = "jobId"),
    @Index(name = "idx_route_user", columnList = "userId"),
    @Index(name = "idx_route_score", columnList = "scenicScore")
})
public class Route {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID jobId;  // Links to RouteJob

    @Column(nullable = false)
    private UUID userId;

    @Column(name = "geometry", columnDefinition = "geometry(LINESTRING, 4326)", nullable = false)
    private LineString geometry;  // Complete route as LineString

    @Column(nullable = false)
    private double totalDistanceKm;

    @Column(nullable = false)
    private int estimatedDurationMinutes;

    @Column(nullable = false)
    private double scenicScore;  // Average scenic score (0.0 - 1.0)

    @Enumerated(EnumType.STRING)
    @Column(name = "route_mode", nullable = false, length = 16)
    private RouteMode routeMode = RouteMode.DRIVE;

    @Column(nullable = false)
    private String vibe;  // "coastal", "mountain", "forest", "mixed"

    @Column
    private String routeProfile; // "most_scenic", "balanced", "shorter"

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RouteWaypoint> waypoints;

    @Column(nullable = false)
    private Instant generatedAt;

    @Column(nullable = false)
    private Instant expiresAt;  // Routes expire after 24 hours

    @Column(columnDefinition = "SMALLINT")
    private Short userRating;

    @Column
    private Instant ratedAt;

    // Constructors
    public Route() {}

    public Route(UUID jobId, UUID userId, LineString geometry, String vibe) {
        this.jobId = jobId;
        this.userId = userId;
        this.geometry = geometry;
        this.routeMode = RouteMode.DRIVE;
        this.vibe = vibe;
        this.generatedAt = Instant.now();
        this.expiresAt = this.generatedAt.plusSeconds(24 * 60 * 60); // 24 hours
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public LineString getGeometry() { return geometry; }
    public void setGeometry(LineString geometry) { this.geometry = geometry; }

    public double getTotalDistanceKm() { return totalDistanceKm; }
    public void setTotalDistanceKm(double totalDistanceKm) { this.totalDistanceKm = totalDistanceKm; }

    public int getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public void setEstimatedDurationMinutes(int estimatedDurationMinutes) { this.estimatedDurationMinutes = estimatedDurationMinutes; }

    public double getScenicScore() { return scenicScore; }
    public void setScenicScore(double scenicScore) { this.scenicScore = scenicScore; }

    public RouteMode getRouteMode() { return routeMode == null ? RouteMode.DRIVE : routeMode; }
    public void setRouteMode(RouteMode routeMode) { this.routeMode = routeMode == null ? RouteMode.DRIVE : routeMode; }

    public String getVibe() { return vibe; }
    public void setVibe(String vibe) { this.vibe = vibe; }

    public String getRouteProfile() { return routeProfile; }
    public void setRouteProfile(String routeProfile) { this.routeProfile = routeProfile; }

    public List<RouteWaypoint> getWaypoints() { return waypoints; }
    public void setWaypoints(List<RouteWaypoint> waypoints) { this.waypoints = waypoints; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Integer getUserRating() {
        return userRating == null ? null : userRating.intValue();
    }

    public void setUserRating(Integer userRating) {
        this.userRating = userRating == null ? null : userRating.shortValue();
    }

    public Instant getRatedAt() { return ratedAt; }
    public void setRatedAt(Instant ratedAt) { this.ratedAt = ratedAt; }
}
