package com.moodride.routeworker.service;

import com.moodride.datamodels.RouteMode;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class RouteGenerationMetricsService {

    private static final String SERVICE_TAG_VALUE = "route-worker";
    private static final Duration MIN_EXPECTED_STAGE_DURATION = Duration.ofMillis(1);
    private static final Duration MAX_EXPECTED_STAGE_DURATION = Duration.ofSeconds(30);

    private final MeterRegistry meterRegistry;

    public RouteGenerationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(RouteGenerationMetrics metrics) {
        String routeMode = routeModeTag(metrics.routeMode());
        String strategy = strategyTag(metrics.strategy());
        String outcome = outcomeTag(metrics.outcome());

        recordStageDuration("total", metrics.totalMs(), routeMode, strategy, outcome);
        recordStageDuration("tile_scoring", metrics.tileScoringMs(), routeMode, strategy, outcome);
        recordStageDuration("variant_build", metrics.variantBuildMs(), routeMode, strategy, outcome);
        recordStageDuration("primary_osrm", metrics.primaryOsrmMs(), routeMode, strategy, outcome);
        recordStageDuration("rescue", metrics.rescueMs(), routeMode, strategy, outcome);
        recordStageDuration("selection", metrics.selectionMs(), routeMode, strategy, outcome);

        recordCount("scored_tiles", metrics.scoredTiles(), routeMode, strategy, outcome);
        recordCount("waypoint_variants", metrics.waypointVariants(), routeMode, strategy, outcome);
        recordCount("candidates", metrics.candidates(), routeMode, strategy, outcome);
        recordCount("selected", metrics.selected(), routeMode, strategy, outcome);
    }

    public void recordStage(String stage, long durationMs, RouteMode routeMode, Object strategy, String outcome) {
        recordStageDuration(stage, durationMs, routeModeTag(routeMode), strategyTag(strategy), outcomeTag(outcome));
    }

    public void recordRoadAnchorLookup(String source, long durationMs) {
        Timer.builder("moodride.route.worker.road_anchor.lookup.duration")
            .description("Road anchor lookup duration")
            .tag("service", SERVICE_TAG_VALUE)
            .tag("source", source == null || source.isBlank() ? "unknown" : source.toLowerCase(Locale.ROOT))
            .publishPercentileHistogram()
            .minimumExpectedValue(MIN_EXPECTED_STAGE_DURATION)
            .maximumExpectedValue(Duration.ofSeconds(10))
            .register(meterRegistry)
            .record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    private void recordStageDuration(String stage, long durationMs, String routeMode, String strategy, String outcome) {
        Timer.builder("moodride.route.worker.generation.stage.duration")
            .description("Route generation stage duration")
            .tag("service", SERVICE_TAG_VALUE)
            .tag("stage", stage)
            .tag("route_mode", routeMode)
            .tag("strategy", strategy)
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .minimumExpectedValue(MIN_EXPECTED_STAGE_DURATION)
            .maximumExpectedValue(MAX_EXPECTED_STAGE_DURATION)
            .register(meterRegistry)
            .record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    private void recordCount(String countType, int count, String routeMode, String strategy, String outcome) {
        DistributionSummary.builder("moodride.route.worker.generation.count")
            .description("Route generation stage input and output counts")
            .tag("service", SERVICE_TAG_VALUE)
            .tag("count", countType)
            .tag("route_mode", routeMode)
            .tag("strategy", strategy)
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(Math.max(0, count));
    }

    private String routeModeTag(RouteMode routeMode) {
        return routeMode == null ? "drive" : routeMode.name().toLowerCase(Locale.ROOT);
    }

    private String strategyTag(Object strategy) {
        return strategy == null ? "unknown" : strategy.toString().toLowerCase(Locale.ROOT);
    }

    private String outcomeTag(String outcome) {
        return outcome == null || outcome.isBlank() ? "unknown" : outcome.toLowerCase(Locale.ROOT);
    }

    public record RouteGenerationMetrics(
        RouteMode routeMode,
        Object strategy,
        String outcome,
        long totalMs,
        long tileScoringMs,
        long variantBuildMs,
        long primaryOsrmMs,
        long rescueMs,
        long selectionMs,
        int scoredTiles,
        int waypointVariants,
        int candidates,
        int selected
    ) {
    }
}
