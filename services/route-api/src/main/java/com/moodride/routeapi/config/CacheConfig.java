package com.moodride.routeapi.config;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.moodride.routeapi.cache.CacheNames;
import com.moodride.routeapi.cache.CachePolicy;

/**
 * Redis-based cache configuration for multi-layer caching strategy.
 *
 * Cache Layers:
 * - Layer 1: Complete rich route details v2 (24-hour TTL)
 * - Layer 2: Scenic Tile Scores (8-day TTL)
 * - Layer 3: Road Segment Metadata (7-day TTL)
 * - Layer 4: Regional Popularity (24-hour TTL)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(1))  // Default 1-hour TTL
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new JdkSerializationRedisSerializer())
            )
            .disableCachingNullValues();

        // Per-cache TTL configurations
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Layer 1: Complete rich route details v2 (24-hour TTL)
        cacheConfigurations.put(CacheNames.ROUTE_DETAILS_V2,
            defaultConfig.entryTtl(CachePolicy.ROUTE_DETAILS_V2_TTL));

        // Layer 2: Scenic Tile Scores (8-day TTL)
        cacheConfigurations.put(CacheNames.SCENIC_TILES,
            defaultConfig.entryTtl(CachePolicy.SCENIC_TILES_TTL));

        // Layer 3: Road Segment Metadata (7-day TTL)
        cacheConfigurations.put(CacheNames.ROAD_SEGMENTS,
            defaultConfig.entryTtl(CachePolicy.ROAD_SEGMENTS_TTL));

        // Layer 4: Regional Popularity (24-hour TTL)
        cacheConfigurations.put(CacheNames.REGIONAL_POPULARITY,
            defaultConfig.entryTtl(CachePolicy.REGIONAL_POPULARITY_TTL));

        CacheManager redisCacheManager = RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .transactionAware()
            .build();

        return new FailOpenCacheManager(redisCacheManager);
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache GET failed for cache '{}' key '{}': {}", cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed for cache '{}' key '{}': {}", cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache EVICT failed for cache '{}' key '{}': {}", cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache CLEAR failed for cache '{}': {}", cacheName(cache), exception.getMessage());
            }

            private String cacheName(Cache cache) {
                return cache != null ? cache.getName() : "unknown";
            }
        };
    }

    private static final class FailOpenCacheManager implements CacheManager {

        private final CacheManager delegate;
        private final Map<String, Cache> fallbackCaches = new ConcurrentHashMap<>();

        private FailOpenCacheManager(CacheManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public Cache getCache(String name) {
            Cache fallback = fallbackCaches.computeIfAbsent(name, ConcurrentMapCache::new);
            try {
                Cache redisCache = delegate.getCache(name);
                if (redisCache == null) {
                    return fallback;
                }
                return new FailOpenCache(redisCache, fallback);
            } catch (RuntimeException ex) {
                log.warn("Redis cache '{}' unavailable, using in-memory fallback: {}", name, ex.getMessage());
                return fallback;
            }
        }

        @Override
        public Collection<String> getCacheNames() {
            try {
                return delegate.getCacheNames();
            } catch (RuntimeException ex) {
                return fallbackCaches.keySet();
            }
        }
    }

    private static final class FailOpenCache implements Cache {

        private final Cache primary;
        private final Cache fallback;

        private FailOpenCache(Cache primary, Cache fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public String getName() {
            return primary.getName();
        }

        @Override
        public Object getNativeCache() {
            return primary.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            try {
                return primary.get(key);
            } catch (RuntimeException ex) {
                log.warn("Redis cache get failed for '{}': {}", getName(), ex.getMessage());
                return fallback.get(key);
            }
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            try {
                return primary.get(key, type);
            } catch (RuntimeException ex) {
                log.warn("Redis cache typed get failed for '{}': {}", getName(), ex.getMessage());
                return fallback.get(key, type);
            }
        }

        @Override
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            try {
                return primary.get(key, valueLoader);
            } catch (RuntimeException ex) {
                log.warn("Redis cache loader get failed for '{}': {}", getName(), ex.getMessage());
                try {
                    return fallback.get(key, valueLoader);
                } catch (RuntimeException fallbackEx) {
                    throw fallbackEx;
                }
            }
        }

        @Override
        public void put(Object key, Object value) {
            try {
                primary.put(key, value);
            } catch (RuntimeException ex) {
                log.warn("Redis cache put failed for '{}': {}", getName(), ex.getMessage());
                fallback.put(key, value);
            }
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            try {
                return primary.putIfAbsent(key, value);
            } catch (RuntimeException ex) {
                log.warn("Redis cache putIfAbsent failed for '{}': {}", getName(), ex.getMessage());
                return fallback.putIfAbsent(key, value);
            }
        }

        @Override
        public void evict(Object key) {
            try {
                primary.evict(key);
            } catch (RuntimeException ex) {
                log.warn("Redis cache evict failed for '{}': {}", getName(), ex.getMessage());
                fallback.evict(key);
            }
        }

        @Override
        public void clear() {
            try {
                primary.clear();
            } catch (RuntimeException ex) {
                log.warn("Redis cache clear failed for '{}': {}", getName(), ex.getMessage());
                fallback.clear();
            }
        }
    }
}
