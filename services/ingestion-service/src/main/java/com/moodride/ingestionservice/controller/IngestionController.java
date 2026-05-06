package com.moodride.ingestionservice.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moodride.ingestionservice.dto.ScenicScoringRequest;
import com.moodride.ingestionservice.dto.TrafficRefreshRequest;
import com.moodride.ingestionservice.elevation.OpenTopoDataClient;
import com.moodride.ingestionservice.elevation.OpenTopoDataProperties;
import com.moodride.ingestionservice.elevation.RoadSegmentElevationEnrichmentService;
import com.moodride.ingestionservice.service.OsmBulkLoadService;
import com.moodride.ingestionservice.service.TrafficRefreshEventPublisher;

/**
 * REST controller for managing ingestion and scoring jobs.
 */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private static final int MAX_SCENIC_TARGET_H3_INDEXES = 1000;
    private static final int DEFAULT_SCENIC_AUTO_TARGET_COUNT = 25;
    private static final int MAX_SCENIC_AUTO_TARGET_COUNT = 250;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("scenicScoringJob")
    private Job scenicScoringJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RoadSegmentElevationEnrichmentService roadSegmentElevationEnrichmentService;

    @Autowired
    private OpenTopoDataProperties openTopoDataProperties;

    @Autowired
    private OpenTopoDataClient openTopoDataClient;

    @Autowired
    private TrafficRefreshEventPublisher trafficRefreshEventPublisher;

    @Autowired
    private OsmBulkLoadService osmBulkLoadService;

    /**
     * Trigger OSM data ingestion job (Phase 1).
     * Converts planet_osm_line data to road_segments.
     *
     * POST /api/ingestion/jobs/osm-ingest
     */
    @PostMapping("/jobs/osm-ingest")
    public ResponseEntity<Map<String, Object>> triggerOsmIngestion() {
        try {
            OsmBulkLoadService.BulkLoadRun run = osmBulkLoadService.startBulkLoad();

            Map<String, Object> response = new HashMap<>();
            response.put("jobId", run.jobId());
            response.put("status", run.status());
            response.put("startTime", run.startedAt());
            response.put("mode", "database-side-native-h3");
            response.put("message", run.message());

            return ResponseEntity.accepted().body(response);
        } catch (IllegalStateException ex) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "REJECTED");
            response.put("message", ex.getMessage());
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(409).body(response);
        }
    }

    /**
     * Fetch Phase 1 bulk load status by execution id.
     *
     * GET /api/ingestion/jobs/osm-ingest/{jobId}
     */
    @GetMapping("/jobs/osm-ingest/{jobId}")
    public ResponseEntity<Map<String, Object>> getOsmIngestionStatus(@PathVariable Long jobId) {
        OsmBulkLoadService.BulkLoadRun run = osmBulkLoadService.getRun(jobId);
        if (run == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "NOT_FOUND");
            response.put("message", "No Phase 1 run found for id " + jobId);
            response.put("jobId", jobId);
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(404).body(response);
        }

        return ResponseEntity.ok(toPhase1RunResponse(run));
    }

    /**
     * Fetch scenic scoring job execution details by execution id.
     *
     * GET /api/ingestion/jobs/scenic-score/{executionId}
     */
    @GetMapping("/jobs/scenic-score/{executionId}")
    public ResponseEntity<Map<String, Object>> getScenicScoringExecution(@PathVariable Long executionId) {
        String summary;
        Map<Long, String> stepSummaries;
        try {
            summary = jobOperator.getSummary(executionId);
            stepSummaries = jobOperator.getStepExecutionSummaries(executionId);
        } catch (Exception ex) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "NOT_FOUND");
            response.put("message", "No job execution found for id " + executionId);
            response.put("executionId", executionId);
            response.put("error", ex.getMessage());
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(404).body(response);
        }

        if (summary == null || summary.isBlank()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "NOT_FOUND");
            response.put("message", "No job summary found for id " + executionId);
            response.put("executionId", executionId);
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(404).body(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("executionId", executionId);
        response.put("status", extractToken(summary, "status"));
        response.put("batchStatus", extractToken(summary, "status"));
        response.put("exitCode", extractToken(summary, "exitStatus"));
        response.put("summary", summary);
        response.put("stepSummaries", stepSummaries);
        response.put("stepStats", parseStepStats(stepSummaries));
        response.put("isScenicExecution", summary.contains("jobName=" + scenicScoringJob.getName()));
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Trigger scenic scoring job (Phase 2).
     * Computes scenic scores for all H3 tiles.
     *
     * POST /api/ingestion/jobs/scenic-score
     */
    @PostMapping("/jobs/scenic-score")
    public ResponseEntity<Map<String, Object>> triggerScenicScoring(@RequestBody(required = false) ScenicScoringRequest request) throws Exception {
        Set<Long> runningExecutions = jobOperator.getRunningExecutions(scenicScoringJob.getName());
        if (!runningExecutions.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "REJECTED");
            response.put("message", "Scenic scoring job is already running. Wait for completion before starting another run.");
            response.put("runningExecutions", runningExecutions);
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(409).body(response);
        }

        List<String> targetH3Indexes = sanitizeTargetH3Indexes(request == null ? null : request.h3Indexes());
        boolean autoSelectionRequested = isAutoSelectionRequested(request);
        int requestedMaxTiles = sanitizeMaxTiles(request == null ? null : request.maxTiles());
        boolean onlyUnscored = request == null || request.onlyUnscored() == null || request.onlyUnscored();
        String startAfterH3 = request == null ? null : sanitizeStartAfterH3(request.startAfterH3());

        if (targetH3Indexes.isEmpty() && autoSelectionRequested) {
            targetH3Indexes = selectAutoTargetH3Indexes(requestedMaxTiles, onlyUnscored, startAfterH3);
            if (targetH3Indexes.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "NOOP");
                response.put("message", "No H3 tiles matched the auto-selection filters.");
                response.put("targetH3Count", 0);
                response.put("onlyUnscored", onlyUnscored);
                response.put("requestedMaxTiles", requestedMaxTiles);
                response.put("startAfterH3", startAfterH3);
                response.put("timestamp", Instant.now().toString());
                return ResponseEntity.ok(response);
            }
        }

        JobParametersBuilder parametersBuilder = new JobParametersBuilder()
                .addString("timestamp", String.valueOf(Instant.now().toEpochMilli()));

        if (!targetH3Indexes.isEmpty()) {
            parametersBuilder.addString("targetH3Csv", String.join(",", targetH3Indexes));
        }

        JobParameters jobParameters = parametersBuilder.toJobParameters();
        JobExecution execution = jobLauncher.run(scenicScoringJob, jobParameters);

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", execution.getId());
        response.put("status", execution.getStatus());
        response.put("batchStatus", execution.getStatus());
        response.put("startTime", execution.getStartTime());
        response.put("targetH3Count", targetH3Indexes.size());
        response.put("selectionMode", targetH3Indexes.isEmpty() ? "all-tiles" : "targeted-subset");
        response.put("onlyUnscored", targetH3Indexes.isEmpty() ? null : onlyUnscored);
        response.put("requestedMaxTiles", targetH3Indexes.isEmpty() ? null : requestedMaxTiles);
        response.put("startAfterH3", targetH3Indexes.isEmpty() ? null : startAfterH3);
        response.put("targetFirstH3", targetH3Indexes.isEmpty() ? null : targetH3Indexes.get(0));
        response.put("targetLastH3", targetH3Indexes.isEmpty() ? null : targetH3Indexes.get(targetH3Indexes.size() - 1));
        response.put("stepStats", summarizeStepExecutions(execution));
        response.put(
                "message",
                targetH3Indexes.isEmpty()
                        ? "Scenic scoring job started. Computing scores for all H3 tiles."
                        : "Scenic scoring job started for requested H3 tile subset."
        );

        return ResponseEntity.accepted().body(response);
    }

    private List<String> sanitizeTargetH3Indexes(List<String> candidateIndexes) {
        if (candidateIndexes == null || candidateIndexes.isEmpty()) {
            return List.of();
        }

        List<String> sanitized = candidateIndexes.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .limit(MAX_SCENIC_TARGET_H3_INDEXES)
                .toList();

        return sanitized;
    }

    private boolean isAutoSelectionRequested(ScenicScoringRequest request) {
        if (request == null) {
            return false;
        }
        return request.maxTiles() != null
                || request.onlyUnscored() != null
                || request.startAfterH3() != null;
    }

    private int sanitizeMaxTiles(Integer value) {
        if (value == null) {
            return DEFAULT_SCENIC_AUTO_TARGET_COUNT;
        }
        if (value <= 0) {
            return DEFAULT_SCENIC_AUTO_TARGET_COUNT;
        }
        return Math.min(value, MAX_SCENIC_AUTO_TARGET_COUNT);
    }

    private String sanitizeStartAfterH3(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> selectAutoTargetH3Indexes(int maxTiles, boolean onlyUnscored, String startAfterH3) {
        StringBuilder sql = new StringBuilder("""
                SELECT rs.h3_tile_index
                FROM road_segments rs
                LEFT JOIN scenic_score_tiles s ON s.h3_index = rs.h3_tile_index
                WHERE 1 = 1
                """);

        List<Object> args = new ArrayList<>();
        if (startAfterH3 != null) {
            sql.append(" AND rs.h3_tile_index > ?\n");
            args.add(startAfterH3);
        }
        if (onlyUnscored) {
            sql.append(" AND s.h3_index IS NULL\n");
        }

        sql.append("""
                GROUP BY rs.h3_tile_index
                ORDER BY rs.h3_tile_index
                LIMIT ?
                """);
        args.add(maxTiles);

        return jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
    }

    private List<Map<String, Object>> summarizeStepExecutions(JobExecution execution) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (StepExecution stepExecution : execution.getStepExecutions()) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("stepName", stepExecution.getStepName());
            summary.put("status", stepExecution.getStatus());
            summary.put("readCount", stepExecution.getReadCount());
            summary.put("writeCount", stepExecution.getWriteCount());
            summary.put("commitCount", stepExecution.getCommitCount());
            summary.put("rollbackCount", stepExecution.getRollbackCount());
            summary.put("readSkipCount", stepExecution.getReadSkipCount());
            summary.put("processSkipCount", stepExecution.getProcessSkipCount());
            summary.put("writeSkipCount", stepExecution.getWriteSkipCount());
            summary.put("exitCode", stepExecution.getExitStatus().getExitCode());
            summary.put("exitDescription", stepExecution.getExitStatus().getExitDescription());
            summaries.add(summary);
        }
        return summaries;
    }

    private List<Map<String, Object>> parseStepStats(Map<Long, String> stepSummaries) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        if (stepSummaries == null || stepSummaries.isEmpty()) {
            return parsed;
        }

        for (Map.Entry<Long, String> entry : stepSummaries.entrySet()) {
            String raw = entry.getValue();
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepExecutionId", entry.getKey());
            step.put("stepName", extractToken(raw, "stepName"));
            step.put("status", extractToken(raw, "status"));
            step.put("readCount", extractIntegerToken(raw, "readCount"));
            step.put("writeCount", extractIntegerToken(raw, "writeCount"));
            step.put("rollbackCount", extractIntegerToken(raw, "rollbackCount"));
            step.put("commitCount", extractIntegerToken(raw, "commitCount"));
            step.put("readSkipCount", extractIntegerToken(raw, "readSkipCount"));
            step.put("processSkipCount", extractIntegerToken(raw, "processSkipCount"));
            step.put("writeSkipCount", extractIntegerToken(raw, "writeSkipCount"));
            step.put("raw", raw);
            parsed.add(step);
        }
        return parsed;
    }

    private String extractToken(String source, String key) {
        if (source == null || source.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String marker = key + "=";
        int start = source.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int endComma = source.indexOf(',', start);
        int endSemicolon = source.indexOf(';', start);
        int end;
        if (endComma < 0 && endSemicolon < 0) {
            end = source.length();
        } else if (endComma < 0) {
            end = endSemicolon;
        } else if (endSemicolon < 0) {
            end = endComma;
        } else {
            end = Math.min(endComma, endSemicolon);
        }
        return source.substring(start, end).trim();
    }

    private Integer extractIntegerToken(String source, String key) {
        String value = extractToken(source, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Trigger elevation enrichment pass (OpenTopoData-backed).
     *
     * POST /api/ingestion/jobs/elevation-enrich
     */
    @PostMapping("/jobs/elevation-enrich")
    public ResponseEntity<Map<String, Object>> triggerElevationEnrichment() {
        int updated = roadSegmentElevationEnrichmentService.enrichMissingElevation();

        Map<String, Object> response = new HashMap<>();
        response.put("updatedSegments", updated);
        response.put("openTopoEnabled", openTopoDataProperties.isEnabled());
        response.put("dataset", openTopoDataProperties.getDataset());
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Seed optional traffic signals from road topology heuristics.
     *
     * POST /api/ingestion/jobs/traffic-seed
     */
    @PostMapping("/jobs/traffic-seed")
    public ResponseEntity<Map<String, Object>> triggerTrafficSeed() {
        String sql = """
                WITH computed AS (
                    SELECT
                        h3_tile_index AS h3_index,
                        GREATEST(
                            0.0,
                            LEAST(
                                1.0,
                                0.65 * (COUNT(*) FILTER (WHERE road_type IN ('residential', 'living_street', 'service'))::double precision / NULLIF(COUNT(*), 0))
                                + 0.35 * (1.0 - (COUNT(*) FILTER (WHERE road_type IN ('motorway', 'trunk', 'primary'))::double precision / NULLIF(COUNT(*), 0)))
                            )
                        ) AS traffic_score,
                        'heuristic-seed' AS provider
                    FROM road_segments
                    GROUP BY h3_tile_index
                ),
                upserted AS (
                    INSERT INTO traffic_tile_signals (h3_index, traffic_score, provider, last_updated)
                    SELECT h3_index, traffic_score, provider, CURRENT_TIMESTAMP
                    FROM computed
                    ON CONFLICT (h3_index) DO UPDATE SET
                        traffic_score = EXCLUDED.traffic_score,
                        provider = EXCLUDED.provider,
                        last_updated = EXCLUDED.last_updated
                    WHERE traffic_tile_signals.traffic_score IS DISTINCT FROM EXCLUDED.traffic_score
                       OR traffic_tile_signals.provider IS DISTINCT FROM EXCLUDED.provider
                    RETURNING h3_index
                )
                SELECT h3_index FROM upserted
                """;

        List<String> affectedTiles = jdbcTemplate.queryForList(sql, String.class);
        String eventId = trafficRefreshEventPublisher.publishTrafficTilesUpdated("traffic-seed", affectedTiles);

        Map<String, Object> response = new HashMap<>();
        response.put("affectedRows", affectedTiles.size());
        response.put("affectedTiles", affectedTiles);
        response.put("eventId", eventId);
        response.put("provider", "heuristic-seed");
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Publish a refresh request for provider/CSV traffic updates.
     *
     * POST /api/ingestion/jobs/traffic-refresh
     */
    @PostMapping("/jobs/traffic-refresh")
    public ResponseEntity<Map<String, Object>> triggerTrafficRefresh(@RequestBody TrafficRefreshRequest request) {
        List<String> tiles = request == null || request.h3Indexes() == null
                ? List.of()
                : request.h3Indexes().stream().filter(v -> v != null && !v.isBlank()).toList();
        String source = request == null || request.source() == null || request.source().isBlank()
                ? "traffic-provider"
                : request.source();

        String eventId = trafficRefreshEventPublisher.publishTrafficTilesUpdated(source, new ArrayList<>(tiles));

        Map<String, Object> response = new HashMap<>();
        response.put("eventId", eventId);
        response.put("source", source);
        response.put("tileCount", tiles.size());
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Phase 1 readiness snapshot.
     *
     * GET /api/ingestion/phase1/status
     */
    @GetMapping("/phase1/status")
    public ResponseEntity<Map<String, Object>> getPhase1Status() {
        Map<String, Object> response = new HashMap<>();

        boolean roadSegmentsTable = tableExists("road_segments");
        long roadSegmentRows = roadSegmentsTable ? countRows("road_segments") : 0L;

        OsmBulkLoadService.BulkLoadRun latestRun = osmBulkLoadService.getLatestRun();

        response.put("road_segments_exists", roadSegmentsTable);
        response.put("road_segments_rows", roadSegmentRows);
        response.put("road_segments_ready", roadSegmentsTable && roadSegmentRows > 0);
        response.put("bulk_load_latest_run", latestRun == null ? null : toPhase1RunResponse(latestRun));
        response.put("bulk_load_mode", latestRun == null ? "not-started" : "database-side-native-h3");
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Get ingestion service health/stats.
     *
     * GET /api/ingestion/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "ingestion-service");
        response.put("status", "UP");
        response.put("port", 8086);
        response.put("phase1_enabled", true);  // OSM ingestion
        response.put("phase2_enabled", true);  // Scenic scoring

        return ResponseEntity.ok(response);
    }

    /**
     * Phase 2 source readiness snapshot.
     *
     * GET /api/ingestion/phase2/status
     */
    @GetMapping("/phase2/status")
    public ResponseEntity<Map<String, Object>> getPhase2Status() {
        Map<String, Object> response = new HashMap<>();

        boolean scenicTilesTable = tableExists("scenic_score_tiles");
        long scenicTilesRows = scenicTilesTable ? countRows("scenic_score_tiles") : 0L;

        boolean waterSummaryTable = tableExists("water_tile_summary");
        long waterSummaryRows = waterSummaryTable ? countRows("water_tile_summary") : 0L;

        boolean poiSummaryTable = tableExists("poi_tile_summary");
        long poiSummaryRows = poiSummaryTable ? countRows("poi_tile_summary") : 0L;

        boolean landuseSummaryTable = tableExists("landuse_tile_summary");
        long landuseSummaryRows = landuseSummaryTable ? countRows("landuse_tile_summary") : 0L;

        boolean nlcdTable = tableExists("nlcd_land_cover_cells");
        long nlcdRows = nlcdTable ? countRows("nlcd_land_cover_cells") : 0L;

        boolean osmPolygon = tableExists("planet_osm_polygon");
        boolean osmPoint = tableExists("planet_osm_point");
        long osmPolygonRows = osmPolygon ? countRows("planet_osm_polygon") : 0L;
        long osmPointRows = osmPoint ? countRows("planet_osm_point") : 0L;

        String naturalEarthTable = null;
        if (tableExists("natural_earth_Water_Bodies")) {
            naturalEarthTable = "natural_earth_Water_Bodies";
        } else if (tableExists("natural_earth_water_bodies")) {
            naturalEarthTable = "natural_earth_water_bodies";
        }
        boolean naturalEarthWaterTable = naturalEarthTable != null;
        long naturalEarthWaterRows = naturalEarthWaterTable ? countRowsQuoted(naturalEarthTable) : 0L;

        boolean trafficTable = tableExists("traffic_tile_signals");
        long trafficRows = trafficTable ? countRows("traffic_tile_signals") : 0L;
        Map<String, Long> trafficProviderBreakdown = trafficTable ? loadTrafficProviderBreakdown() : Map.of();
        long providerFedTrafficRows = trafficProviderBreakdown.entrySet().stream()
                .filter(entry -> !"heuristic-seed".equalsIgnoreCase(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        boolean trafficProviderReady = trafficTable && providerFedTrafficRows > 0;
        String trafficMode = trafficRows == 0
                ? "unavailable"
                : (trafficProviderReady ? "provider-fed" : "heuristic-fallback");

        response.put("nlcd_table_exists", nlcdTable);
        response.put("nlcd_rows", nlcdRows);
        response.put("nlcd_ready", nlcdTable && nlcdRows > 0);

        response.put("osm_polygon_exists", osmPolygon);
        response.put("osm_polygon_rows", osmPolygonRows);
        response.put("osm_point_exists", osmPoint);
        response.put("osm_point_rows", osmPointRows);

        response.put("natural_earth_water_table", naturalEarthTable);
        response.put("natural_earth_water_table_exists", naturalEarthWaterTable);
        response.put("natural_earth_water_rows", naturalEarthWaterRows);
        response.put("natural_earth_water_ready", naturalEarthWaterTable && naturalEarthWaterRows > 0);

        response.put("scenic_score_tiles_exists", scenicTilesTable);
        response.put("scenic_score_tiles_rows", scenicTilesRows);
        response.put("scenic_score_tiles_ready", scenicTilesTable && scenicTilesRows > 0);

        response.put("water_tile_summary_exists", waterSummaryTable);
        response.put("water_tile_summary_rows", waterSummaryRows);
        response.put("water_tile_summary_ready", waterSummaryTable && waterSummaryRows > 0);

        response.put("poi_tile_summary_exists", poiSummaryTable);
        response.put("poi_tile_summary_rows", poiSummaryRows);
        response.put("poi_tile_summary_ready", poiSummaryTable && poiSummaryRows > 0);

        response.put("landuse_tile_summary_exists", landuseSummaryTable);
        response.put("landuse_tile_summary_rows", landuseSummaryRows);
        response.put("landuse_tile_summary_ready", landuseSummaryTable && landuseSummaryRows > 0);

        response.put("phase2_summary_tables_ready",
            waterSummaryTable && waterSummaryRows > 0
                && poiSummaryTable && poiSummaryRows > 0
                && landuseSummaryTable && landuseSummaryRows > 0);

        response.put("traffic_table_exists", trafficTable);
        response.put("traffic_rows", trafficRows);
        response.put("traffic_ready", trafficTable && trafficRows > 0);
        response.put("traffic_provider_breakdown", trafficProviderBreakdown);
        response.put("traffic_provider_rows", providerFedTrafficRows);
        response.put("traffic_provider_ready", trafficProviderReady);
        response.put("traffic_mode", trafficMode);

        response.put("opentopo_enabled", openTopoDataProperties.isEnabled());
        response.put("opentopo_base_url", openTopoDataProperties.getBaseUrl());
        response.put("opentopo_dataset", openTopoDataProperties.getDataset());
        response.put("opentopo_reachable", openTopoDataClient.isServiceReachable());
        response.put("opentopo_dataset_available", openTopoDataClient.isDatasetAvailable());

        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    private boolean tableExists(String tableName) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = ?
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, tableName);
        return Boolean.TRUE.equals(exists);
    }

    private long countRows(String tableName) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private long countRowsQuoted(String tableName) {
        String sql = "SELECT COUNT(*) FROM \"" + tableName.replace("\"", "\"\"") + "\"";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private Map<String, Object> toPhase1RunResponse(OsmBulkLoadService.BulkLoadRun run) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", run.jobId());
        response.put("status", run.status());
        response.put("startedAt", run.startedAt());
        response.put("completedAt", run.completedAt());
        response.put("nativeH3ExtensionAvailable", run.nativeH3ExtensionAvailable());
        response.put("rowsInserted", run.rowsInserted());
        response.put("h3RowsUpdated", run.h3RowsUpdated());
        response.put("message", run.message());
        response.put("error", run.error());
        return response;
    }

    private Map<String, Long> loadTrafficProviderBreakdown() {
        String sql = """
                SELECT COALESCE(provider, 'unknown') AS provider, COUNT(*) AS cnt
                FROM traffic_tile_signals
                GROUP BY COALESCE(provider, 'unknown')
                ORDER BY COUNT(*) DESC
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return rows.stream().collect(Collectors.toMap(
                row -> String.valueOf(row.get("provider")),
                row -> {
                    Object value = row.get("cnt");
                    if (value instanceof Number number) {
                        return number.longValue();
                    }
                    return 0L;
                },
                Long::sum,
                java.util.LinkedHashMap::new
        ));
    }
}
