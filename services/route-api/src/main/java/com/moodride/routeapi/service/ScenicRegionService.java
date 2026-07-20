package com.moodride.routeapi.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeapi.dto.BoundingBoxResponse;
import com.moodride.routeapi.dto.ScenicRegionResponse;
import com.moodride.routeapi.dto.ScenicRegionsResponse;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScenicRegionService {

    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final String scenicScoringVersion;
    private final ScenicScoreCalculator scenicScoreCalculator = new ScenicScoreCalculator();

    public ScenicRegionService(
        ScenicScoreTileRepository scenicScoreTileRepository,
        ScenicCacheConfiguration scenicCacheConfiguration
    ) {
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.scenicScoringVersion = scenicCacheConfiguration.getScenicScoringVersion();
    }

    public ScenicRegionsResponse getScenicRegions(
        double latitude,
        double longitude,
        double radiusKm,
        int limit,
        String vibe
    ) {
        double sanitizedRadiusKm = Math.max(1.0, radiusKm);
        int sanitizedLimit = Math.max(1, Math.min(limit, 100));
        int candidateLimit = Math.min(Math.max(sanitizedLimit * 3, sanitizedLimit), 300);
        String parsedVibe = normalizeVibe(vibe);

        List<ScenicRegionResponse> regions = scenicScoreTileRepository
            .findTopScenicRegionsNearPoint(
                scenicScoringVersion,
                latitude,
                longitude,
                sanitizedRadiusKm * 1000.0,
                candidateLimit
            )
            .stream()
            .filter(tile -> tile != null && scenicScoringVersion.equals(tile.getScoringVersion()))
            .sorted(Comparator.comparingDouble((ScenicScoreTile tile) -> scoreTile(tile, parsedVibe)).reversed())
            .limit(sanitizedLimit)
            .map(tile -> toResponse(tile, scoreTile(tile, parsedVibe)))
            .toList();

        return new ScenicRegionsResponse(
            regions,
            regions.size(),
            buildBoundingBox(latitude, longitude, sanitizedRadiusKm)
        );
    }

    private ScenicRegionResponse toResponse(ScenicScoreTile tile, double compositeScore) {
        Point center = tile.getGeometry().getCentroid();
        return new ScenicRegionResponse(
            tile.getH3Index(),
            center.getY(),
            center.getX(),
            compositeScore,
            getDominantFeature(tile),
            getConfidence(tile)
        );
    }

    private double scoreTile(ScenicScoreTile tile, String vibe) {
        if (vibe == null) {
            return clamp01(tile.getScenicScore());
        }

        VibeCatalog.ComponentWeights weights = VibeCatalog.weightsFor(vibe);
        return scenicScoreCalculator.scoreTile(
            tile,
            new PreferenceWeights(
                weights.water(),
                weights.greenery(),
                weights.elevation(),
                weights.solitude(),
                weights.curves(),
                weights.poi()
            )
        );
    }

    private String normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            return null;
        }
        return VibeCatalog.normalizeIfSupported(vibe).orElse(null);
    }

    private BoundingBoxResponse buildBoundingBox(double latitude, double longitude, double radiusKm) {
        double latitudeDelta = radiusKm / 111.0;
        double longitudeDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(latitude)));

        return new BoundingBoxResponse(
            latitude + latitudeDelta,
            latitude - latitudeDelta,
            longitude + longitudeDelta,
            longitude - longitudeDelta
        );
    }

    private String getDominantFeature(ScenicScoreTile tile) {
        double bestScore = tile.getWaterProximity();
        String dominantFeature = "waterfront";

        if (tile.getElevationVariance() > bestScore) {
            bestScore = tile.getElevationVariance();
            dominantFeature = "elevation";
        }
        if (tile.getNaturalLandUse() > bestScore) {
            bestScore = tile.getNaturalLandUse();
            dominantFeature = "nature";
        }
        if (tile.getPoiDensity() > bestScore) {
            bestScore = tile.getPoiDensity();
            dominantFeature = "landmarks";
        }
        if (tile.getVisualComplexity() > bestScore) {
            dominantFeature = "views";
        }

        return dominantFeature;
    }

    private double getConfidence(ScenicScoreTile tile) {
        return Math.min(
            1.0,
            Math.max(
                0.1,
                (
                    tile.getWaterProximity()
                    + tile.getElevationVariance()
                    + tile.getNaturalLandUse()
                    + tile.getPoiDensity()
                    + tile.getVisualComplexity()
                ) / 5.0
            )
        );
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
