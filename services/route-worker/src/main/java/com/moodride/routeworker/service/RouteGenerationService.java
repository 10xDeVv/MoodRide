package com.moodride.routeworker.service;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteDurationCalibration;
import com.moodride.geo.H3Utils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.algorithm.RoutePlanner;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.repository.RouteRepository;
import com.moodride.routeworker.repository.RouteWaypointRepository;

@Service
@Transactional
public class RouteGenerationService {

    private static final List<String> ROUTE_OPTION_PROFILES = List.of(
        "most_scenic",
        "balanced",
        "shorter"
    );
    private static final int DURATION_CALIBRATION_H3_RESOLUTION = 5;
    private static final String[] GEOMETRY_STRATEGY_NAMES = {
        "WATER_FOLLOWING",
        "OPEN_SPACE_ESCAPE",
        "PHOTO_PEAKS",
        "QUIET_LOW_PRESSURE",
        "CURVY_ELEVATION",
        "BALANCED_VARIETY"
    };
    
    private final RoutePlanner routePlanner;
    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final RouteWaypointRepository waypointRepository;
    private final RouteDurationCalibrationRepository routeDurationCalibrationRepository;
    private final GeometryFactory geometryFactory;
    private final ObjectMapper objectMapper;
    
    public RouteGenerationService(RoutePlanner routePlanner,
                                  RouteJobRepository jobRepository,
                                  RouteRepository routeRepository,
                                  RouteWaypointRepository waypointRepository,
                                  RouteDurationCalibrationRepository routeDurationCalibrationRepository,
                                  ObjectMapper objectMapper) {
        this.routePlanner = routePlanner;
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.waypointRepository = waypointRepository;
        this.routeDurationCalibrationRepository = routeDurationCalibrationRepository;
        this.geometryFactory = new GeometryFactory();
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }
    
    public RouteGenerationResult processRoute(RouteJob job) {
        List<RouteCandidate> candidates = routePlanner.generateRouteOptions(job);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No route candidates generated for job " + job.getId());
        }

        RouteCandidate primaryCandidate = candidates.getFirst();
        job.setAlgorithmVersion(primaryCandidate.getAlgorithmVersion());
        job.setBeamCandidates(primaryCandidate.getBeamCandidates());
        jobRepository.save(job);

        Instant generatedAtBase = Instant.now();
        Route primaryRoute = null;
        List<RouteWaypoint> primaryWaypoints = List.of();
        for (int i = 0; i < candidates.size(); i++) {
            RouteCandidate candidate = candidates.get(i);
            Route route = new Route(job.getId(), job.getUserId(),
                buildLineString(candidate.getWaypoints()), job.getVibe());
            route.setRouteMode(job.getRouteMode());
            if (i < ROUTE_OPTION_PROFILES.size()) {
                route.setRouteProfile(ROUTE_OPTION_PROFILES.get(i));
            }
            route.setTotalDistanceKm(candidate.getTotalDistanceKm());
            route.setEstimatedDurationMinutes(candidate.getEstimatedMinutes());
            route.setScenicScore(candidate.getTotalScenicScore());
            route.setScoreBreakdownJson(serializeScoreBreakdown(candidate.getScoreBreakdown()));
            route.setGeneratedAt(generatedAtBase.plusMillis(i));
            route.setExpiresAt(route.getGeneratedAt().plusSeconds(24 * 60 * 60));

            routeRepository.save(route);
            recordDurationCalibration(job, candidate);

            List<RouteWaypoint> waypoints = persistWaypoints(route, candidate.getWaypoints());
            if (i == 0) {
                primaryRoute = route;
                primaryWaypoints = waypoints;
            }
        }

        List<RouteCompletionEvent.RouteWaypoint> eventWaypoints = primaryWaypoints.stream()
            .map(wp -> new RouteCompletionEvent.RouteWaypoint(
                wp.getLatitude(),
                wp.getLongitude(),
                wp.getInstruction(),
                wp.getDistanceToNext()
            ))
            .toList();

        if (primaryRoute == null) {
            throw new IllegalStateException("No primary route persisted for job " + job.getId());
        }

        return new RouteGenerationResult(primaryRoute, eventWaypoints);
    }

    private List<RouteWaypoint> persistWaypoints(Route route, List<RoadNode> nodes) {
        List<RouteWaypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            double distanceToNext = i < nodes.size() - 1
                    ? distanceKm(nodes.get(i), nodes.get(i + 1))
                    : 0.0;
            String instruction = i < nodes.size() - 1
                    ? "Continue to waypoint " + (i + 1)
                    : "Arrive at destination";

            RouteWaypoint wp = new RouteWaypoint(
                route, i, nodes.get(i).getLatitude(), nodes.get(i).getLongitude(),
                instruction,
                distanceToNext
            );
            waypointRepository.save(wp);
            waypoints.add(wp);
        }
        return waypoints;
    }

    private String serializeScoreBreakdown(Map<String, Double> scoreBreakdown) {
        if (scoreBreakdown == null || scoreBreakdown.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(scoreBreakdown);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize route score breakdown", ex);
        }
    }

    private void recordDurationCalibration(RouteJob job, RouteCandidate candidate) {
        Map<String, Double> breakdown = candidate.getScoreBreakdown();
        Double requestedRadiusKm = getBreakdownNumber(breakdown, "requested_avg_radius_km");
        Double requestedWaypointCount = getBreakdownNumber(breakdown, "requested_waypoint_count");
        Double geometryStrategyCode = getBreakdownNumber(breakdown, "geometry_strategy_code");
        if (requestedRadiusKm == null
            || requestedWaypointCount == null
            || geometryStrategyCode == null
            || requestedRadiusKm <= 0.0
            || requestedWaypointCount <= 0.0
            || job.getTimeBudgetMinutes() <= 0
            || candidate.getEstimatedMinutes() <= 0) {
            return;
        }

        String regionKey = H3Utils.getH3Index(
            job.getStartLatitude(),
            job.getStartLongitude(),
            DURATION_CALIBRATION_H3_RESOLUTION
        );
        int timeBudgetBucket = RouteDurationCalibration.bucketMinutes(job.getTimeBudgetMinutes());
        String geometryStrategy = geometryStrategyName(geometryStrategyCode);
        String routeMode = job.getRouteMode().apiValue();
        String calibrationId = RouteDurationCalibration.idFor(
            routeMode,
            regionKey,
            timeBudgetBucket,
            geometryStrategy
        );
        RouteDurationCalibration calibration = routeDurationCalibrationRepository.findById(calibrationId)
            .orElseGet(() -> new RouteDurationCalibration(
                routeMode,
                regionKey,
                timeBudgetBucket,
                geometryStrategy
            ));
        calibration.observe(
            requestedRadiusKm,
            Math.max(1, (int) Math.round(requestedWaypointCount)),
            job.getTimeBudgetMinutes(),
            candidate.getEstimatedMinutes(),
            Instant.now()
        );
        routeDurationCalibrationRepository.save(calibration);
    }

    private Double getBreakdownNumber(Map<String, Double> breakdown, String key) {
        if (breakdown == null || key == null) {
            return null;
        }
        return breakdown.get(key);
    }

    private String geometryStrategyName(double geometryStrategyCode) {
        int index = (int) Math.round(geometryStrategyCode);
        if (index < 0 || index >= GEOMETRY_STRATEGY_NAMES.length) {
            return "BALANCED_VARIETY";
        }
        return GEOMETRY_STRATEGY_NAMES[index];
    }
    
    private LineString buildLineString(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return geometryFactory.createLineString();
        }

        if (nodes.size() == 1) {
            Coordinate only = new Coordinate(nodes.getFirst().getLongitude(), nodes.getFirst().getLatitude());
            return geometryFactory.createLineString(new Coordinate[] { only, only });
        }

        Coordinate[] coords = nodes.stream()
            .map(n -> new Coordinate(n.getLongitude(), n.getLatitude()))
            .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coords);
    }

    private double distanceKm(RoadNode from, RoadNode to) {
        final double earthRadiusKm = 6371.0;
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.getLongitude() - from.getLongitude());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    public record RouteGenerationResult(Route route, List<RouteCompletionEvent.RouteWaypoint> waypoints) {
    }
}
