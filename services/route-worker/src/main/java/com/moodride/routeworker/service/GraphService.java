package com.moodride.routeworker.service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.graph.RoadNetworkGraph;
import com.moodride.routeworker.repository.RoadSegmentRepository;
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphService {
    
    private final RoadSegmentRepository roadSegmentRepository;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private RoadNetworkGraph cachedGraph;
    
    public GraphService(RoadSegmentRepository roadSegmentRepository,
                        ScenicScoreTileRepository scenicScoreTileRepository) {
        this.roadSegmentRepository = roadSegmentRepository;
        this.scenicScoreTileRepository = scenicScoreTileRepository;
    }
    
    @Transactional(readOnly = true)
    public RoadNetworkGraph buildGraph() {
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
                        this::resolveEffectiveScenicScore,
                        (first, second) -> first
                ));

        graph.addRoadSegments(allSegments, scenicScoresByTile);
        this.cachedGraph = graph;
        return graph;
    }
    
    public RoadNetworkGraph getGraph() {
        if (cachedGraph == null) {
            return buildGraph();
        }
        return cachedGraph;
    }
    
    public void invalidateCache() {
        this.cachedGraph = null;
    }

    private double resolveEffectiveScenicScore(ScenicScoreTile tile) {
        double scenic = clamp01(tile.getScenicScore());
        double traffic = clamp01(tile.getTrafficSignalScore());
        String version = tile.getScoringVersion() == null ? "" : tile.getScoringVersion();

        // New scoring versions already include traffic as a first-class component.
        if (version.startsWith("2.1-traffic")) {
            return scenic;
        }

        // Backward compatibility for stale pre-2.1 tiles.
        return clamp01((scenic * 0.85) + (traffic * 0.15));
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
