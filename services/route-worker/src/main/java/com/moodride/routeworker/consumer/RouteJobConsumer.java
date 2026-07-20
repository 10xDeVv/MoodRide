package com.moodride.routeworker.consumer;

import com.moodride.datamodels.RouteJob;
import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.routeworker.algorithm.NoFeasibleRouteException;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobTerminalEventPublisher;
import com.moodride.routeworker.service.LeaseLostException;
import com.moodride.routeworker.service.RouteGenerationService;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import com.moodride.routeworker.service.RouteJobHeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class RouteJobConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RouteJobConsumer.class);

    private final RouteJobLifecycleService lifecycleService;
    private final RouteJobHeartbeatService heartbeatService;
    private final RouteGenerationService routeGenerationService;
    private final RouteCompletionProducer completionProducer;
    private final RouteJobTerminalEventPublisher terminalEventPublisher;
    private final RouteJobDlqProducer dlqProducer;
    private final int configuredMaxRetries;

    public RouteJobConsumer(
        RouteJobLifecycleService lifecycleService,
        RouteJobHeartbeatService heartbeatService,
        RouteGenerationService routeGenerationService,
        RouteCompletionProducer completionProducer,
        RouteJobTerminalEventPublisher terminalEventPublisher,
        RouteJobDlqProducer dlqProducer,
        @Value("${moodride.route.retry.max-attempts:3}") int configuredMaxRetries
    ) {
        this.lifecycleService = lifecycleService;
        this.heartbeatService = heartbeatService;
        this.routeGenerationService = routeGenerationService;
        this.completionProducer = completionProducer;
        this.terminalEventPublisher = terminalEventPublisher;
        this.dlqProducer = dlqProducer;
        this.configuredMaxRetries = Math.max(1, configuredMaxRetries);
    }

    @KafkaListener(topics = RouteJobEvent.TOPIC)
    public void consumeRouteJob(String message, Acknowledgment acknowledgment) {
        final UUID jobId;
        try {
            jobId = UUID.fromString(message);
        } catch (IllegalArgumentException exception) {
            logger.error("Invalid job ID format: {}", message);
            dlqProducer.publishToDlq(null, "Invalid UUID payload", message);
            acknowledgment.acknowledge();
            return;
        }

        RouteJobLifecycleService.ClaimResult claim;
        try {
            claim = lifecycleService.claim(jobId, configuredMaxRetries);
        } catch (Exception exception) {
            logger.error("Failed to claim route job {}: {}", jobId, exception.getMessage(), exception);
            throw new IllegalStateException("Failed to claim route job " + jobId, exception);
        }

        switch (claim.action()) {
            case SKIP -> {
                logger.info("Skipping job {} because status is {}", jobId, claim.state().status());
                acknowledgment.acknowledge();
                return;
            }
            case REPLAY_TERMINAL -> {
                publishTerminal(claim.state());
                acknowledgment.acknowledge();
                return;
            }
            case FAILED -> {
                publishTerminal(claim.state());
                acknowledgment.acknowledge();
                return;
            }
            case FINALIZED -> {
                publishTerminal(claim.state());
                acknowledgment.acknowledge();
                return;
            }
            case CLAIMED -> {
                // Continue below with the fenced worker ownership token.
            }
        }

        RouteJob job = claim.job();
        UUID leaseToken = claim.leaseToken();
        RouteJobHeartbeatService.Heartbeat heartbeat = heartbeatService.start(jobId, leaseToken);
        long queueMs = millisBetween(job.getSubmittedAt(), job.getStartedAt());
        logger.info(
            "Route job {} claimed queueMs={} retryCount={} leaseToken={}",
            jobId,
            queueMs,
            job.getRetryCount(),
            leaseToken
        );

        try {
            long processingStartedNanos = System.nanoTime();
            RouteGenerationService.RouteGenerationResult primary = routeGenerationService.processRoute(
                job,
                leaseToken,
                committedPrimary -> {
                    logger.info(
                        "Route job {} primary ready routeId={} optionRevision={} optionCount={}",
                        jobId,
                        committedPrimary.route().getId(),
                        committedPrimary.state().optionRevision(),
                        committedPrimary.state().optionCount()
                    );
                    publishPrimaryReady(committedPrimary, heartbeat, jobId, leaseToken);
                }
            );

            heartbeat.requireActive();
            RouteJobLifecycleService.LifecycleSnapshot completed = lifecycleService.complete(jobId, leaseToken);
            long processingMs = Duration.ofNanos(System.nanoTime() - processingStartedNanos).toMillis();
            logger.info(
                "Route job {} completed processingMs={} routeId={} stateRevision={} optionRevision={} optionCount={}",
                jobId,
                processingMs,
                completed.routeId(),
                completed.stateRevision(),
                completed.optionRevision(),
                completed.optionCount()
            );
            publishTerminal(completed);
            acknowledgment.acknowledge();
        } catch (RouteCompletionProducer.PublicationException exception) {
            logger.error(
                "Route event publication for job {} was not broker-acknowledged; leaving input unacknowledged",
                jobId,
                exception
            );
            throw exception;
        } catch (LeaseLostException exception) {
            logger.info("Route job {} lost its worker lease; delegating redelivery to Kafka", jobId);
            throw exception;
        } catch (NoFeasibleRouteException exception) {
            heartbeat.requireActive();
            logger.warn("No feasible route for job {}: {}", jobId, exception.getMessage());
            handleNonRetryableFailure(
                jobId,
                leaseToken,
                exception.getMessage(),
                acknowledgment
            );
        } catch (Exception exception) {
            heartbeat.requireActive();
            logger.error("Error processing route job {}: {}", jobId, exception.getMessage(), exception);
            handleTransientFailure(jobId, leaseToken, acknowledgment);
        } finally {
            heartbeat.close();
        }
    }

    private void publishPrimaryReady(
        RouteGenerationService.RouteGenerationResult primary,
        RouteJobHeartbeatService.Heartbeat heartbeat,
        UUID jobId,
        UUID leaseToken
    ) {
        try {
            completionProducer.publishPrimaryReady(
                primary.state().jobId(),
                primary.state().userId(),
                primary.route().getTotalDistanceKm(),
                primary.route().getId(),
                primary.route().getEstimatedDurationMinutes(),
                primary.route().getScenicScore(),
                primary.waypoints(),
                primary.state()
            );
        } catch (RouteCompletionProducer.PublicationException publicationFailure) {
            heartbeat.close();
            try {
                lifecycleService.abandonPrimaryPublication(jobId, leaseToken);
            } catch (LeaseLostException staleOwner) {
                logger.info(
                    "Route job {} primary publisher no longer owns lease {}; no ownership was released",
                    jobId,
                    leaseToken
                );
            } catch (RuntimeException cleanupFailure) {
                publicationFailure.addSuppressed(cleanupFailure);
                logger.error(
                    "Route job {} primary publication failed and its ownership could not be released",
                    jobId,
                    cleanupFailure
                );
            }
            throw publicationFailure;
        }
    }

    private void handleTransientFailure(
        UUID jobId,
        UUID leaseToken,
        Acknowledgment acknowledgment
    ) {
        try {
            RouteJobLifecycleService.RecoveryResult recovery = lifecycleService.handleTransientFailure(
                jobId,
                leaseToken,
                configuredMaxRetries,
                "Route generation failed after retries"
            );
            if (recovery.action() == RouteJobLifecycleService.RecoveryAction.RETRY) {
                acknowledgment.acknowledge();
            } else if (recovery.action() == RouteJobLifecycleService.RecoveryAction.FINALIZED
                || recovery.action() == RouteJobLifecycleService.RecoveryAction.FAILED) {
                publishTerminal(recovery.state());
                acknowledgment.acknowledge();
            }
        } catch (LeaseLostException exception) {
            logger.info("Route job {} failure handler observed lease loss", jobId);
            throw exception;
        } catch (Exception exception) {
            logger.error("Failed to persist retry transition for job {}: {}", jobId, exception.getMessage(), exception);
            throw new IllegalStateException("Failed to persist route job retry " + jobId, exception);
        }
    }

    private void handleNonRetryableFailure(
        UUID jobId,
        UUID leaseToken,
        String reason,
        Acknowledgment acknowledgment
    ) {
        try {
            RouteJobLifecycleService.RecoveryResult recovery =
                lifecycleService.handleNonRetryableFailure(
                    jobId,
                    leaseToken,
                    reason
                );
            if (recovery.action() == RouteJobLifecycleService.RecoveryAction.FINALIZED
                || recovery.action() == RouteJobLifecycleService.RecoveryAction.FAILED) {
                publishTerminal(recovery.state());
            }
            acknowledgment.acknowledge();
        } catch (LeaseLostException exception) {
            logger.info("Route job {} non-retryable failure observed lease loss", jobId);
            throw exception;
        } catch (Exception exception) {
            logger.error("Failed to finalize non-retryable job {}: {}", jobId, exception.getMessage(), exception);
            throw new IllegalStateException("Failed to finalize route job " + jobId, exception);
        }
    }

    private void publishTerminal(RouteJobLifecycleService.LifecycleSnapshot state) {
        terminalEventPublisher.publishPending(state.jobId(), state.stateRevision());
    }

    private long millisBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(from, to).toMillis());
    }
}
