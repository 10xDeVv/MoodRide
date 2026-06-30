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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import com.moodride.datamodels.scoring.ComponentScores;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
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

    private static final Map<String, String> PREFERENCE_KEY_ALIASES = Map.ofEntries(
        Map.entry("water", "water"),
        Map.entry("greenery", "greenery"),
        Map.entry("green", "greenery"),
        Map.entry("elevation", "elevation"),
        Map.entry("solitude", "solitude"),
        Map.entry("open_space", "open_space"),
        Map.entry("openspace", "open_space"),
        Map.entry("curves", "curves"),
        Map.entry("curve", "curves"),
        Map.entry("poi", "poi"),
        Map.entry("scenic_poi", "scenic_poi"),
        Map.entry("viewpoint", "viewpoint"),
        Map.entry("bridge_coastal", "bridge_coastal")
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
        "open_space",
        "curves",
        "poi",
        "scenic_poi",
        "viewpoint",
        "bridge_coastal"
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
    private static final double CONTRACT_WATER_SHARE_MIN = 0.28;
    private static final double CONTRACT_MOUNTAIN_CURVE_ELEVATION_MIN = 0.28;
    private static final double CONTRACT_QUIET_SHARE_MIN = 0.32;
    private static final double CONTRACT_CORRIDOR_URBAN_PRESSURE_MAX = 0.42;
    private static final double CONTRACT_EDGE_URBAN_PRESSURE_MAX = 0.58;
    private static final double CONTRACT_ROAD_STRESS_MAX = 0.48;
    private static final double CONTRACT_TREE_CANOPY_MIN = 0.26;
    private static final double CONTRACT_SCENIC_PEAK_MIN = 0.42;
    private static final double CONTRACT_PHOTO_POI_SIGNAL_MIN = 0.30;
    private static final double CONTRACT_MAX_LOOP_CLOSURE_KM = 3.0;
    private static final double CONTRACT_MIN_UNIQUE_COORDINATE_RATIO = 0.72;
    private static final double CONTRACT_MAX_REPEATED_CORRIDOR_CELL_SHARE = 0.25;
    private static final double CONTRACT_MIN_LEG_SEPARATION_SCORE = 0.35;
    private static final double CONTRACT_MAX_BACKTRACKING_PENALTY = 0.35;
    private static final int MAX_FEEDBACK_TAGS = 4;
    private static final Set<String> SUPPORTED_FEEDBACK_TAGS = Set.of(
        "more_like_this",
        "too_urban",
        "too_long",
        "too_short",
        "too_boring",
        "not_scenic",
        "loved_quiet",
        "loved_curves",
        "loved_water",
        "loved_greenery",
        "loved_stops"
    );

    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final RouteWeightCalibrationRepository calibrationRepository;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ScenicScoreCalculator scenicScoreCalculator = new ScenicScoreCalculator();
    
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
        Map<String, Double> requestedPreferenceVector = normalizePreferenceVector(request.preferenceVector());
        job.setPreferenceVector(serializePreferenceVector(applyRouteWeightCalibrations(resolvedVibes, requestedPreferenceVector)));
        jobRepository.save(job);

        publishRouteJobAfterCommit(job.getId());

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

    private void publishRouteJobAfterCommit(UUID jobId) {
        Runnable publish = () -> kafkaTemplate.send(RouteJobEvent.TOPIC, jobId.toString(), jobId.toString());
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            publish.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
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
        FailureGuidance failureGuidance = buildFailureGuidance(job);

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
            job.getRouteMode().apiValue(),
            failureGuidance.code(),
            failureGuidance.userMessage(),
            failureGuidance.suggestedVibes(),
            failureGuidance.suggestedActions()
        );
    }

    private FailureGuidance buildFailureGuidance(RouteJob job) {
        if (job == null || job.getStatus() != RouteJob.JobStatus.FAILED) {
            return FailureGuidance.empty();
        }

        String reason = job.getFailureReason();
        if (reason == null || reason.isBlank()) {
            return new FailureGuidance(
                "route_generation_failed",
                "Route generation failed. Try a different starting point, more time, or another vibe.",
                List.of("scenic", "open_roads"),
                List.of("Try Scenic", "Try Open Roads", "Increase time budget")
            );
        }

        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("no strong ") || normalized.contains("no feasible route found")) {
            return new FailureGuidance(
                "vibe_unavailable",
                reason,
                suggestedFallbackVibes(job),
                suggestedFailureActions(job)
            );
        }

        return new FailureGuidance(
            "route_generation_failed",
            reason,
            List.of("scenic", "open_roads"),
            List.of("Try Scenic", "Try Open Roads", "Increase time budget")
        );
    }

    private List<String> suggestedFallbackVibes(RouteJob job) {
        List<String> routeVibes = resolveRouteVibes(job, null);
        Set<String> suggestions = new LinkedHashSet<>();
        if (routeVibes.stream().anyMatch(vibe -> vibe.equals("countryside") || vibe.equals("sunday_cruise"))) {
            suggestions.add("scenic");
            suggestions.add("open_roads");
            suggestions.add("relaxing");
        } else if (routeVibes.contains("mountain")) {
            suggestions.add("scenic");
            suggestions.add("winding_roads");
            suggestions.add("open_roads");
        } else {
            suggestions.add("scenic");
            suggestions.add("open_roads");
            suggestions.add("relaxing");
        }
        suggestions.removeAll(routeVibes);
        return List.copyOf(suggestions);
    }

    private List<String> suggestedFailureActions(RouteJob job) {
        int currentBudget = job == null ? 60 : job.getTimeBudgetMinutes();
        int nextBudget = currentBudget < 60 ? 60 : currentBudget < 90 ? 90 : currentBudget < 120 ? 120 : currentBudget + 30;
        return List.of(
            "Try Scenic",
            "Try Open Roads",
            "Increase time budget to " + nextBudget + " minutes",
            "Move the start point farther from downtown"
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

        List<String> feedbackTags = normalizeFeedbackTags(request.feedbackTags());
        Instant ratedAt = Instant.now();
        route.setUserRating(rating);
        route.setRatedAt(ratedAt);
        route.setFeedbackTagsJson(serializeFeedbackTags(feedbackTags));
        routeRepository.save(route);

        publishUserFeedbackEvents(route, rating, ratedAt, feedbackTags);
        applyCalibrationFeedback(route, rating, ratedAt, feedbackTags);

        return new RouteRatingResponse(route.getId(), rating, ratedAt, feedbackTags);
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
            parseScoreBreakdown(route.getScoreBreakdownJson()),
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

    private Map<String, Double> parseScoreBreakdown(String rawScoreBreakdownJson) {
        if (rawScoreBreakdownJson == null || rawScoreBreakdownJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(rawScoreBreakdownJson, new TypeReference<>() {
            });
            Map<String, Double> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (entry.getValue() instanceof Number number) {
                    normalized.put(entry.getKey(), number.doubleValue());
                }
            }
            return Map.copyOf(normalized);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private void publishUserFeedbackEvents(Route route, int rating, Instant ratedAt, List<String> feedbackTags) {
        RouteRatedEvent ratedEvent = new RouteRatedEvent(
            route.getId(),
            route.getJobId(),
            route.getUserId(),
            rating,
            ratedAt,
            feedbackTags
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

    private void applyCalibrationFeedback(Route route, int rating, Instant ratedAt, List<String> feedbackTags) {
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
            calibration.setWaterMultiplier(adjustMultiplier(calibration.getWaterMultiplier(), componentRatios.get("water"), feedbackSignal, feedbackTagSignal("water", feedbackTags)));
            calibration.setGreeneryMultiplier(adjustMultiplier(calibration.getGreeneryMultiplier(), componentRatios.get("greenery"), feedbackSignal, feedbackTagSignal("greenery", feedbackTags)));
            calibration.setElevationMultiplier(adjustMultiplier(calibration.getElevationMultiplier(), componentRatios.get("elevation"), feedbackSignal, feedbackTagSignal("elevation", feedbackTags)));
            calibration.setSolitudeMultiplier(adjustMultiplier(calibration.getSolitudeMultiplier(), componentRatios.get("solitude"), feedbackSignal, feedbackTagSignal("solitude", feedbackTags)));
            calibration.setCurvesMultiplier(adjustMultiplier(calibration.getCurvesMultiplier(), componentRatios.get("curves"), feedbackSignal, feedbackTagSignal("curves", feedbackTags)));
            calibration.setPoiMultiplier(adjustMultiplier(calibration.getPoiMultiplier(), componentRatios.get("poi"), feedbackSignal, feedbackTagSignal("poi", feedbackTags)));
            calibration.setSampleCount(Math.max(0, calibration.getSampleCount()) + 1);
            calibration.setUpdatedAt(ratedAt);
            updates.add(calibration);
        }

        calibrationRepository.saveAll(updates);
    }

    private List<String> normalizeFeedbackTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            if (rawTag == null || rawTag.isBlank()) {
                continue;
            }
            String normalized = rawTag.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
            if (SUPPORTED_FEEDBACK_TAGS.contains(normalized)) {
                accepted.add(normalized);
            }
            if (accepted.size() >= MAX_FEEDBACK_TAGS) {
                break;
            }
        }
        return List.copyOf(accepted);
    }

    private String serializeFeedbackTags(List<String> feedbackTags) {
        if (feedbackTags == null || feedbackTags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(feedbackTags);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid feedbackTags payload", ex);
        }
    }

    private double feedbackTagSignal(String component, List<String> feedbackTags) {
        if (feedbackTags == null || feedbackTags.isEmpty()) {
            return 0.0;
        }
        double signal = 0.0;
        for (String tag : feedbackTags) {
            signal += switch (tag) {
                case "more_like_this" -> switch (component) {
                    case "water", "greenery", "elevation", "solitude", "curves" -> 0.18;
                    case "poi" -> 0.08;
                    default -> 0.0;
                };
                case "too_urban" -> switch (component) {
                    case "solitude" -> 0.80;
                    case "greenery" -> 0.42;
                    case "water" -> 0.22;
                    case "poi" -> -0.36;
                    default -> 0.0;
                };
                case "not_scenic" -> switch (component) {
                    case "water", "greenery", "elevation", "solitude", "curves" -> 0.30;
                    case "poi" -> -0.18;
                    default -> 0.0;
                };
                case "too_boring" -> switch (component) {
                    case "curves" -> 0.74;
                    case "elevation" -> 0.32;
                    case "poi" -> 0.18;
                    default -> 0.0;
                };
                case "loved_quiet" -> switch (component) {
                    case "solitude" -> 0.72;
                    case "greenery" -> 0.24;
                    default -> 0.0;
                };
                case "loved_curves" -> switch (component) {
                    case "curves" -> 0.72;
                    case "elevation" -> 0.26;
                    default -> 0.0;
                };
                case "loved_water" -> component.equals("water") ? 0.80 : 0.0;
                case "loved_greenery" -> switch (component) {
                    case "greenery" -> 0.78;
                    case "solitude" -> 0.18;
                    default -> 0.0;
                };
                case "loved_stops" -> switch (component) {
                    case "poi" -> 0.72;
                    case "water", "elevation" -> 0.12;
                    default -> 0.0;
                };
                default -> 0.0;
            };
        }
        return clamp(signal, -1.0, 1.0);
    }

    private double adjustMultiplier(double current, double componentRatio, double feedbackSignal, double tagSignal) {
        double safeCurrent = current <= 0.0 ? 1.0 : current;
        double delta = CALIBRATION_LEARNING_RATE * ((feedbackSignal * clamp01(componentRatio)) + tagSignal);
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
                parseScoreBreakdown(route.getScoreBreakdownJson()),
                route.getTotalDistanceKm(),
                route.getEstimatedDurationMinutes(),
                buildRouteOptionExplanation(route, routeJob, baselineAccumulator)
            ));
        }

        return diversifyRouteOptionExplanations(options);
    }

    private List<RouteOptionResponse> diversifyRouteOptionExplanations(List<RouteOptionResponse> options) {
        if (options == null || options.size() < 2) {
            return options == null ? List.of() : List.copyOf(options);
        }

        Set<String> uniqueLeadingComponents = new HashSet<>();
        for (RouteOptionResponse option : options) {
            RouteOptionExplanationResponse explanation = option.explanation();
            if (explanation == null || explanation.leadingComponents() == null || explanation.leadingComponents().isEmpty()) {
                return List.copyOf(options);
            }
            uniqueLeadingComponents.add(explanation.leadingComponents().getFirst());
        }
        if (uniqueLeadingComponents.size() != 1) {
            return List.copyOf(options);
        }

        Set<String> usedLeadingComponents = new HashSet<>();
        List<RouteOptionResponse> diversified = new ArrayList<>(options.size());
        for (RouteOptionResponse option : options) {
            RouteOptionExplanationResponse explanation = option.explanation();
            List<String> reorderedLeadingComponents = reorderLeadingComponentsForProfile(
                option.profile(),
                explanation,
                usedLeadingComponents
            );
            if (reorderedLeadingComponents.isEmpty()) {
                diversified.add(option);
                continue;
            }

            usedLeadingComponents.add(reorderedLeadingComponents.getFirst());
            boolean liftBased = explanation.componentLifts().getOrDefault(reorderedLeadingComponents.getFirst(), 0.0)
                > ROUTE_EXPLANATION_LIFT_EPSILON;
            RouteOptionExplanationResponse diversifiedExplanation = new RouteOptionExplanationResponse(
                explanation.componentAverages(),
                explanation.baselineAverages(),
                explanation.componentLifts(),
                explanation.componentWeights(),
                explanation.weightedContributions(),
                reorderedLeadingComponents,
                buildProfileRouteOptionSummary(
                    option.profile(),
                    option.estimatedDurationMinutes(),
                    option.scoreBreakdown(),
                    reorderedLeadingComponents,
                    explanation.humanReasons()
                ),
                explanation.humanReasons(),
                explanation.contractFlags(),
                explanation.contractWarnings(),
                explanation.sampleTileCount(),
                explanation.baselineTileCount()
            );
            diversified.add(new RouteOptionResponse(
                option.profile(),
                option.routeId(),
                option.routeUrl(),
                option.scenicScore(),
                option.scoreBreakdown(),
                option.totalDistanceKm(),
                option.estimatedDurationMinutes(),
                diversifiedExplanation
            ));
        }
        return List.copyOf(diversified);
    }

    private List<String> reorderLeadingComponentsForProfile(
        String profile,
        RouteOptionExplanationResponse explanation,
        Set<String> usedLeadingComponents
    ) {
        List<String> leadingComponents = explanation.leadingComponents();
        if (leadingComponents == null || leadingComponents.isEmpty()) {
            return List.of();
        }

        String normalizedProfile = normalizeRouteProfile(profile);
        List<String> profilePreference;
        if ("balanced".equals(normalizedProfile)) {
            profilePreference = List.of("solitude", "greenery", "curves", "elevation", "water", "poi");
        } else if ("shorter".equals(normalizedProfile)) {
            profilePreference = List.of("curves", "elevation", "solitude", "greenery", "water", "poi");
        } else {
            profilePreference = leadingComponents;
        }

        String preferred = profilePreference.stream()
            .filter(leadingComponents::contains)
            .filter(component -> !usedLeadingComponents.contains(component))
            .findFirst()
            .orElseGet(() -> leadingComponents.stream()
                .filter(component -> !usedLeadingComponents.contains(component))
                .findFirst()
                .orElse(leadingComponents.getFirst()));

        List<String> reordered = new ArrayList<>();
        reordered.add(preferred);
        for (String component : leadingComponents) {
            if (!component.equals(preferred)) {
                reordered.add(component);
            }
        }
        return List.copyOf(reordered);
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
        List<String> routeVibes = resolveRouteVibes(routeJob, route);
        VibeCatalog.BlendedVibeProfile vibeProfile = VibeCatalog.blendProfiles(routeVibes);
        Map<String, Double> componentWeights = resolveEffectivePreferenceWeights(
            routeVibes,
            routeJob == null ? Map.of() : parseStoredPreferenceVector(routeJob.getPreferenceVector())
        ).componentRatios();
        Map<String, Double> componentLifts = buildComponentLifts(averages, baselineAverages);
        Map<String, Double> weightedContributions = buildWeightedContributions(averages, componentWeights);
        Map<String, Double> scoreBreakdown = parseScoreBreakdown(route.getScoreBreakdownJson());
        Map<String, Double> weightedLifts = buildWeightedLifts(componentLifts, componentWeights);
        boolean liftBased = weightedLifts.values().stream().anyMatch(value -> value > ROUTE_EXPLANATION_LIFT_EPSILON);
        Map<String, Double> rankingSignals = buildExplanationRankingSignals(
            liftBased ? weightedLifts : weightedContributions,
            weightedContributions,
            componentLifts,
            componentWeights,
            vibeProfile,
            liftBased
        );

        List<String> leadingComponents = rankingSignals.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .toList();
        Map<String, Boolean> contractFlags = buildRouteContractFlags(route, routeJob, routeVibes, averages, scoreBreakdown);
        List<String> contractWarnings = buildRouteContractWarnings(routeVibes, contractFlags);
        List<String> humanReasons = buildHumanRouteReasons(route, routeJob, routeVibes, averages, scoreBreakdown, leadingComponents);
        String summary = buildProfileRouteOptionSummary(
            route.getRouteProfile(),
            route.getEstimatedDurationMinutes(),
            scoreBreakdown,
            leadingComponents,
            humanReasons
        );

        return new RouteOptionExplanationResponse(
            averages,
            baselineAverages,
            componentLifts,
            componentWeights,
            weightedContributions,
            leadingComponents,
            summary,
            humanReasons,
            contractFlags,
            contractWarnings,
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
        Map<String, Double> fallbackSignals,
        Map<String, Double> componentLifts,
        Map<String, Double> componentWeights,
        VibeCatalog.BlendedVibeProfile vibeProfile,
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

            if (vibeProfile != null && vibeProfile.targetComponents().contains(component)) {
                signal *= vibeProfile.strictIntent() ? 1.35 : 1.18;
            }
            if (vibeProfile != null && vibeProfile.antiComponents().contains(component)) {
                signal *= 0.45;
            }
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
            if (liftBased && fallbackSignals != null && !fallbackSignals.isEmpty()) {
                return buildExplanationRankingSignals(
                    fallbackSignals,
                    Map.of(),
                    componentLifts,
                    componentWeights,
                    vibeProfile,
                    false
                );
            }
            return Map.copyOf(rankingSignals);
        }
        return Map.copyOf(rankingSignals);
    }

    private String buildProfileRouteOptionSummary(
        String profile,
        int durationMinutes,
        Map<String, Double> scoreBreakdown,
        List<String> leadingComponents,
        List<String> fallbackReasons
    ) {
        String normalizedProfile = normalizeRouteProfile(profile);
        String primaryTrait = leadingComponents == null || leadingComponents.isEmpty()
            ? "scenic"
            : componentAdjective(leadingComponents.getFirst());
        String secondaryTrait = leadingComponents == null || leadingComponents.size() < 2
            ? null
            : componentAdjective(leadingComponents.get(1));

        if ("most_scenic".equals(normalizedProfile)) {
            return "Best scenic match nearby, with the strongest " + primaryTrait
                + " signal" + supportingTraitPhrase(secondaryTrait) + ".";
        }

        if ("balanced".equals(normalizedProfile)) {
            List<String> tradeoffs = new ArrayList<>();
            if (getMetric(scoreBreakdown, "duration_fit_ratio") >= 0.88) {
                tradeoffs.add("closer timing");
            }
            if (getMetric(scoreBreakdown, "backtracking_penalty") <= 0.22
                || getMetric(scoreBreakdown, "leg_separation_score") >= 0.55
                || getMetric(scoreBreakdown, "route_shape_score") >= 0.72) {
                tradeoffs.add("a cleaner loop shape");
            }
            if (getMetric(scoreBreakdown, "corridor_urban_pressure") <= 0.42
                || getMetric(scoreBreakdown, "urban_penalty") <= 0.42) {
                tradeoffs.add("less urban pressure");
            }
            String tradeoff = tradeoffs.isEmpty()
                ? "a steadier drive"
                : joinEvidence(tradeoffs.stream().limit(2).toList());
            return "Balanced trades peak scenic intensity for " + tradeoff
                + ", while keeping " + primaryTrait + " character in the route.";
        }

        if ("shorter".equals(normalizedProfile)) {
            String durationPhrase = durationMinutes > 0 ? " in a " + durationMinutes + "-minute drive" : "";
            return "Shorter keeps the best available " + primaryTrait + " feel"
                + durationPhrase + ", with less time commitment than the other options.";
        }

        if (fallbackReasons != null && !fallbackReasons.isEmpty()) {
            return fallbackReasons.getFirst();
        }
        return "Selected from nearby scenic tiles along this route.";
    }

    private String componentAdjective(String component) {
        return switch (component) {
            case "water" -> "waterfront";
            case "greenery" -> "green";
            case "elevation" -> "rolling terrain";
            case "solitude" -> "quiet";
            case "open_space" -> "open road";
            case "curves" -> "winding road";
            case "poi" -> "stop-worthy";
            default -> humanVibeLabel(component);
        };
    }

    private String supportingTraitPhrase(String secondaryTrait) {
        if (secondaryTrait == null || secondaryTrait.isBlank()) {
            return "";
        }
        return " plus " + secondaryTrait + " support";
    }

    private List<String> buildHumanRouteReasons(
        Route route,
        RouteJob routeJob,
        List<String> routeVibes,
        Map<String, Double> componentAverages,
        Map<String, Double> scoreBreakdown,
        List<String> leadingComponents
    ) {
        List<String> evidence = new ArrayList<>();
        double waterShare = bestMetric(scoreBreakdown, componentAverages, "water_corridor_share", "water");
        double waterVisibility = getMetric(scoreBreakdown, "water_visibility_score");
        double waterCrossing = getMetric(scoreBreakdown, "water_crossing_score");
        double coastalRoad = getMetric(scoreBreakdown, "coastal_road_score");
        double treeCanopy = getMetric(scoreBreakdown, "tree_canopy_score");
        double scenicPoi = getMetric(scoreBreakdown, "scenic_poi_score");
        double viewpoint = getMetric(scoreBreakdown, "viewpoint_score");
        double bridgeCoastal = getMetric(scoreBreakdown, "bridge_coastal_score");
        double quietShare = bestMetric(scoreBreakdown, componentAverages, "quiet_corridor_share", "solitude");
        double curveElevationShare = bestMetric(scoreBreakdown, componentAverages, "curve_elevation_corridor_share", "elevation");
        double openSpaceShare = bestMetric(scoreBreakdown, componentAverages, "open_space_corridor_share", "open_space");
        double roadStress = getMetric(scoreBreakdown, "road_stress_score");
        double scenicMoments = Math.max(
            getMetric(scoreBreakdown, "scenic_moments_score"),
            getMetric(scoreBreakdown, "photo_peak_score")
        );
        double urbanPenalty = getMetric(scoreBreakdown, "urban_penalty");

        if (waterShare >= 0.20) {
            evidence.add("follows water for " + formatPercent(waterShare) + " of the drive");
        }
        if (hasMetric(scoreBreakdown, "water_visibility_score") && waterVisibility >= 0.25) {
            evidence.add("uses roads with stronger visible-water context");
        }
        if (hasMetric(scoreBreakdown, "coastal_road_score") && coastalRoad >= 0.25) {
            evidence.add("stays on more water-adjacent road corridors");
        }
        if (hasMetric(scoreBreakdown, "bridge_coastal_score") && bridgeCoastal >= 0.18) {
            evidence.add("adds bridge, pier, or coastal-road moments");
        }
        if (hasMetric(scoreBreakdown, "water_crossing_score") && waterCrossing >= 0.20) {
            evidence.add("includes bridge or water-crossing moments");
        }
        if (hasMetric(scoreBreakdown, "tree_canopy_score") && treeCanopy >= 0.26) {
            evidence.add("spends meaningful time in tree-covered corridors");
        }
        if (hasMetric(scoreBreakdown, "scenic_poi_score") && scenicPoi >= 0.18) {
            evidence.add("passes stronger scenic stops, landmarks, or natural features");
        }
        if (hasMetric(scoreBreakdown, "viewpoint_score") && viewpoint >= 0.18) {
            evidence.add("has stronger viewpoint or photo-landmark signal");
        }
        if (quietShare >= 0.25) {
            evidence.add("keeps " + formatPercent(quietShare) + " of the drive in quieter corridors");
        }
        if (curveElevationShare >= 0.25) {
            evidence.add("has rolling or curvy terrain through " + formatPercent(curveElevationShare) + " of the route");
        }
        if (openSpaceShare >= 0.25 && !containsSimilarEvidence(evidence, "quieter corridors")) {
            evidence.add("leans into open-space roads");
        }
        if (hasMetric(scoreBreakdown, "road_stress_score") && roadStress <= 0.32) {
            evidence.add("avoids high-stress road classes");
        }
        if (scenicMoments >= 0.42) {
            evidence.add("includes strong scenic stretches");
        }
        if (urbanPenalty > 0.0 && urbanPenalty <= 0.25) {
            evidence.add("has lower urban pressure than nearby alternatives");
        } else if (urbanPenalty >= 0.45) {
            evidence.add("still carries some urban pressure near the start or finish");
        }

        if (evidence.isEmpty() && leadingComponents != null) {
            for (String component : leadingComponents) {
                evidence.add("leans on " + componentPhrase(component));
                if (evidence.size() >= 2) {
                    break;
                }
            }
        }

        if (evidence.isEmpty()) {
            evidence.add("uses the strongest scenic tiles available near this start");
        }

        int budget = routeJob == null ? 0 : routeJob.getTimeBudgetMinutes();
        if (budget > 0) {
            int delta = route.getEstimatedDurationMinutes() - budget;
            if (Math.abs(delta) <= Math.max(5, Math.round(budget * 0.10f))) {
                evidence.add("stays close to your " + budget + "-minute budget");
            } else if (delta > 0) {
                evidence.add("runs about " + delta + " minutes over your requested budget");
            }
        }

        String opener = routeExplanationOpener(route, routeVibes);
        String primary = opener + " because it " + joinEvidence(evidence.stream().limit(3).toList()) + ".";
        List<String> reasons = new ArrayList<>();
        reasons.add(primary);
        for (String item : evidence) {
            reasons.add(capitalizeFirst(item) + ".");
        }
        return List.copyOf(reasons);
    }

    private Map<String, Boolean> buildRouteContractFlags(
        Route route,
        RouteJob routeJob,
        List<String> routeVibes,
        Map<String, Double> componentAverages,
        Map<String, Double> scoreBreakdown
    ) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        double target = routeJob == null ? 0.0 : routeJob.getTimeBudgetMinutes();
        double maxAllowed = target <= 0.0 ? Double.POSITIVE_INFINITY : target + Math.max(5.0, target * 0.15);
        double minExpected = target <= 0.0 ? 0.0 : Math.max(10.0, target * 0.55);
        double duration = route == null ? 0.0 : route.getEstimatedDurationMinutes();
        flags.put("time_budget_fit", target <= 0.0 || (duration <= maxAllowed && duration >= minExpected));
        flags.put("loop_closure", computeLoopClosureKm(route) <= CONTRACT_MAX_LOOP_CLOSURE_KM);
        flags.put("low_repeated_road_risk", computeUniqueCoordinateRatio(route) >= CONTRACT_MIN_UNIQUE_COORDINATE_RATIO);
        flags.put("repeated_corridor_ok", metricAtMost(scoreBreakdown, "repeated_corridor_cell_share", CONTRACT_MAX_REPEATED_CORRIDOR_CELL_SHARE));
        flags.put("leg_separation_ok", metricAtLeast(scoreBreakdown, "leg_separation_score", CONTRACT_MIN_LEG_SEPARATION_SCORE));
        flags.put("backtracking_risk_ok", metricAtMost(scoreBreakdown, "backtracking_penalty", CONTRACT_MAX_BACKTRACKING_PENALTY));
        boolean corridorUrbanPressureOk = metricAtMostAny(
            scoreBreakdown,
            CONTRACT_CORRIDOR_URBAN_PRESSURE_MAX,
            "corridor_urban_pressure",
            "urban_penalty"
        );
        flags.put("corridor_urban_pressure_ok", corridorUrbanPressureOk);
        flags.put("edge_urban_pressure_ok", metricAtMostAny(
            scoreBreakdown,
            CONTRACT_EDGE_URBAN_PRESSURE_MAX,
            "edge_urban_pressure",
            "start_end_penalty"
        ));
        flags.put("urban_pressure_ok", corridorUrbanPressureOk);
        double scenicPeak = Math.max(
            getMetric(scoreBreakdown, "scenic_moments_score"),
            getMetric(scoreBreakdown, "photo_peak_score")
        );
        flags.put("scenic_peak_ok", scenicPeak >= CONTRACT_SCENIC_PEAK_MIN || (route != null && route.getScenicScore() >= 0.65));

        if (hasAnyVibe(routeVibes, "coastal", "riverside", "sunset", "golden_hour", "sunrise")) {
            double waterSignal = Math.max(
                bestMetric(scoreBreakdown, componentAverages, "water_corridor_share", "water"),
                getMetric(scoreBreakdown, "bridge_coastal_score")
            );
            flags.put("water_share_ok", waterSignal >= CONTRACT_WATER_SHARE_MIN);
        }
        if (hasAnyVibe(routeVibes, "mountain", "winding_roads", "winding", "adventure")) {
            flags.put("elevation_curve_share_ok", bestMetric(scoreBreakdown, componentAverages, "curve_elevation_corridor_share", "elevation") >= CONTRACT_MOUNTAIN_CURVE_ELEVATION_MIN);
        }
        if (hasAnyVibe(routeVibes, "countryside", "country", "sunday", "sunday_cruise", "quiet", "minimal_traffic", "low_traffic", "open_roads", "relaxing", "clear_my_head", "smooth_cruise")) {
            flags.put("quiet_share_ok", bestMetric(scoreBreakdown, componentAverages, "quiet_corridor_share", "solitude") >= CONTRACT_QUIET_SHARE_MIN);
            flags.put("road_stress_ok", metricAtMost(scoreBreakdown, "road_stress_score", CONTRACT_ROAD_STRESS_MAX));
        }
        if (hasAnyVibe(routeVibes, "forest", "nature_escape", "nature")) {
            flags.put("tree_canopy_ok", metricAtLeast(scoreBreakdown, "tree_canopy_score", CONTRACT_TREE_CANOPY_MIN));
        }
        if (hasAnyVibe(routeVibes, "photo_worthy", "photo_run", "photo", "date_night", "hidden_gems")) {
            double photoSignal = Math.max(
                Math.max(
                    Math.max(getMetric(scoreBreakdown, "photo_peak_score"), getMetric(scoreBreakdown, "scenic_poi_score")),
                    getMetric(scoreBreakdown, "viewpoint_score")
                ),
                componentAverages == null ? 0.0 : componentAverages.getOrDefault("poi", 0.0)
            );
            flags.put("photo_poi_signal_ok", photoSignal >= CONTRACT_PHOTO_POI_SIGNAL_MIN);
        }

        return Map.copyOf(flags);
    }

    private List<String> buildRouteContractWarnings(List<String> routeVibes, Map<String, Boolean> contractFlags) {
        if (contractFlags == null || contractFlags.isEmpty()) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        addWarningIfFalse(warnings, contractFlags, "time_budget_fit", "Route duration does not fit the requested time budget.");
        addWarningIfFalse(warnings, contractFlags, "loop_closure", "Route does not close cleanly as a loop.");
        addWarningIfFalse(warnings, contractFlags, "low_repeated_road_risk", "Route may repeat too much road.");
        addWarningIfFalse(warnings, contractFlags, "repeated_corridor_ok", "Route reuses too many corridor cells.");
        addWarningIfFalse(warnings, contractFlags, "leg_separation_ok", "Route legs may not separate enough.");
        addWarningIfFalse(warnings, contractFlags, "backtracking_risk_ok", "Route may backtrack too much.");
        addWarningIfFalse(warnings, contractFlags, "corridor_urban_pressure_ok", "Route corridor has more urban pressure than expected.");
        addWarningIfFalse(warnings, contractFlags, "edge_urban_pressure_ok", "Route starts or ends in a more urban area.");
        addWarningIfFalse(warnings, contractFlags, "scenic_peak_ok", "Route lacks a strong scenic stretch.");
        if (hasAnyVibe(routeVibes, "coastal", "riverside", "sunset", "golden_hour", "sunrise")) {
            addWarningIfFalse(warnings, contractFlags, "water_share_ok", "Water-focused vibe has low water corridor share.");
        }
        if (hasAnyVibe(routeVibes, "mountain", "winding_roads", "winding", "adventure")) {
            addWarningIfFalse(warnings, contractFlags, "elevation_curve_share_ok", "Mountain or winding vibe has weak elevation/curve share.");
        }
        if (hasAnyVibe(routeVibes, "countryside", "country", "sunday", "sunday_cruise", "quiet", "minimal_traffic", "low_traffic", "open_roads", "relaxing", "clear_my_head", "smooth_cruise")) {
            addWarningIfFalse(warnings, contractFlags, "quiet_share_ok", "Quiet/rural vibe has weak quiet corridor share.");
            addWarningIfFalse(warnings, contractFlags, "road_stress_ok", "Quiet/rural vibe uses higher-stress road classes.");
        }
        if (hasAnyVibe(routeVibes, "forest", "nature_escape", "nature")) {
            addWarningIfFalse(warnings, contractFlags, "tree_canopy_ok", "Forest/nature vibe has weak tree-canopy signal.");
        }
        if (hasAnyVibe(routeVibes, "photo_worthy", "photo_run", "photo", "date_night", "hidden_gems")) {
            addWarningIfFalse(warnings, contractFlags, "photo_poi_signal_ok", "Photo/discovery vibe has weak photo or POI signal.");
        }
        return List.copyOf(warnings);
    }

    private void addWarningIfFalse(List<String> warnings, Map<String, Boolean> flags, String key, String warning) {
        if (Boolean.FALSE.equals(flags.get(key))) {
            warnings.add(warning);
        }
    }

    private boolean hasAnyVibe(List<String> routeVibes, String... expected) {
        if (routeVibes == null || routeVibes.isEmpty()) {
            return false;
        }
        Set<String> active = routeVibes.stream()
            .filter(Objects::nonNull)
            .map(VibeCatalog::normalize)
            .collect(Collectors.toSet());
        for (String vibe : expected) {
            if (active.contains(VibeCatalog.normalize(vibe))) {
                return true;
            }
        }
        return false;
    }

    private double bestMetric(Map<String, Double> scoreBreakdown, Map<String, Double> componentAverages, String breakdownKey, String componentKey) {
        double breakdownValue = getMetric(scoreBreakdown, breakdownKey);
        double componentValue = componentAverages == null ? 0.0 : componentAverages.getOrDefault(componentKey, 0.0);
        return clamp01(Math.max(breakdownValue, componentValue));
    }

    private boolean metricAtMost(Map<String, Double> scoreBreakdown, String key, double maxValue) {
        return !hasMetric(scoreBreakdown, key) || getMetric(scoreBreakdown, key) <= maxValue;
    }

    private boolean metricAtMostAny(Map<String, Double> scoreBreakdown, double maxValue, String... keys) {
        for (String key : keys) {
            if (hasMetric(scoreBreakdown, key)) {
                return getMetric(scoreBreakdown, key) <= maxValue;
            }
        }
        return true;
    }

    private boolean metricAtLeast(Map<String, Double> scoreBreakdown, String key, double minValue) {
        return !hasMetric(scoreBreakdown, key) || getMetric(scoreBreakdown, key) >= minValue;
    }

    private boolean hasMetric(Map<String, Double> scoreBreakdown, String key) {
        return scoreBreakdown != null && scoreBreakdown.containsKey(key) && scoreBreakdown.get(key) != null;
    }

    private double getMetric(Map<String, Double> scoreBreakdown, String key) {
        if (scoreBreakdown == null || scoreBreakdown.isEmpty()) {
            return 0.0;
        }
        return clamp01(scoreBreakdown.getOrDefault(key, 0.0));
    }

    private double computeLoopClosureKm(Route route) {
        if (route == null || route.getGeometry() == null || route.getGeometry().isEmpty()) {
            return 0.0;
        }
        Coordinate[] coordinates = route.getGeometry().getCoordinates();
        if (coordinates == null || coordinates.length < 2) {
            return 0.0;
        }
        return distanceKm(coordinates[0], coordinates[coordinates.length - 1]);
    }

    private double computeUniqueCoordinateRatio(Route route) {
        if (route == null || route.getGeometry() == null || route.getGeometry().isEmpty()) {
            return 1.0;
        }
        Coordinate[] coordinates = route.getGeometry().getCoordinates();
        if (coordinates == null || coordinates.length < 2) {
            return 1.0;
        }
        Set<String> unique = new HashSet<>();
        for (Coordinate coordinate : coordinates) {
            unique.add(String.format(Locale.ROOT, "%.5f,%.5f", coordinate.getX(), coordinate.getY()));
        }
        return (double) unique.size() / coordinates.length;
    }

    private double distanceKm(Coordinate first, Coordinate second) {
        if (first == null || second == null) {
            return 0.0;
        }
        double earthRadiusKm = 6371.0088;
        double lat1 = Math.toRadians(first.getY());
        double lat2 = Math.toRadians(second.getY());
        double deltaLat = Math.toRadians(second.getY() - first.getY());
        double deltaLng = Math.toRadians(second.getX() - first.getX());
        double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2.0) * Math.sin(deltaLng / 2.0);
        return earthRadiusKm * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private String routeExplanationOpener(Route route, List<String> routeVibes) {
        String profile = normalizeRouteProfile(route == null ? null : route.getRouteProfile());
        if ("most_scenic".equals(profile)) {
            return "This is the strongest scenic option nearby";
        }
        if ("balanced".equals(profile)) {
            return "This route balances scenery with drive time";
        }
        if ("shorter".equals(profile)) {
            return "This is the shorter scenic option";
        }
        if (routeVibes != null && !routeVibes.isEmpty()) {
            return "This route fits " + humanVibeLabel(routeVibes.getFirst());
        }
        return "This route was selected";
    }

    private String humanVibeLabel(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            return "selected vibe";
        }
        return vibe.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
    }

    private String componentPhrase(String component) {
        return switch (component) {
            case "water" -> "waterfront views";
            case "greenery" -> "green cover";
            case "elevation" -> "rolling terrain";
            case "solitude" -> "quieter roads";
            case "open_space" -> "open roads";
            case "curves" -> "curvier roads";
            case "poi" -> "interesting stops";
            case "scenic_poi" -> "scenic stops";
            case "viewpoint" -> "viewpoints";
            case "bridge_coastal" -> "bridge and coastal-road moments";
            default -> component == null ? "scenic signals" : component.replace('_', ' ');
        };
    }

    private boolean containsSimilarEvidence(List<String> evidence, String text) {
        return evidence.stream().anyMatch(item -> item.contains(text));
    }

    private String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", clamp01(ratio) * 100.0);
    }

    private String joinEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "uses the strongest scenic signals available";
        }
        if (evidence.size() == 1) {
            return evidence.getFirst();
        }
        if (evidence.size() == 2) {
            return evidence.get(0) + " and " + evidence.get(1);
        }
        return evidence.get(0) + ", " + evidence.get(1) + ", and " + evidence.get(2);
    }

    private String capitalizeFirst(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
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

    private Map<String, Double> applyRouteWeightCalibrations(List<String> vibes, Map<String, Double> requestedPreferenceVector) {
        PreferenceWeights baseWeights = resolveEffectivePreferenceWeights(vibes, requestedPreferenceVector);
        List<RouteWeightCalibration> calibrations = calibrationRepository.findByVibeIn(
            vibes == null || vibes.isEmpty() ? List.of(VibeCatalog.defaultVibe()) : vibes
        );
        if (calibrations.isEmpty()) {
            return baseWeights.componentRatios();
        }

        double count = calibrations.size();
        PreferenceWeights calibrated = new PreferenceWeights(
            baseWeights.water() * calibrations.stream().mapToDouble(RouteWeightCalibration::getWaterMultiplier).sum() / count,
            baseWeights.greenery() * calibrations.stream().mapToDouble(RouteWeightCalibration::getGreeneryMultiplier).sum() / count,
            baseWeights.elevation() * calibrations.stream().mapToDouble(RouteWeightCalibration::getElevationMultiplier).sum() / count,
            baseWeights.solitude() * calibrations.stream().mapToDouble(RouteWeightCalibration::getSolitudeMultiplier).sum() / count,
            baseWeights.curves() * calibrations.stream().mapToDouble(RouteWeightCalibration::getCurvesMultiplier).sum() / count,
            baseWeights.poi() * calibrations.stream().mapToDouble(RouteWeightCalibration::getPoiMultiplier).sum() / count
        ).normalized();

        return calibrated.componentRatios();
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
        private double openSpace;
        private double curves;
        private double poi;
        private int count;

        private void add(ScenicScoreTile tile) {
            ComponentScores scores = scenicScoreCalculator.componentScores(tile);
            water += scores.water();
            greenery += scores.greenery();
            elevation += scores.elevation();
            solitude += scores.solitude();
            openSpace += openSpaceScore(tile, scores);
            curves += scores.curves();
            poi += scores.poi();
            count++;
        }

        private Map<String, Double> averages() {
            double safeCount = Math.max(1.0, count);
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("water", roundComponent(water / safeCount));
            values.put("greenery", roundComponent(greenery / safeCount));
            values.put("elevation", roundComponent(elevation / safeCount));
            values.put("solitude", roundComponent(solitude / safeCount));
            values.put("open_space", roundComponent(openSpace / safeCount));
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

        private double openSpaceScore(ScenicScoreTile tile, ComponentScores scores) {
            double lowRoadDensity = 1.0 - clamp01(tile.getRoadDensity());
            double lowBuildingDensity = 1.0 - clamp01(tile.getBuildingDensityScore());
            double lowUrbanPressure = 1.0 - clamp01(tile.getUrbanPenaltyScore());
            double lowRoadStress = 1.0 - clamp01(tile.getRoadStressScore());
            double lowPoiDensity = 1.0 - scores.poi();
            return clamp01(
                (scores.solitude() * 0.40)
                    + (lowRoadDensity * 0.20)
                    + (lowBuildingDensity * 0.15)
                    + (lowUrbanPressure * 0.10)
                    + (lowRoadStress * 0.10)
                    + (lowPoiDensity * 0.05)
            );
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
                "open_space", normalized.solitude(),
                "curves", normalized.curves(),
                "poi", normalized.poi()
            );
        }
    }

    private record FailureGuidance(String code,
                                   String userMessage,
                                   List<String> suggestedVibes,
                                   List<String> suggestedActions) {
        private static FailureGuidance empty() {
            return new FailureGuidance(null, null, List.of(), List.of());
        }
    }

}
