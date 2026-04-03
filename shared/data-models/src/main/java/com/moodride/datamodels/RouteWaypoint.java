package com.moodride.datamodels;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity representing individual waypoints in a generated route.
 * Each waypoint provides navigation instructions and distance information.
 */
@Entity
@Table(name = "route_waypoints", indexes = {
    @Index(name = "idx_waypoint_route", columnList = "route_id"),
    @Index(name = "idx_waypoint_order", columnList = "route_id, waypointOrder")
})
public class RouteWaypoint {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(nullable = false)
    private int waypointOrder;  // Order in the route (0-based)

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(length = 200)
    private String instruction;  // "Turn left onto Highway 1", "Continue straight", etc.

    @Column(nullable = false)
    private double distanceToNext;  // Kilometers to next waypoint (0.0 for last waypoint)

    // Constructors
    public RouteWaypoint() {}

    public RouteWaypoint(Route route, int waypointOrder, double latitude, double longitude, 
                        String instruction, double distanceToNext) {
        this.route = route;
        this.waypointOrder = waypointOrder;
        this.latitude = latitude;
        this.longitude = longitude;
        this.instruction = instruction;
        this.distanceToNext = distanceToNext;
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Route getRoute() { return route; }
    public void setRoute(Route route) { this.route = route; }

    public int getWaypointOrder() { return waypointOrder; }
    public void setWaypointOrder(int waypointOrder) { this.waypointOrder = waypointOrder; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public double getDistanceToNext() { return distanceToNext; }
    public void setDistanceToNext(double distanceToNext) { this.distanceToNext = distanceToNext; }
}