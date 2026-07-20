package com.moodride.routeworker.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CachePolicy;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenicTileLookupServiceTest {

    private static final String SCORING_VERSION = "3.7-bridge-coastal-calibration";
    private static final String REDIS_PREFIX =
        "scenicTiles::scenic:tile:" + SCORING_VERSION + ":";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private RedisTemplate<String, ScenicScoreTile> redisTemplate;
    @Mock
    private ValueOperations<String, ScenicScoreTile> valueOperations;

    private ScenicTileLookupService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(redisTemplate);
            return List.of();
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));
        ScenicCacheConfiguration cacheConfiguration = new ScenicCacheConfiguration();
        cacheConfiguration.setScenicScoringVersion(SCORING_VERSION);
        service = new ScenicTileLookupService(jdbcTemplate, redisTemplate, cacheConfiguration);
    }

    @Test
    void localWarmLookupPerformsNoRedisOrSqlWork() {
        ScenicScoreTile cached = tile("local");
        when(valueOperations.multiGet(List.of(key("local")))).thenReturn(List.of(cached));
        service.findMapByH3Indexes(List.of("local"));
        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("local"));

        assertSame(cached, found.get("local"));
        verifyNoInteractions(jdbcTemplate, redisTemplate, valueOperations);
    }

    @Test
    void redisWarmLookupUsesOneBatchAndNoSql() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        when(valueOperations.multiGet(List.of(key("first"), key("second"))))
            .thenReturn(List.of(first, second));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("first", "second"));

        assertEquals(List.of("first", "second"), new ArrayList<>(found.keySet()));
        assertSame(first, found.get("first"));
        assertSame(second, found.get("second"));
        verify(valueOperations).multiGet(List.of(key("first"), key("second")));
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void partialRedisHitUsesOneReadOneSqlQueryAndOnePipelinedWrite() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        ScenicScoreTile third = tile("third");
        when(valueOperations.multiGet(List.of(key("first"), key("second"), key("third"))))
            .thenReturn(Arrays.asList(first, null, third));
        sqlReturns(List.of(second));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(
            List.of("first", "second", "third")
        );

        assertEquals(List.of("first", "second", "third"), new ArrayList<>(found.keySet()));
        verify(valueOperations).multiGet(List.of(key("first"), key("second"), key("third")));
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        verify(valueOperations).set(key("second"), second, CachePolicy.SCENIC_TILES_TTL);
        verify(valueOperations, times(1)).set(anyString(), any(), any(java.time.Duration.class));
    }

    @Test
    void redisReadAndWriteFailureFallsBackToOneSqlQueryAndLocalFill() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        when(valueOperations.multiGet(anyList())).thenThrow(new IllegalStateException("redis down"));
        sqlReturns(List.of(second, first));
        doThrow(new IllegalStateException("redis still down"))
            .when(redisTemplate).executePipelined(any(SessionCallback.class));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("first", "second"));

        assertEquals(List.of("first", "second"), new ArrayList<>(found.keySet()));
        assertSame(first, found.get("first"));
        assertSame(second, found.get("second"));
        verify(valueOperations).multiGet(List.of(key("first"), key("second")));
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));

        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);
        Map<String, ScenicScoreTile> locallyFound = service.findMapByH3Indexes(List.of("second", "first"));
        assertEquals(List.of("second", "first"), new ArrayList<>(locallyFound.keySet()));
        verifyNoInteractions(jdbcTemplate, redisTemplate, valueOperations);
    }

    @Test
    void normalizesDeduplicatesAndPreservesFirstInputOrder() {
        ScenicScoreTile beta = tile("beta");
        ScenicScoreTile alpha = tile("alpha");
        when(valueOperations.multiGet(List.of(key("beta"), key("alpha"))))
            .thenReturn(List.of(beta, alpha));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(
            Arrays.asList(" beta ", "alpha", "beta", null, " ", "alpha")
        );

        assertEquals(List.of("beta", "alpha"), new ArrayList<>(found.keySet()));
        verify(valueOperations).multiGet(List.of(key("beta"), key("alpha")));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void bulkEvictionUsesOneDeleteAndClearsLocalEvenWhenRedisFails() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        List<String> keys = List.of(key("first"), key("second"));
        when(valueOperations.multiGet(keys)).thenReturn(List.of(first, second));
        service.findMapByH3Indexes(List.of("first", "second"));
        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);
        doThrow(new IllegalStateException("delete failed")).when(redisTemplate).delete(keys);

        service.evict(Arrays.asList(" first ", "second", "first", null));

        verify(redisTemplate).delete(keys);
        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);
        service.findMapByH3Indexes(List.of("first", "second"));
        verify(valueOperations).multiGet(keys);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void corruptRedisValueTypesAndKeyMismatchesAreTreatedAsMisses() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        List corruptValues = List.of(tile("different-index"), "not-a-tile");
        when(valueOperations.multiGet(List.of(key("first"), key("second"))))
            .thenReturn(corruptValues);
        sqlReturns(List.of(first, second));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("first", "second"));

        assertSame(first, found.get("first"));
        assertSame(second, found.get("second"));
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void mismatchedRedisScoringVersionFallsBackToDatabaseAndReplacesCacheValue() {
        ScenicScoreTile stale = tile("versioned");
        stale.setScoringVersion("previous-release");
        ScenicScoreTile active = tile("versioned");
        when(valueOperations.multiGet(List.of(key("versioned")))).thenReturn(List.of(stale));
        sqlReturns(List.of(active));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("versioned"));

        assertSame(active, found.get("versioned"));
        verifySingleSqlQuery();
        verify(valueOperations).set(key("versioned"), active, CachePolicy.SCENIC_TILES_TTL);
    }

    @Test
    void localTileWhoseVersionChangesIsRejectedAndRefreshedFromDatabase() {
        ScenicScoreTile cached = tile("local-versioned");
        when(valueOperations.multiGet(List.of(key("local-versioned")))).thenReturn(List.of(cached));
        service.findMapByH3Indexes(List.of("local-versioned"));
        cached.setScoringVersion("previous-release");
        ScenicScoreTile active = tile("local-versioned");
        sqlReturns(List.of(active));
        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("local-versioned"));

        assertSame(active, found.get("local-versioned"));
        verify(valueOperations).multiGet(List.of(key("local-versioned")));
        verifySingleSqlQuery();
    }

    @Test
    void misalignedRedisResponseDiscardsWholeBatchAndRunsOneSqlQuery() {
        ScenicScoreTile first = tile("first");
        ScenicScoreTile second = tile("second");
        when(valueOperations.multiGet(List.of(key("first"), key("second"))))
            .thenReturn(List.of(first));
        sqlReturns(List.of(first, second));

        Map<String, ScenicScoreTile> found = service.findMapByH3Indexes(List.of("first", "second"));

        assertSame(first, found.get("first"));
        assertSame(second, found.get("second"));
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void remoteBatchCallCountsRemainConstantForOneAndFifteenHundredColdMisses() {
        List<String> oneIndex = List.of("small-0");
        List<ScenicScoreTile> oneTile = List.of(tile("small-0"));
        List<String> manyIndexes = IntStream.range(0, 1_500)
            .mapToObj(index -> "large-" + index)
            .toList();
        List<ScenicScoreTile> manyTiles = manyIndexes.stream().map(ScenicTileLookupServiceTest::tile).toList();
        when(valueOperations.multiGet(anyList())).thenReturn(
            Collections.singletonList(null),
            Collections.nCopies(1_500, null)
        );
        sqlReturns(oneTile, manyTiles);

        assertEquals(1, service.findMapByH3Indexes(oneIndex).size());
        verify(valueOperations).multiGet(anyList());
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));

        clearInvocations(jdbcTemplate, redisTemplate, valueOperations);
        assertEquals(1_500, service.findMapByH3Indexes(manyIndexes).size());
        verify(valueOperations).multiGet(anyList());
        verifySingleSqlQuery();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    private static ScenicScoreTile tile(String h3Index) {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setScenicScore(0.75);
        tile.setScoringVersion(SCORING_VERSION);
        return tile;
    }

    private static String key(String h3Index) {
        return REDIS_PREFIX + h3Index;
    }

    @SafeVarargs
    @SuppressWarnings({"unchecked", "varargs"})
    private final void sqlReturns(List<ScenicScoreTile>... results) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(results[0], Arrays.copyOfRange(results, 1, results.length));
    }

    @SuppressWarnings("unchecked")
    private void verifySingleSqlQuery() {
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
