package com.moodride.datamodels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "route_duration_calibrations")
public class RouteDurationCalibration {

    public static final int TIME_BUDGET_BUCKET_MINUTES = 15;

    @Id
    @Column(length = 128, nullable = false)
    private String id;

    @Column(name = "route_mode", nullable = false, length = 16)
    private String routeMode;

    @Column(name = "region_key", nullable = false, length = 32)
    private String regionKey;

    @Column(name = "time_budget_bucket_minutes", nullable = false)
    private int timeBudgetBucketMinutes;

    @Column(name = "geometry_strategy", nullable = false, length = 32)
    private String geometryStrategy;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Column(name = "radius_multiplier", nullable = false)
    private double radiusMultiplier = 1.0;

    @Column(name = "learned_waypoint_count", nullable = false)
    private double learnedWaypointCount = 6.0;

    @Column(name = "avg_requested_radius_km", nullable = false)
    private double avgRequestedRadiusKm = 0.0;

    @Column(name = "avg_requested_waypoint_count", nullable = false)
    private double avgRequestedWaypointCount = 0.0;

    @Column(name = "avg_duration_ratio", nullable = false)
    private double avgDurationRatio = 1.0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public RouteDurationCalibration() {
    }

    public RouteDurationCalibration(String routeMode,
                                    String regionKey,
                                    int timeBudgetBucketMinutes,
                                    String geometryStrategy) {
        this.routeMode = normalizeKey(routeMode);
        this.regionKey = normalizeKey(regionKey);
        this.timeBudgetBucketMinutes = timeBudgetBucketMinutes;
        this.geometryStrategy = normalizeKey(geometryStrategy);
        this.id = idFor(this.routeMode, this.regionKey, this.timeBudgetBucketMinutes, this.geometryStrategy);
    }

    public void observe(double requestedRadiusKm,
                        int requestedWaypointCount,
                        int targetMinutes,
                        int durationMinutes,
                        Instant observedAt) {
        if (requestedRadiusKm <= 0.0 || requestedWaypointCount <= 0 || targetMinutes <= 0 || durationMinutes <= 0) {
            return;
        }

        double durationRatio = clamp(durationMinutes / (double) targetMinutes, 0.25, 3.0);
        double correction = clamp(targetMinutes / (double) durationMinutes, 0.75, 1.25);
        double correctedWaypointCount = clamp(requestedWaypointCount * correction, 2.0, 10.0);
        int nextCount = sampleCount + 1;

        radiusMultiplier = runningAverage(radiusMultiplier, correction, sampleCount, nextCount);
        learnedWaypointCount = runningAverage(learnedWaypointCount, correctedWaypointCount, sampleCount, nextCount);
        avgRequestedRadiusKm = runningAverage(avgRequestedRadiusKm, requestedRadiusKm, sampleCount, nextCount);
        avgRequestedWaypointCount = runningAverage(avgRequestedWaypointCount, requestedWaypointCount, sampleCount, nextCount);
        avgDurationRatio = runningAverage(avgDurationRatio, durationRatio, sampleCount, nextCount);
        sampleCount = nextCount;
        updatedAt = observedAt == null ? Instant.now() : observedAt;
    }

    public static String idFor(String routeMode,
                               String regionKey,
                               int timeBudgetBucketMinutes,
                               String geometryStrategy) {
        return normalizeKey(routeMode)
            + "|" + normalizeKey(regionKey)
            + "|" + timeBudgetBucketMinutes
            + "|" + normalizeKey(geometryStrategy);
    }

    public static int bucketMinutes(int timeBudgetMinutes) {
        int safeMinutes = Math.max(1, timeBudgetMinutes);
        return Math.max(
            TIME_BUDGET_BUCKET_MINUTES,
            ((safeMinutes + (TIME_BUDGET_BUCKET_MINUTES / 2)) / TIME_BUDGET_BUCKET_MINUTES)
                * TIME_BUDGET_BUCKET_MINUTES
        );
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static double runningAverage(double previous, double observed, int currentCount, int nextCount) {
        if (currentCount <= 0) {
            return observed;
        }
        return ((previous * currentCount) + observed) / nextCount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(String regionKey) {
        this.regionKey = regionKey;
    }

    public int getTimeBudgetBucketMinutes() {
        return timeBudgetBucketMinutes;
    }

    public void setTimeBudgetBucketMinutes(int timeBudgetBucketMinutes) {
        this.timeBudgetBucketMinutes = timeBudgetBucketMinutes;
    }

    public String getGeometryStrategy() {
        return geometryStrategy;
    }

    public void setGeometryStrategy(String geometryStrategy) {
        this.geometryStrategy = geometryStrategy;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public double getRadiusMultiplier() {
        return radiusMultiplier;
    }

    public void setRadiusMultiplier(double radiusMultiplier) {
        this.radiusMultiplier = radiusMultiplier;
    }

    public double getLearnedWaypointCount() {
        return learnedWaypointCount;
    }

    public void setLearnedWaypointCount(double learnedWaypointCount) {
        this.learnedWaypointCount = learnedWaypointCount;
    }

    public double getAvgRequestedRadiusKm() {
        return avgRequestedRadiusKm;
    }

    public void setAvgRequestedRadiusKm(double avgRequestedRadiusKm) {
        this.avgRequestedRadiusKm = avgRequestedRadiusKm;
    }

    public double getAvgRequestedWaypointCount() {
        return avgRequestedWaypointCount;
    }

    public void setAvgRequestedWaypointCount(double avgRequestedWaypointCount) {
        this.avgRequestedWaypointCount = avgRequestedWaypointCount;
    }

    public double getAvgDurationRatio() {
        return avgDurationRatio;
    }

    public void setAvgDurationRatio(double avgDurationRatio) {
        this.avgDurationRatio = avgDurationRatio;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
