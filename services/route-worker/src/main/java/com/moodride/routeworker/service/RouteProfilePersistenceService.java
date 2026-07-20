package com.moodride.routeworker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteDurationCalibration;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.geo.H3Utils;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteRepository;
import com.moodride.routeworker.repository.RouteWaypointRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RouteProfilePersistenceService {
    public static final String PRIMARY_PROFILE = "most_scenic";

    private static final int MAX_PERSISTED_WAYPOINTS_PER_ROUTE = 80;
    private static final int DURATION_CALIBRATION_H3_RESOLUTION = 5;
    private static final String[] GEOMETRY_STRATEGY_NAMES = {
        "WATER_FOLLOWING",
        "OPEN_SPACE_ESCAPE",
        "PHOTO_PEAKS",
        "QUIET_LOW_PRESSURE",
        "CURVY_ELEVATION",
        "BALANCED_VARIETY"
    };

    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final RouteWaypointRepository waypointRepository;
    private final RouteDurationCalibrationRepository routeDurationCalibrationRepository;
    private final GeometryFactory geometryFactory;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;

    public RouteProfilePersistenceService(
        RouteJobRepository jobRepository,
        RouteRepository routeRepository,
        RouteWaypointRepository waypointRepository,
        RouteDurationCalibrationRepository routeDurationCalibrationRepository,
        ObjectMapper objectMapper,
        @Value("${route.generation.timeout.seconds:30}") int leaseSeconds
    ) {
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.waypointRepository = waypointRepository;
        this.routeDurationCalibrationRepository = routeDurationCalibrationRepository;
        this.geometryFactory = new GeometryFactory();
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.leaseDuration = Duration.ofSeconds(Math.max(1, leaseSeconds));
    }

    @Transactional
    public PersistedProfile persistProfile(
        UUID jobId,
        UUID expectedLeaseToken,
        RouteCandidate candidate,
        String profile,
        Instant generatedAt
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(generatedAt, "generatedAt");

        RouteJob job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Route job not found: " + jobId));
        Instant now = Instant.now();
        WorkerLeaseGuard.requireActive(job, expectedLeaseToken, now);

        Optional<Route> existingRoute = findExistingRoute(job, profile);
        if (existingRoute.isPresent()) {
            Route route = existingRoute.get();
            requireSameJob(jobId, route);
            if (PRIMARY_PROFILE.equals(profile) && job.getRouteId() == null) {
                job.markPrimaryReady(route.getId());
            }
            renewLease(job, now);
            jobRepository.saveAndFlush(job);
            return result(job, route, false);
        }

        Route route = new Route(
            job.getId(),
            job.getUserId(),
            buildLineString(candidate.getWaypoints()),
            job.getVibe()
        );
        route.setRouteMode(job.getRouteMode());
        route.setRouteProfile(profile);
        route.setTotalDistanceKm(candidate.getTotalDistanceKm());
        route.setEstimatedDurationMinutes(candidate.getEstimatedMinutes());
        route.setScenicScore(candidate.getTotalScenicScore());
        route.setScoreBreakdownJson(serializeScoreBreakdown(candidate.getScoreBreakdown()));
        route.setGeneratedAt(generatedAt);
        route.setExpiresAt(generatedAt.plusSeconds(24 * 60 * 60));
        route = routeRepository.saveAndFlush(route);

        List<RouteWaypoint> waypoints = createWaypoints(
            route,
            persistedWaypointSamples(candidate.getWaypoints())
        );
        waypointRepository.saveAllAndFlush(waypoints);
        recordDurationCalibration(job, candidate);

        int committedOptionCount = Math.toIntExact(
            routeRepository.countByJobIdAndRouteProfileIsNotNull(jobId)
        );
        job.recordVisibleOption(committedOptionCount);
        if (PRIMARY_PROFILE.equals(profile)) {
            job.setAlgorithmVersion(candidate.getAlgorithmVersion());
            job.setBeamCandidates(candidate.getBeamCandidates());
            job.markPrimaryReady(route.getId());
        }
        renewLease(job, now);
        jobRepository.saveAndFlush(job);
        return new PersistedProfile(
            route,
            eventWaypoints(waypoints),
            true,
            RouteJobLifecycleService.snapshot(job)
        );
    }

    @Transactional(readOnly = true)
    public PersistedProfile loadPrimary(UUID jobId) {
        RouteJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Route job not found: " + jobId));
        if (job.getRouteId() == null) {
            throw new IllegalStateException("Route job has no committed primary: " + jobId);
        }
        Route route = routeRepository.findById(job.getRouteId())
            .orElseThrow(() -> new IllegalStateException("Committed primary route not found: " + job.getRouteId()));
        requireSameJob(jobId, route);
        return result(job, route, false);
    }

    private Optional<Route> findExistingRoute(RouteJob job, String profile) {
        if (PRIMARY_PROFILE.equals(profile) && job.getRouteId() != null) {
            return Optional.of(
                routeRepository.findById(job.getRouteId())
                    .orElseThrow(() -> new IllegalStateException(
                        "Committed primary route not found: " + job.getRouteId()
                    ))
            );
        }
        return routeRepository.findByJobIdAndRouteProfile(job.getId(), profile);
    }

    private PersistedProfile result(RouteJob job, Route route, boolean inserted) {
        List<RouteWaypoint> waypoints = waypointRepository.findByRouteIdOrderByWaypointOrderAsc(route.getId());
        return new PersistedProfile(
            route,
            eventWaypoints(waypoints),
            inserted,
            RouteJobLifecycleService.snapshot(job)
        );
    }

    private void requireSameJob(UUID jobId, Route route) {
        if (!jobId.equals(route.getJobId())) {
            throw new IllegalStateException(
                "Primary route " + route.getId() + " belongs to a different route job"
            );
        }
    }

    private void renewLease(RouteJob job, Instant now) {
        job.setLeaseExpiresAt(now.plus(leaseDuration));
    }

    private List<RouteWaypoint> createWaypoints(Route route, List<RoadNode> nodes) {
        List<RouteWaypoint> waypoints = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            double distanceToNext = i < nodes.size() - 1
                ? distanceKm(nodes.get(i), nodes.get(i + 1))
                : 0.0;
            String instruction = i < nodes.size() - 1
                ? "Continue to waypoint " + (i + 1)
                : "Arrive at destination";
            waypoints.add(new RouteWaypoint(
                route,
                i,
                nodes.get(i).getLatitude(),
                nodes.get(i).getLongitude(),
                instruction,
                distanceToNext
            ));
        }
        return waypoints;
    }

    private List<RouteCompletionEvent.RouteWaypoint> eventWaypoints(List<RouteWaypoint> waypoints) {
        return waypoints.stream()
            .map(waypoint -> new RouteCompletionEvent.RouteWaypoint(
                waypoint.getLatitude(),
                waypoint.getLongitude(),
                waypoint.getInstruction(),
                waypoint.getDistanceToNext()
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

    private String serializeScoreBreakdown(Map<String, Double> scoreBreakdown) {
        if (scoreBreakdown == null || scoreBreakdown.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(scoreBreakdown);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize route score breakdown", exception);
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
        Coordinate[] coordinates = nodes.stream()
            .map(node -> new Coordinate(node.getLongitude(), node.getLatitude()))
            .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coordinates);
    }

    private double distanceKm(RoadNode from, RoadNode to) {
        final double earthRadiusKm = 6371.0;
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double latitudeDelta = lat2 - lat1;
        double longitudeDelta = Math.toRadians(to.getLongitude() - from.getLongitude());
        double haversine = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
            + Math.cos(lat1) * Math.cos(lat2)
            * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    public record PersistedProfile(
        Route route,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        boolean inserted,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
    }
}
