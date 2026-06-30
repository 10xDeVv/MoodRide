package com.moodride.routeworker.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ScenicTileLookupService {

    private static final Logger logger = LoggerFactory.getLogger(ScenicTileLookupService.class);
    private static final int MAX_LOCAL_TILES = 25_000;

    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final CacheManager cacheManager;
    private final Map<String, ScenicScoreTile> localTiles = new LinkedHashMap<>(1024, 0.75f, true);

    public ScenicTileLookupService(ScenicScoreTileRepository scenicScoreTileRepository,
                                   CacheManager cacheManager) {
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.cacheManager = cacheManager;
    }

    @Transactional(readOnly = true)
    public List<ScenicScoreTile> findByH3Indexes(Collection<String> h3Indexes) {
        return new ArrayList<>(findMapByH3Indexes(h3Indexes).values());
    }

    @Transactional(readOnly = true)
    public Map<String, ScenicScoreTile> findMapByH3Indexes(Collection<String> h3Indexes) {
        List<String> orderedIndexes = normalizeIndexes(h3Indexes);
        if (orderedIndexes.isEmpty()) {
            return Map.of();
        }

        Map<String, ScenicScoreTile> found = new LinkedHashMap<>();
        List<String> cacheMisses = new ArrayList<>();
        Cache redisCache = scenicCache();

        for (String h3Index : orderedIndexes) {
            ScenicScoreTile localTile = getLocal(h3Index);
            if (localTile != null) {
                found.put(h3Index, localTile);
                continue;
            }

            ScenicScoreTile cachedTile = getRedis(redisCache, h3Index);
            if (cachedTile != null) {
                putLocal(h3Index, cachedTile);
                found.put(h3Index, cachedTile);
                continue;
            }

            cacheMisses.add(h3Index);
        }

        if (!cacheMisses.isEmpty()) {
            List<ScenicScoreTile> fetchedTiles = scenicScoreTileRepository.findByH3IndexIn(cacheMisses);
            for (ScenicScoreTile tile : fetchedTiles) {
                if (tile == null || tile.getH3Index() == null) {
                    continue;
                }
                found.put(tile.getH3Index(), tile);
                putLocal(tile.getH3Index(), tile);
                putRedis(redisCache, tile.getH3Index(), tile);
            }
        }

        Map<String, ScenicScoreTile> orderedFound = new LinkedHashMap<>();
        for (String h3Index : orderedIndexes) {
            ScenicScoreTile tile = found.get(h3Index);
            if (tile != null) {
                orderedFound.put(h3Index, tile);
            }
        }
        return orderedFound;
    }

    public void evict(Collection<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return;
        }
        Cache redisCache = scenicCache();
        for (String h3Index : normalizeIndexes(h3Indexes)) {
            evictLocal(h3Index);
            if (redisCache != null) {
                try {
                    redisCache.evict(CacheKeySchema.scenicTile(h3Index));
                } catch (RuntimeException ex) {
                    logger.debug("Failed to evict scenic tile {} from Redis cache", h3Index, ex);
                }
            }
        }
    }

    public void clearLocal() {
        synchronized (localTiles) {
            localTiles.clear();
        }
    }

    private Cache scenicCache() {
        try {
            return cacheManager.getCache(CacheNames.SCENIC_TILES);
        } catch (RuntimeException ex) {
            logger.debug("Scenic tile Redis cache unavailable", ex);
            return null;
        }
    }

    private ScenicScoreTile getRedis(Cache cache, String h3Index) {
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(CacheKeySchema.scenicTile(h3Index), ScenicScoreTile.class);
        } catch (RuntimeException ex) {
            logger.debug("Failed to read scenic tile {} from Redis cache", h3Index, ex);
            return null;
        }
    }

    private void putRedis(Cache cache, String h3Index, ScenicScoreTile tile) {
        if (cache == null || tile == null) {
            return;
        }
        try {
            cache.put(CacheKeySchema.scenicTile(h3Index), tile);
        } catch (RuntimeException ex) {
            logger.debug("Failed to write scenic tile {} to Redis cache", h3Index, ex);
        }
    }

    private ScenicScoreTile getLocal(String h3Index) {
        synchronized (localTiles) {
            return localTiles.get(h3Index);
        }
    }

    private void putLocal(String h3Index, ScenicScoreTile tile) {
        synchronized (localTiles) {
            localTiles.put(h3Index, tile);
            while (localTiles.size() > MAX_LOCAL_TILES) {
                String eldest = localTiles.keySet().iterator().next();
                localTiles.remove(eldest);
            }
        }
    }

    private void evictLocal(String h3Index) {
        synchronized (localTiles) {
            localTiles.remove(h3Index);
        }
    }

    private List<String> normalizeIndexes(Collection<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String h3Index : h3Indexes) {
            if (h3Index == null) {
                continue;
            }
            String trimmed = h3Index.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }
}
