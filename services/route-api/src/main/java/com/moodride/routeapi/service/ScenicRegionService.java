package com.moodride.routeapi.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.geo.VibeWeights;
import com.moodride.routeapi.dto.BoundingBoxResponse;
import com.moodride.routeapi.dto.ScenicRegionResponse;
import com.moodride.routeapi.dto.ScenicRegionsResponse;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScenicRegionService {

    private final ScenicScoreTileRepository scenicScoreTileRepository;

    public ScenicRegionService(ScenicScoreTileRepository scenicScoreTileRepository) {
        this.scenicScoreTileRepository = scenicScoreTileRepository;
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
        VibeWeights.Vibe parsedVibe = normalizeVibe(vibe);

        List<ScenicRegionResponse> regions = scenicScoreTileRepository
            .findTopScenicRegionsNearPoint(
                latitude,
                longitude,
                sanitizedRadiusKm * 1000.0,
                candidateLimit
            )
            .stream()
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

    private double scoreTile(ScenicScoreTile tile, VibeWeights.Vibe vibe) {
        if (vibe == null) {
            return tile.getScenicScore();
        }

        Map<String, Double> signals = new HashMap<>();
        signals.put("water_proximity", tile.getWaterProximity());
        signals.put("elevation", tile.getElevationVariance());
        signals.put("land_use", tile.getNaturalLandUse());
        signals.put("curvature", tile.getVisualComplexity());
        signals.put("traffic", tile.getTrafficSignalScore());
        signals.put("poi", tile.getPoiDensity());
        return VibeWeights.calculateCompositeScore(vibe, signals);
    }

    private VibeWeights.Vibe normalizeVibe(String vibe) {
        if (vibe == null || vibe.isBlank()) {
            return null;
        }
        String normalized = vibe.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return VibeWeights.Vibe.valueOf(normalized);
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
}
