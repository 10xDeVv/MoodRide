package com.moodride.routeworker.producer;

import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteJobTerminalEvent;
import com.moodride.routeworker.config.RouteWorkerSchedulingConfiguration;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import com.moodride.routeworker.service.RouteGenerationService;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leased transactional-outbox publisher for committed terminal route-job events.
 */
@Service
public class RouteJobTerminalEventPublisher {
    static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final Logger logger = LoggerFactory.getLogger(RouteJobTerminalEventPublisher.class);

    private final RouteJobTerminalEventRepository eventRepository;
    private final RouteJobRepository jobRepository;
    private final RouteGenerationService routeGenerationService;
    private final RouteCompletionProducer completionProducer;
    private final RouteJobDlqProducer dlqProducer;
    private final TransactionTemplate transactionTemplate;
    private final boolean recoveryEnabled;
    private final Duration leaseDuration;
    private final int batchSize;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    public RouteJobTerminalEventPublisher(
        RouteJobTerminalEventRepository eventRepository,
        RouteJobRepository jobRepository,
        RouteGenerationService routeGenerationService,
        RouteCompletionProducer completionProducer,
        RouteJobDlqProducer dlqProducer,
        PlatformTransactionManager transactionManager,
        @Value("${moodride.route-terminal-events.recovery-enabled:true}") boolean recoveryEnabled,
        @Value("${moodride.route-terminal-events.lease-duration:30s}") Duration leaseDuration,
        @Value("${moodride.kafka.producer.ack-timeout:10s}") Duration acknowledgmentTimeout,
        @Value("${moodride.route-terminal-events.batch-size:25}") int batchSize,
        @Value("${moodride.route-terminal-events.retry-base-delay:10s}") Duration retryBaseDelay,
        @Value("${moodride.route-terminal-events.retry-max-delay:5m}") Duration retryMaxDelay
    ) {
        requirePositive(leaseDuration, "Terminal event lease duration");
        requirePositive(acknowledgmentTimeout, "Kafka acknowledgment timeout");
        requirePositive(retryBaseDelay, "Terminal event retry base delay");
        requirePositive(retryMaxDelay, "Terminal event retry max delay");
        if (leaseDuration.compareTo(acknowledgmentTimeout) <= 0) {
            throw new IllegalArgumentException(
                "Terminal event lease duration must exceed the Kafka acknowledgment timeout"
            );
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                "Terminal event retry max delay must not be shorter than its base delay"
            );
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Terminal event batch size must be between 1 and " + MAX_BATCH_SIZE
            );
        }

        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
        this.routeGenerationService = routeGenerationService;
        this.completionProducer = completionProducer;
        this.dlqProducer = dlqProducer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recoveryEnabled = recoveryEnabled;
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
    }

    /**
     * Best-effort immediate dispatch after a terminal transaction commits. Any failed row remains
     * durable and scheduled; callers may safely finish their own input acknowledgment.
     */
    public void publishPending(UUID jobId, long stateRevision) {
        List<TerminalEventClaim> claims;
        try {
            claims = claimTerminal(jobId, stateRevision, Instant.now());
        } catch (RuntimeException exception) {
            logger.error(
                "Failed to claim terminal events for route job {} revision {}; scheduled recovery remains authoritative",
                jobId,
                stateRevision,
                exception
            );
            return;
        }
        for (TerminalEventClaim claim : claims) {
            publishClaim(claim);
        }
    }

    @Scheduled(
        fixedDelayString = "${moodride.route-terminal-events.interval-ms:10000}",
        initialDelayString = "${moodride.route-terminal-events.initial-delay-ms:10000}",
        scheduler = RouteWorkerSchedulingConfiguration.TERMINAL_EVENT_TASK_SCHEDULER
    )
    public void redispatchPending() {
        if (!recoveryEnabled) {
            return;
        }

        int attempted = 0;
        int delivered = 0;
        while (attempted < batchSize && !Thread.currentThread().isInterrupted()) {
            Optional<TerminalEventClaim> claim;
            try {
                claim = claimOldestDue(Instant.now());
            } catch (RuntimeException exception) {
                logger.error("Failed to claim a pending route terminal event", exception);
                break;
            }
            if (claim.isEmpty()) {
                break;
            }
            attempted++;
            if (publishClaim(claim.get())) {
                delivered++;
            }
        }

        if (attempted > 0) {
            logger.info(
                "Route terminal event recovery attempted={} delivered={} failed={}",
                attempted,
                delivered,
                attempted - delivered
            );
        }
    }

    private List<TerminalEventClaim> claimTerminal(UUID jobId, long stateRevision, Instant now) {
        List<TerminalEventClaim> claims = transactionTemplate.execute(status -> {
            List<RouteJobTerminalEvent> events =
                eventRepository.lockPublishableForTerminal(jobId, stateRevision, now);
            List<TerminalEventClaim> claimed = new ArrayList<>(events.size());
            for (RouteJobTerminalEvent event : events) {
                claimed.add(claim(event, now));
            }
            return claimed;
        });
        return claims == null ? List.of() : claims;
    }

    private Optional<TerminalEventClaim> claimOldestDue(Instant now) {
        Optional<TerminalEventClaim> claim = transactionTemplate.execute(status ->
            eventRepository.lockOldestDue(now).map(event -> claim(event, now))
        );
        return claim == null ? Optional.empty() : claim;
    }

    private TerminalEventClaim claim(RouteJobTerminalEvent event, Instant now) {
        UUID leaseToken = UUID.randomUUID();
        event.claim(leaseToken, now.plus(leaseDuration));
        return new TerminalEventClaim(
            event.getEventId(),
            event.getJobId(),
            event.getStateRevision(),
            event.getEventType(),
            event.getTerminalStatus(),
            event.getOriginalPayload(),
            event.getCreatedAt(),
            leaseToken,
            event.getAttemptCount()
        );
    }

    private boolean publishClaim(TerminalEventClaim claim) {
        try {
            RouteJob job = jobRepository.findById(claim.jobId())
                .orElseThrow(() -> new IllegalStateException(
                    "Route job missing for terminal event " + claim.eventId()
                ));
            if (job.getStateRevision() != claim.stateRevision()
                || job.getStatus() != claim.terminalStatus()) {
                throw new IllegalStateException(
                    "Terminal event " + claim.eventId() + " no longer matches route job state"
                );
            }

            RouteJobLifecycleService.LifecycleSnapshot state =
                RouteJobLifecycleService.snapshot(job);
            if (claim.eventType() == RouteJobTerminalEvent.EventType.COMPLETION) {
                publishCompletion(claim.eventId(), state);
            } else {
                dlqProducer.publishToDlq(
                    claim.eventId(),
                    claim.jobId(),
                    state.failureReason(),
                    claim.originalPayload(),
                    claim.createdAt()
                );
            }
        } catch (RuntimeException exception) {
            recordPublicationFailure(claim, "Failed to publish terminal route event", exception);
            return false;
        }

        try {
            boolean delivered = completeClaim(claim, Instant.now());
            if (!delivered) {
                logger.warn(
                    "Broker acknowledged terminal event {} after its delivery lease changed; a duplicate may be retried",
                    claim.eventId()
                );
            }
            return delivered;
        } catch (RuntimeException exception) {
            logger.error(
                "Broker acknowledged terminal event {} but it could not be marked delivered; recovery may duplicate it",
                claim.eventId(),
                exception
            );
            return false;
        }
    }

    private void publishCompletion(
        String eventId,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
        if (state.status() == RouteJob.JobStatus.COMPLETED) {
            RouteGenerationService.RouteGenerationResult primary =
                routeGenerationService.loadPrimary(state.jobId());
            completionProducer.publishCompletion(
                state.jobId(),
                state.userId(),
                primary.route().getTotalDistanceKm(),
                primary.route().getId(),
                primary.route().getEstimatedDurationMinutes(),
                primary.route().getScenicScore(),
                primary.waypoints(),
                state,
                eventId
            );
        } else {
            completionProducer.publishFailure(state, eventId);
        }
    }

    private boolean completeClaim(TerminalEventClaim claim, Instant acknowledgedAt) {
        Boolean delivered = transactionTemplate.execute(status ->
            eventRepository.lockByEventId(claim.eventId())
                .map(event -> event.markDelivered(claim.leaseToken(), acknowledgedAt))
                .orElse(false)
        );
        return Boolean.TRUE.equals(delivered);
    }

    private void recordPublicationFailure(
        TerminalEventClaim claim,
        String message,
        Throwable cause
    ) {
        Instant retryAt = Instant.now().plus(retryDelay(claim.attemptNumber()));
        String error = describeFailure(message, cause);
        boolean retryScheduled = false;
        try {
            Boolean scheduled = transactionTemplate.execute(status ->
                eventRepository.lockByEventId(claim.eventId())
                    .map(event -> event.scheduleRetry(claim.leaseToken(), retryAt, error))
                    .orElse(false)
            );
            retryScheduled = Boolean.TRUE.equals(scheduled);
        } catch (RuntimeException persistenceFailure) {
            logger.error(
                "Could not persist the retry for terminal event {}; lease expiry will make it eligible again",
                claim.eventId(),
                persistenceFailure
            );
        }

        if (retryScheduled) {
            logger.error("{} {}; retry scheduled at {}", message, claim.eventId(), retryAt, cause);
        } else {
            logger.error("{} {}; this attempt did not reschedule it", message, claim.eventId(), cause);
        }
    }

    private Duration retryDelay(int attemptNumber) {
        int exponent = Math.min(Math.max(0, attemptNumber - 1), 30);
        long multiplier = 1L << exponent;
        Duration delay;
        try {
            delay = retryBaseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return retryMaxDelay;
        }
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }

    private static String describeFailure(String message, Throwable cause) {
        String causeMessage = cause.getMessage();
        String description = causeMessage == null || causeMessage.isBlank()
            ? message + ": " + cause.getClass().getSimpleName()
            : message + ": " + cause.getClass().getSimpleName() + ": " + causeMessage;
        return description.length() <= MAX_ERROR_LENGTH
            ? description
            : description.substring(0, MAX_ERROR_LENGTH);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record TerminalEventClaim(
        String eventId,
        UUID jobId,
        long stateRevision,
        RouteJobTerminalEvent.EventType eventType,
        RouteJob.JobStatus terminalStatus,
        String originalPayload,
        Instant createdAt,
        UUID leaseToken,
        int attemptNumber
    ) {
    }
}
