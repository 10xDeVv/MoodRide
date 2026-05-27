package com.moodride.routeworker.service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.datamodels.scoring.PreferenceWeights;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import com.moodride.geo.VibeCatalog;
import com.moodride.routeworker.graph.RoadNetworkGraph;
import com.moodride.routeworker.repository.RoadSegmentRepository;
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphService {
    private static final int MAX_CACHED_GRAPHS = 16;
    
    private final RoadSegmentRepository roadSegmentRepository;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final ScenicScoreCalculator scenicScoreCalculator;
    private final PreferenceWeights defaultPreferences;
    private final Map<String, RoadNetworkGraph> cachedGraphs = new LinkedHashMap<>(16, 0.75f, true);
    
    public GraphService(RoadSegmentRepository roadSegmentRepository,
                        ScenicScoreTileRepository scenicScoreTileRepository,
                        ScenicScoreCalculator scenicScoreCalculator) {
        this.roadSegmentRepository = roadSegmentRepository;
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.scenicScoreCalculator = scenicScoreCalculator;
        VibeCatalog.ComponentWeights defaults = VibeCatalog.weightsFor(VibeCatalog.defaultVibe());
        this.defaultPreferences = new PreferenceWeights(
            defaults.water(),
            defaults.greenery(),
            defaults.elevation(),
            defaults.solitude(),
            defaults.curves(),
            defaults.poi()
        );
    }
    
    @Transactional(readOnly = true)
    public RoadNetworkGraph buildGraph() {
        return buildGraph(defaultPreferences);
    }

    @Transactional(readOnly = true)
    public RoadNetworkGraph buildGraph(PreferenceWeights preferences) {
        PreferenceWeights effective = preferences == null ? defaultPreferences : preferences;
        String cacheKey = cacheKey(effective);
        RoadNetworkGraph graph = new RoadNetworkGraph();

        List<RoadSegment> allSegments = roadSegmentRepository.findAll();
        Set<String> h3Indexes = allSegments.stream()
                .map(RoadSegment::getH3TileIndex)
                .filter(index -> index != null && !index.isBlank())
                .collect(Collectors.toSet());

        Map<String, Double> scenicScoresByTile = scenicScoreTileRepository.findByH3IndexIn(h3Indexes)
                .stream()
                .collect(Collectors.toMap(
                        ScenicScoreTile::getH3Index,
                        tile -> scenicScoreCalculator.scoreTile(tile, effective),
                        (first, second) -> first
                ));

        graph.addRoadSegments(allSegments, scenicScoresByTile);
        putCachedGraph(cacheKey, graph);
        return graph;
    }
    
    public RoadNetworkGraph getGraph() {
        return getGraph(defaultPreferences);
    }

    public RoadNetworkGraph getGraph(PreferenceWeights preferences) {
        PreferenceWeights effective = preferences == null ? defaultPreferences : preferences;
        String cacheKey = cacheKey(effective);
        RoadNetworkGraph cachedGraph = getCachedGraph(cacheKey);
        if (cachedGraph == null) {
            return buildGraph(effective);
        }
        return cachedGraph;
    }
    
    public void invalidateCache() {
        synchronized (cachedGraphs) {
            cachedGraphs.clear();
        }
    }

    private RoadNetworkGraph getCachedGraph(String cacheKey) {
        synchronized (cachedGraphs) {
            return cachedGraphs.get(cacheKey);
        }
    }

    private void putCachedGraph(String cacheKey, RoadNetworkGraph graph) {
        synchronized (cachedGraphs) {
            cachedGraphs.put(cacheKey, graph);
            while (cachedGraphs.size() > MAX_CACHED_GRAPHS) {
                String eldestKey = cachedGraphs.keySet().iterator().next();
                cachedGraphs.remove(eldestKey);
            }
        }
    }

    private String cacheKey(PreferenceWeights preferences) {
        PreferenceWeights normalized = preferences.normalized();
        return String.format(
            java.util.Locale.ROOT,
            "%.4f|%.4f|%.4f|%.4f|%.4f|%.4f",
            normalized.water(),
            normalized.greenery(),
            normalized.elevation(),
            normalized.solitude(),
            normalized.curves(),
            normalized.poi()
        );
    }
}
