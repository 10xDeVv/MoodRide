package com.moodride.routeapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.AnalyticsEvent;
import com.moodride.routeapi.dto.AnalyticsCountResponse;
import com.moodride.routeapi.dto.AnalyticsEventRequest;
import com.moodride.routeapi.dto.AnalyticsEventResponse;
import com.moodride.routeapi.dto.AnalyticsSummaryResponse;
import com.moodride.routeapi.repository.AnalyticsEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class AnalyticsService {

    private static final int MAX_METADATA_JSON_LENGTH = 4_000;
    private static final Set<String> ALLOWED_EVENT_NAMES = Set.of(
        "route_generate_clicked",
        "route_generate_submitted",
        "route_generation_completed",
        "route_generation_failed",
        "vibe_unavailable",
        "route_option_selected",
        "start_drive_clicked",
        "navigation_opened",
        "gpx_exported",
        "plan_new_route_clicked",
        "route_results_minimized",
        "route_results_viewed",
        "location_selected",
        "geolocate_clicked",
        "theme_toggled"
    );

    private final AnalyticsEventRepository eventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsService(AnalyticsEventRepository eventRepository,
                            JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public AnalyticsEventResponse recordEvent(AnalyticsEventRequest request) {
        String eventName = normalizeEventName(request.eventName());
        AnalyticsEvent event = new AnalyticsEvent();
        event.setAnonymousSessionId(request.anonymousSessionId().trim());
        event.setEventName(eventName);
        event.setJobId(request.jobId());
        event.setRouteId(request.routeId());
        event.setRouteProfile(normalizeNullable(request.routeProfile()));
        event.setRouteMode(normalizeNullable(request.routeMode()));
        event.setVibesJson(serializeNullable(request.vibes()));
        event.setTimeBudgetMinutes(request.timeBudgetMinutes());
        event.setRouteCount(request.routeCount());
        event.setStatus(normalizeNullable(request.status()));
        event.setDurationMs(request.durationMs());
        event.setScenicScore(request.scenicScore());
        event.setMetadataJson(serializeMetadata(request.metadata()));

        AnalyticsEvent saved = eventRepository.save(event);
        return new AnalyticsEventResponse(saved.getId(), saved.getEventName(), saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse getSummary(int days) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(boundedDays));

        long totalEvents = countEvents(from, to, null);
        long generateClicks = countEvents(from, to, "route_generate_clicked");
        long submittedRoutes = countEvents(from, to, "route_generate_submitted");
        long completedRoutes = countEvents(from, to, "route_generation_completed");
        long failedRoutes = countEvents(from, to, "route_generation_failed");
        long vibeUnavailableRoutes = countEvents(from, to, "vibe_unavailable");
        long startDriveClicks = countEvents(from, to, "start_drive_clicked");
        long navigationOpens = countEvents(from, to, "navigation_opened");
        long planNewRouteClicks = countEvents(from, to, "plan_new_route_clicked");
        long finishedRoutes = completedRoutes + failedRoutes;

        double routeSuccessRate = finishedRoutes == 0 ? 0.0 : (double) completedRoutes / finishedRoutes;

        return new AnalyticsSummaryResponse(
            from,
            to,
            totalEvents,
            generateClicks,
            submittedRoutes,
            completedRoutes,
            failedRoutes,
            vibeUnavailableRoutes,
            startDriveClicks,
            navigationOpens,
            planNewRouteClicks,
            round4(routeSuccessRate),
            round2(avgNumber(from, to, "duration_ms", "event_name IN ('route_generation_completed', 'route_generation_failed')")),
            round2(avgNumber(from, to, "route_count", "event_name = 'route_generation_completed'")),
            round4(avgNumber(from, to, "scenic_score", "event_name = 'route_generation_completed'")),
            topVibes(from, to),
            groupedCounts(from, to, "route_profile", "event_name = 'route_option_selected'", 10),
            groupedCounts(from, to, "route_mode", "event_name IN ('route_generate_submitted', 'route_generation_completed')", 10)
        );
    }

    private String normalizeEventName(String eventName) {
        String normalized = eventName.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_EVENT_NAMES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported analytics event: " + eventName);
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String serializeNullable(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Analytics payload could not be serialized", ex);
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        String json = serializeNullable(metadata);
        if (json == null) {
            return null;
        }
        return json.length() <= MAX_METADATA_JSON_LENGTH ? json : json.substring(0, MAX_METADATA_JSON_LENGTH);
    }

    private long countEvents(Instant from, Instant to, String eventName) {
        Object[] args = eventName == null
            ? new Object[] { Timestamp.from(from), Timestamp.from(to) }
            : new Object[] { Timestamp.from(from), Timestamp.from(to), eventName };
        String sql = eventName == null
            ? "SELECT COUNT(*) FROM analytics_events WHERE created_at >= ? AND created_at < ?"
            : "SELECT COUNT(*) FROM analytics_events WHERE created_at >= ? AND created_at < ? AND event_name = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private double avgNumber(Instant from, Instant to, String columnName, String whereClause) {
        String sql = "SELECT COALESCE(AVG(" + columnName + "), 0) FROM analytics_events " +
            "WHERE created_at >= ? AND created_at < ? AND " + whereClause;
        Double value = jdbcTemplate.queryForObject(sql, Double.class, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0.0 : value;
    }

    private List<AnalyticsCountResponse> groupedCounts(Instant from,
                                                       Instant to,
                                                       String columnName,
                                                       String whereClause,
                                                       int limit) {
        String sql = "SELECT " + columnName + " AS name, COUNT(*) AS count FROM analytics_events " +
            "WHERE created_at >= ? AND created_at < ? AND " + whereClause + " AND " + columnName + " IS NOT NULL " +
            "GROUP BY " + columnName + " ORDER BY count DESC, name ASC LIMIT ?";
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new AnalyticsCountResponse(rs.getString("name"), rs.getLong("count")),
            Timestamp.from(from),
            Timestamp.from(to),
            limit
        );
    }

    private List<AnalyticsCountResponse> topVibes(Instant from, Instant to) {
        String sql = """
            SELECT vibe AS name, COUNT(*) AS count
            FROM analytics_events e
            CROSS JOIN LATERAL jsonb_array_elements_text(e.vibes_json::jsonb) AS vibes(vibe)
            WHERE e.created_at >= ?
              AND e.created_at < ?
              AND e.event_name IN ('route_generate_submitted', 'route_generation_completed')
              AND e.vibes_json IS NOT NULL
            GROUP BY vibe
            ORDER BY count DESC, vibe ASC
            LIMIT 10
            """;
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new AnalyticsCountResponse(rs.getString("name"), rs.getLong("count")),
            Timestamp.from(from),
            Timestamp.from(to)
        );
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
