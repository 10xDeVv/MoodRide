package com.moodride.routeapi.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.routeapi.dispatch.RouteJobDispatch;
import com.moodride.routeapi.repository.RouteJobDispatchRepository;

@Service
public class RouteJobDispatchService {

    static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final Logger logger = LoggerFactory.getLogger(RouteJobDispatchService.class);

    private final RouteJobDispatchRepository dispatchRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final boolean recoveryEnabled;
    private final Duration staleAfter;
    private final Duration leaseDuration;
    private final Duration acknowledgmentTimeout;
    private final long acknowledgmentTimeoutNanos;
    private final int batchSize;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    public RouteJobDispatchService(
            RouteJobDispatchRepository dispatchRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${moodride.route-job.dispatch.recovery-enabled:true}") boolean recoveryEnabled,
            @Value("${moodride.route-job.dispatch.stale-after:1m}") Duration staleAfter,
            @Value("${moodride.route-job.dispatch.lease-duration:30s}") Duration leaseDuration,
            @Value("${moodride.kafka.producer.ack-timeout:10s}") Duration acknowledgmentTimeout,
            @Value("${moodride.route-job.dispatch.batch-size:25}") int batchSize,
            @Value("${moodride.route-job.dispatch.retry-base-delay:30s}") Duration retryBaseDelay,
            @Value("${moodride.route-job.dispatch.retry-max-delay:5m}") Duration retryMaxDelay
    ) {
        requirePositive(staleAfter, "Route job dispatch stale age");
        requirePositive(leaseDuration, "Route job dispatch lease duration");
        requirePositive(acknowledgmentTimeout, "Kafka acknowledgment timeout");
        requirePositive(retryBaseDelay, "Route job dispatch retry base delay");
        requirePositive(retryMaxDelay, "Route job dispatch retry max delay");
        if (leaseDuration.compareTo(acknowledgmentTimeout) <= 0) {
            throw new IllegalArgumentException(
                "Route job dispatch lease duration must exceed the Kafka acknowledgment timeout"
            );
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                "Route job dispatch retry max delay must not be shorter than its base delay"
            );
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Route job dispatch batch size must be between 1 and " + MAX_BATCH_SIZE
            );
        }

        this.dispatchRepository = dispatchRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recoveryEnabled = recoveryEnabled;
        this.staleAfter = staleAfter;
        this.leaseDuration = leaseDuration;
        this.acknowledgmentTimeout = acknowledgmentTimeout;
        this.batchSize = batchSize;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        try {
            this.acknowledgmentTimeoutNanos = acknowledgmentTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Kafka acknowledgment timeout is too large", exception);
        }
    }

    public void enqueue(UUID jobId, Instant createdAt) {
        dispatchRepository.save(
            new RouteJobDispatch(jobId, createdAt, createdAt.plus(staleAfter))
        );
    }

    public void publishCommitted(UUID jobId) {
        try {
            claimByJobId(jobId, Instant.now()).ifPresent(this::publishClaim);
        } catch (RuntimeException exception) {
            logger.error(
                "Route job {} remains pending after immediate dispatch failed; recovery will retry it",
                jobId,
                exception
            );
        }
    }

    @Scheduled(
        fixedDelayString = "${moodride.route-job.dispatch.interval-ms:60000}",
        initialDelayString = "${moodride.route-job.dispatch.initial-delay-ms:60000}"
    )
    public void redispatchDueRouteJobs() {
        if (!recoveryEnabled) {
            return;
        }

        int attempted = 0;
        int published = 0;
        while (attempted < batchSize && !Thread.currentThread().isInterrupted()) {
            Optional<DispatchClaim> claim;
            try {
                claim = claimOldestDue(Instant.now());
            } catch (RuntimeException exception) {
                logger.error("Failed to claim a pending route job dispatch", exception);
                break;
            }
            if (claim.isEmpty()) {
                break;
            }

            attempted++;
            if (publishClaim(claim.get())) {
                published++;
            }
        }

        if (attempted > 0) {
            logger.info(
                "Route job dispatch recovery attempted={} published={} failed={}",
                attempted,
                published,
                attempted - published
            );
        }
    }

    private Optional<DispatchClaim> claimByJobId(UUID jobId, Instant now) {
        Optional<DispatchClaim> claim = transactionTemplate.execute(status ->
            dispatchRepository.lockPublishableByJobId(jobId, now)
                .map(dispatch -> claim(dispatch, now))
        );
        return claim == null ? Optional.empty() : claim;
    }

    private Optional<DispatchClaim> claimOldestDue(Instant now) {
        Optional<DispatchClaim> claim = transactionTemplate.execute(status ->
            dispatchRepository.lockOldestDue(now)
                .map(dispatch -> claim(dispatch, now))
        );
        return claim == null ? Optional.empty() : claim;
    }

    private DispatchClaim claim(RouteJobDispatch dispatch, Instant now) {
        UUID leaseToken = UUID.randomUUID();
        dispatch.claim(leaseToken, now.plus(leaseDuration));
        return new DispatchClaim(dispatch.getJobId(), leaseToken, dispatch.getAttemptCount());
    }

    private boolean publishClaim(DispatchClaim claim) {
        try {
            kafkaTemplate.send(
                RouteJobEvent.TOPIC,
                claim.jobId().toString(),
                claim.jobId().toString()
            ).get(acknowledgmentTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordPublicationFailure(claim, "Interrupted while awaiting Kafka acknowledgment", exception);
            return false;
        } catch (TimeoutException exception) {
            recordPublicationFailure(
                claim,
                "Timed out after " + acknowledgmentTimeout + " awaiting Kafka acknowledgment",
                exception
            );
            return false;
        } catch (CancellationException exception) {
            recordPublicationFailure(claim, "Kafka publication was cancelled", exception);
            return false;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            recordPublicationFailure(claim, "Kafka broker rejected route job publication", cause);
            return false;
        } catch (RuntimeException exception) {
            recordPublicationFailure(claim, "Failed to send route job to Kafka", exception);
            return false;
        }

        try {
            boolean markedSent = completeClaim(claim, Instant.now());
            if (!markedSent) {
                logger.warn(
                    "Kafka acknowledged route job {} after its dispatch lease changed; a duplicate may be retried",
                    claim.jobId()
                );
            }
            return markedSent;
        } catch (RuntimeException exception) {
            logger.error(
                "Kafka acknowledged route job {} but its dispatch could not be marked sent; recovery may duplicate it",
                claim.jobId(),
                exception
            );
            return false;
        }
    }

    private boolean completeClaim(DispatchClaim claim, Instant acknowledgedAt) {
        Boolean completed = transactionTemplate.execute(status ->
            dispatchRepository.lockByJobId(claim.jobId())
                .map(dispatch -> dispatch.markSent(claim.leaseToken(), acknowledgedAt))
                .orElse(false)
        );
        return Boolean.TRUE.equals(completed);
    }

    private void recordPublicationFailure(DispatchClaim claim, String message, Throwable cause) {
        Instant retryAt = Instant.now().plus(retryDelay(claim.attemptNumber()));
        String error = describeFailure(message, cause);
        boolean retryScheduled = false;
        try {
            Boolean scheduled = transactionTemplate.execute(status ->
                dispatchRepository.lockByJobId(claim.jobId())
                    .map(dispatch -> dispatch.scheduleRetry(claim.leaseToken(), retryAt, error))
                    .orElse(false)
            );
            retryScheduled = Boolean.TRUE.equals(scheduled);
            if (!retryScheduled) {
                logger.warn(
                    "Could not schedule route job {} retry because its dispatch lease changed",
                    claim.jobId()
                );
            }
        } catch (RuntimeException persistenceFailure) {
            logger.error(
                "Could not persist the next retry for route job {}; its lease expiry will make it eligible again",
                claim.jobId(),
                persistenceFailure
            );
        }
        if (retryScheduled) {
            logger.error("{} for route job {}; retry scheduled at {}", message, claim.jobId(), retryAt, cause);
        } else {
            logger.error("{} for route job {}; this attempt did not reschedule it", message, claim.jobId(), cause);
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

    private record DispatchClaim(UUID jobId, UUID leaseToken, int attemptNumber) {
    }
}
