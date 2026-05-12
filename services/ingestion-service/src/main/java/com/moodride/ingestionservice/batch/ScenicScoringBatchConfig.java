package com.moodride.ingestionservice.batch;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.ingestionservice.elevation.RoadSegmentElevationEnrichmentService;
import com.moodride.ingestionservice.processor.ScenicScoringProcessor;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBReader;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Batch configuration for Phase 2: Scenic Scoring Pipeline
 *
 * Processes all H3 tiles within the region and computes scenic scores
 * using multiple data sources:
 * - NLCD land use classification
 * - OpenTopoData elevation
 * - Natural Earth water bodies
 * - OSM water polygons
 * - OSM POI data
 * - Road network density
 *
 * Runs weekly to update all tiles.
 */
@Configuration
public class ScenicScoringBatchConfig {

    private static final String WATER_TILE_SUMMARY_TABLE = "water_tile_summary";
    private static final String POI_TILE_SUMMARY_TABLE = "poi_tile_summary";
    private static final String LANDUSE_TILE_SUMMARY_TABLE = "landuse_tile_summary";

    private static final String NE_WATER_TABLE_CANONICAL = "natural_earth_Water_Bodies";
    private static final String NE_WATER_TABLE_LEGACY = "natural_earth_water_bodies";

    private static final int DEFAULT_CHUNK_SIZE = 1;
    private static final double H3_RES7_TILE_AREA_SQ_KM = 5.16;
    private static final double WATER_SEARCH_RADIUS_METERS = 5000.0;
    private static final double POI_LINK_RADIUS_METERS = 250.0;
    private static final int[] NLCD_NATURAL_CLASSES = {
        41, 42, 43, // forest
        52, // shrub/scrub
        71, // grassland
        90, 95 // wetlands
    };

    @Autowired
    private ScenicScoringProcessor scoringProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private volatile boolean osmSchemaDetected = false;
    private boolean planetOsmPolygonExists;
    private boolean planetOsmPointExists;
    private boolean polygonHasTags;
    private boolean pointHasTags;
    private boolean pointHasTourism;
    private boolean pointHasLeisure;
    private boolean pointHasNatural;
    private boolean pointHasAmenity;
    private boolean polygonHasNatural;
    private boolean polygonHasWater;
    private boolean polygonHasWaterway;
    private boolean polygonHasLanduse;
    private volatile boolean nlcdSchemaDetected = false;
    private boolean nlcdTableExists;
    private boolean nlcdHasGeometry;
    private boolean nlcdHasClass;
    private volatile boolean naturalEarthSchemaDetected = false;
    private boolean naturalEarthWaterTableExists;
    private boolean naturalEarthWaterHasGeometry;
    private String naturalEarthWaterTableName;
    private volatile boolean trafficSchemaDetected = false;
    private boolean trafficTableExists;
    private boolean trafficScoreColumnExists;

    /**
     * Main job for scenic scoring.
     */
    @Bean
    public Job scenicScoringJob(JobRepository jobRepository, Step scenicScoringStep) {
    return new JobBuilder("scenicScoringJob", jobRepository)
        .incrementer(new RunIdIncrementer())
        .start(scenicScoringStep)
        .build();
    }

    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("scenic-job-"));
        launcher.afterPropertiesSet();
        return launcher;
    }

    /**
     * Step: Score all H3 tiles in the region.
     */
    @Bean
    public Step scenicScoringStep(
        JobRepository jobRepository,
        @Qualifier("batchDataSourceTransactionManager") PlatformTransactionManager transactionManager,
        JdbcCursorItemReader<H3TileData> h3TileReader,
        ItemProcessor<H3TileData, ScenicScoreTile> scenicTileProcessor,
        ItemWriter<ScenicScoreTile> scenicTileWriter,
        @Value("${moodride.scenic.batch.chunk-size:" + DEFAULT_CHUNK_SIZE + "}") int chunkSize) {

    int effectiveChunkSize = Math.max(1, chunkSize);

    return new StepBuilder("scenicScoringStep", jobRepository)
        .<H3TileData, ScenicScoreTile>chunk(effectiveChunkSize, transactionManager)
        .reader(h3TileReader)
        .processor(scenicTileProcessor)
        .writer(scenicTileWriter)
        .build();
    }

    @Bean(name = "batchDataSourceTransactionManager")
    public PlatformTransactionManager batchDataSourceTransactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    public PlatformTransactionManager transactionManagerAlias(
            EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * ItemReader: Stream H3 tiles directly from the database.
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<H3TileData> h3TileReader(
            @Value("#{jobParameters['targetH3Csv']}") String targetH3Csv,
            @Value("${moodride.scenic.batch.reader-fetch-size:25}") int readerFetchSize
    ) {
        List<String> targetH3Indexes = parseCsv(targetH3Csv);
        String sql;
        if (targetH3Indexes.isEmpty()) {
            sql = """
                SELECT h3_tile_index,
                       ST_AsBinary(ST_Envelope(ST_Collect(geometry))) AS tile_geometry
                FROM road_segments
                GROUP BY h3_tile_index
                ORDER BY h3_tile_index
                """;
        } else {
            String quotedIndexes = targetH3Indexes.stream()
                    .map(value -> "'" + value.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            sql = """
                SELECT h3_tile_index,
                       ST_AsBinary(ST_Envelope(ST_Collect(geometry))) AS tile_geometry
                FROM road_segments
                WHERE h3_tile_index IN (%s)
                GROUP BY h3_tile_index
                ORDER BY h3_tile_index
                """.formatted(quotedIndexes);
        }

        JdbcCursorItemReader<H3TileData> reader = new JdbcCursorItemReader<>(
                dataSource,
                sql,
                (rs, rowNum) -> {
                    H3TileData data = new H3TileData();
                    data.h3Index = rs.getString("h3_tile_index");
                    byte[] wkb = rs.getBytes("tile_geometry");
                    if (wkb != null) {
                        try {
                            Geometry geometry = new WKBReader().read(wkb);
                            if (geometry instanceof Polygon polygon) {
                                data.geometry = polygon;
                            }
                        } catch (Exception ignored) {
                            data.geometry = null;
                        }
                    }
                    return data;
                }
        );

        reader.setFetchSize(Math.max(1, readerFetchSize));
        reader.setSaveState(false);
        return reader;
    }
    /**
     * ItemProcessor: Computes scenic score for each H3 tile.
     */
    @Bean
    @StepScope
    public ItemProcessor<H3TileData, ScenicScoreTile> scenicTileProcessor() {
        return tileData -> {
            if (tileData.geometry == null) {
                return null;
            }

            // For each H3 tile, compute scenic score from data sources

            // 1. Water proximity (from Natural Earth + OSM)
            double waterScore = computeWaterProximity(tileData.h3Index);

            // 2. Elevation variance (from OpenTopoData)
            double elevScore = computeElevationVariance(tileData.h3Index);

            // 3. Natural land use (from NLCD)
            double landUseScore = computeNaturalLandUse(tileData.h3Index);

            // 4. Road density (from OSM)
            double roadDensityScore = computeRoadDensity(tileData.h3Index);

            // 4b. Traffic signal (optional for v1, neutral fallback)
            double trafficScore = computeTrafficScore(tileData.h3Index);
            roadDensityScore = blendRoadDensityWithTraffic(roadDensityScore, trafficScore);

            // 5. POI density (from OSM)
            double poiScore = computePoiDensity(tileData.h3Index);

            // 6. Visual complexity (derived)
            double visualScore = scoringProcessor.scoreVisualComplexity(elevScore, landUseScore);

            // Create and return scored tile
            ScenicScoreTile tile = scoringProcessor.computeScenicScore(
                tileData.h3Index,
                tileData.geometry,
                waterScore,
                elevScore,
                landUseScore,
                roadDensityScore,
                trafficScore,
                poiScore,
                visualScore
            );

            tile.setScoringVersion("2.1-traffic-signals");

            return tile;
        };
    }

    /**
     * ItemWriter: Persists scored tiles to database.
     */
    @Bean
    @StepScope
    public ItemWriter<ScenicScoreTile> scenicTileWriter() {
        String upsertSql = """
            INSERT INTO scenic_score_tiles (
                h3_index,
                geometry,
                scenic_score,
                water_proximity,
                water_score,
                elevation_variance,
                elevation_score,
                natural_land_use,
                green_score,
                road_density,
                solitude_score,
                poi_density,
                poi_score,
                traffic_signal_score,
                visual_complexity,
                curve_score,
                last_scored,
                scoring_version
            )
            VALUES (
                ?, ST_GeomFromText(?, 4326), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            ON CONFLICT (h3_index) DO UPDATE SET
                geometry = EXCLUDED.geometry,
                scenic_score = EXCLUDED.scenic_score,
                water_proximity = EXCLUDED.water_proximity,
                water_score = EXCLUDED.water_score,
                elevation_variance = EXCLUDED.elevation_variance,
                elevation_score = EXCLUDED.elevation_score,
                natural_land_use = EXCLUDED.natural_land_use,
                green_score = EXCLUDED.green_score,
                road_density = EXCLUDED.road_density,
                solitude_score = EXCLUDED.solitude_score,
                poi_density = EXCLUDED.poi_density,
                poi_score = EXCLUDED.poi_score,
                visual_complexity = EXCLUDED.visual_complexity,
                curve_score = EXCLUDED.curve_score,
                traffic_signal_score = EXCLUDED.traffic_signal_score,
                last_scored = EXCLUDED.last_scored,
                scoring_version = EXCLUDED.scoring_version
            """;

        return scenicTiles -> {
            for (ScenicScoreTile tile : scenicTiles) {
                if (tile.getGeometry() == null) {
                    continue;
                }

                tile.setLastScored(Instant.now());
                Timestamp lastScoredTs = Timestamp.from(tile.getLastScored());
                jdbcTemplate.update(
                        upsertSql,
                        tile.getH3Index(),
                        tile.getGeometry().toText(),
                        tile.getScenicScore(),
                        tile.getWaterProximity(),
                        tile.getWaterScore(),
                        tile.getElevationVariance(),
                        tile.getElevationScore(),
                        tile.getNaturalLandUse(),
                        tile.getGreenScore(),
                        tile.getRoadDensity(),
                        tile.getSolitudeScore(),
                        tile.getPoiDensity(),
                        tile.getPoiScore(),
                        tile.getTrafficSignalScore(),
                        tile.getVisualComplexity(),
                        tile.getCurveScore(),
                        lastScoredTs,
                        tile.getScoringVersion()
                );
            }
        };
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Computes water proximity score for an H3 tile.
     * Uses the pre-aggregated tile water summary.
     */
    private double computeWaterProximity(String h3Index) {
        String sql = """
                SELECT COALESCE(min_distance_to_water_m, 5000.0)
                FROM water_tile_summary
            WHERE h3_index = ?
                """;

        try {
            Double minDistance = jdbcTemplate.queryForObject(sql, Double.class, h3Index);
            if (minDistance == null) {
                return 0.5;
            }
            return scoringProcessor.scoreWaterProximity(null, Math.max(0.0, minDistance));
        } catch (Exception e) {
            return 0.5;
        }
    }

    /**
     * Computes elevation variance for an H3 tile.
     * Queries OpenTopoData elevation API.
     */
    private double computeElevationVariance(String h3Index) {
        String sql = """
                SELECT
                    COALESCE(MIN(elevation_change), 0.0) AS min_elev,
                    COALESCE(MAX(elevation_change), 0.0) AS max_elev
                FROM road_segments
                WHERE h3_tile_index = ?
                """;

        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    return 0.0;
                }
                double minElev = rs.getDouble("min_elev");
                double maxElev = rs.getDouble("max_elev");
                return scoringProcessor.scoreElevationVariance(minElev, maxElev);
            }, h3Index);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Computes natural land use score for an H3 tile.
     * Uses the pre-aggregated tile land-use summary.
     */
    private double computeNaturalLandUse(String h3Index) {
        String sql = """
            SELECT natural_count,
                   urban_count,
                   residential_count
            FROM landuse_tile_summary
            WHERE h3_index = ?
            """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return 0.5;
            }
            int naturalCount = rs.getInt("natural_count");
            int urbanCount = rs.getInt("urban_count");
            int residentialCount = rs.getInt("residential_count");
            int total = naturalCount + urbanCount + residentialCount;

            if (total == 0) {
                return 0.5;
            }

            double naturalRatio = (double) naturalCount / total;
            return scoringProcessor.scoreNaturalLandUse(naturalRatio * 100.0);
        }, h3Index);
    }

    /**
     * Computes road density score for an H3 tile.
     * Uses OSM road network.
     */
    private double computeRoadDensity(String h3Index) {
        String sql = """
            SELECT COUNT(*) as road_count
            FROM road_segments
            WHERE h3_tile_index = ?
            """;

        try {
            Integer roadCount = jdbcTemplate.queryForObject(sql, Integer.class, h3Index);
            return scoringProcessor.scoreRoadDensity(roadCount == null ? 0 : roadCount, H3_RES7_TILE_AREA_SQ_KM);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double computeTrafficScore(String h3Index) {
        ensureTrafficSchemaMetadata();
        if (!trafficTableExists || !trafficScoreColumnExists) {
            return 0.5;
        }

        String sql = """
                SELECT COALESCE(AVG(traffic_score), 0.5)
                FROM traffic_tile_signals
                WHERE h3_index = ?
                """;
        try {
            Double trafficScore = jdbcTemplate.queryForObject(sql, Double.class, h3Index);
            if (trafficScore == null) {
                return 0.5;
            }
            return clamp01(trafficScore);
        } catch (Exception ex) {
            return 0.5;
        }
    }

    private void ensureTrafficSchemaMetadata() {
        if (trafficSchemaDetected) {
            return;
        }
        synchronized (this) {
            if (trafficSchemaDetected) {
                return;
            }

            trafficTableExists = tableExists("traffic_tile_signals");
            if (trafficTableExists) {
                trafficScoreColumnExists = columnExists("traffic_tile_signals", "traffic_score");
            }

            trafficSchemaDetected = true;
        }
    }

    private double blendRoadDensityWithTraffic(double roadDensityScore, double trafficScore) {
        // Lower traffic should improve scenic quality without overwhelming road-density signal.
        return clamp01((roadDensityScore * 0.75) + (trafficScore * 0.25));
    }

    /**
     * Computes POI density score for an H3 tile.
     * Uses the pre-aggregated tile POI summary.
     */
    private double computePoiDensity(String h3Index) {
        String sql = """
                SELECT poi_count
                FROM poi_tile_summary
            WHERE h3_index = ?
                """;

        try {
            Integer poiCount = jdbcTemplate.queryForObject(
                    sql,
                    Integer.class,
                    h3Index
            );
            return scoringProcessor.scorePoiDensity(poiCount == null ? 0 : poiCount, H3_RES7_TILE_AREA_SQ_KM);
        } catch (Exception e) {
            return 0.5;
        }
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

    private boolean columnExists(String tableName, String columnName) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, tableName, columnName);
        return Boolean.TRUE.equals(exists);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private boolean tileSummaryTableExists(String tableName) {
        return tableExists(tableName);
    }

    /**
     * Data class for H3 tile metadata.
     */
    public static class H3TileData {
        public String h3Index;
        public Polygon geometry;  // H3 hexagon boundaries
    }
}

