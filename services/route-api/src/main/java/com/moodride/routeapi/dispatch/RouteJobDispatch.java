package com.moodride.routeapi.dispatch;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "route_job_dispatches")
public class RouteJobDispatch {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected RouteJobDispatch() {
    }

    public RouteJobDispatch(UUID jobId, Instant createdAt, Instant nextAttemptAt) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    public void claim(UUID token, Instant expiresAt) {
        if (sentAt != null) {
            throw new IllegalStateException("A sent route job dispatch cannot be claimed");
        }
        leaseToken = Objects.requireNonNull(token, "token");
        leaseExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        attemptCount++;
    }

    public boolean markSent(UUID expectedToken, Instant acknowledgedAt) {
        if (!leaseMatches(expectedToken)) {
            return false;
        }
        sentAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
        lastError = null;
        clearLease();
        return true;
    }

    public boolean scheduleRetry(UUID expectedToken, Instant retryAt, String error) {
        if (!leaseMatches(expectedToken)) {
            return false;
        }
        nextAttemptAt = Objects.requireNonNull(retryAt, "retryAt");
        lastError = Objects.requireNonNull(error, "error");
        clearLease();
        return true;
    }

    private boolean leaseMatches(UUID expectedToken) {
        return expectedToken != null && expectedToken.equals(leaseToken);
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    public UUID getJobId() {
        return jobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastError() {
        return lastError;
    }
}
