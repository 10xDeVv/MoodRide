package com.moodride.routeapi.cache;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeapi.config.CacheConfig;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import com.moodride.routeapi.service.RouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheWarmupServiceTest {

    private static final String H3_INDEX = "872a1070bffffff";
    private static final String SCORING_VERSION = "3.7-cache-contract";
    private static final String PHYSICAL_SCENIC_KEY =
        "scenicTiles::scenic:tile:" + SCORING_VERSION + ":" + H3_INDEX;

    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache cache;
    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private RouteJobRepository routeJobRepository;
    @Mock
    private RouteService routeService;
    @Mock
    private CacheMetricsService cacheMetricsService;

    @Test
    void scenicWarmupCachesDetachedSerializableTileWithoutChangingSource() {
        ScenicScoreTile source = geometryBearingTile();
        Polygon sourceGeometry = source.getGeometry();
        when(cacheManager.getCache(CacheNames.SCENIC_TILES)).thenReturn(cache);
        when(scenicScoreTileRepository.findTopByScenicScore(SCORING_VERSION, 1))
            .thenReturn(List.of(source));

        int warmed = service(cacheManager).warmScenicTiles(1);

        ArgumentCaptor<Object> cachedValue = ArgumentCaptor.forClass(Object.class);
        verify(cache).put(
            eq(CacheKeySchema.scenicTile(SCORING_VERSION, H3_INDEX)),
            cachedValue.capture()
        );
        assertThat(warmed).isEqualTo(1);
        assertThat(CacheNames.SCENIC_TILES + "::"
            + CacheKeySchema.scenicTile(SCORING_VERSION, H3_INDEX))
            .isEqualTo(PHYSICAL_SCENIC_KEY);

        ScenicScoreTile detached = (ScenicScoreTile) cachedValue.getValue();
        assertThat(detached).isNotSameAs(source);
        assertThat(source.getGeometry()).isSameAs(sourceGeometry);
        assertThat(detached.getGeometry()).isNull();
        assertThat(detached)
            .usingRecursiveComparison()
            .ignoringFields("geometry")
            .isEqualTo(source);

        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();
        byte[] bytes = serializer.serialize(detached);
        ScenicScoreTile restored = (ScenicScoreTile) serializer.deserialize(bytes);
        assertThat(restored).isNotNull();
        assertThat(restored.getGeometry()).isNull();
        assertThat(restored).usingRecursiveComparison().isEqualTo(detached);
    }

    @Test
    void scenicWarmupRejectsRepositoryValueFromDifferentScoringVersion() {
        ScenicScoreTile stale = geometryBearingTile();
        stale.setScoringVersion("previous-release");
        when(cacheManager.getCache(CacheNames.SCENIC_TILES)).thenReturn(cache);
        when(scenicScoreTileRepository.findTopByScenicScore(SCORING_VERSION, 1))
            .thenReturn(List.of(stale));

        int warmed = service(cacheManager).warmScenicTiles(1);

        assertThat(warmed).isZero();
        verify(cache, never()).put(any(), any());
        verify(cacheMetricsService).warmSuccess(CacheNames.SCENIC_TILES, 0);
    }

    @Test
    void warmAllSkipsUnusedSegmentAndPopularityWriters() {
        when(cacheManager.getCache(CacheNames.SCENIC_TILES)).thenReturn(cache);
        when(scenicScoreTileRepository.findTopByScenicScore(SCORING_VERSION, 1000))
            .thenReturn(List.of());
        when(routeRepository.findAll(PageRequest.of(0, 250)))
            .thenReturn(new PageImpl<>(List.of()));
        when(routeJobRepository.findAllById(Set.of())).thenReturn(List.of());

        CacheWarmupService.WarmupReport report = service(cacheManager).warmAll(10_000);

        assertThat(report).isEqualTo(new CacheWarmupService.WarmupReport(0, 0, 0, 0));
        verify(cacheManager, never()).getCache(CacheNames.ROAD_SEGMENTS);
        verify(cacheManager, never()).getCache(CacheNames.REGIONAL_POPULARITY);
        verify(routeRepository, never()).findTop1000ByOrderByGeneratedAtDesc();
        verify(cacheMetricsService, never()).warmSuccess(eq(CacheNames.ROAD_SEGMENTS), anyInt());
        verify(cacheMetricsService, never()).warmSuccess(eq(CacheNames.REGIONAL_POPULARITY), anyInt());
        verifyNoMoreInteractions(cache);
    }

    @Test
    void routeDetailsWarmupCallsRouteServiceOnlyForCompleteJobs() {
        UUID completeRouteId = UUID.randomUUID();
        UUID incompleteRouteId = UUID.randomUUID();
        UUID completeJobId = UUID.randomUUID();
        UUID incompleteJobId = UUID.randomUUID();
        Route completeRoute = org.mockito.Mockito.mock(Route.class);
        Route incompleteRoute = org.mockito.Mockito.mock(Route.class);
        RouteJob completeJob = org.mockito.Mockito.mock(RouteJob.class);
        RouteJob incompleteJob = org.mockito.Mockito.mock(RouteJob.class);
        RouteDetailResponse completeDetail = new RouteDetailResponse(
            completeRouteId,
            completeJobId,
            null,
            0.0,
            Map.of(),
            null,
            0.0,
            0,
            null,
            null,
            0.0,
            0.0,
            List.of(),
            Map.of(),
            List.of(),
            List.of(),
            0L,
            0,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        when(completeRoute.getId()).thenReturn(completeRouteId);
        when(completeRoute.getJobId()).thenReturn(completeJobId);
        when(incompleteRoute.getJobId()).thenReturn(incompleteJobId);
        when(completeJob.getId()).thenReturn(completeJobId);
        when(completeJob.isOptionsComplete()).thenReturn(true);
        when(incompleteJob.isOptionsComplete()).thenReturn(false);
        when(routeRepository.findAll(PageRequest.of(0, 2)))
            .thenReturn(new PageImpl<>(List.of(completeRoute, incompleteRoute)));
        when(routeJobRepository.findAllById(Set.of(completeJobId, incompleteJobId)))
            .thenReturn(List.of(completeJob, incompleteJob));
        when(routeService.getRoute(completeRouteId)).thenReturn(completeDetail);

        int warmed = service(cacheManager).warmRouteDetails(2);

        assertThat(warmed).isEqualTo(1);
        verify(routeService).getRoute(completeRouteId);
        verify(routeService, never()).getRoute(incompleteRouteId);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MOODRIDE_REDIS_INTEGRATION_TEST", matches = "(?i)true")
    void apiWarmupWritesExactPhysicalKeyReadableByWorkerSerializer() {
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
            environmentOrDefault("MOODRIDE_REDIS_HOST", "127.0.0.1"),
            Integer.parseInt(environmentOrDefault("MOODRIDE_REDIS_PORT", "6379"))
        );
        String password = System.getenv("MOODRIDE_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            redisConfiguration.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        RedisTemplate<String, ScenicScoreTile> workerReader = new RedisTemplate<>();
        workerReader.setConnectionFactory(connectionFactory);
        workerReader.setKeySerializer(new StringRedisSerializer());
        workerReader.setValueSerializer(new JdkSerializationRedisSerializer());
        workerReader.afterPropertiesSet();

        try {
            workerReader.delete(PHYSICAL_SCENIC_KEY);
            when(scenicScoreTileRepository.findTopByScenicScore(SCORING_VERSION, 1))
                .thenReturn(List.of(geometryBearingTile()));
            CacheManager redisCacheManager = new CacheConfig().cacheManager(connectionFactory);

            assertThat(service(redisCacheManager).warmScenicTiles(1)).isEqualTo(1);

            ScenicScoreTile workerValue = workerReader.opsForValue().get(PHYSICAL_SCENIC_KEY);
            assertThat(workerValue).isNotNull();
            assertThat(workerValue.getH3Index()).isEqualTo(H3_INDEX);
            assertThat(workerValue.getGeometry()).isNull();
            assertThat(workerValue.getScenicScore()).isEqualTo(0.91);
            assertThat(workerValue.getBridgeCoastalScore()).isEqualTo(0.29);
        } finally {
            workerReader.delete(PHYSICAL_SCENIC_KEY);
            connectionFactory.destroy();
        }
    }

    private CacheWarmupService service(CacheManager manager) {
        return new CacheWarmupService(
            manager,
            scenicScoreTileRepository,
            routeRepository,
            routeJobRepository,
            routeService,
            cacheMetricsService,
            scenicCacheConfiguration()
        );
    }

    private static ScenicCacheConfiguration scenicCacheConfiguration() {
        ScenicCacheConfiguration configuration = new ScenicCacheConfiguration();
        configuration.setScenicScoringVersion(SCORING_VERSION);
        return configuration;
    }

    private static ScenicScoreTile geometryBearingTile() {
        Polygon geometry = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(-122.80, 45.40),
            new Coordinate(-122.75, 45.40),
            new Coordinate(-122.75, 45.45),
            new Coordinate(-122.80, 45.45),
            new Coordinate(-122.80, 45.40)
        });
        ScenicScoreTile tile = new ScenicScoreTile(H3_INDEX, geometry);
        tile.setScenicScore(0.91);
        tile.setWaterProximity(0.11);
        tile.setElevationVariance(0.12);
        tile.setNaturalLandUse(0.13);
        tile.setRoadDensity(0.14);
        tile.setTrafficSignalScore(0.15);
        tile.setPoiDensity(0.16);
        tile.setVisualComplexity(0.17);
        tile.setWaterScore(0.18);
        tile.setGreenScore(0.19);
        tile.setElevationScore(0.20);
        tile.setSolitudeScore(0.21);
        tile.setCurveScore(0.22);
        tile.setPoiScore(0.23);
        tile.setParkScore(0.24);
        tile.setOverturePoiScore(0.25);
        tile.setBuildingDensityScore(0.26);
        tile.setDarknessScore(0.27);
        tile.setUrbanPenaltyScore(0.28);
        tile.setRoadStressScore(0.29);
        tile.setWaterVisibilityScore(0.30);
        tile.setWaterCrossingScore(0.31);
        tile.setCoastalRoadScore(0.32);
        tile.setTreeCanopyScore(0.33);
        tile.setScenicPoiScore(0.34);
        tile.setViewpointScore(0.35);
        tile.setBridgeCoastalScore(0.29);
        tile.setLastScored(Instant.parse("2026-07-19T12:00:00Z"));
        tile.setScoringVersion("3.7-cache-contract");
        return tile;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
