package com.moodride.routeworker.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.config.CacheConfig;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "MOODRIDE_REDIS_INTEGRATION_TEST", matches = "(?i)true")
class ScenicTileRedisIntegrationTest {

    private static final int TILE_COUNT = 1_500;
    private static final String PHYSICAL_KEY_PREFIX = CacheNames.SCENIC_TILES + "::";
    private static final String SCORING_VERSION = "redis-integration-v1";
    private static final String PREVIOUS_SCORING_VERSION = "redis-integration-v0";

    @Test
    @SuppressWarnings("unchecked")
    void realRedisKeepsBatchLookupConstantAndCompatibleAcrossCacheLayers() {
        String host = environmentOrDefault("MOODRIDE_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(environmentOrDefault("MOODRIDE_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration redisConfiguration =
            new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("MOODRIDE_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            redisConfiguration.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connectionFactory =
            new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        CacheConfig cacheConfig = new CacheConfig();
        RedisTemplate<String, ScenicScoreTile> redisTemplate = cacheConfig.scenicTileRedisTemplate(
            connectionFactory,
            cacheConfig.cacheKeySerializer(),
            cacheConfig.cacheValueSerializer()
        );
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ScenicCacheConfiguration cacheIdentity = new ScenicCacheConfiguration();
        cacheIdentity.setScenicScoringVersion(SCORING_VERSION);
        ScenicTileLookupService service = new ScenicTileLookupService(
            jdbcTemplate,
            redisTemplate,
            cacheIdentity
        );
        List<String> indexes = IntStream.range(0, TILE_COUNT)
            .mapToObj(index -> "moodride-integration-scenic-" + String.format(Locale.ROOT, "%04d", index))
            .toList();
        List<String> physicalKeys = indexes.stream()
            .map(index -> PHYSICAL_KEY_PREFIX + CacheKeySchema.scenicTile(SCORING_VERSION, index))
            .toList();
        String previousReleaseKey = PHYSICAL_KEY_PREFIX
            + CacheKeySchema.scenicTile(PREVIOUS_SCORING_VERSION, indexes.get(0));
        ScenicScoreTile previousReleaseTile = tile(indexes.get(0), 0);
        previousReleaseTile.setScenicScore(-1.0);
        previousReleaseTile.setScoringVersion(PREVIOUS_SCORING_VERSION);
        List<ScenicScoreTile> sqlTiles = IntStream.range(0, TILE_COUNT)
            .mapToObj(index -> tile(indexes.get(index), index))
            .toList();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(sqlTiles);

        try {
            redisTemplate.delete(physicalKeys);
            redisTemplate.delete(previousReleaseKey);
            redisTemplate.opsForValue().set(previousReleaseKey, previousReleaseTile);
            assertEquals(0L, redisTemplate.countExistingKeys(physicalKeys));

            long started = System.nanoTime();
            Map<String, ScenicScoreTile> cold = service.findMapByH3Indexes(indexes);
            long coldMillis = elapsedMillis(started);
            assertTiles(indexes, cold);
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
            assertEquals((long) TILE_COUNT, redisTemplate.countExistingKeys(physicalKeys));
            assertTrue(redisTemplate.getExpire(physicalKeys.get(0), TimeUnit.DAYS) >= 7L);
            ScenicScoreTile isolatedOldValue = redisTemplate.opsForValue().get(previousReleaseKey);
            assertNotNull(isolatedOldValue);
            assertEquals(PREVIOUS_SCORING_VERSION, isolatedOldValue.getScoringVersion());
            assertEquals(-1.0, isolatedOldValue.getScenicScore());

            service.clearLocal();
            started = System.nanoTime();
            Map<String, ScenicScoreTile> redisWarm = service.findMapByH3Indexes(indexes);
            long redisWarmMillis = elapsedMillis(started);
            assertTiles(indexes, redisWarm);
            verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));

            started = System.nanoTime();
            Map<String, ScenicScoreTile> localWarm = service.findMapByH3Indexes(indexes);
            long localWarmMillis = elapsedMillis(started);
            assertTiles(indexes, localWarm);
            verifyNoMoreInteractions(jdbcTemplate);

            started = System.nanoTime();
            List<ScenicScoreTile> serialValues = new ArrayList<>(TILE_COUNT);
            for (String physicalKey : physicalKeys) {
                serialValues.add(redisTemplate.opsForValue().get(physicalKey));
            }
            long serialGetMillis = elapsedMillis(started);
            assertEquals(TILE_COUNT, serialValues.size());
            assertTrue(serialValues.stream().allMatch(value -> value != null));

            started = System.nanoTime();
            List<ScenicScoreTile> batchValues = redisTemplate.opsForValue().multiGet(physicalKeys);
            long multiGetMillis = elapsedMillis(started);
            assertNotNull(batchValues);
            assertEquals(TILE_COUNT, batchValues.size());
            assertTrue(batchValues.stream().allMatch(value -> value != null));

            System.out.printf(
                Locale.ROOT,
                "ScenicTileRedisIntegrationTest tiles=%d cold-fill=%dms redis-warm=%dms "
                    + "local-warm=%dms serial-GET=%dms MGET=%dms%n",
                TILE_COUNT,
                coldMillis,
                redisWarmMillis,
                localWarmMillis,
                serialGetMillis,
                multiGetMillis
            );

            service.evict(indexes);
            assertEquals(0L, redisTemplate.countExistingKeys(physicalKeys));
        } finally {
            redisTemplate.delete(physicalKeys);
            redisTemplate.delete(previousReleaseKey);
            connectionFactory.destroy();
        }
    }

    private static ScenicScoreTile tile(String h3Index, int index) {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setScenicScore((index + 1.0) / TILE_COUNT);
        tile.setScoringVersion(SCORING_VERSION);
        return tile;
    }

    private static void assertTiles(
        List<String> expectedIndexes,
        Map<String, ScenicScoreTile> actual
    ) {
        assertEquals(expectedIndexes, new ArrayList<>(actual.keySet()));
        assertEquals(TILE_COUNT, actual.size());
        for (int index = 0; index < TILE_COUNT; index++) {
            ScenicScoreTile tile = actual.get(expectedIndexes.get(index));
            assertNotNull(tile);
            assertEquals((index + 1.0) / TILE_COUNT, tile.getScenicScore());
            assertEquals(SCORING_VERSION, tile.getScoringVersion());
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
