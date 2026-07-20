package com.moodride.datamodels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable publication ledger for one committed terminal route-job event.
 */
@Entity
@Table(name = "route_job_terminal_events")
public class RouteJobTerminalEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "state_revision", nullable = false)
    private long stateRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_status", nullable = false, length = 20)
    private RouteJob.JobStatus terminalStatus;

    @Column(name = "original_payload", length = 1000)
    private String originalPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected RouteJobTerminalEvent() {
    }

    public RouteJobTerminalEvent(
        UUID jobId,
        long stateRevision,
        EventType eventType,
        RouteJob.JobStatus terminalStatus,
        String originalPayload,
        Instant createdAt
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        if (stateRevision < 0) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        this.stateRevision = stateRevision;
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.terminalStatus = requireTerminal(terminalStatus);
        this.originalPayload = originalPayload;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.nextAttemptAt = createdAt;
        this.eventId = identity(jobId, stateRevision, eventType);
    }

    public static String identity(UUID jobId, long stateRevision, EventType eventType) {
        return Objects.requireNonNull(jobId, "jobId")
            + ":" + stateRevision
            + ":" + Objects.requireNonNull(eventType, "eventType").name();
    }

    public void claim(UUID token, Instant expiresAt) {
        if (deliveredAt != null) {
            throw new IllegalStateException("A delivered terminal event cannot be claimed");
        }
        leaseToken = Objects.requireNonNull(token, "token");
        leaseExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        attemptCount++;
    }

    public boolean markDelivered(UUID expectedToken, Instant acknowledgedAt) {
        if (!leaseMatches(expectedToken)) {
            return false;
        }
        deliveredAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
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

    private static RouteJob.JobStatus requireTerminal(RouteJob.JobStatus status) {
        RouteJob.JobStatus required = Objects.requireNonNull(status, "terminalStatus");
        if (required != RouteJob.JobStatus.COMPLETED
            && required != RouteJob.JobStatus.FAILED
            && required != RouteJob.JobStatus.TIMEOUT) {
            throw new IllegalArgumentException("Terminal event status must be terminal: " + required);
        }
        return required;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public long getStateRevision() {
        return stateRevision;
    }

    public EventType getEventType() {
        return eventType;
    }

    public RouteJob.JobStatus getTerminalStatus() {
        return terminalStatus;
    }

    public String getOriginalPayload() {
        return originalPayload;
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

    public Instant getDeliveredAt() {
        return deliveredAt;
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

    public enum EventType {
        COMPLETION,
        DLQ
    }
}
