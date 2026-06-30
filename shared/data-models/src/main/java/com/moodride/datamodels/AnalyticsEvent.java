package com.moodride.datamodels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analytics_events")
public class AnalyticsEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "anonymous_session_id", nullable = false, length = 80)
    private String anonymousSessionId;

    @Column(name = "event_name", nullable = false, length = 80)
    private String eventName;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "route_profile", length = 40)
    private String routeProfile;

    @Column(name = "route_mode", length = 16)
    private String routeMode;

    @Column(name = "vibes_json", columnDefinition = "TEXT")
    private String vibesJson;

    @Column(name = "time_budget_minutes")
    private Integer timeBudgetMinutes;

    @Column(name = "route_count")
    private Integer routeCount;

    @Column(length = 40)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "scenic_score")
    private Double scenicScore;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAnonymousSessionId() {
        return anonymousSessionId;
    }

    public void setAnonymousSessionId(String anonymousSessionId) {
        this.anonymousSessionId = anonymousSessionId;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public void setRouteId(UUID routeId) {
        this.routeId = routeId;
    }

    public String getRouteProfile() {
        return routeProfile;
    }

    public void setRouteProfile(String routeProfile) {
        this.routeProfile = routeProfile;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getVibesJson() {
        return vibesJson;
    }

    public void setVibesJson(String vibesJson) {
        this.vibesJson = vibesJson;
    }

    public Integer getTimeBudgetMinutes() {
        return timeBudgetMinutes;
    }

    public void setTimeBudgetMinutes(Integer timeBudgetMinutes) {
        this.timeBudgetMinutes = timeBudgetMinutes;
    }

    public Integer getRouteCount() {
        return routeCount;
    }

    public void setRouteCount(Integer routeCount) {
        this.routeCount = routeCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Double getScenicScore() {
        return scenicScore;
    }

    public void setScenicScore(Double scenicScore) {
        this.scenicScore = scenicScore;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
