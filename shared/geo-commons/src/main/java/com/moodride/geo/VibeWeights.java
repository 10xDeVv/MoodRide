package com.moodride.geo;

import java.util.HashMap;
import java.util.Map;

/**
 * Vibe-specific weight configurations for scenic scoring.
 * These weights determine how different signals are combined for each vibe preference.
 */
public class VibeWeights {
    
    public enum Vibe {
        COASTAL,
        MOUNTAIN,
        COUNTRYSIDE,
        FOREST,
        OPEN_ROADS,
        URBAN_EXPLORER
    }
    
    /**
     * Signal weights for each vibe.
     * Weights sum to 1.0 for each vibe.
     */
    private static final Map<Vibe, Map<String, Double>> VIBE_WEIGHTS = new HashMap<>();
    
    static {
        // Coastal: emphasize water proximity and curvature
        Map<String, Double> coastal = new HashMap<>();
        coastal.put("water_proximity", 0.40);
        coastal.put("elevation", 0.15);
        coastal.put("land_use", 0.15);
        coastal.put("curvature", 0.20);
        coastal.put("traffic", 0.05);
        coastal.put("poi", 0.05);
        VIBE_WEIGHTS.put(Vibe.COASTAL, coastal);
        
        // Mountain: emphasize elevation and curvature
        Map<String, Double> mountain = new HashMap<>();
        mountain.put("water_proximity", 0.10);
        mountain.put("elevation", 0.40);
        mountain.put("land_use", 0.15);
        mountain.put("curvature", 0.25);
        mountain.put("traffic", 0.05);
        mountain.put("poi", 0.05);
        VIBE_WEIGHTS.put(Vibe.MOUNTAIN, mountain);
        
        // Countryside: emphasize land use (farmland, rural)
        Map<String, Double> countryside = new HashMap<>();
        countryside.put("water_proximity", 0.15);
        countryside.put("elevation", 0.15);
        countryside.put("land_use", 0.40);
        countryside.put("curvature", 0.15);
        countryside.put("traffic", 0.10);
        countryside.put("poi", 0.05);
        VIBE_WEIGHTS.put(Vibe.COUNTRYSIDE, countryside);
        
        // Forest: emphasize land use (forest) and low traffic
        Map<String, Double> forest = new HashMap<>();
        forest.put("water_proximity", 0.10);
        forest.put("elevation", 0.20);
        forest.put("land_use", 0.40);
        forest.put("curvature", 0.15);
        forest.put("traffic", 0.10);
        forest.put("poi", 0.05);
        VIBE_WEIGHTS.put(Vibe.FOREST, forest);
        
        // Open Roads: emphasize curvature and low traffic
        Map<String, Double> openRoads = new HashMap<>();
        openRoads.put("water_proximity", 0.10);
        openRoads.put("elevation", 0.15);
        openRoads.put("land_use", 0.15);
        openRoads.put("curvature", 0.35);
        openRoads.put("traffic", 0.20);
        openRoads.put("poi", 0.05);
        VIBE_WEIGHTS.put(Vibe.OPEN_ROADS, openRoads);
        
        // Urban Explorer: emphasize POI and land use (urban)
        Map<String, Double> urbanExplorer = new HashMap<>();
        urbanExplorer.put("water_proximity", 0.15);
        urbanExplorer.put("elevation", 0.10);
        urbanExplorer.put("land_use", 0.25);
        urbanExplorer.put("curvature", 0.15);
        urbanExplorer.put("traffic", 0.05);
        urbanExplorer.put("poi", 0.30);
        VIBE_WEIGHTS.put(Vibe.URBAN_EXPLORER, urbanExplorer);
    }
    
    /**
     * Get the weight for a specific signal and vibe.
     */
    public static double getWeight(Vibe vibe, String signal) {
        Map<String, Double> weights = VIBE_WEIGHTS.get(vibe);
        if (weights == null) {
            throw new IllegalArgumentException("Unknown vibe: " + vibe);
        }
        Double weight = weights.get(signal);
        if (weight == null) {
            throw new IllegalArgumentException("Unknown signal: " + signal);
        }
        return weight;
    }
    
    /**
     * Get all weights for a vibe.
     */
    public static Map<String, Double> getWeights(Vibe vibe) {
        Map<String, Double> weights = VIBE_WEIGHTS.get(vibe);
        if (weights == null) {
            throw new IllegalArgumentException("Unknown vibe: " + vibe);
        }
        return new HashMap<>(weights); // Return a copy
    }
    
    /**
     * Calculate composite score from individual signal scores.
     */
    public static double calculateCompositeScore(Vibe vibe, Map<String, Double> signalScores) {
        Map<String, Double> weights = getWeights(vibe);
        double compositeScore = 0.0;
        
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String signal = entry.getKey();
            double weight = entry.getValue();
            double signalScore = signalScores.getOrDefault(signal, 0.5); // Default to neutral
            compositeScore += weight * signalScore;
        }
        
        return compositeScore;
    }
    
    /**
     * Parse vibe from string (case-insensitive).
     */
    public static Vibe parseVibe(String vibeString) {
        try {
            return Vibe.valueOf(vibeString.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid vibe: " + vibeString + 
                ". Valid vibes: coastal, mountain, countryside, forest, open_roads, urban_explorer");
        }
    }
}
