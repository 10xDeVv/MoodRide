package com.moodride.ingestionservice.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

/**
 * Phase 1 bulk loader that runs entirely in PostgreSQL/PostGIS using native H3 SQL.
 */
@Service
public class OsmBulkLoadService {

    private static final Logger log = LoggerFactory.getLogger(OsmBulkLoadService.class);
    private static final String ROAD_TYPE_CASE = """
            CASE road_type
                WHEN 'motorway' THEN 100
                WHEN 'trunk' THEN 80
                WHEN 'primary' THEN 60
                WHEN 'secondary' THEN 50
                WHEN 'tertiary' THEN 40
                ELSE 30
            END
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executorService;
    private final AtomicLong nextJobId = new AtomicLong();
    private final Map<Long, BulkLoadRun> runs = new ConcurrentHashMap<>();
    private final Object runLock = new Object();

    public OsmBulkLoadService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "osm-bulk-load");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public BulkLoadRun startBulkLoad() {
        synchronized (runLock) {
            BulkLoadRun activeRun = getActiveRun();
            if (activeRun != null) {
                throw new IllegalStateException("Phase 1 bulk load is already running");
            }

            if (!isNativeH3ExtensionAvailable()) {
                throw new IllegalStateException("H3 PostgreSQL extension is not installed in moodride");
            }

            long jobId = nextJobId.incrementAndGet();
            BulkLoadRun running = BulkLoadRun.running(jobId, Instant.now(), true);
            runs.put(jobId, running);
            executorService.submit(() -> executeBulkLoad(jobId));
            return running;
        }
    }

    public BulkLoadRun getRun(long jobId) {
        return runs.get(jobId);
    }

    public BulkLoadRun getLatestRun() {
        return runs.entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    public BulkLoadRun getActiveRun() {
        return runs.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(run -> "RUNNING".equals(run.status()))
                .findFirst()
                .orElse(null);
    }

    public boolean isNativeH3ExtensionAvailable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'h3'",
                Integer.class
        );
        return count != null && count > 0;
    }

    private void executeBulkLoad(long jobId) {
        Instant startedAt = Instant.now();
        try {
            Integer insertedRows = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("TRUNCATE TABLE road_segments");
                return jdbcTemplate.update(buildBulkInsertSql());
            });

            int rowsInserted = insertedRows == null ? 0 : insertedRows;
            runs.put(jobId, BulkLoadRun.completed(
                    jobId,
                    startedAt,
                    Instant.now(),
                    rowsInserted,
                    rowsInserted,
                    true,
                    "Phase 1 bulk load completed with native H3 SQL"
            ));
        } catch (Exception ex) {
            log.error("Phase 1 bulk load failed", ex);
            runs.put(jobId, BulkLoadRun.failed(
                    jobId,
                    startedAt,
                    Instant.now(),
                    true,
                    ex.getMessage()
            ));
        }
    }

    private String buildBulkInsertSql() {
        boolean hasTags = hasColumn("planet_osm_line", "tags");
        String roadTypeSource = hasTags ? "tags->>'highway'" : "highway";
        String surfaceSource = hasTags ? "tags->>'surface'" : (hasColumn("planet_osm_line", "surface") ? "surface" : "NULL::text");
        String maxspeedSource = hasTags ? "tags->>'maxspeed'" : (hasColumn("planet_osm_line", "maxspeed") ? "maxspeed" : "NULL::text");

        return """
                WITH candidate_roads AS (
                    SELECT
                        osm_id,
                        ST_Transform(way, 4326) AS geometry,
                        %1$s AS road_type,
                        %2$s AS surface,
                        %3$s AS maxspeed
                    FROM planet_osm_line
                    WHERE %1$s IN (
                        'motorway', 'trunk', 'primary', 'secondary', 'tertiary',
                        'unclassified', 'residential', 'service', 'living_street',
                        'motorway_link', 'trunk_link', 'primary_link', 'secondary_link'
                    )
                    AND way IS NOT NULL
                    AND ST_IsValid(way)
                    AND ST_NumPoints(way) >= 2
                )
                INSERT INTO road_segments (
                    osm_way_id,
                    geometry,
                    road_type,
                    surface,
                    speed_limit_kmh,
                    length_meters,
                    curvature,
                    h3_tile_index,
                    elevation_change,
                    last_updated
                )
                SELECT
                    osm_id,
                    geometry,
                    road_type,
                    surface,
                    COALESCE(
                        CASE
                            WHEN maxspeed IS NULL OR btrim(maxspeed) = '' THEN NULL
                            WHEN maxspeed ILIKE '%%mph%%' THEN ROUND(
                                NULLIF(regexp_replace(maxspeed, '[^0-9.]', '', 'g'), '')::numeric * 1.609
                            )::int
                            ELSE NULLIF(regexp_replace(maxspeed, '[^0-9.]', '', 'g'), '')::int
                        END,
                        %4$s
                    ) AS speed_limit_kmh,
                    ST_Length(geometry::geography) AS length_meters,
                    CASE
                        WHEN ST_Distance(
                            ST_StartPoint(geometry)::geography,
                            ST_EndPoint(geometry)::geography
                        ) > 0 THEN
                            LEAST(
                                ST_Length(geometry::geography) /
                                ST_Distance(
                                    ST_StartPoint(geometry)::geography,
                                    ST_EndPoint(geometry)::geography
                                ),
                                3.0
                            ) / 3.0
                        ELSE 0.0
                    END AS curvature,
                    h3_lat_lng_to_cell(
                        point(ST_X(ST_Centroid(geometry)), ST_Y(ST_Centroid(geometry))),
                        7
                    )::text AS h3_tile_index,
                    0.0 AS elevation_change,
                    NOW() AS last_updated
                FROM candidate_roads
                ON CONFLICT (osm_way_id) DO NOTHING
                """.formatted(
                roadTypeSource,
                surfaceSource,
                maxspeedSource,
                ROAD_TYPE_CASE
        );
    }

    private boolean hasColumn(String tableName, String columnName) {
        String sql = """
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
            )
            """;

        try {
            Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, tableName, columnName);
            return Boolean.TRUE.equals(exists);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to inspect OSM schema columns", ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    public record BulkLoadRun(
            long jobId,
            String status,
            Instant startedAt,
            Instant completedAt,
            boolean nativeH3ExtensionAvailable,
            int rowsInserted,
            int h3RowsUpdated,
            String message,
            String error
    ) {
        static BulkLoadRun running(long jobId, Instant startedAt, boolean nativeH3ExtensionAvailable) {
            return new BulkLoadRun(
                    jobId,
                    "RUNNING",
                    startedAt,
                    null,
                    nativeH3ExtensionAvailable,
                    0,
                    0,
                    "Phase 1 bulk load started with native H3 SQL",
                    null
            );
        }

        static BulkLoadRun completed(
                long jobId,
                Instant startedAt,
                Instant completedAt,
                int rowsInserted,
                int h3RowsUpdated,
                boolean nativeH3ExtensionAvailable,
                String message
        ) {
            return new BulkLoadRun(jobId, "COMPLETED", startedAt, completedAt, nativeH3ExtensionAvailable, rowsInserted, h3RowsUpdated, message, null);
        }

        static BulkLoadRun failed(long jobId, Instant startedAt, Instant completedAt, boolean nativeH3ExtensionAvailable, String error) {
            return new BulkLoadRun(jobId, "FAILED", startedAt, completedAt, nativeH3ExtensionAvailable, 0, 0, "Phase 1 bulk load failed", error);
        }
    }
}