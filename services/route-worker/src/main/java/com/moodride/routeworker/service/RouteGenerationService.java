package com.moodride.routeworker.service;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.algorithm.RoutePlanner;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteRepository;
import com.moodride.routeworker.repository.RouteWaypointRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RouteGenerationService {
    
    private final RoutePlanner routePlanner;
    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final RouteWaypointRepository waypointRepository;
    private final RouteCompletionProducer completionProducer;
    private final GeometryFactory geometryFactory;
    
    public RouteGenerationService(RoutePlanner routePlanner,
                                  RouteJobRepository jobRepository,
                                  RouteRepository routeRepository,
                                  RouteWaypointRepository waypointRepository,
                                  RouteCompletionProducer completionProducer) {
        this.routePlanner = routePlanner;
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.waypointRepository = waypointRepository;
        this.completionProducer = completionProducer;
        this.geometryFactory = new GeometryFactory();
    }
    
    public void processRoute(RouteJob job) {
        try {
            RouteCandidate candidate = routePlanner.generateRoute(job);
            
            // Create Route entity
            Route route = new Route(job.getId(), job.getUserId(), 
                buildLineString(candidate.getWaypoints()), job.getVibe());
            route.setTotalDistanceKm(candidate.getTotalDistanceKm());
            route.setEstimatedDurationMinutes(candidate.getEstimatedMinutes());
            route.setScenicScore(candidate.getTotalScenicScore());
            
            routeRepository.save(route);
            
            // Create waypoints
            List<RouteWaypoint> waypoints = new ArrayList<>();
            List<RoadNode> nodes = candidate.getWaypoints();
            for (int i = 0; i < nodes.size() - 1; i++) {
                RouteWaypoint wp = new RouteWaypoint(
                    route, i, nodes.get(i).getLatitude(), nodes.get(i).getLongitude(),
                    "Continue to waypoint " + (i + 1),
                    0.0
                );
                waypointRepository.save(wp);
                waypoints.add(wp);
            }
            
            // Update job status
            job.markCompleted();
            jobRepository.save(job);
            
            // Convert to DTO and publish
            List<RouteCompletionEvent.RouteWaypoint> eventWaypoints = waypoints.stream()
                .map(wp -> new RouteCompletionEvent.RouteWaypoint(
                    wp.getLatitude(),
                    wp.getLongitude(),
                    wp.getInstruction(),
                    wp.getDistanceToNext()
                ))
                .toList();
            
            completionProducer.publishCompletion(
                job.getId(),
                job.getUserId(),
                candidate.getTotalDistanceKm(),
                candidate.getEstimatedMinutes(),
                candidate.getTotalScenicScore(),
                eventWaypoints
            );
            
        } catch (Exception e) {
            job.markFailed(e.getMessage());
            jobRepository.save(job);
            completionProducer.publishFailure(job.getId(), job.getUserId(), e.getMessage());
        }
    }
    
    private LineString buildLineString(List<RoadNode> nodes) {
        Coordinate[] coords = nodes.stream()
            .map(n -> new Coordinate(n.getLongitude(), n.getLatitude()))
            .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coords);
    }
}
