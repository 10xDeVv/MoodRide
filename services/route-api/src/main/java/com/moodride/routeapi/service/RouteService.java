package com.moodride.routeapi.service;

import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.eventmodels.DriveCompletedEvent;
import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.eventmodels.RouteRatedEvent;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.dto.RouteJobStatusResponse;
import com.moodride.routeapi.dto.RouteOptionResponse;
import com.moodride.routeapi.dto.RouteRatingRequest;
import com.moodride.routeapi.dto.RouteRatingResponse;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteSubmissionResponse;
import com.moodride.routeapi.exception.JobNotFoundException;
import com.moodride.routeapi.exception.RouteNotFoundException;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;

@Service
@Transactional
public class RouteService {

    private static final Set<String> ALLOWED_VIBES = Set.of(
        "coastal",
        "mountain",
        "countryside",
        "forest",
        "open_roads",
        "riverside"
    );

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
    
    private final RouteJobRepository jobRepository;
    private final RouteRepository routeRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public RouteService(RouteJobRepository jobRepository, RouteRepository routeRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }
    
    public RouteSubmissionResponse submitRoute(RouteRequest request) {
        List<String> resolvedVibes = normalizeAndValidateVibes(request.resolvedVibes());

        RouteJob job = new RouteJob(
            request.userId(),
            request.lat(),
            request.lng(),
            request.timeBudgetMinutes(),
            resolvedVibes.getFirst()
        );
        job.setStatus(RouteJob.JobStatus.QUEUED);
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
        List<RouteOptionResponse> routeOptions = buildRouteOptions(jobRoutes);

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
            job.getMaxRetries()
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

        return new RouteRatingResponse(route.getId(), rating, ratedAt);
    }

    private RouteDetailResponse mapRouteToDetail(Route route) {
        RouteJob routeJob = jobRepository.findById(route.getJobId()).orElse(null);
        List<RouteOptionResponse> routeOptions = buildRouteOptions(
            routeRepository.findByJobIdOrderByGeneratedAtAsc(route.getJobId())
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
            waypoints.isEmpty() ? 0.0 : waypoints.getFirst().getLatitude(),
            waypoints.isEmpty() ? 0.0 : waypoints.getFirst().getLongitude(),
            List.of(route.getVibe()),
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

    private List<RouteOptionResponse> buildRouteOptions(List<Route> routes) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        List<Route> chronologicallyOrdered = routes.stream()
            .sorted(Comparator.comparing(Route::getGeneratedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        List<Route> profileOrdered = orderByProfileWithFallback(chronologicallyOrdered);

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
                route.getEstimatedDurationMinutes()
            ));
        }

        return List.copyOf(options);
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

    private String normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            throw new IllegalArgumentException("Vibe cannot be empty");
        }
        return vibe.trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
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
        for (Map.Entry<String, Object> entry : preferenceVector.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String normalizedKey = normalizePreferenceKey(entry.getKey());
            if (normalizedKey == null) {
                continue;
            }

            Double normalizedValue = parsePreferenceValue(entry.getValue());
            if (normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        }

        return Map.copyOf(normalized);
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

}
