package com.moodride.routeworker.watchdog;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.config.RouteWorkerSchedulingConfiguration;
import com.moodride.routeworker.producer.RouteJobTerminalEventPublisher;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovers expired worker leases without discarding a committed primary route.
 */
@Service
public class TimeoutWatchdog {
    private static final Logger logger = LoggerFactory.getLogger(TimeoutWatchdog.class);
    private static final List<RouteJob.JobStatus> ACTIVE_STATUSES = List.of(
        RouteJob.JobStatus.PROCESSING,
        RouteJob.JobStatus.PRIMARY_READY
    );

    private final RouteJobRepository jobRepository;
    private final RouteJobLifecycleService lifecycleService;
    private final RouteJobTerminalEventPublisher terminalEventPublisher;
    private final Duration timeoutThreshold;
    private final int configuredMaxRetries;

    public TimeoutWatchdog(
        RouteJobRepository jobRepository,
        RouteJobLifecycleService lifecycleService,
        RouteJobTerminalEventPublisher terminalEventPublisher,
        @Value("${route.generation.timeout.seconds:30}") int timeoutSeconds,
        @Value("${moodride.route.retry.max-attempts:3}") int configuredMaxRetries
    ) {
        this.jobRepository = jobRepository;
        this.lifecycleService = lifecycleService;
        this.terminalEventPublisher = terminalEventPublisher;
        this.timeoutThreshold = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.configuredMaxRetries = Math.max(1, configuredMaxRetries);
    }

    @Scheduled(
        fixedDelay = 10000,
        initialDelay = 10000,
        scheduler = RouteWorkerSchedulingConfiguration.WATCHDOG_TASK_SCHEDULER
    )
    public void checkForTimedOutJobs() {
        Instant now = Instant.now();
        List<RouteJob> expiredJobs = jobRepository.findExpiredActiveJobs(
            ACTIVE_STATUSES,
            now,
            now.minus(timeoutThreshold)
        );

        for (RouteJob observed : expiredJobs) {
            handleExpiredJob(observed, now);
        }
    }

    private void handleExpiredJob(RouteJob observed, Instant now) {
        String timeoutReason = "Route generation timed out after retries";
        logger.warn(
            "Route job {} lease expired with status={} retryCount={}",
            observed.getId(),
            observed.getStatus(),
            observed.getRetryCount()
        );

        RouteJobLifecycleService.RecoveryResult recovery = lifecycleService.recoverExpired(
            observed.getId(),
            observed.getLeaseToken(),
            configuredMaxRetries,
            timeoutReason,
            now
        );

        if (recovery.action() == RouteJobLifecycleService.RecoveryAction.FINALIZED
            || recovery.action() == RouteJobLifecycleService.RecoveryAction.FAILED) {
            terminalEventPublisher.publishPending(
                recovery.state().jobId(),
                recovery.state().stateRevision()
            );
        }

        logger.info(
            "Route job {} timeout handled action={} status={} retryCount={}",
            observed.getId(),
            recovery.action(),
            recovery.state().status(),
            recovery.state().retryCount()
        );
    }

}
