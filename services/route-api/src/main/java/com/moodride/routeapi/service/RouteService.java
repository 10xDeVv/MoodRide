package com.moodride.routeapi.service;

import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.Route;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteResponse;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.eventmodels.RouteJobEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.moodride.routeapi.dto.WaypointResponse;

@Service
@Transactional
public class RouteService {
    
    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    public RouteService(RouteJobRepository jobRepository, RouteRepository routeRepository,
                       KafkaTemplate<String, String> kafkaTemplate) {
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.kafkaTemplate = kafkaTemplate;
    }
    
    public RouteResponse generateRoute(RouteRequest request) {
        UUID jobId = UUID.randomUUID();
        
        // Create and save job
        RouteJob job = new RouteJob(
            request.userId(),
            request.startLatitude(),
            request.startLongitude(),
            request.timeBudgetMinutes(),
            request.vibe()
        );
        jobRepository.save(job);
        
        // Publish event to Kafka
        RouteJobEvent event = new RouteJobEvent(
            job.getId(),
            request.userId(),
            request.startLatitude(),
            request.startLongitude(),
            request.timeBudgetMinutes(),
            request.vibe(),
            Instant.now()
        );
        kafkaTemplate.send(RouteJobEvent.TOPIC, job.getId().toString(), event.toString());
        
        return new RouteResponse(
            null, job.getId(), 0, 0, 0,
            List.of(), "SUBMITTED"
        );
    }
    
    public RouteResponse getRouteJob(UUID jobId) {
        RouteJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found"));
        
        Route route = routeRepository.findByJobId(jobId).orElse(null);
        
        if (route == null) {
            return new RouteResponse(null, jobId, 0, 0, 0, List.of(), job.getStatus().toString());
        }
        
        return mapRouteToResponse(route);
    }
    
    public RouteResponse getRoute(UUID routeId) {
        Route route = routeRepository.findById(routeId)
            .orElseThrow(() -> new RuntimeException("Route not found"));
        return mapRouteToResponse(route);
    }
    
    private RouteResponse mapRouteToResponse(Route route) {
        List<WaypointResponse> waypoints = route.getWaypoints().stream()
            .map(wp -> new WaypointResponse(
                wp.getLatitude(),
                wp.getLongitude(),
                wp.getInstruction(),
                wp.getDistanceToNext()
            ))
            .toList();
        
        return new RouteResponse(
            route.getId(),
            route.getJobId(),
            route.getTotalDistanceKm(),
            route.getEstimatedDurationMinutes(),
            route.getScenicScore(),
            waypoints,
            "COMPLETED"
        );
    }
}
