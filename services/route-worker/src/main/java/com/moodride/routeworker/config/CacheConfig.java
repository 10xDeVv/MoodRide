package com.moodride.routeworker.config;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.cache.CachePolicy;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis-based cache configuration for route worker service.
 * Uses same cache layers as route-api for consistency.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public StringRedisSerializer cacheKeySerializer() {
        return new StringRedisSerializer();
    }

    @Bean
    public JdkSerializationRedisSerializer cacheValueSerializer() {
        return new JdkSerializationRedisSerializer();
    }

    @Bean
    public RedisTemplate<String, ScenicScoreTile> scenicTileRedisTemplate(
        RedisConnectionFactory connectionFactory,
        StringRedisSerializer cacheKeySerializer,
        JdkSerializationRedisSerializer cacheValueSerializer
    ) {
        RedisTemplate<String, ScenicScoreTile> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(cacheKeySerializer);
        redisTemplate.setValueSerializer(cacheValueSerializer);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager(
        RedisConnectionFactory connectionFactory,
        StringRedisSerializer cacheKeySerializer,
        JdkSerializationRedisSerializer cacheValueSerializer
    ) {
        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(1))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(cacheKeySerializer)
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(cacheValueSerializer)
            )
            .disableCachingNullValues();

        // Per-cache TTL configurations
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Layer 1: Route Results (24-hour TTL)
        cacheConfigurations.put(CacheNames.ROUTE_RESULTS,
            defaultConfig.entryTtl(CachePolicy.ROUTE_RESULTS_TTL));

        // Layer 2: Scenic Tile Scores (8-day TTL)
        cacheConfigurations.put(CacheNames.SCENIC_TILES,
            defaultConfig.entryTtl(CachePolicy.SCENIC_TILES_TTL));

        // Layer 3: Road Segment Metadata (7-day TTL)
        cacheConfigurations.put(CacheNames.ROAD_SEGMENTS,
            defaultConfig.entryTtl(CachePolicy.ROAD_SEGMENTS_TTL));

        // Layer 4: Regional Popularity (24-hour TTL)
        cacheConfigurations.put(CacheNames.REGIONAL_POPULARITY,
            defaultConfig.entryTtl(CachePolicy.REGIONAL_POPULARITY_TTL));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .transactionAware()
            .build();
    }
}
