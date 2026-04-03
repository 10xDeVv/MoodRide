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

    @Column(nullable = false, length = 20)
    private String vibe;  // "coastal", "mountain", "forest", "mixed"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private Instant submittedAt;

    private Instant startedAt;

    private Instant completedAt;

    @OneToOne(mappedBy = "jobId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Route generatedRoute;

    // Constructors
    public RouteJob() {}

    public RouteJob(UUID userId, double startLatitude, double startLongitude, 
                   int timeBudgetMinutes, String vibe) {
        this.userId = userId;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
        this.timeBudgetMinutes = timeBudgetMinutes;
        this.vibe = vibe;
        this.status = JobStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    /**
     * Marks the job as started and records the start time.
     */
    public void markStarted() {
        this.status = JobStatus.PROCESSING;
        this.startedAt = Instant.now();
    }

    /**
     * Marks the job as completed successfully.
     */
    public void markCompleted() {
        this.status = JobStatus.SUCCESS;
        this.completedAt = Instant.now();
    }

    /**
     * Marks the job as failed with a reason.
     */
    public void markFailed(String reason) {
        this.status = JobStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    // Job status enumeration
    public enum JobStatus {
        SUBMITTED,   // Job received but not started
        PROCESSING,  // Route generation in progress
        SUCCESS,     // Route generated successfully
        FAILED,      // Route generation failed
        TIMEOUT      // Job exceeded time limit
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

    public String getVibe() { return vibe; }
    public void setVibe(String vibe) { this.vibe = vibe; }

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

    public Route getGeneratedRoute() { return generatedRoute; }
    public void setGeneratedRoute(Route generatedRoute) { this.generatedRoute = generatedRoute; }
}