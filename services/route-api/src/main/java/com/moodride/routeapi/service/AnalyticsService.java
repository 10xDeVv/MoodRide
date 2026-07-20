package com.moodride.routeapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.AnalyticsEvent;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeapi.dto.AnalyticsCountResponse;
import com.moodride.routeapi.dto.AnalyticsEventRequest;
import com.moodride.routeapi.dto.AnalyticsEventResponse;
import com.moodride.routeapi.dto.AnalyticsSummaryResponse;
import com.moodride.routeapi.repository.AnalyticsEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class AnalyticsService {

    private static final int MAX_METADATA_JSON_LENGTH = 4_000;
    private static final int MAX_METADATA_ITEMS = 50;
    private static final int MAX_METADATA_DEPTH = 4;
    private static final int MAX_METADATA_VALUE_LENGTH = 1_000;
    private static final int MAX_VIBES = 3;
    private static final Set<String> ALLOWED_VIBES = VibeCatalog.supportedVibes();
    private static final Set<String> ALLOWED_EVENT_NAMES = Set.of(
        "route_generate_clicked",
        "route_generate_submitted",
        "route_generation_primary_ready",
        "route_results_committed",
        "route_map_painted",
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
    private final String analyticsHashSecret;

    public AnalyticsService(AnalyticsEventRepository eventRepository,
                            JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper,
                            @Value("${moodride.analytics.hash-secret}") String analyticsHashSecret) {
        if (analyticsHashSecret == null || analyticsHashSecret.isBlank()) {
            throw new IllegalStateException("moodride.analytics.hash-secret must be configured");
        }
        this.eventRepository = eventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.analyticsHashSecret = analyticsHashSecret;
    }

    public AnalyticsEventResponse recordEvent(AnalyticsEventRequest request) {
        String eventName = normalizeEventName(request.eventName());
        List<String> normalizedVibes = normalizeAndValidateVibes(request.vibes());
        String anonymousClientHash = hashAnonymousClientId(request.anonymousClientId(), request.anonymousSessionId());
        String regionKey = normalizeRegionKey(request.regionKey());
        Integer timeBudgetBucket = bucketTimeBudget(request.timeBudgetMinutes());
        AnalyticsEvent event = new AnalyticsEvent();
        event.setAnonymousSessionId(anonymousClientHash);
        event.setAnonymousClientHash(anonymousClientHash);
        event.setEventName(eventName);
        event.setJobId(request.jobId());
        event.setRouteId(request.routeId());
        event.setRouteProfile(normalizeNullable(request.routeProfile()));
        event.setRouteMode(normalizeNullable(request.routeMode()));
        event.setVibesJson(serializeNullable(normalizedVibes));
        event.setTimeBudgetMinutes(request.timeBudgetMinutes());
        event.setTimeBudgetBucket(timeBudgetBucket);
        event.setRegionKey(regionKey);
        event.setRouteCount(request.routeCount());
        event.setStatus(normalizeNullable(request.status()));
        event.setDurationMs(request.durationMs());
        event.setScenicScore(request.scenicScore());
        event.setMetadataJson(serializeMetadata(request.metadata()));
        event.setCreatedAt(Instant.now());

        AnalyticsEvent saved = eventRepository.save(event);
        updateDailyRollups(saved, normalizedVibes);
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
        long uniqueAnonymousClients = distinctCount(from, to, "anonymous_client_hash");
        long finishedRoutes = completedRoutes + failedRoutes;

        double routeSuccessRate = finishedRoutes == 0 ? 0.0 : (double) completedRoutes / finishedRoutes;
        long completedWithRouteCount = countWhere(from, to, "event_name = 'route_generation_completed' AND route_count IS NOT NULL");
        long completedWithThreeOptions = countWhere(from, to, "event_name = 'route_generation_completed' AND route_count >= 3");
        double threeOptionRouteRate = completedWithRouteCount == 0 ? 0.0 : (double) completedWithThreeOptions / completedWithRouteCount;

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
            uniqueAnonymousClients,
            round4(routeSuccessRate),
            round2(avgNumber(from, to, "duration_ms", "event_name IN ('route_generation_completed', 'route_generation_failed')")),
            round2(percentileNumber(from, to, "duration_ms", "event_name IN ('route_generation_completed', 'route_generation_failed')", 0.95)),
            round2(avgNumber(from, to, "route_count", "event_name = 'route_generation_completed'")),
            round4(threeOptionRouteRate),
            round4(avgNumber(from, to, "scenic_score", "event_name = 'route_generation_completed'")),
            topVibes(from, to),
            groupedCounts(from, to, "route_profile", "event_name = 'route_option_selected'", 10),
            groupedCounts(from, to, "route_mode", "event_name IN ('route_generate_submitted', 'route_generation_completed')", 10),
            groupedCounts(from, to, "region_key", "event_name IN ('route_generate_submitted', 'route_generation_completed', 'vibe_unavailable')", 10),
            groupedCounts(from, to, "time_budget_bucket", "event_name IN ('route_generate_submitted', 'route_generation_completed', 'vibe_unavailable')", 10)
        );
    }

    private String hashAnonymousClientId(String anonymousClientId, String anonymousSessionId) {
        String source = anonymousClientId != null && !anonymousClientId.isBlank()
            ? anonymousClientId
            : anonymousSessionId;
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Anonymous client id is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(analyticsHashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(source.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                encoded.append(String.format("%02x", b));
            }
            return encoded.toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Anonymous client id could not be hashed", ex);
        }
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

    private List<String> normalizeAndValidateVibes(List<String> vibes) {
        if (vibes == null) {
            return null;
        }
        if (vibes.size() > MAX_VIBES) {
            throw new IllegalArgumentException("At most three vibes are allowed");
        }

        LinkedHashSet<String> normalizedVibes = new LinkedHashSet<>();
        for (String vibe : vibes) {
            String normalized = VibeCatalog.normalize(vibe);
            if (!ALLOWED_VIBES.contains(normalized)) {
                throw new IllegalArgumentException("Unsupported analytics vibe: " + vibe);
            }
            normalizedVibes.add(normalized);
        }
        return List.copyOf(normalizedVibes);
    }

    private String normalizeRegionKey(String regionKey) {
        String normalized = normalizeNullable(regionKey);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
    }

    private Integer bucketTimeBudget(Integer timeBudgetMinutes) {
        if (timeBudgetMinutes == null || timeBudgetMinutes <= 0) {
            return null;
        }
        int bucket = Math.max(15, ((timeBudgetMinutes + 7) / 15) * 15);
        return Math.min(bucket, 360);
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
        if (metadata == null) {
            return null;
        }
        validateMetadataValue(metadata, 1, new int[] { 0 });
        String json = serializeNullable(metadata);
        if (json.length() > MAX_METADATA_JSON_LENGTH) {
            throw new IllegalArgumentException("Analytics metadata exceeds maximum serialized size");
        }
        return json;
    }

    private void validateMetadataValue(Object value, int depth, int[] itemCount) {
        if (value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof CharSequence text) {
            validateMetadataValueLength(text.length());
            return;
        }
        if (value instanceof Number number) {
            validateMetadataValueLength(number.toString().length());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            validateMetadataDepth(depth);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                countMetadataItem(itemCount);
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Analytics metadata keys must be strings");
                }
                validateMetadataValueLength(key.length());
                validateMetadataValue(entry.getValue(), depth + 1, itemCount);
            }
            return;
        }
        if (value instanceof List<?> list) {
            validateMetadataDepth(depth);
            for (Object item : list) {
                countMetadataItem(itemCount);
                validateMetadataValue(item, depth + 1, itemCount);
            }
            return;
        }
        throw new IllegalArgumentException("Analytics metadata values must be JSON-compatible");
    }

    private void validateMetadataDepth(int depth) {
        if (depth > MAX_METADATA_DEPTH) {
            throw new IllegalArgumentException("Analytics metadata exceeds maximum nesting depth");
        }
    }

    private void validateMetadataValueLength(int length) {
        if (length > MAX_METADATA_VALUE_LENGTH) {
            throw new IllegalArgumentException("Analytics metadata value exceeds maximum size");
        }
    }

    private void countMetadataItem(int[] itemCount) {
        itemCount[0]++;
        if (itemCount[0] > MAX_METADATA_ITEMS) {
            throw new IllegalArgumentException("Analytics metadata contains too many items");
        }
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

    private long countWhere(Instant from, Instant to, String whereClause) {
        String sql = "SELECT COUNT(*) FROM analytics_events WHERE created_at >= ? AND created_at < ? AND " + whereClause;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0L : count;
    }

    private long distinctCount(Instant from, Instant to, String columnName) {
        String sql = "SELECT COUNT(DISTINCT " + columnName + ") FROM analytics_events " +
            "WHERE created_at >= ? AND created_at < ? AND " + columnName + " IS NOT NULL";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0L : count;
    }

    private double avgNumber(Instant from, Instant to, String columnName, String whereClause) {
        String sql = "SELECT COALESCE(AVG(" + columnName + "), 0) FROM analytics_events " +
            "WHERE created_at >= ? AND created_at < ? AND " + whereClause;
        Double value = jdbcTemplate.queryForObject(sql, Double.class, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0.0 : value;
    }

    private double percentileNumber(Instant from, Instant to, String columnName, String whereClause, double percentile) {
        String sql = "SELECT COALESCE(percentile_cont(?) WITHIN GROUP (ORDER BY " + columnName + "), 0) " +
            "FROM analytics_events WHERE created_at >= ? AND created_at < ? AND " + whereClause + " AND " + columnName + " IS NOT NULL";
        Double value = jdbcTemplate.queryForObject(sql, Double.class, percentile, Timestamp.from(from), Timestamp.from(to));
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

    private void updateDailyRollups(AnalyticsEvent event, List<String> vibes) {
        LocalDate day = event.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        String regionKey = event.getRegionKey() == null ? "unknown" : event.getRegionKey();
        String routeMode = event.getRouteMode() == null ? "unknown" : event.getRouteMode();
        int timeBudgetBucket = event.getTimeBudgetBucket() == null ? 0 : event.getTimeBudgetBucket();
        List<String> rollupVibes = vibes == null || vibes.isEmpty()
            ? List.of("unknown")
            : vibes.stream()
                .map(this::normalizeNullable)
                .filter(vibe -> vibe != null && !vibe.isBlank())
                .distinct()
                .toList();
        if (rollupVibes.isEmpty()) {
            rollupVibes = List.of("unknown");
        }

        for (String vibe : rollupVibes) {
            upsertDailyRollup(day, regionKey, routeMode, vibe, timeBudgetBucket, event);
        }
    }

    private void upsertDailyRollup(LocalDate day,
                                   String regionKey,
                                   String routeMode,
                                   String vibe,
                                   int timeBudgetBucket,
                                   AnalyticsEvent event) {
        long submitted = "route_generate_submitted".equals(event.getEventName()) ? 1L : 0L;
        long completed = "route_generation_completed".equals(event.getEventName()) ? 1L : 0L;
        long failed = "route_generation_failed".equals(event.getEventName()) ? 1L : 0L;
        long unavailable = "vibe_unavailable".equals(event.getEventName()) ? 1L : 0L;
        long startDrive = "start_drive_clicked".equals(event.getEventName()) ? 1L : 0L;
        long navigationOpen = "navigation_opened".equals(event.getEventName()) ? 1L : 0L;
        boolean firstLatencyMilestone = switch (event.getEventName()) {
            case "route_generation_primary_ready", "route_results_committed", "route_map_painted" -> true;
            default -> false;
        };
        double generationMsTotal = !firstLatencyMilestone && event.getDurationMs() != null ? event.getDurationMs() : 0.0;
        long generationMsCount = !firstLatencyMilestone && event.getDurationMs() != null ? 1L : 0L;
        long routeOptionsTotal = !firstLatencyMilestone && event.getRouteCount() != null
            ? event.getRouteCount()
            : 0L;
        long routeOptionsCount = !firstLatencyMilestone && event.getRouteCount() != null ? 1L : 0L;
        double scenicScoreTotal = !firstLatencyMilestone && event.getScenicScore() != null
            ? event.getScenicScore()
            : 0.0;
        long scenicScoreCount = !firstLatencyMilestone && event.getScenicScore() != null ? 1L : 0L;

        String sql = """
            INSERT INTO route_analytics_daily (
                day, region_key, route_mode, vibe, time_budget_bucket,
                event_count, submitted_count, completed_count, failed_count, vibe_unavailable_count,
                start_drive_count, navigation_open_count,
                generation_ms_total, generation_ms_count,
                route_options_total, route_options_count,
                scenic_score_total, scenic_score_count,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (day, region_key, route_mode, vibe, time_budget_bucket)
            DO UPDATE SET
                event_count = route_analytics_daily.event_count + 1,
                submitted_count = route_analytics_daily.submitted_count + EXCLUDED.submitted_count,
                completed_count = route_analytics_daily.completed_count + EXCLUDED.completed_count,
                failed_count = route_analytics_daily.failed_count + EXCLUDED.failed_count,
                vibe_unavailable_count = route_analytics_daily.vibe_unavailable_count + EXCLUDED.vibe_unavailable_count,
                start_drive_count = route_analytics_daily.start_drive_count + EXCLUDED.start_drive_count,
                navigation_open_count = route_analytics_daily.navigation_open_count + EXCLUDED.navigation_open_count,
                generation_ms_total = route_analytics_daily.generation_ms_total + EXCLUDED.generation_ms_total,
                generation_ms_count = route_analytics_daily.generation_ms_count + EXCLUDED.generation_ms_count,
                route_options_total = route_analytics_daily.route_options_total + EXCLUDED.route_options_total,
                route_options_count = route_analytics_daily.route_options_count + EXCLUDED.route_options_count,
                scenic_score_total = route_analytics_daily.scenic_score_total + EXCLUDED.scenic_score_total,
                scenic_score_count = route_analytics_daily.scenic_score_count + EXCLUDED.scenic_score_count,
                updated_at = NOW()
            """;
        jdbcTemplate.update(
            sql,
            day,
            regionKey,
            routeMode,
            vibe,
            timeBudgetBucket,
            submitted,
            completed,
            failed,
            unavailable,
            startDrive,
            navigationOpen,
            generationMsTotal,
            generationMsCount,
            routeOptionsTotal,
            routeOptionsCount,
            scenicScoreTotal,
            scenicScoreCount
        );
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
