package com.moodride.routeworker.config;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.cache.CachePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class CacheConfigTest {

    @Test
    void scenicTemplateMatchesCacheManagerPhysicalKeyTtlAndJdkSerialization() {
        CacheConfig config = new CacheConfig();
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        StringRedisSerializer keySerializer = config.cacheKeySerializer();
        JdkSerializationRedisSerializer valueSerializer = config.cacheValueSerializer();
        RedisTemplate<String, ScenicScoreTile> template = config.scenicTileRedisTemplate(
            connectionFactory,
            keySerializer,
            valueSerializer
        );
        RedisCacheManager cacheManager = (RedisCacheManager) config.cacheManager(
            connectionFactory,
            keySerializer,
            valueSerializer
        );
        cacheManager.afterPropertiesSet();
        TransactionAwareCacheDecorator scenicCache =
            (TransactionAwareCacheDecorator) cacheManager.getCache(CacheNames.SCENIC_TILES);
        RedisCacheConfiguration scenicConfiguration =
            ((RedisCache) scenicCache.getTargetCache()).getCacheConfiguration();
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index("892a100d2d7ffff");
        String logicalKey = CacheKeySchema.scenicTile(
            ScenicCacheConfiguration.DEFAULT_SCENIC_SCORING_VERSION,
            tile.getH3Index()
        );
        String physicalKey = scenicConfiguration.getKeyPrefixFor(CacheNames.SCENIC_TILES) + logicalKey;

        assertEquals(CacheNames.SCENIC_TILES + "::" + logicalKey, physicalKey);
        assertEquals(
            CachePolicy.SCENIC_TILES_TTL,
            scenicConfiguration.getTtlFunction().getTimeToLive(physicalKey, tile)
        );
        assertSame(keySerializer, template.getKeySerializer());
        assertSame(valueSerializer, template.getValueSerializer());
        assertArrayEquals(
            keySerializer.serialize(physicalKey),
            bytes(scenicConfiguration.getKeySerializationPair().write(physicalKey))
        );
        byte[] templateValue = valueSerializer.serialize(tile);
        assertArrayEquals(
            templateValue,
            bytes(scenicConfiguration.getValueSerializationPair().write(tile))
        );
        assertInstanceOf(ScenicScoreTile.class, valueSerializer.deserialize(templateValue));
    }

    @Test
    void cacheKeysIsolateEveryReleaseIdentityDimension() {
        String h3Index = "892a100d2d7ffff";
        String scenicV1 = CacheKeySchema.scenicTile("scenic-v1", h3Index);
        String scenicV2 = CacheKeySchema.scenicTile("scenic-v2", h3Index);
        assertEquals(2, Set.of(scenicV1, scenicV2).size());

        Set<String> anchorKeys = Set.of(
            CacheKeySchema.roadAnchor("scenic-v1", "roads-v1", "v1", h3Index),
            CacheKeySchema.roadAnchor("scenic-v2", "roads-v1", "v1", h3Index),
            CacheKeySchema.roadAnchor("scenic-v1", "roads-v2", "v1", h3Index),
            CacheKeySchema.roadAnchor("scenic-v1", "roads-v1", "v2", h3Index)
        );
        assertEquals(4, anchorKeys.size());
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
