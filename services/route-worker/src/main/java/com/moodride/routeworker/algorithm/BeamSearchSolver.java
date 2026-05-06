package com.moodride.routeworker.algorithm;

import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNetworkGraph;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.graph.RoadSegmentEdge;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BeamSearchSolver {
    
    private final ApplicationConfiguration config;
    
    public BeamSearchSolver(ApplicationConfiguration config) {
        this.config = config;
    }
    
    public RouteCandidate solveOrienteering(RoadNetworkGraph graph, double startLat, 
                                            double startLon, int timeBudgetMinutes) {
        RoadNode startNode = graph.getNearestNode(startLat, startLon);
        if (startNode == null) {
            throw new IllegalStateException("Road network graph is empty");
        }
        DefaultDirectedWeightedGraph<RoadNode, RoadSegmentEdge> g = graph.getGraph();
        
        // Initialize with starting node
        PriorityQueue<RouteCandidate> candidates = new PriorityQueue<>();
        candidates.offer(new RouteCandidate(
            List.of(startNode), 0.0, 0.0, 0, "beam_v1", config.getBeamWidth()
        ));
        
        // Beam search iterations
        for (int iter = 0; iter < config.getMaxIterations(); iter++) {
            PriorityQueue<RouteCandidate> nextCandidates = new PriorityQueue<>();
            
            Set<RouteCandidate> processed = new HashSet<>();
            for (RouteCandidate candidate : candidates) {
                if (processed.size() >= config.getBeamWidth()) break;
                if (processed.contains(candidate)) continue;
                processed.add(candidate);
                
                // Expand this candidate
                RoadNode current = candidate.getWaypoints().get(candidate.getWaypoints().size() - 1);
                
                // Explore neighbors
                for (RoadSegmentEdge edge : g.outgoingEdgesOf(current)) {
                    RoadNode next = g.getEdgeTarget(edge);
                    
                    // Calculate new metrics
                    int newTime = candidate.getEstimatedMinutes() + (int)(edge.getLengthMeters() / 83.33);
                    double newDistance = candidate.getTotalDistanceKm() + (edge.getLengthMeters() / 1000.0);
                    double newScore = candidate.getTotalScenicScore() + (edge.getScenicScore() / 100.0);
                    
                    // Check time constraint
                    if (newTime <= timeBudgetMinutes) {
                        List<RoadNode> newPath = new ArrayList<>(candidate.getWaypoints());
                        newPath.add(next);
                        
                        RouteCandidate newCandidate = new RouteCandidate(
                            newPath, newScore, newDistance, newTime, "beam_v1", config.getBeamWidth()
                        );
                        nextCandidates.offer(newCandidate);
                    }
                }
            }
            
            if (nextCandidates.isEmpty()) break;
            candidates = nextCandidates;
        }
        
        // Return best candidate (highest scenic score)
        return candidates.peek() != null ? candidates.peek() : 
            new RouteCandidate(List.of(startNode), 0.0, 0.0, 0, "beam_v1", config.getBeamWidth());
    }
}
