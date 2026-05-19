package com.moodride.routeapi.service;

import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;

import org.locationtech.jts.geom.Coordinate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.datamodels.RouteWeightCalibration;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.eventmodels.DriveCompletedEvent;
import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.eventmodels.RouteRatedEvent;
import com.moodride.geo.H3Utils;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.dto.RouteJobStatusResponse;
import com.moodride.routeapi.dto.RouteOptionExplanationResponse;
import com.moodride.routeapi.dto.RouteOptionResponse;
import com.moodride.routeapi.dto.RouteRatingRequest;
import com.moodride.routeapi.dto.RouteRatingResponse;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteSubmissionResponse;
import com.moodride.routeapi.exception.JobNotFoundException;
import com.moodride.routeapi.exception.RouteNotFoundException;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.RouteWeightCalibrationRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;

@Service
@Transactional
public class RouteService {

    private static final Set<String> ALLOWED_VIBES = VibeCatalog.supportedVibes();

    private static final Set<RouteMode> ENABLED_ROUTE_MODES = Set.of(RouteMode.DRIVE);

    private static final Map<String, String> PREFERENCE_KEY_ALIASES = Map.of(
        "water", "water",
        "greenery", "greenery",
        "green", "greenery",
        "elevation", "elevation",
        "solitude", "solitude",
        "curves", "curves",
        "curve", "curves",
        "poi", "poi"
    );

    private static final List<String> ROUTE_OPTION_PROFILES = List.of(
        "most_scenic",
        "balanced",
        "shorter"
    );

    private static final List<String> SUPPORTED_PREFERENCE_KEYS = List.of(
        "water",
        "greenery",
        "elevation",
        "solitude",
        "curves",
        "poi"
    );

    private static final double CALIBRATION_LEARNING_RATE = 0.04;
    private static final double CALIBRATION_MIN_MULTIPLIER = 0.70;
    private static final double CALIBRATION_MAX_MULTIPLIER = 1.30;
    private static final int ROUTE_EXPLANATION_SAMPLE_LIMIT = 160;
    private static final double ROUTE_EXPLANATION_BASELINE_RADIUS_METERS = 50_000.0;
    private static final int ROUTE_EXPLANATION_BASELINE_TILE_LIMIT = 1_000;
    private static final double ROUTE_EXPLANATION_LIFT_EPSILON = 0.003;
    private static final double ROUTE_EXPLANATION_POI_SUPPORT_MULTIPLIER = 0.04;
    private static final double ROUTE_EXPLANATION_POI_REQUESTED_MULTIPLIER = 0.62;
    private static final double ROUTE_EXPLANATION_POI_REQUESTED_WEIGHT = 0.30;
    private static final double ROUTE_EXPLANATION_POI_SUPPORT_CAP_RATIO = 0.82;

    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final RouteWeightCalibrationRepository calibrationRepository;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public RouteService(RouteJobRepository jobRepository,
                        RouteRepository routeRepository,
                        RouteWeightCalibrationRepository calibrationRepository,
                        ScenicScoreTileRepository scenicScoreTileRepository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.calibrationRepository = calibrationRepository;
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }
    
    public RouteSubmissionResponse submitRoute(RouteRequest request) {
        List<String> resolvedVibes = normalizeAndValidateVibes(request.resolvedVibes());
        RouteMode routeMode = normalizeAndValidateRouteMode(request.routeMode());

        RouteJob job = new RouteJob(
            request.userId(),
            request.lat(),
            request.lng(),
            request.timeBudgetMinutes(),
            resolvedVibes.getFirst()
        );
        job.setRouteMode(routeMode);
        job.setStatus(RouteJob.JobStatus.QUEUED);
        job.setVibesJson(serializeVibes(resolvedVibes));
        job.setPreferenceVector(serializePreferenceVector(normalizePreferenceVector(request.preferenceVector())));
        jobRepository.save(job);

        kafkaTemplate.send(RouteJobEvent.TOPIC, job.getId().toString(), job.getId().toString());

        return new RouteSubmissionResponse(
            job.getId(),
            job.getStatus().name(),
            5,
            "/routes/" + job.getId(),
            "job:" + job.getId(),
            job.getSubmittedAt(),
            job.getRetryCount(),
            job.getMaxRetries()
        );
    }

    public RouteJobStatusResponse getRouteJobStatus(UUID jobId) {
        RouteJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        List<Route> jobRoutes = routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId);
        List<RouteOptionResponse> routeOptions = buildRouteOptions(jobRoutes, job);

        UUID routeId = job.getRouteId();
        if (routeId == null) {
            routeId = resolvePrimaryRouteId(jobRoutes).orElse(null);
        }
        if (routeId == null && !routeOptions.isEmpty()) {
            routeId = routeOptions.getFirst().routeId();
        }

        String routeUrl = routeId == null ? null : "/routes/route/" + routeId;
        Integer estimatedRemaining = job.getStatus() == RouteJob.JobStatus.QUEUED || job.getStatus() == RouteJob.JobStatus.PROCESSING
                ? 3
                : null;

        return new RouteJobStatusResponse(
            job.getId(),
            job.getStatus().name(),
            routeId,
            routeUrl,
            routeOptions,
            job.getFailureReason(),
            job.getSubmittedAt(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getFailedAt(),
            estimatedRemaining,
            job.getRetryCount(),
            job.getMaxRetries(),
            job.getRouteMode().apiValue()
        );
    }
    
    @Cacheable(cacheNames = "routeResults", key = "#routeId.toString()")
    public RouteDetailResponse getRoute(UUID routeId) {
        Route route = routeRepository.findById(routeId)
            .orElseThrow(() -> new RouteNotFoundException(routeId));
        return mapRouteToDetail(route);
    }

    public Object getRouteOrJob(UUID id) {
        return routeRepository.findById(id)
            .<Object>map(this::mapRouteToDetail)
            .orElseGet(() -> getRouteJobStatus(id));
    }

    @CacheEvict(cacheNames = "routeResults", key = "#routeId.toString()")
    public RouteRatingResponse rateRoute(UUID routeId, RouteRatingRequest request) {
        Route route = routeRepository.findById(routeId)
            .orElseThrow(() -> new RouteNotFoundException(routeId));

        int rating = request.rating();
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }

        Instant ratedAt = Instant.now();
        route.setUserRating(rating);
        route.setRatedAt(ratedAt);
        routeRepository.save(route);

        publishUserFeedbackEvents(route, rating, ratedAt);
        applyCalibrationFeedback(route, rating, ratedAt);

        return new RouteRatingResponse(route.getId(), rating, ratedAt);
    }

    private RouteDetailResponse mapRouteToDetail(Route route) {
        RouteJob routeJob = jobRepository.findById(route.getJobId()).orElse(null);
        List<RouteOptionResponse> routeOptions = buildRouteOptions(
            routeRepository.findByJobIdOrderByGeneratedAtAsc(route.getJobId()),
            routeJob
        );

        Map<String, Object> lineGeometry = new HashMap<>();
        lineGeometry.put("type", "LineString");

        List<List<Double>> coordinates = resolveCoordinates(route);
        lineGeometry.put("coordinates", coordinates);

        Map<String, Object> feature = new HashMap<>();
        feature.put("type", "Feature");
        feature.put("geometry", lineGeometry);
        List<RouteWaypoint> waypoints = sortedWaypoints(route);
        feature.put("properties", Map.of(
            "segmentScores", buildSegmentScores(route, waypoints),
            "segmentColors", buildSegmentColors(route, waypoints)
        ));

        List<Map<String, Object>> scenicHighlights = buildScenicHighlights(route, waypoints);
        Integer computationTimeMs = null;
        if (routeJob != null && routeJob.getStartedAt() != null && routeJob.getCompletedAt() != null) {
            computationTimeMs = Math.toIntExact(Duration.between(routeJob.getStartedAt(), routeJob.getCompletedAt()).toMillis());
        }
        String algorithmVersion = routeJob == null ? null : routeJob.getAlgorithmVersion();
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            algorithmVersion = "unknown";
        }
        Integer beamCandidates = routeJob == null ? null : routeJob.getBeamCandidates();
        if (beamCandidates == null && algorithmVersion.startsWith("beam")) {
            beamCandidates = 10;
        }

        return new RouteDetailResponse(
            route.getId(),
            route.getJobId(),
            "/routes/route/" + route.getId(),
            route.getScenicScore() * 100.0,
            deriveQualityTier(route),
            route.getTotalDistanceKm(),
            route.getEstimatedDurationMinutes(),
            routeJob == null ? null : routeJob.getTimeBudgetMinutes(),
            resolveRouteMode(routeJob, route).apiValue(),
            waypoints.isEmpty() ? 0.0 : waypoints.getFirst().getLatitude(),
            waypoints.isEmpty() ? 0.0 : waypoints.getFirst().getLongitude(),
            resolveRouteVibes(routeJob, route),
            feature,
            scenicHighlights,
            routeOptions,
            algorithmVersion,
            beamCandidates,
            computationTimeMs,
            route.getUserRating(),
            route.getRatedAt(),
            route.getGeneratedAt(),
            route.getExpiresAt()
        );
    }

    private void publishUserFeedbackEvents(Route route, int rating, Instant ratedAt) {
        RouteRatedEvent ratedEvent = new RouteRatedEvent(
            route.getId(),
            route.getJobId(),
            route.getUserId(),
            rating,
            ratedAt
        );

        DriveCompletedEvent completedEvent = new DriveCompletedEvent(
            route.getId(),
            route.getJobId(),
            route.getUserId(),
            ratedAt
        );

        try {
            kafkaTemplate.send(RouteRatedEvent.TOPIC, route.getUserId().toString(), objectMapper.writeValueAsString(ratedEvent));
            kafkaTemplate.send(DriveCompletedEvent.TOPIC, route.getUserId().toString(), objectMapper.writeValueAsString(completedEvent));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize user feedback events", ex);
        }
    }

    private void applyCalibrationFeedback(Route route, int rating, Instant ratedAt) {
        RouteJob routeJob = jobRepository.findById(route.getJobId()).orElse(null);
        List<String> vibes = resolveRouteVibes(routeJob, route);
        if (vibes.isEmpty()) {
            return;
        }

        PreferenceWeights effectiveWeights = resolveEffectivePreferenceWeights(
            vibes,
            routeJob == null ? Map.of() : parseStoredPreferenceVector(routeJob.getPreferenceVector())
        );
        Map<String, Double> componentRatios = effectiveWeights.componentRatios();
        double feedbackSignal = (rating - 3.0) / 2.0;

        List<RouteWeightCalibration> existing = calibrationRepository.findByVibeIn(vibes);
        Map<String, RouteWeightCalibration> byVibe = new HashMap<>();
        for (RouteWeightCalibration calibration : existing) {
            byVibe.put(calibration.getVibe(), calibration);
        }

        List<RouteWeightCalibration> updates = new ArrayList<>();
        for (String vibe : vibes) {
            RouteWeightCalibration calibration = byVibe.getOrDefault(vibe, new RouteWeightCalibration(vibe));
            calibration.setWaterMultiplier(adjustMultiplier(calibration.getWaterMultiplier(), componentRatios.get("water"), feedbackSignal));
            calibration.setGreeneryMultiplier(adjustMultiplier(calibration.getGreeneryMultiplier(), componentRatios.get("greenery"), feedbackSignal));
            calibration.setElevationMultiplier(adjustMultiplier(calibration.getElevationMultiplier(), componentRatios.get("elevation"), feedbackSignal));
            calibration.setSolitudeMultiplier(adjustMultiplier(calibration.getSolitudeMultiplier(), componentRatios.get("solitude"), feedbackSignal));
            calibration.setCurvesMultiplier(adjustMultiplier(calibration.getCurvesMultiplier(), componentRatios.get("curves"), feedbackSignal));
            calibration.setPoiMultiplier(adjustMultiplier(calibration.getPoiMultiplier(), componentRatios.get("poi"), feedbackSignal));
            calibration.setSampleCount(Math.max(0, calibration.getSampleCount()) + 1);
            calibration.setUpdatedAt(ratedAt);
            updates.add(calibration);
        }

        calibrationRepository.saveAll(updates);
    }

    private double adjustMultiplier(double current, double componentRatio, double feedbackSignal) {
        double safeCurrent = current <= 0.0 ? 1.0 : current;
        double delta = CALIBRATION_LEARNING_RATE * feedbackSignal * clamp01(componentRatio);
        return clamp(safeCurrent + delta, CALIBRATION_MIN_MULTIPLIER, CALIBRATION_MAX_MULTIPLIER);
    }

    private List<Double> toCoordinate(RouteWaypoint waypoint) {
        return List.of(waypoint.getLongitude(), waypoint.getLatitude());
    }

    private List<List<Double>> resolveCoordinates(Route route) {
        List<RouteWaypoint> waypoints = sortedWaypoints(route);
        if (waypoints.size() >= 2) {
            return waypoints.stream()
                    .map(this::toCoordinate)
                    .toList();
        }

        if (route.getGeometry() != null && !route.getGeometry().isEmpty() && route.getGeometry().getNumPoints() >= 2) {
            return Arrays.stream(route.getGeometry().getCoordinates())
                    .map(this::toCoordinate)
                    .toList();
        }

        if (!waypoints.isEmpty()) {
            return waypoints.stream()
                    .map(this::toCoordinate)
                    .toList();
        }

        return List.of();
    }

    private List<Double> toCoordinate(Coordinate coordinate) {
        return List.of(coordinate.getX(), coordinate.getY());
    }

    private List<RouteWaypoint> sortedWaypoints(Route route) {
        if (route.getWaypoints() == null || route.getWaypoints().isEmpty()) {
            return List.of();
        }
        return route.getWaypoints().stream()
            .sorted(Comparator.comparingInt(RouteWaypoint::getWaypointOrder))
            .toList();
    }

    private List<Double> buildSegmentScores(Route route, List<RouteWaypoint> waypoints) {
        if (waypoints.size() < 2) {
            return List.of();
        }

        double totalDistance = waypoints.stream()
            .limit(Math.max(0L, waypoints.size() - 1L))
            .mapToDouble(RouteWaypoint::getDistanceToNext)
            .sum();
        double averageShare = 1.0 / (waypoints.size() - 1);
        List<Double> scores = new ArrayList<>();

        for (int i = 0; i < waypoints.size() - 1; i++) {
            double segmentDistance = Math.max(0.0, waypoints.get(i).getDistanceToNext());
            double distanceShare = totalDistance > 0.0 ? segmentDistance / totalDistance : averageShare;
            double progress = (double) i / Math.max(1, waypoints.size() - 2);
            double score = clamp01((route.getScenicScore() * 0.82)
                + (distanceShare * 0.12)
                + ((1.0 - Math.abs(progress - 0.5) * 2.0) * 0.06));
            scores.add(score);
        }

        return List.copyOf(scores);
    }

    private List<String> buildSegmentColors(Route route, List<RouteWaypoint> waypoints) {
        List<Double> scores = buildSegmentScores(route, waypoints);
        if (scores.isEmpty()) {
            return List.of();
        }

        List<String> colors = new ArrayList<>(scores.size());
        for (double score : scores) {
            colors.add(scoreToColor(score));
        }
        return List.copyOf(colors);
    }

    private List<Map<String, Object>> buildScenicHighlights(Route route, List<RouteWaypoint> waypoints) {
        List<Map<String, Object>> highlights = new ArrayList<>();

        Map<String, Object> summary = new HashMap<>();
        summary.put("type", "route_summary");
        summary.put("description", String.format(
            "%s loop with scenic score %.1f and %d waypoint%s",
            route.getVibe(),
            route.getScenicScore() * 100.0,
            waypoints.size(),
            waypoints.size() == 1 ? "" : "s"
        ));
        summary.put("totalDistanceKm", route.getTotalDistanceKm());
        summary.put("estimatedDurationMinutes", route.getEstimatedDurationMinutes());
        highlights.add(summary);

        if (!waypoints.isEmpty()) {
            highlights.add(buildWaypointHighlight("start", waypoints.getFirst(), 0));
        }

        if (waypoints.size() > 2) {
            int middleIndex = waypoints.size() / 2;
            highlights.add(buildWaypointHighlight("midpoint", waypoints.get(middleIndex), middleIndex));
        }

        if (waypoints.size() > 1) {
            highlights.add(buildWaypointHighlight("destination", waypoints.getLast(), waypoints.size() - 1));
        }

        return List.copyOf(highlights);
    }

    private Map<String, Object> buildWaypointHighlight(String type, RouteWaypoint waypoint, int index) {
        Map<String, Object> highlight = new HashMap<>();
        highlight.put("type", type);
        highlight.put("segmentIndex", index);
        highlight.put("instruction", waypoint.getInstruction());
        highlight.put("latitude", waypoint.getLatitude());
        highlight.put("longitude", waypoint.getLongitude());
        highlight.put("distanceToNextKm", waypoint.getDistanceToNext());
        return highlight;
    }

    private String deriveQualityTier(Route route) {
        int waypointCount = route.getWaypoints() == null ? 0 : route.getWaypoints().size();
        if (waypointCount < 2 || route.getScenicScore() < 0.45) {
            return "DEGRADED";
        }
        if (route.getScenicScore() >= 0.78 && waypointCount >= 3) {
            return "PREMIUM";
        }
        return "STANDARD";
    }

    private List<RouteOptionResponse> buildRouteOptions(List<Route> routes, RouteJob routeJob) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        List<Route> chronologicallyOrdered = routes.stream()
            .sorted(Comparator.comparing(Route::getGeneratedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        List<Route> profileOrdered = orderByProfileWithFallback(chronologicallyOrdered);
        ComponentAccumulator baselineAccumulator = buildBaselineAccumulator(routeJob, profileOrdered);

        List<RouteOptionResponse> options = new ArrayList<>(profileOrdered.size());
        for (int i = 0; i < profileOrdered.size(); i++) {
            Route route = profileOrdered.get(i);
            String profile = normalizeRouteProfile(route.getRouteProfile());
            if (profile == null && i < ROUTE_OPTION_PROFILES.size()) {
                profile = ROUTE_OPTION_PROFILES.get(i);
            }
            options.add(new RouteOptionResponse(
                profile == null ? "option_" + (i + 1) : profile,
                route.getId(),
                "/routes/route/" + route.getId(),
                route.getScenicScore() * 100.0,
                route.getTotalDistanceKm(),
                route.getEstimatedDurationMinutes(),
                buildRouteOptionExplanation(route, routeJob, baselineAccumulator)
            ));
        }

        return List.copyOf(options);
    }

    private RouteOptionExplanationResponse buildRouteOptionExplanation(
        Route route,
        RouteJob routeJob,
        ComponentAccumulator baselineAccumulator
    ) {
        if (route == null || route.getGeometry() == null || route.getGeometry().isEmpty()) {
            return null;
        }

        Set<String> h3Indexes = sampleRouteH3Indexes(route);
        if (h3Indexes.isEmpty()) {
            return null;
        }

        List<ScenicScoreTile> tiles = scenicScoreTileRepository.findByH3IndexIn(h3Indexes);
        if (tiles == null || tiles.isEmpty()) {
            return null;
        }

        ComponentAccumulator accumulator = new ComponentAccumulator();
        for (ScenicScoreTile tile : tiles) {
            accumulator.add(tile);
        }

        Map<String, Double> averages = accumulator.averages();
        Map<String, Double> baselineAverages = baselineAccumulator == null ? Map.of() : baselineAccumulator.averages();
        Map<String, Double> componentWeights = resolveEffectivePreferenceWeights(
            resolveRouteVibes(routeJob, route),
            routeJob == null ? Map.of() : parseStoredPreferenceVector(routeJob.getPreferenceVector())
        ).componentRatios();
        Map<String, Double> componentLifts = buildComponentLifts(averages, baselineAverages);
        Map<String, Double> weightedContributions = buildWeightedContributions(averages, componentWeights);
        Map<String, Double> weightedLifts = buildWeightedLifts(componentLifts, componentWeights);
        boolean liftBased = weightedLifts.values().stream().anyMatch(value -> value > ROUTE_EXPLANATION_LIFT_EPSILON);
        Map<String, Double> rankingSignals = buildExplanationRankingSignals(
            liftBased ? weightedLifts : weightedContributions,
            componentLifts,
            componentWeights,
            liftBased
        );

        List<String> leadingComponents = rankingSignals.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .toList();

        return new RouteOptionExplanationResponse(
            averages,
            baselineAverages,
            componentLifts,
            componentWeights,
            weightedContributions,
            leadingComponents,
            buildRouteExplanationSummary(leadingComponents, componentLifts, weightedContributions, liftBased),
            tiles.size(),
            baselineAccumulator == null ? 0 : baselineAccumulator.count()
        );
    }

    private ComponentAccumulator buildBaselineAccumulator(RouteJob routeJob, List<Route> routes) {
        Coordinate origin = resolveExplanationOrigin(routeJob, routes);
        if (origin == null) {
            return null;
        }

        List<ScenicScoreTile> baselineTiles = scenicScoreTileRepository.findScenicTilesNearPoint(
            origin.getY(),
            origin.getX(),
            ROUTE_EXPLANATION_BASELINE_RADIUS_METERS,
            ROUTE_EXPLANATION_BASELINE_TILE_LIMIT
        );
        if (baselineTiles == null || baselineTiles.isEmpty()) {
            return null;
        }

        ComponentAccumulator accumulator = new ComponentAccumulator();
        for (ScenicScoreTile tile : baselineTiles) {
            accumulator.add(tile);
        }
        return accumulator;
    }

    private Coordinate resolveExplanationOrigin(RouteJob routeJob, List<Route> routes) {
        if (routeJob != null) {
            return new Coordinate(routeJob.getStartLongitude(), routeJob.getStartLatitude());
        }
        if (routes == null) {
            return null;
        }
        for (Route route : routes) {
            if (route != null && route.getGeometry() != null && !route.getGeometry().isEmpty()) {
                Coordinate[] coordinates = route.getGeometry().getCoordinates();
                if (coordinates != null && coordinates.length > 0) {
                    return coordinates[0];
                }
            }
        }
        return null;
    }

    private Set<String> sampleRouteH3Indexes(Route route) {
        Coordinate[] coordinates = route.getGeometry().getCoordinates();
        if (coordinates == null || coordinates.length == 0) {
            return Set.of();
        }

        Set<String> h3Indexes = new LinkedHashSet<>();
        int step = Math.max(1, (int) Math.ceil((double) coordinates.length / ROUTE_EXPLANATION_SAMPLE_LIMIT));
        for (int i = 0; i < coordinates.length; i += step) {
            Coordinate coordinate = coordinates[i];
            h3Indexes.add(H3Utils.getH3Index(coordinate.getY(), coordinate.getX(), H3Utils.DEFAULT_RESOLUTION));
        }

        Coordinate last = coordinates[coordinates.length - 1];
        h3Indexes.add(H3Utils.getH3Index(last.getY(), last.getX(), H3Utils.DEFAULT_RESOLUTION));
        return h3Indexes;
    }

    private Map<String, Double> buildComponentLifts(Map<String, Double> averages, Map<String, Double> baselineAverages) {
        if (averages == null || averages.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> lifts = new LinkedHashMap<>();
        for (String component : SUPPORTED_PREFERENCE_KEYS) {
            double average = averages.getOrDefault(component, 0.0);
            double baseline = baselineAverages == null ? 0.0 : baselineAverages.getOrDefault(component, average);
            lifts.put(component, roundSignedComponent(average - baseline));
        }
        return Map.copyOf(lifts);
    }

    private Map<String, Double> buildWeightedContributions(Map<String, Double> averages, Map<String, Double> componentWeights) {
        if (averages == null || averages.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> rawContributions = new LinkedHashMap<>();
        double total = 0.0;
        for (String component : SUPPORTED_PREFERENCE_KEYS) {
            double contribution = averages.getOrDefault(component, 0.0) * componentWeights.getOrDefault(component, 0.0);
            rawContributions.put(component, contribution);
            total += contribution;
        }

        Map<String, Double> normalized = new LinkedHashMap<>();
        double safeTotal = Math.max(0.0001, total);
        for (String component : SUPPORTED_PREFERENCE_KEYS) {
            normalized.put(component, roundComponent(rawContributions.getOrDefault(component, 0.0) / safeTotal));
        }
        return Map.copyOf(normalized);
    }

    private Map<String, Double> buildWeightedLifts(Map<String, Double> componentLifts, Map<String, Double> componentWeights) {
        if (componentLifts == null || componentLifts.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> weightedLifts = new LinkedHashMap<>();
        for (String component : SUPPORTED_PREFERENCE_KEYS) {
            double positiveLift = Math.max(0.0, componentLifts.getOrDefault(component, 0.0));
            weightedLifts.put(component, roundComponent(positiveLift * componentWeights.getOrDefault(component, 0.0)));
        }
        return Map.copyOf(weightedLifts);
    }

    private Map<String, Double> buildExplanationRankingSignals(
        Map<String, Double> baseSignals,
        Map<String, Double> componentLifts,
        Map<String, Double> componentWeights,
        boolean liftBased
    ) {
        if (baseSignals == null || baseSignals.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> rankingSignals = new LinkedHashMap<>();
        boolean requestedPoi = false;
        for (String component : SUPPORTED_PREFERENCE_KEYS) {
            double signal = baseSignals.getOrDefault(component, 0.0);
            double weight = componentWeights == null ? 0.0 : componentWeights.getOrDefault(component, 0.0);
            double lift = componentLifts == null ? 0.0 : componentLifts.getOrDefault(component, 0.0);

            if ("poi".equals(component)) {
                requestedPoi = weight >= ROUTE_EXPLANATION_POI_REQUESTED_WEIGHT;
                signal *= requestedPoi
                    ? ROUTE_EXPLANATION_POI_REQUESTED_MULTIPLIER
                    : ROUTE_EXPLANATION_POI_SUPPORT_MULTIPLIER;
            } else if (weight >= 0.16) {
                signal *= 1.08;
            }

            if (liftBased && lift <= ROUTE_EXPLANATION_LIFT_EPSILON && !"poi".equals(component)) {
                signal *= 0.40;
            }
            rankingSignals.put(component, roundComponent(signal));
        }

        double maxNonPoiSignal = rankingSignals.entrySet().stream()
            .filter(entry -> !"poi".equals(entry.getKey()))
            .mapToDouble(Map.Entry::getValue)
            .max()
            .orElse(0.0);
        if (!requestedPoi && maxNonPoiSignal > ROUTE_EXPLANATION_LIFT_EPSILON) {
            double cappedPoiSignal = Math.min(
                rankingSignals.getOrDefault("poi", 0.0),
                maxNonPoiSignal * ROUTE_EXPLANATION_POI_SUPPORT_CAP_RATIO
            );
            rankingSignals.put("poi", roundComponent(cappedPoiSignal));
        }

        boolean hasNonPoiSignal = rankingSignals.entrySet().stream()
            .anyMatch(entry -> !"poi".equals(entry.getKey()) && entry.getValue() > ROUTE_EXPLANATION_LIFT_EPSILON);
        if (!hasNonPoiSignal) {
            return Map.copyOf(baseSignals);
        }
        return Map.copyOf(rankingSignals);
    }

    private String buildRouteExplanationSummary(
        List<String> leadingComponents,
        Map<String, Double> componentLifts,
        Map<String, Double> weightedContributions,
        boolean liftBased
    ) {
        if (leadingComponents == null || leadingComponents.isEmpty()) {
            return "Scored from nearby scenic tiles along this route.";
        }

        String first = componentLabel(leadingComponents.getFirst());
        if (leadingComponents.size() == 1) {
            return liftBased
                ? first + " is the strongest above-area signal on this option (" + formatLift(componentLifts.get(leadingComponents.getFirst())) + ")."
                : first + " carries the strongest weighted influence on this option (" + formatContribution(weightedContributions.get(leadingComponents.getFirst())) + ").";
        }

        String second = componentLabel(leadingComponents.get(1));
        if (liftBased) {
            return first + " (" + formatLift(componentLifts.get(leadingComponents.getFirst())) + ") and "
                + second + " (" + formatLift(componentLifts.get(leadingComponents.get(1))) + ") are strongest above the local area baseline.";
        }
        return first + " (" + formatContribution(weightedContributions.get(leadingComponents.getFirst())) + ") and "
            + second + " (" + formatContribution(weightedContributions.get(leadingComponents.get(1))) + ") carry the most weighted influence on this option.";
    }

    private String formatLift(Double lift) {
        double points = (lift == null ? 0.0 : lift) * 100.0;
        return String.format(Locale.ROOT, "%+.0f pts vs area", points);
    }

    private String formatContribution(Double contribution) {
        double percent = (contribution == null ? 0.0 : contribution) * 100.0;
        return String.format(Locale.ROOT, "%.0f%% contribution", percent);
    }

    private String componentLabel(String component) {
        return switch (component) {
            case "water" -> "Water";
            case "greenery" -> "Greenery";
            case "elevation" -> "Elevation";
            case "solitude" -> "Solitude";
            case "curves" -> "Curves";
            case "poi" -> "Stops";
            default -> component;
        };
    }

    private List<Route> orderByProfileWithFallback(List<Route> routes) {
        Map<String, Route> byProfile = new LinkedHashMap<>();
        List<Route> unprofiled = new ArrayList<>();

        for (Route route : routes) {
            String profile = normalizeRouteProfile(route.getRouteProfile());
            if (profile == null) {
                unprofiled.add(route);
                continue;
            }
            byProfile.putIfAbsent(profile, route);
        }

        List<Route> ordered = new ArrayList<>();
        Set<UUID> includedIds = new java.util.HashSet<>();
        for (String profile : ROUTE_OPTION_PROFILES) {
            Route route = byProfile.get(profile);
            if (route != null) {
                ordered.add(route);
                includedIds.add(route.getId());
            }
        }

        for (Route route : unprofiled) {
            if (ordered.size() >= ROUTE_OPTION_PROFILES.size()) {
                break;
            }
            if (includedIds.add(route.getId())) {
                ordered.add(route);
            }
        }

        return List.copyOf(ordered);
    }

    private java.util.Optional<UUID> resolvePrimaryRouteId(List<Route> routes) {
        if (routes == null || routes.isEmpty()) {
            return java.util.Optional.empty();
        }

        return routes.stream()
            .filter(route -> "most_scenic".equals(normalizeRouteProfile(route.getRouteProfile())))
            .min(Comparator.comparing(Route::getGeneratedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(Route::getId)
            .or(() -> routes.stream()
                .min(Comparator.comparing(Route::getGeneratedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Route::getId));
    }

    private List<String> resolveRouteVibes(RouteJob routeJob, Route route) {
        if (routeJob != null) {
            List<String> parsed = parseVibesJson(routeJob.getVibesJson());
            if (!parsed.isEmpty()) {
                return parsed;
            }
            if (routeJob.getVibe() != null && !routeJob.getVibe().isBlank()) {
                return List.of(routeJob.getVibe());
            }
        }
        if (route != null && route.getVibe() != null && !route.getVibe().isBlank()) {
            return List.of(route.getVibe());
        }
        return List.of("countryside");
    }

    private RouteMode resolveRouteMode(RouteJob routeJob, Route route) {
        if (routeJob != null) {
            return routeJob.getRouteMode();
        }
        if (route != null) {
            return route.getRouteMode();
        }
        return RouteMode.DRIVE;
    }

    private List<String> parseVibesJson(String rawVibesJson) {
        if (rawVibesJson == null || rawVibesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(rawVibesJson, new TypeReference<>() {
            });
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String value : raw) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String vibe = normalizeVibe(value);
                if (ALLOWED_VIBES.contains(vibe) && seen.add(vibe)) {
                    normalized.add(vibe);
                }
            }
            return List.copyOf(normalized);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String normalizeRouteProfile(String routeProfile) {
        if (routeProfile == null || routeProfile.isBlank()) {
            return null;
        }

        String normalized = routeProfile.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return ROUTE_OPTION_PROFILES.contains(normalized) ? normalized : null;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String scoreToColor(double score) {
        double clamped = clamp01(score);
        int red;
        int green;

        if (clamped < 0.5) {
            double t = clamped / 0.5;
            red = 255;
            green = (int) Math.round(96 + (128 * t));
        } else {
            double t = (clamped - 0.5) / 0.5;
            red = (int) Math.round(255 - (154 * t));
            green = 224;
        }

        return String.format("#%02X%02X4D", red, green);
    }

    private List<String> normalizeAndValidateVibes(List<String> vibes) {
        if (vibes == null || vibes.isEmpty()) {
            throw new IllegalArgumentException("At least one vibe is required");
        }
        if (vibes.size() > 3) {
            throw new IllegalArgumentException("At most three vibes are allowed");
        }

        return vibes.stream()
            .map(this::normalizeVibe)
            .peek(v -> {
                if (!ALLOWED_VIBES.contains(v)) {
                    throw new IllegalArgumentException("Invalid vibe: " + v);
                }
            })
            .distinct()
            .toList();
    }

    private RouteMode normalizeAndValidateRouteMode(String routeMode) {
        RouteMode normalized = RouteMode.fromApiValue(routeMode);
        if (!ENABLED_ROUTE_MODES.contains(normalized)) {
            throw new IllegalArgumentException(
                normalized.displayName() + " mode is not enabled yet. Canada driving is live; walking and biking are planned city pilots."
            );
        }
        return normalized;
    }

    private String normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            throw new IllegalArgumentException("Vibe cannot be empty");
        }
        return VibeCatalog.normalize(vibe);
    }

    private String serializeVibes(List<String> vibes) {
        if (vibes == null || vibes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vibes);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid vibes payload", ex);
        }
    }

    private String serializePreferenceVector(Map<String, Double> preferenceVector) {
        if (preferenceVector == null || preferenceVector.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(preferenceVector);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid preferenceVector payload", ex);
        }
    }

    private Map<String, Double> normalizePreferenceVector(Map<String, Object> preferenceVector) {
        if (preferenceVector == null || preferenceVector.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> normalized = new LinkedHashMap<>();
        List<String> unknownKeys = new ArrayList<>();
        List<String> invalidValueKeys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : preferenceVector.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String normalizedKey = normalizePreferenceKey(entry.getKey());
            if (normalizedKey == null) {
                unknownKeys.add(entry.getKey());
                continue;
            }

            Double normalizedValue = parsePreferenceValue(entry.getValue());
            if (normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            } else {
                invalidValueKeys.add(entry.getKey());
            }
        }

        if (!unknownKeys.isEmpty() || !invalidValueKeys.isEmpty()) {
            throw new IllegalArgumentException(
                "preferenceVector must use numeric values for keys: "
                    + String.join(", ", SUPPORTED_PREFERENCE_KEYS)
                    + ". Unknown keys: " + unknownKeys
                    + ". Invalid values: " + invalidValueKeys
            );
        }

        return Map.copyOf(normalized);
    }

    private Map<String, Double> parseStoredPreferenceVector(String rawPreferenceVector) {
        if (rawPreferenceVector == null || rawPreferenceVector.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(rawPreferenceVector, new TypeReference<>() {
            });
            return normalizePreferenceVector(raw);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private PreferenceWeights resolveEffectivePreferenceWeights(List<String> vibes, Map<String, Double> overrides) {
        PreferenceWeights defaults = blendVibeDefaults(vibes);
        if (overrides == null || overrides.isEmpty()) {
            return defaults.normalized();
        }
        return defaults.withOverrides(overrides).normalized();
    }

    private PreferenceWeights blendVibeDefaults(List<String> vibes) {
        List<String> activeVibes = (vibes == null || vibes.isEmpty()) ? List.of(VibeCatalog.defaultVibe()) : vibes;
        double water = 0.0;
        double greenery = 0.0;
        double elevation = 0.0;
        double solitude = 0.0;
        double curves = 0.0;
        double poi = 0.0;

        for (String vibe : activeVibes) {
            VibeCatalog.ComponentWeights defaults = VibeCatalog.weightsFor(vibe);
            water += defaults.water();
            greenery += defaults.greenery();
            elevation += defaults.elevation();
            solitude += defaults.solitude();
            curves += defaults.curves();
            poi += defaults.poi();
        }

        double count = Math.max(1.0, activeVibes.size());
        return new PreferenceWeights(
            water / count,
            greenery / count,
            elevation / count,
            solitude / count,
            curves / count,
            poi / count
        );
    }

    private String normalizePreferenceKey(String rawKey) {
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return PREFERENCE_KEY_ALIASES.get(normalized);
    }

    private Double parsePreferenceValue(Object value) {
        if (value instanceof Number numeric) {
            return clamp01(numeric.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return clamp01(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double normalizeElevation(double value) {
        if (value <= 1.0) {
            return clamp01(value);
        }
        return clamp01(value / 40.0);
    }

    private double resolveComponentScore(double component, double legacyFallback) {
        if (component > 0.0) {
            return clamp01(component);
        }
        return clamp01(legacyFallback);
    }

    private double roundComponent(double value) {
        return Math.round(clamp01(value) * 10_000.0) / 10_000.0;
    }

    private double roundSignedComponent(double value) {
        return Math.round(clamp(value, -1.0, 1.0) * 10_000.0) / 10_000.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class ComponentAccumulator {
        private double water;
        private double greenery;
        private double elevation;
        private double solitude;
        private double curves;
        private double poi;
        private int count;

        private void add(ScenicScoreTile tile) {
            water += resolveComponentScore(tile.getWaterScore(), tile.getWaterProximity());
            greenery += resolveComponentScore(tile.getGreenScore(), tile.getNaturalLandUse());
            elevation += normalizeElevation(resolveComponentScore(tile.getElevationScore(), tile.getElevationVariance()));
            solitude += resolveComponentScore(
                tile.getSolitudeScore(),
                (1.0 - clamp01(tile.getRoadDensity()) + clamp01(tile.getTrafficSignalScore())) / 2.0
            );
            curves += resolveComponentScore(tile.getCurveScore(), tile.getVisualComplexity());
            poi += resolveComponentScore(tile.getPoiScore(), tile.getPoiDensity());
            count++;
        }

        private Map<String, Double> averages() {
            double safeCount = Math.max(1.0, count);
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("water", roundComponent(water / safeCount));
            values.put("greenery", roundComponent(greenery / safeCount));
            values.put("elevation", roundComponent(elevation / safeCount));
            values.put("solitude", roundComponent(solitude / safeCount));
            values.put("curves", roundComponent(curves / safeCount));
            values.put("poi", roundComponent(poi / safeCount));
            return Map.copyOf(values);
        }

        private int count() {
            return count;
        }

        private double roundComponent(double value) {
            return Math.round(clamp01(value) * 10_000.0) / 10_000.0;
        }
    }

    private record PreferenceWeights(double water,
                                     double greenery,
                                     double elevation,
                                     double solitude,
                                     double curves,
                                     double poi) {
        private double totalWeight() {
            return Math.max(0.0001, water + greenery + elevation + solitude + curves + poi);
        }

        private PreferenceWeights withOverrides(Map<String, Double> overrides) {
            return new PreferenceWeights(
                overrides.getOrDefault("water", water),
                overrides.getOrDefault("greenery", greenery),
                overrides.getOrDefault("elevation", elevation),
                overrides.getOrDefault("solitude", solitude),
                overrides.getOrDefault("curves", curves),
                overrides.getOrDefault("poi", poi)
            );
        }

        private PreferenceWeights normalized() {
            double total = totalWeight();
            return new PreferenceWeights(
                water / total,
                greenery / total,
                elevation / total,
                solitude / total,
                curves / total,
                poi / total
            );
        }

        private Map<String, Double> componentRatios() {
            PreferenceWeights normalized = normalized();
            return Map.of(
                "water", normalized.water(),
                "greenery", normalized.greenery(),
                "elevation", normalized.elevation(),
                "solitude", normalized.solitude(),
                "curves", normalized.curves(),
                "poi", normalized.poi()
            );
        }
    }

}
