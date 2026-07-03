package com.moodride.routeworker.consumer;

import com.moodride.datamodels.RouteJob;
import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.routeworker.algorithm.NoFeasibleRouteException;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.service.RouteGenerationService;
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
    private final RouteJobRepository jobRepository;
    private final RouteGenerationService routeGenerationService;
    private final RouteCompletionProducer completionProducer;
    private final RouteJobDlqProducer dlqProducer;
    private final int configuredMaxRetries;

    public RouteJobConsumer(RouteJobRepository jobRepository,
                           RouteGenerationService routeGenerationService,
                           RouteCompletionProducer completionProducer,
                           RouteJobDlqProducer dlqProducer,
                           @Value("${moodride.route.retry.max-attempts:3}") int configuredMaxRetries) {
        this.jobRepository = jobRepository;
        this.routeGenerationService = routeGenerationService;
        this.completionProducer = completionProducer;
        this.dlqProducer = dlqProducer;
        this.configuredMaxRetries = Math.max(1, configuredMaxRetries);
    }
    
    @KafkaListener(topics = RouteJobEvent.TOPIC)
    public void consumeRouteJob(String message, Acknowledgment acknowledgment) {
        final UUID jobId;
        try {
            jobId = UUID.fromString(message);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid job ID format: {}", message);
            dlqProducer.publishToDlq(null, "Invalid UUID payload", message);
            acknowledgment.acknowledge();
            return;
        }
        
        try {
            RouteJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

            if (job.getStatus() != RouteJob.JobStatus.QUEUED) {
                logger.info("Skipping job {} because status is {}", jobId, job.getStatus());
                acknowledgment.acknowledge();
                return;
            }

            // Check if job has been retried too many times
            int retryCount = job.getRetryCount();
            int retryLimit = Math.min(configuredMaxRetries, job.getMaxRetries());
            if (retryCount >= retryLimit) {
                logger.warn("Job {} exceeded max retries ({}), marking as failed", jobId, retryLimit);
                job.markFailed("Exceeded maximum retry attempts: " + retryLimit);
                jobRepository.save(job);
                completionProducer.publishFailure(job.getId(), job.getUserId(), job.getFailureReason());
                dlqProducer.publishToDlq(job.getId(), job.getFailureReason(), message);
                acknowledgment.acknowledge();
                return;
            }
            
            // Mark as started
            job.markStarted();
            long queueMs = millisBetween(job.getSubmittedAt(), job.getStartedAt());
            if (retryCount > 0) {
                logger.info("Retrying job {} (attempt {})", jobId, retryCount + 1);
            }
            logger.info("Route job {} started queueMs={} retryCount={}", jobId, queueMs, retryCount);
            jobRepository.save(job);
            
            // Process and persist route before publishing completion
            long processingStartedNanos = System.nanoTime();
            RouteGenerationService.RouteGenerationResult result = routeGenerationService.processRoute(job);
            long processingMs = Duration.ofNanos(System.nanoTime() - processingStartedNanos).toMillis();
            job.markCompleted(result.route().getId());
            jobRepository.save(job);
            logger.info(
                "Route job {} completed processingMs={} routeId={} optionsPrimaryDistanceKm={} optionsPrimaryDurationMin={}",
                jobId,
                processingMs,
                result.route().getId(),
                result.route().getTotalDistanceKm(),
                result.route().getEstimatedDurationMinutes()
            );

            completionProducer.publishCompletion(
                job.getId(),
                job.getUserId(),
                result.route().getTotalDistanceKm(),
                result.route().getId(),
                result.route().getEstimatedDurationMinutes(),
                result.route().getScenicScore(),
                result.waypoints()
            );

            // Acknowledge message on success
            acknowledgment.acknowledge();
            
        } catch (NoFeasibleRouteException e) {
            logger.warn("No feasible route for job {}: {}", jobId, e.getMessage());
            handleNonRetryableFailure(jobId, e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            logger.error("Error processing route job {}: {}", jobId, e.getMessage(), e);
            
            // Increment retry count for transient failures
            handleFailureAndRetry(jobId);
            
            // Don't acknowledge - message will be reprocessed
            // Note: In production, add dead letter queue logic here
        }
    }
    
    private long millisBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(from, to).toMillis());
    }
    private void handleFailureAndRetry(UUID jobId) {
        try {
            jobRepository.findById(jobId).ifPresent(job -> {
                job.incrementRetryCount();
                if (job.canRetry()) {
                    job.requeueForRetry();
                } else {
                    job.markFailed("Route generation failed after retries");
                    completionProducer.publishFailure(job.getId(), job.getUserId(), job.getFailureReason());
                    dlqProducer.publishToDlq(job.getId(), job.getFailureReason(), jobId.toString());
                }
                jobRepository.save(job);
                logger.info("Job {} retry count incremented to {}", jobId, job.getRetryCount());
            });
        } catch (Exception ex) {
            logger.error("Failed to increment retry count for job {}: {}", jobId, ex.getMessage());
        }
    }

    private void handleNonRetryableFailure(UUID jobId, String reason) {
        try {
            jobRepository.findById(jobId).ifPresent(job -> {
                job.markFailed(reason);
                jobRepository.save(job);
                completionProducer.publishFailure(job.getId(), job.getUserId(), job.getFailureReason());
            });
        } catch (Exception ex) {
            logger.error("Failed to mark non-retryable failure for job {}: {}", jobId, ex.getMessage());
        }
    }
}
