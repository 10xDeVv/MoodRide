package com.moodride.routeworker.service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.routeworker.graph.RoadNetworkGraph;
import com.moodride.routeworker.repository.RoadSegmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphService {
    
    private final RoadSegmentRepository roadSegmentRepository;
    private RoadNetworkGraph cachedGraph;
    
    public GraphService(RoadSegmentRepository roadSegmentRepository) {
        this.roadSegmentRepository = roadSegmentRepository;
    }
    
    @Transactional(readOnly = true)
    public RoadNetworkGraph buildGraph() {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        Iterable<RoadSegment> allSegments = roadSegmentRepository.findAll();
        graph.addRoadSegments((java.util.Collection<RoadSegment>) allSegments);
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
}
