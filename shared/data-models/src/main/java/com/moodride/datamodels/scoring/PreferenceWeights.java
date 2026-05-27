package com.moodride.datamodels.scoring;

import java.util.Map;

public record PreferenceWeights(double water,
                                double greenery,
                                double elevation,
                                double solitude,
                                double curves,
                                double poi) {
    public double totalWeight() {
        return Math.max(0.0001, water + greenery + elevation + solitude + curves + poi);
    }

    public PreferenceWeights withOverrides(Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        return new PreferenceWeights(
            overrides.getOrDefault("water", water),
            overrides.getOrDefault("greenery", greenery),
            overrides.getOrDefault("elevation", elevation),
            overrides.getOrDefault("solitude", solitude),
            overrides.getOrDefault("curves", curves),
            overrides.getOrDefault("poi", poi)
        );
    }

    public PreferenceWeights normalized() {
        double total = totalWeight();
        return new PreferenceWeights(
            water / total,
            greenery / total,
            elevation / total,
            solitude / total,
            curves / total,
            poi / total
        );
    }

    public Map<String, Double> componentRatios() {
        PreferenceWeights normalized = normalized();
        return Map.of(
            "water", normalized.water(),
            "greenery", normalized.greenery(),
            "elevation", normalized.elevation(),
            "solitude", normalized.solitude(),
            "curves", normalized.curves(),
            "poi", normalized.poi()
        );
    }
}
