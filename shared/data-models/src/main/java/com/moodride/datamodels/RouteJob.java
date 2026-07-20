package com.moodride.datamodels;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a route generation job.
 * Tracks the lifecycle and status of route generation requests.
 */
@Entity
@Table(name = "route_jobs", indexes = {
    @Index(name = "idx_job_user", columnList = "userId"),
    @Index(name = "idx_job_status", columnList = "status"),
    @Index(name = "idx_job_submitted", columnList = "submittedAt")
})
public class RouteJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private double startLatitude;

    @Column(nullable = false)
    private double startLongitude;

    @Column(nullable = false)
    private int timeBudgetMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_mode", nullable = false, length = 16)
    private RouteMode routeMode = RouteMode.DRIVE;

    @Column(nullable = false, length = 20)
    private String vibe;  // "coastal", "mountain", "forest", "mixed"

    @Column(name = "preference_vector", columnDefinition = "TEXT")
    private String preferenceVector;

    @Column(name = "vibes_json", columnDefinition = "TEXT")
    private String vibesJson;

    @Column(name = "algorithm_version", length = 50)
    private String algorithmVersion;

    @Column(name = "beam_candidates")
    private Integer beamCandidates;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private Instant submittedAt;

    private Instant startedAt;

    private Instant completedAt;

    private Instant failedAt;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private int maxRetries = 2;

    private UUID routeId;

    private Instant primaryReadyAt;

    @Column(nullable = false)
    private long stateRevision = 0L;

    @Column(nullable = false)
    private long optionRevision = 0L;

    @Column(nullable = false)
    private int optionCount = 0;

    @Column(nullable = false)
    private boolean optionsComplete = false;

    private UUID leaseToken;

    private Instant leaseExpiresAt;

    @Version
    @Column(nullable = false)
    private long rowVersion = 0L;

    // Constructors
    public RouteJob() {}

    public RouteJob(UUID userId, double startLatitude, double startLongitude, 
                   int timeBudgetMinutes, String vibe) {
        this.userId = userId;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
        this.timeBudgetMinutes = timeBudgetMinutes;
        this.routeMode = RouteMode.DRIVE;
        this.vibe = vibe;
        this.status = JobStatus.QUEUED;
        this.submittedAt = Instant.now();
    }

    /**
     * Marks the job as started and records the start time.
     */
    public void markStarted() {
        boolean changed = this.status != JobStatus.PROCESSING;
        this.status = JobStatus.PROCESSING;
        this.startedAt = Instant.now();
        this.failureReason = null;
        if (changed) {
            incrementStateRevision();
        }
    }

    /**
     * Marks the job as having a primary route ready while alternatives continue.
     */
    public void markPrimaryReady(UUID routeId) {
        boolean changed = this.status != JobStatus.PRIMARY_READY
            || this.routeId == null
            || this.primaryReadyAt == null;
        preservePrimaryRoute(routeId);
        this.status = JobStatus.PRIMARY_READY;
        if (this.primaryReadyAt == null) {
            this.primaryReadyAt = Instant.now();
        }
        this.failureReason = null;
        if (changed) {
            incrementStateRevision();
        }
    }


    /**
     * Marks the job as completed successfully.
     */
    public void markCompleted(UUID routeId) {
        boolean changed = this.status != JobStatus.COMPLETED || !this.optionsComplete;
        preservePrimaryRoute(routeId);
        this.status = JobStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.failureReason = null;
        this.optionsComplete = true;
        clearLease();
        if (changed) {
            incrementStateRevision();
        }
    }

    /**
     * Marks the job as failed with a reason.
     */
    public void markFailed(String reason) {
        boolean changed = this.status != JobStatus.FAILED || !java.util.Objects.equals(this.failureReason, reason);
        this.status = JobStatus.FAILED;
        this.failureReason = reason;
        this.failedAt = Instant.now();
        this.completedAt = this.failedAt;
        clearLease();
        if (changed) {
            incrementStateRevision();
        }
    }

    public void markTimeout(String reason) {
        boolean changed = this.status != JobStatus.TIMEOUT || !java.util.Objects.equals(this.failureReason, reason);
        this.status = JobStatus.TIMEOUT;
        this.failureReason = reason;
        this.failedAt = Instant.now();
        this.completedAt = this.failedAt;
        clearLease();
        if (changed) {
            incrementStateRevision();
        }
    }

    public void requeueForRetry() {
        boolean changed = this.status != JobStatus.QUEUED;
        this.status = JobStatus.QUEUED;
        this.startedAt = null;
        this.completedAt = null;
        this.failedAt = null;
        clearLease();
        if (changed) {
            incrementStateRevision();
        }
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void claimLease(UUID token, Instant expiresAt) {
        this.leaseToken = java.util.Objects.requireNonNull(token, "token");
        this.leaseExpiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public void clearLease() {
        this.leaseToken = null;
        this.leaseExpiresAt = null;
    }

    public boolean leaseMatches(UUID expectedToken) {
        return expectedToken != null && expectedToken.equals(this.leaseToken);
    }

    public void recordVisibleOption(int committedOptionCount) {
        this.optionRevision++;
        this.optionCount = committedOptionCount;
    }

    public void incrementStateRevision() {
        this.stateRevision++;
    }

    private void preservePrimaryRoute(UUID candidateRouteId) {
        UUID requiredRouteId = java.util.Objects.requireNonNull(candidateRouteId, "routeId");
        if (this.routeId != null && !this.routeId.equals(requiredRouteId)) {
            throw new IllegalStateException(
                "Primary route " + this.routeId + " cannot be replaced by " + requiredRouteId
            );
        }
        this.routeId = requiredRouteId;
    }

    // Job status enumeration
    public enum JobStatus {
        QUEUED,        // Job received but not started
        PROCESSING,    // Route generation in progress
        PRIMARY_READY, // Primary route is visible while alternatives continue
        COMPLETED,     // Route options generated successfully
        FAILED,        // Route generation failed
        TIMEOUT        // Job exceeded time limit
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public double getStartLatitude() { return startLatitude; }
    public void setStartLatitude(double startLatitude) { this.startLatitude = startLatitude; }

    public double getStartLongitude() { return startLongitude; }
    public void setStartLongitude(double startLongitude) { this.startLongitude = startLongitude; }

    public int getTimeBudgetMinutes() { return timeBudgetMinutes; }
    public void setTimeBudgetMinutes(int timeBudgetMinutes) { this.timeBudgetMinutes = timeBudgetMinutes; }

    public RouteMode getRouteMode() { return routeMode == null ? RouteMode.DRIVE : routeMode; }
    public void setRouteMode(RouteMode routeMode) { this.routeMode = routeMode == null ? RouteMode.DRIVE : routeMode; }

    public String getVibe() { return vibe; }
    public void setVibe(String vibe) { this.vibe = vibe; }

    public String getPreferenceVector() { return preferenceVector; }
    public void setPreferenceVector(String preferenceVector) { this.preferenceVector = preferenceVector; }

    public String getVibesJson() { return vibesJson; }
    public void setVibesJson(String vibesJson) { this.vibesJson = vibesJson; }

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }

    public Integer getBeamCandidates() { return beamCandidates; }
    public void setBeamCandidates(Integer beamCandidates) { this.beamCandidates = beamCandidates; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getFailedAt() { return failedAt; }
    public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public UUID getRouteId() { return routeId; }
    public void setRouteId(UUID routeId) { this.routeId = routeId; }

    public Instant getPrimaryReadyAt() { return primaryReadyAt; }
    public void setPrimaryReadyAt(Instant primaryReadyAt) { this.primaryReadyAt = primaryReadyAt; }

    public long getStateRevision() { return stateRevision; }
    public void setStateRevision(long stateRevision) { this.stateRevision = stateRevision; }

    public long getOptionRevision() { return optionRevision; }
    public void setOptionRevision(long optionRevision) { this.optionRevision = optionRevision; }

    public int getOptionCount() { return optionCount; }
    public void setOptionCount(int optionCount) { this.optionCount = optionCount; }

    public boolean isOptionsComplete() { return optionsComplete; }
    public void setOptionsComplete(boolean optionsComplete) { this.optionsComplete = optionsComplete; }

    public UUID getLeaseToken() { return leaseToken; }
    public void setLeaseToken(UUID leaseToken) { this.leaseToken = leaseToken; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public long getRowVersion() { return rowVersion; }
    public void setRowVersion(long rowVersion) { this.rowVersion = rowVersion; }

    public void incrementRetryCount() { this.retryCount++; }

}
