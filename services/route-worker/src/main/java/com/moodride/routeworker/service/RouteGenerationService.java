package com.moodride.routeworker.service;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteDurationCalibration;
import com.moodride.geo.H3Utils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
public class RouteGenerationService {
    private static final Logger logger = LoggerFactory.getLogger(RouteGenerationService.class);
    private static final int MAX_PERSISTED_WAYPOINTS_PER_ROUTE = 80;


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
        return processRoute(job, null);
    }

    public RouteGenerationResult processRoute(RouteJob job, Consumer<RouteGenerationResult> primaryReadyConsumer) {
        long processStartedNanos = System.nanoTime();
        long stageStartedNanos = System.nanoTime();
        List<RouteCandidate> candidates = routePlanner.generateRouteOptions(job);
        long planningMs = elapsedMillis(stageStartedNanos);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No route candidates generated for job " + job.getId());
        }

        stageStartedNanos = System.nanoTime();
        RouteCandidate primaryCandidate = candidates.getFirst();
        job.setAlgorithmVersion(primaryCandidate.getAlgorithmVersion());
        job.setBeamCandidates(primaryCandidate.getBeamCandidates());
        jobRepository.save(job);
        long jobMetadataMs = elapsedMillis(stageStartedNanos);

        Instant generatedAtBase = Instant.now();
        long routePersistMs = 0L;
        long calibrationMs = 0L;
        long waypointPersistMs = 0L;
        int waypointCount = 0;

        stageStartedNanos = System.nanoTime();
        PersistedRoute primary = persistRouteCandidate(job, primaryCandidate, ROUTE_OPTION_PROFILES.getFirst(), generatedAtBase);
        routePersistMs += primary.routePersistMs();
        calibrationMs += primary.calibrationMs();
        waypointPersistMs += primary.waypointPersistMs();
        waypointCount += primary.waypoints().size();
        RouteGenerationResult primaryResult = new RouteGenerationResult(primary.route(), eventWaypoints(primary.waypoints()));
        if (primaryReadyConsumer != null) {
            primaryReadyConsumer.accept(primaryResult);
        }

        for (int i = 1; i < candidates.size(); i++) {
            RouteCandidate candidate = candidates.get(i);
            String profile = i < ROUTE_OPTION_PROFILES.size() ? ROUTE_OPTION_PROFILES.get(i) : null;
            PersistedRoute persisted = persistRouteCandidate(job, candidate, profile, generatedAtBase.plusMillis(i));
            routePersistMs += persisted.routePersistMs();
            calibrationMs += persisted.calibrationMs();
            waypointPersistMs += persisted.waypointPersistMs();
            waypointCount += persisted.waypoints().size();
        }

        logger.info(
            "Route job {} persistence timings totalMs={} planningMs={} jobMetadataMs={} routePersistMs={} calibrationMs={} waypointPersistMs={} eventMappingMs={} candidates={} waypoints={}",
            job.getId(),
            elapsedMillis(processStartedNanos),
            planningMs,
            jobMetadataMs,
            routePersistMs,
            calibrationMs,
            waypointPersistMs,
            0L,
            candidates.size(),
            waypointCount
        );
        return primaryResult;
    }

    private PersistedRoute persistRouteCandidate(RouteJob job, RouteCandidate candidate, String profile, Instant generatedAt) {
        Route route = new Route(job.getId(), job.getUserId(),
            buildLineString(candidate.getWaypoints()), job.getVibe());
        route.setRouteMode(job.getRouteMode());
        route.setRouteProfile(profile);
        route.setTotalDistanceKm(candidate.getTotalDistanceKm());
        route.setEstimatedDurationMinutes(candidate.getEstimatedMinutes());
        route.setScenicScore(candidate.getTotalScenicScore());
        route.setScoreBreakdownJson(serializeScoreBreakdown(candidate.getScoreBreakdown()));
        route.setGeneratedAt(generatedAt);
        route.setExpiresAt(route.getGeneratedAt().plusSeconds(24 * 60 * 60));

        long stageStartedNanos = System.nanoTime();
        route = routeRepository.save(route);
        long routePersistMs = elapsedMillis(stageStartedNanos);

        stageStartedNanos = System.nanoTime();
        recordDurationCalibration(job, candidate);
        long calibrationMs = elapsedMillis(stageStartedNanos);

        stageStartedNanos = System.nanoTime();
        List<RouteWaypoint> waypoints = persistWaypoints(route, persistedWaypointSamples(candidate.getWaypoints()));
        long waypointPersistMs = elapsedMillis(stageStartedNanos);

        return new PersistedRoute(route, waypoints, routePersistMs, calibrationMs, waypointPersistMs);
    }

    private List<RouteCompletionEvent.RouteWaypoint> eventWaypoints(List<RouteWaypoint> waypoints) {
        return waypoints.stream()
            .map(wp -> new RouteCompletionEvent.RouteWaypoint(
                wp.getLatitude(),
                wp.getLongitude(),
                wp.getInstruction(),
                wp.getDistanceToNext()
            ))
            .toList();
    }

    private List<RoadNode> persistedWaypointSamples(List<RoadNode> nodes) {
        if (nodes == null || nodes.size() <= MAX_PERSISTED_WAYPOINTS_PER_ROUTE) {
            return nodes == null ? List.of() : nodes;
        }

        List<RoadNode> samples = new ArrayList<>(MAX_PERSISTED_WAYPOINTS_PER_ROUTE);
        int lastIndex = -1;
        double step = (double) (nodes.size() - 1) / (MAX_PERSISTED_WAYPOINTS_PER_ROUTE - 1);
        for (int i = 0; i < MAX_PERSISTED_WAYPOINTS_PER_ROUTE; i++) {
            int index = (int) Math.round(i * step);
            if (index <= lastIndex) {
                index = Math.min(nodes.size() - 1, lastIndex + 1);
            }
            samples.add(nodes.get(index));
            lastIndex = index;
        }
        if (lastIndex != nodes.size() - 1) {
            samples.set(samples.size() - 1, nodes.getLast());
        }
        return samples;
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
            waypoints.add(wp);
        }
        return waypointRepository.saveAll(waypoints);
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
    
    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
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

    private record PersistedRoute(Route route,
                                  List<RouteWaypoint> waypoints,
                                  long routePersistMs,
                                  long calibrationMs,
                                  long waypointPersistMs) {
    }

    public record RouteGenerationResult(Route route, List<RouteCompletionEvent.RouteWaypoint> waypoints) {
    }
}
