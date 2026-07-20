package com.moodride.routeworker.service;

import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteJobTerminalEvent;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RouteJobLifecycleService {
    private final RouteJobRepository jobRepository;
    private final RouteJobTerminalEventRepository terminalEventRepository;
    private final Duration leaseDuration;
    private final Clock clock;

    @Autowired
    public RouteJobLifecycleService(
        RouteJobRepository jobRepository,
        RouteJobTerminalEventRepository terminalEventRepository,
        @Value("${route.generation.timeout.seconds:30}") int leaseSeconds
    ) {
        this(jobRepository, terminalEventRepository, leaseSeconds, Clock.systemUTC());
    }


    RouteJobLifecycleService(
        RouteJobRepository jobRepository,
        RouteJobTerminalEventRepository terminalEventRepository,
        int leaseSeconds,
        Clock clock
    ) {
        this.jobRepository = jobRepository;
        this.terminalEventRepository = Objects.requireNonNull(
            terminalEventRepository,
            "terminalEventRepository"
        );
        this.leaseDuration = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ClaimResult claim(UUID jobId, int configuredMaxAttempts) {
        RouteJob job = lock(jobId);
        LifecycleSnapshot initial = snapshot(job);

        if (job.getStatus() == RouteJob.JobStatus.COMPLETED && job.getRouteId() != null) {
            ensureTerminalEvents(job);
            return new ClaimResult(ClaimAction.REPLAY_TERMINAL, job, null, initial);
        }
        if (job.getStatus() == RouteJob.JobStatus.FAILED
            || job.getStatus() == RouteJob.JobStatus.TIMEOUT) {
            ensureTerminalEvents(job);
            return new ClaimResult(ClaimAction.REPLAY_TERMINAL, job, null, initial);
        }
        if (isTerminal(job.getStatus())) {
            return new ClaimResult(ClaimAction.SKIP, job, null, initial);
        }
        if (job.getStatus() != RouteJob.JobStatus.QUEUED
            && job.getStatus() != RouteJob.JobStatus.PRIMARY_READY) {
            return new ClaimResult(ClaimAction.SKIP, job, null, initial);
        }
        Instant now = clock.instant();
        if (job.getStatus() == RouteJob.JobStatus.PRIMARY_READY
            && job.getLeaseToken() != null) {
            return new ClaimResult(ClaimAction.SKIP, job, null, initial);
        }
        if (job.isOptionsComplete() && job.getRouteId() != null) {
            job.markCompleted(job.getRouteId());
            LifecycleSnapshot terminal = persistTerminal(job);
            return new ClaimResult(ClaimAction.FINALIZED, job, null, terminal);
        }

        int retryBudget = effectiveRetryLimit(job, configuredMaxAttempts);
        if (job.getRetryCount() > retryBudget) {
            if (hasUsablePrimary(job)) {
                job.markCompleted(job.getRouteId());
                LifecycleSnapshot terminal = persistTerminal(job);
                return new ClaimResult(ClaimAction.FINALIZED, job, null, terminal);
            }
            job.markFailed("Exceeded maximum retry attempts: " + (retryBudget + 1));
            LifecycleSnapshot terminal = persistTerminal(job);
            return new ClaimResult(ClaimAction.FAILED, job, null, terminal);
        }

        UUID leaseToken = UUID.randomUUID();
        if (job.getStatus() == RouteJob.JobStatus.QUEUED) {
            job.markStarted();
        }
        job.claimLease(leaseToken, now.plus(leaseDuration));
        jobRepository.saveAndFlush(job);
        return new ClaimResult(ClaimAction.CLAIMED, job, leaseToken, snapshot(job));
    }

    @Transactional
    public Instant renewLease(UUID jobId, UUID expectedLeaseToken) {
        RouteJob job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new LeaseLostException(jobId));
        if (job.getStatus() != RouteJob.JobStatus.PROCESSING
            && job.getStatus() != RouteJob.JobStatus.PRIMARY_READY) {
            throw new LeaseLostException(jobId);
        }

        Instant now = clock.instant();
        WorkerLeaseGuard.requireActive(job, expectedLeaseToken, now);
        Instant renewedUntil = now.plus(leaseDuration);
        if (job.getLeaseExpiresAt().isAfter(renewedUntil)) {
            renewedUntil = job.getLeaseExpiresAt();
        }
        job.setLeaseExpiresAt(renewedUntil);
        jobRepository.saveAndFlush(job);
        return renewedUntil;
    }

    /**
     * Releases only the owner whose committed primary publication failed. The exact
     * token fence prevents a delayed publisher from clearing a successor's lease.
     */
    @Transactional
    public LifecycleSnapshot abandonPrimaryPublication(
        UUID jobId,
        UUID expectedLeaseToken
    ) {
        RouteJob job = lock(jobId);
        if (job.getStatus() != RouteJob.JobStatus.PRIMARY_READY
            || !job.leaseMatches(expectedLeaseToken)) {
            throw new LeaseLostException(jobId);
        }
        job.clearLease();
        jobRepository.saveAndFlush(job);
        return snapshot(job);
    }

    @Transactional
    public LifecycleSnapshot complete(UUID jobId, UUID expectedLeaseToken) {
        RouteJob job = lock(jobId);
        WorkerLeaseGuard.requireActive(job, expectedLeaseToken, clock.instant());
        if (!hasUsablePrimary(job)) {
            throw new IllegalStateException("Cannot complete route job without a committed primary: " + jobId);
        }
        job.markCompleted(job.getRouteId());
        return persistTerminal(job);
    }

    @Transactional
    public RecoveryResult handleTransientFailure(
        UUID jobId,
        UUID expectedLeaseToken,
        int configuredMaxAttempts,
        String failureReason
    ) {
        RouteJob job = lock(jobId);
        Instant now = clock.instant();
        WorkerLeaseGuard.requireActive(job, expectedLeaseToken, now);
        return transitionAfterFailure(job, configuredMaxAttempts, failureReason, now);
    }

    @Transactional
    public RecoveryResult handleNonRetryableFailure(
        UUID jobId,
        UUID expectedLeaseToken,
        String failureReason
    ) {
        RouteJob job = lock(jobId);
        WorkerLeaseGuard.requireActive(job, expectedLeaseToken, clock.instant());
        if (hasUsablePrimary(job)) {
            job.markCompleted(job.getRouteId());
            LifecycleSnapshot terminal = persistTerminal(job);
            return new RecoveryResult(RecoveryAction.FINALIZED, terminal);
        }
        job.markFailed(failureReason);
        return new RecoveryResult(RecoveryAction.FAILED, persistTerminal(job));
    }

    @Transactional
    public RecoveryResult recoverExpired(
        UUID jobId,
        UUID observedLeaseToken,
        int configuredMaxAttempts,
        String failureReason,
        Instant now
    ) {
        RouteJob job = lock(jobId);
        if (job.getStatus() != RouteJob.JobStatus.PROCESSING
            && job.getStatus() != RouteJob.JobStatus.PRIMARY_READY) {
            return new RecoveryResult(RecoveryAction.NONE, snapshot(job));
        }
        if (job.getStatus() == RouteJob.JobStatus.PRIMARY_READY
            && job.getLeaseToken() == null) {
            if (observedLeaseToken != null
                || (job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isAfter(now))) {
                return new RecoveryResult(RecoveryAction.NONE, snapshot(job));
            }
            if (jobRepository.hasPendingRetryDispatch(jobId)) {
                return new RecoveryResult(RecoveryAction.NONE, snapshot(job));
            }
            armRetryDispatchDeadline(job, now);
            persistRetry(job, now);
            return new RecoveryResult(RecoveryAction.RETRY, snapshot(job));
        }
        if (!Objects.equals(job.getLeaseToken(), observedLeaseToken)) {
            return new RecoveryResult(RecoveryAction.NONE, snapshot(job));
        }
        if (job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isAfter(now)) {
            return new RecoveryResult(RecoveryAction.NONE, snapshot(job));
        }
        return transitionAfterFailure(job, configuredMaxAttempts, failureReason, now);
    }


    private RecoveryResult transitionAfterFailure(
        RouteJob job,
        int configuredMaxAttempts,
        String failureReason,
        Instant now
    ) {
        int retryBudget = effectiveRetryLimit(job, configuredMaxAttempts);
        boolean retryAvailable = job.getRetryCount() < retryBudget;

        if (hasUsablePrimary(job)) {
            if (retryAvailable) {
                job.incrementRetryCount();
                armRetryDispatchDeadline(job, now);
                job.incrementStateRevision();
                persistRetry(job, now);
                return new RecoveryResult(RecoveryAction.RETRY, snapshot(job));
            }
            job.markCompleted(job.getRouteId());
            return new RecoveryResult(RecoveryAction.FINALIZED, persistTerminal(job));
        }

        if (retryAvailable) {
            job.incrementRetryCount();
            job.requeueForRetry();
            persistRetry(job, now);
            return new RecoveryResult(RecoveryAction.RETRY, snapshot(job));
        }

        job.markFailed(failureReason);
        return new RecoveryResult(RecoveryAction.FAILED, persistTerminal(job));
    }

    private void persistRetry(RouteJob job, Instant now) {
        jobRepository.saveAndFlush(job);
        jobRepository.upsertRetryDispatch(job.getId(), now);
    }

    private LifecycleSnapshot persistTerminal(RouteJob job) {
        jobRepository.saveAndFlush(job);
        ensureTerminalEvents(job);
        return snapshot(job);
    }

    private void ensureTerminalEvents(RouteJob job) {
        if (!isTerminal(job.getStatus())) {
            return;
        }
        Instant createdAt = job.getCompletedAt() == null ? clock.instant() : job.getCompletedAt();
        saveTerminalEventIfMissing(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null,
            createdAt
        );
        if (job.getStatus() == RouteJob.JobStatus.FAILED
            || job.getStatus() == RouteJob.JobStatus.TIMEOUT) {
            saveTerminalEventIfMissing(
                job,
                RouteJobTerminalEvent.EventType.DLQ,
                job.getId().toString(),
                createdAt
            );
        }
    }

    private void saveTerminalEventIfMissing(
        RouteJob job,
        RouteJobTerminalEvent.EventType eventType,
        String originalPayload,
        Instant createdAt
    ) {
        String eventId = RouteJobTerminalEvent.identity(
            job.getId(),
            job.getStateRevision(),
            eventType
        );
        if (terminalEventRepository.findById(eventId).isEmpty()) {
            terminalEventRepository.save(new RouteJobTerminalEvent(
                job.getId(),
                job.getStateRevision(),
                eventType,
                job.getStatus(),
                originalPayload,
                createdAt
            ));
        }
    }


    private void armRetryDispatchDeadline(RouteJob job, Instant now) {
        job.clearLease();
        job.setLeaseExpiresAt(now.plus(leaseDuration));
    }

    private RouteJob lock(UUID jobId) {
        return jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Route job not found: " + jobId));
    }

    private int effectiveRetryLimit(RouteJob job, int configuredMaxAttempts) {
        int configuredRetries = Math.max(0, configuredMaxAttempts - 1);
        return Math.min(configuredRetries, Math.max(0, job.getMaxRetries()));
    }

    private boolean hasUsablePrimary(RouteJob job) {
        return job.getRouteId() != null;
    }

    private boolean isTerminal(RouteJob.JobStatus status) {
        return status == RouteJob.JobStatus.COMPLETED
            || status == RouteJob.JobStatus.FAILED
            || status == RouteJob.JobStatus.TIMEOUT;
    }

    public static LifecycleSnapshot snapshot(RouteJob job) {
        return new LifecycleSnapshot(
            job.getId(),
            job.getUserId(),
            job.getRouteId(),
            job.getStatus(),
            job.getStateRevision(),
            job.getOptionRevision(),
            job.getOptionCount(),
            job.isOptionsComplete(),
            job.getFailureReason(),
            job.getRetryCount(),
            job.getCompletedAt()
        );
    }

    public enum ClaimAction {
        CLAIMED,
        REPLAY_TERMINAL,
        SKIP,
        FINALIZED,
        FAILED
    }

    public enum RecoveryAction {
        RETRY,
        FINALIZED,
        FAILED,
        NONE
    }

    public record ClaimResult(
        ClaimAction action,
        RouteJob job,
        UUID leaseToken,
        LifecycleSnapshot state
    ) {
    }

    public record RecoveryResult(RecoveryAction action, LifecycleSnapshot state) {
    }

    public record LifecycleSnapshot(
        UUID jobId,
        UUID userId,
        UUID routeId,
        RouteJob.JobStatus status,
        long stateRevision,
        long optionRevision,
        int optionCount,
        boolean optionsComplete,
        String failureReason,
        int retryCount,
        Instant completedAt
    ) {
    }
}
