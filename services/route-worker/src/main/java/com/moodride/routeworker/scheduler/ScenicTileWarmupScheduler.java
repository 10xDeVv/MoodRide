package com.moodride.routeworker.scheduler;

import com.moodride.geo.H3Utils;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.service.ScenicTileLookupService;
import com.moodride.routeworker.service.WorkerCacheMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ScenicTileWarmupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ScenicTileWarmupScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final ScenicTileLookupService scenicTileLookupService;
    private final WorkerCacheMetricsService metricsService;
    private final ApplicationConfiguration algorithmConfig;
    private final boolean enabled;
    private final int recentJobLimit;
    private final int maxCells;

    public ScenicTileWarmupScheduler(
        JdbcTemplate jdbcTemplate,
        ScenicTileLookupService scenicTileLookupService,
        WorkerCacheMetricsService metricsService,
        ApplicationConfiguration algorithmConfig,
        @Value("${moodride.cache.scenic-warmup.enabled:true}") boolean enabled,
        @Value("${moodride.cache.scenic-warmup.recent-job-limit:8}") int recentJobLimit,
        @Value("${moodride.cache.scenic-warmup.max-cells:10000}") int maxCells
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scenicTileLookupService = scenicTileLookupService;
        this.metricsService = metricsService;
        this.algorithmConfig = algorithmConfig;
        this.enabled = enabled;
        this.recentJobLimit = Math.max(1, recentJobLimit);
        this.maxCells = Math.max(100, maxCells);
    }

    @Scheduled(
        fixedDelayString = "${moodride.cache.scenic-warmup.interval-ms:1800000}",
        initialDelayString = "${moodride.cache.scenic-warmup.initial-delay-ms:5000}"
    )
    public void warmRecentRouteRegions() {
        if (!enabled) {
            return;
        }

        long startedNanos = System.nanoTime();
        try {
            List<StartPoint> recentStarts = recentStartPoints();
            if (recentStarts.isEmpty()) {
                logger.info("Route-worker scenic tile warmup skipped; no recent route jobs found");
                return;
            }

            Set<String> cells = cellsForRecentStarts(recentStarts);
            if (cells.isEmpty()) {
                logger.info("Route-worker scenic tile warmup skipped; no H3 cells resolved startCount={}", recentStarts.size());
                return;
            }

            int loaded = scenicTileLookupService.findByH3Indexes(cells).size();
            metricsService.warmSuccess("scenicTiles");
            logger.info(
                "Route-worker scenic tile warmup complete startCount={} requestedCells={} loadedTiles={} elapsedMs={}",
                recentStarts.size(),
                cells.size(),
                loaded,
                elapsedMillis(startedNanos)
            );
        } catch (RuntimeException ex) {
            logger.warn("Route-worker scenic tile warmup failed: {}", ex.getMessage());
        }
    }

    private List<StartPoint> recentStartPoints() {
        return jdbcTemplate.query(
            """
            SELECT start_latitude, start_longitude
            FROM route_jobs
            WHERE start_latitude IS NOT NULL
              AND start_longitude IS NOT NULL
            ORDER BY submitted_at DESC
            LIMIT ?
            """,
            ps -> ps.setInt(1, recentJobLimit),
            (rs, rowNum) -> new StartPoint(rs.getDouble("start_latitude"), rs.getDouble("start_longitude"))
        );
    }

    private Set<String> cellsForRecentStarts(List<StartPoint> recentStarts) {
        int resolution = Math.max(0, algorithmConfig.getH3Resolution());
        int ringSize = Math.max(1, algorithmConfig.getTileSelectionRingMax());
        Set<String> cells = new LinkedHashSet<>();
        Set<String> centers = new LinkedHashSet<>();

        for (StartPoint start : recentStarts) {
            String center = H3Utils.getH3Index(start.latitude(), start.longitude(), resolution);
            if (!centers.add(center)) {
                continue;
            }
            for (String cell : H3Utils.getKRing(center, ringSize)) {
                cells.add(cell);
                if (cells.size() >= maxCells) {
                    return cells;
                }
            }
        }
        return cells;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private record StartPoint(double latitude, double longitude) {
    }
}
