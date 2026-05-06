package com.moodride.routeworker.graph;

import com.moodride.datamodels.RoadSegment;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class RoadNetworkGraph {
    private final DefaultDirectedWeightedGraph<RoadNode, RoadSegmentEdge> graph;
    private final Map<RoadNode, RoadNode> nodeCache;
    
    public RoadNetworkGraph() {
        this.graph = new DefaultDirectedWeightedGraph<>(RoadSegmentEdge.class);
        this.nodeCache = new HashMap<>();
    }
    
    public void addRoadSegment(RoadSegment segment) {
        addRoadSegment(segment, 0.5);
    }

    public void addRoadSegment(RoadSegment segment, double scenicScore) {
        LineString geometry = segment.getGeometry();
        Coordinate[] coords = geometry.getCoordinates();
        
        if (coords.length < 2) return;
        
        RoadNode startNode = getOrCreateNode(coords[0]);
        RoadNode endNode = getOrCreateNode(coords[coords.length - 1]);
        
        RoadSegmentEdge edge = new RoadSegmentEdge(
            segment.getId(),
            segment.getLengthMeters(),
            scenicScore,
            segment.getRoadType()
        );
        
        graph.addVertex(startNode);
        graph.addVertex(endNode);
        graph.addEdge(startNode, endNode, edge);
        graph.setEdgeWeight(edge, edge.getWeight());
    }
    
    public void addRoadSegments(Collection<RoadSegment> segments) {
        segments.forEach(this::addRoadSegment);
    }

    public void addRoadSegments(Collection<RoadSegment> segments, Map<String, Double> scenicScoresByTile) {
        segments.forEach(segment -> {
            String h3TileIndex = segment.getH3TileIndex();
            double scenicScore = scenicScoresByTile.getOrDefault(h3TileIndex, 0.5);
            addRoadSegment(segment, scenicScore);
        });
    }

    private RoadNode getOrCreateNode(Coordinate coord) {
        RoadNode node = new RoadNode(coord.y, coord.x);
        return nodeCache.computeIfAbsent(node, k -> {
            graph.addVertex(k);
            return k;
        });
    }
    
    public DefaultDirectedWeightedGraph<RoadNode, RoadSegmentEdge> getGraph() {
        return graph;
    }
    
    public int getNodeCount() {
        return graph.vertexSet().size();
    }
    
    public int getEdgeCount() {
        return graph.edgeSet().size();
    }
    
    public RoadNode getNodeAt(double latitude, double longitude) {
        return nodeCache.get(new RoadNode(latitude, longitude));
    }

    public RoadNode getNearestNode(double latitude, double longitude) {
        RoadNode nearest = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (RoadNode node : nodeCache.keySet()) {
            double latDiff = node.getLatitude() - latitude;
            double lonDiff = node.getLongitude() - longitude;
            double distanceSquared = latDiff * latDiff + lonDiff * lonDiff;

            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                nearest = node;
            }
        }

        return nearest;
    }
}
