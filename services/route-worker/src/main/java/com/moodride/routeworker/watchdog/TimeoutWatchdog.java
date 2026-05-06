package com.moodride.routeworker.watchdog;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.repository.RouteJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Monitors long-running route generation jobs and marks them as timed out
 * if they exceed the configured timeout threshold.
 */
@Service
@Transactional
public class TimeoutWatchdog {
    
    private static final Logger logger = LoggerFactory.getLogger(TimeoutWatchdog.class);
    
    private final RouteJobRepository jobRepository;
    private final RouteCompletionProducer completionProducer;
    private final RouteJobDlqProducer dlqProducer;
    private final Duration timeoutThreshold;
    
    public TimeoutWatchdog(RouteJobRepository jobRepository,
                           RouteCompletionProducer completionProducer,
                           RouteJobDlqProducer dlqProducer,
                          @Value("${route.generation.timeout.seconds:30}") int timeoutSeconds) {
        this.jobRepository = jobRepository;
        this.completionProducer = completionProducer;
        this.dlqProducer = dlqProducer;
        this.timeoutThreshold = Duration.ofSeconds(timeoutSeconds);
    }
    
    /**
     * Runs every 10 seconds to check for timed-out jobs.
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void checkForTimedOutJobs() {
        Instant cutoffTime = Instant.now().minus(timeoutThreshold);
        
        List<RouteJob> processingJobs = jobRepository.findByStatusAndStartedAtBefore(
            RouteJob.JobStatus.PROCESSING, cutoffTime);
        
        for (RouteJob job : processingJobs) {
            Duration elapsed = Duration.between(job.getStartedAt(), Instant.now());
            logger.warn("Job {} exceeded timeout threshold. Elapsed: {} seconds, Threshold: {} seconds",
                job.getId(), elapsed.getSeconds(), timeoutThreshold.getSeconds());

            job.markTimeout("Route generation exceeded " + timeoutThreshold.getSeconds() + " seconds");
            if (job.canRetry()) {
                job.incrementRetryCount();
                job.requeueForRetry();
            } else {
                job.markFailed("Route generation timed out after retries");
                completionProducer.publishFailure(job.getId(), job.getUserId(), job.getFailureReason());
                String originalPayload = job.getId() == null ? "unknown" : job.getId().toString();
                dlqProducer.publishToDlq(job.getId(), job.getFailureReason(), originalPayload);
            }
            jobRepository.save(job);

            logger.info("Job {} timeout handled; status={} retryCount={}", job.getId(), job.getStatus(), job.getRetryCount());
        }
    }
}
