package com.moodride.routeworker.consumer;

import com.moodride.datamodels.RouteJob;
import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.service.RouteGenerationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional
public class RouteJobConsumer {
    
    private final RouteJobRepository jobRepository;
    private final RouteGenerationService routeGenerationService;
    
    public RouteJobConsumer(RouteJobRepository jobRepository,
                           RouteGenerationService routeGenerationService) {
        this.jobRepository = jobRepository;
        this.routeGenerationService = routeGenerationService;
    }
    
    @KafkaListener(topics = "route-jobs", groupId = "route-workers")
    public void consumeRouteJob(String message) {
        try {
            String[] parts = message.split(":");
            if (parts.length < 2) return;
            
            UUID jobId = UUID.fromString(parts[0]);
            RouteJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
            
            job.markStarted();
            jobRepository.save(job);
            
            // Route generation will be handled here (Phase 3)
            processRoute(job);
            
        } catch (Exception e) {
            System.err.println("Error processing route job: " + e.getMessage());
        }
    }
    
    private void processRoute(RouteJob job) {
        routeGenerationService.processRoute(job);
    }
}
