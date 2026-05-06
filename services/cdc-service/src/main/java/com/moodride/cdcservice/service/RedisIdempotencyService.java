package com.moodride.cdcservice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisIdempotencyService {

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean firstSeen(String key, int ttlSeconds) {
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(Math.max(1, ttlSeconds)));
        return Boolean.TRUE.equals(inserted);
    }
}

