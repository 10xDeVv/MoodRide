package com.moodride.routeapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeapi.dto.PrimaryRouteOptionResponse;
import com.moodride.routeapi.dto.PrimaryRouteResponse;
import com.moodride.routeapi.exception.RouteNotFoundException;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PrimaryRouteService {

    private static final Set<String> ALLOWED_VIBES = VibeCatalog.supportedVibes();

    private final RouteRepository routeRepository;
    private final RouteJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public PrimaryRouteService(RouteRepository routeRepository,
                               RouteJobRepository jobRepository,
                               ObjectMapper objectMapper) {
        this.routeRepository = routeRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    public PrimaryRouteResponse getPrimaryRoute(UUID routeId) {
        Route route = routeRepository.findById(routeId)
            .orElseThrow(() -> new RouteNotFoundException(routeId));

        UUID jobId = route.getJobId();
        if (jobId == null) {
            throw new RouteNotFoundException(routeId);
        }

        RouteJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RouteNotFoundException(routeId));
        if (!isPublishedPrimary(routeId, route, job)) {
            throw new RouteNotFoundException(routeId);
        }

        List<List<Double>> coordinates = persistedCoordinates(routeId, route.getGeometry());
        List<PrimaryRouteOptionResponse> routeOptions = routeRepository.findOptionSummariesByJobId(jobId).stream()
            .map(this::toResponse)
            .toList();

        return new PrimaryRouteResponse(
            route.getId(),
            job.getId(),
            routeUrl(route.getId()),
            route.getRouteProfile(),
            route.getScenicScore() * 100.0,
            route.getTotalDistanceKm(),
            route.getEstimatedDurationMinutes(),
            job.getTimeBudgetMinutes(),
            job.getRouteMode().apiValue(),
            job.getStartLatitude(),
            job.getStartLongitude(),
            resolveVibes(job, route),
            geoJsonFeature(coordinates),
            routeOptions,
            resolveAlgorithmVersion(job),
            computationTimeMs(job),
            job.getOptionRevision(),
            job.getOptionCount(),
            job.isOptionsComplete(),
            route.getGeneratedAt(),
            route.getExpiresAt()
        );
    }

    private boolean isPublishedPrimary(UUID routeId, Route route, RouteJob job) {
        RouteJob.JobStatus status = job.getStatus();
        boolean published = status == RouteJob.JobStatus.PRIMARY_READY
            || status == RouteJob.JobStatus.COMPLETED;
        return published
            && routeId.equals(route.getId())
            && routeId.equals(job.getRouteId())
            && route.getJobId().equals(job.getId());
    }

    private List<List<Double>> persistedCoordinates(UUID routeId, LineString geometry) {
        if (geometry == null || geometry.isEmpty() || geometry.getNumPoints() < 2) {
            throw new RouteNotFoundException(routeId);
        }

        List<List<Double>> coordinates = new ArrayList<>(geometry.getNumPoints());
        for (int index = 0; index < geometry.getNumPoints(); index++) {
            Coordinate coordinate = geometry.getCoordinateN(index);
            coordinates.add(List.of(coordinate.getX(), coordinate.getY()));
        }
        return List.copyOf(coordinates);
    }

    private Map<String, Object> geoJsonFeature(List<List<Double>> coordinates) {
        Map<String, Object> lineString = Map.of(
            "type", "LineString",
            "coordinates", coordinates
        );
        return Map.of(
            "type", "Feature",
            "geometry", lineString,
            "properties", Map.of()
        );
    }

    private PrimaryRouteOptionResponse toResponse(RouteRepository.RouteOptionSummary summary) {
        return new PrimaryRouteOptionResponse(
            summary.getProfile(),
            summary.getRouteId(),
            routeUrl(summary.getRouteId()),
            summary.getScenicScore() * 100.0,
            summary.getTotalDistanceKm(),
            summary.getEstimatedDurationMinutes()
        );
    }

    private String routeUrl(UUID routeId) {
        return "/routes/route/" + routeId;
    }

    private String resolveAlgorithmVersion(RouteJob job) {
        String algorithmVersion = job.getAlgorithmVersion();
        return algorithmVersion == null || algorithmVersion.isBlank() ? "unknown" : algorithmVersion;
    }

    private Integer computationTimeMs(RouteJob job) {
        Instant startedAt = job.getStartedAt();
        Instant primaryCommittedAt = job.getPrimaryReadyAt();
        if (primaryCommittedAt == null && job.getStatus() == RouteJob.JobStatus.COMPLETED) {
            primaryCommittedAt = job.getCompletedAt();
        }
        if (startedAt == null || primaryCommittedAt == null) {
            return null;
        }
        return Math.toIntExact(Duration.between(startedAt, primaryCommittedAt).toMillis());
    }

    private List<String> resolveVibes(RouteJob job, Route route) {
        List<String> parsed = parseVibesJson(job.getVibesJson());
        if (!parsed.isEmpty()) {
            return parsed;
        }
        if (job.getVibe() != null && !job.getVibe().isBlank()) {
            return List.of(job.getVibe());
        }
        if (route.getVibe() != null && !route.getVibe().isBlank()) {
            return List.of(route.getVibe());
        }
        return List.of("countryside");
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
                String vibe = VibeCatalog.normalize(value);
                if (ALLOWED_VIBES.contains(vibe) && seen.add(vibe)) {
                    normalized.add(vibe);
                }
            }
            return List.copyOf(normalized);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
