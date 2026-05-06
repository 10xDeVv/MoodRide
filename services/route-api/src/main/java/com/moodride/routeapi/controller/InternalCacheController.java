package com.moodride.routeapi.controller;

import com.moodride.routeapi.cache.CacheKeySchema;
import com.moodride.routeapi.cache.CachePolicy;
import com.moodride.routeapi.cache.CacheWarmupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/cache")
public class InternalCacheController {

    private final CacheWarmupService warmupService;
    private final RedisConnectionFactory redisConnectionFactory;
    private final int defaultLimit;

    public InternalCacheController(CacheWarmupService warmupService,
                                   RedisConnectionFactory redisConnectionFactory,
                                   @Value("${moodride.cache.warmup.limit:200}") int defaultLimit) {
        this.warmupService = warmupService;
        this.redisConnectionFactory = redisConnectionFactory;
        this.defaultLimit = defaultLimit;
    }

    @PostMapping("/warm")
    public ResponseEntity<CacheWarmupService.WarmupReport> warm(@RequestParam(required = false) Integer limit) {
        int effectiveLimit = limit == null ? defaultLimit : limit;
        return ResponseEntity.accepted().body(warmupService.warmAll(effectiveLimit));
    }

    @GetMapping("/policy")
    public ResponseEntity<Map<String, Object>> policy() {
        Map<String, Object> response = new HashMap<>();
        response.putAll(CachePolicy.ttlSummary());
        response.put("keyExamples", Map.of(
                "route", CacheKeySchema.routeResult(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")),
                "tile", CacheKeySchema.scenicTile("872a1070bffffff"),
                "segment", CacheKeySchema.segmentMeta("872a1070bffffff"),
                "popularity", CacheKeySchema.regionalPopularity("872a107")
        ));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flush() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Redis cache flushed"));
        }
    }
}

