package com.moodride.geo;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Shared user-facing vibe taxonomy.
 *
 * Core services use these weights for request defaults, scenic previews, and
 * route-quality checks. Keep labels broad enough for UI search while mapping
 * back to the six route-scoring components.
 */
public final class VibeCatalog {

    private static final String DEFAULT_VIBE = "countryside";
    private static final Map<String, VibeDefinition> DEFINITIONS = buildDefinitions();
    private static final Map<String, String> ALIASES = buildAliases();

    private VibeCatalog() {
    }

    public static String defaultVibe() {
        return DEFAULT_VIBE;
    }

    public static Set<String> supportedVibes() {
        return DEFINITIONS.keySet();
    }

    public static boolean isSupported(String rawVibe) {
        return normalizeIfSupported(rawVibe).isPresent();
    }

    public static Optional<String> normalizeIfSupported(String rawVibe) {
        String normalized = normalize(rawVibe);
        if (normalized.isBlank() || !DEFINITIONS.containsKey(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    public static String normalize(String rawVibe) {
        if (rawVibe == null || rawVibe.isBlank()) {
            return "";
        }
        String normalized = rawVibe.trim()
            .toLowerCase(Locale.ROOT)
            .replace("&", " and ")
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static ComponentWeights weightsFor(String rawVibe) {
        String normalized = normalize(rawVibe);
        VibeDefinition definition = DEFINITIONS.getOrDefault(normalized, DEFINITIONS.get(DEFAULT_VIBE));
        return definition.weights();
    }

    public static String displayNameFor(String rawVibe) {
        String normalized = normalize(rawVibe);
        VibeDefinition definition = DEFINITIONS.get(normalized);
        return definition == null ? rawVibe : definition.displayName();
    }

    public static String displayList(Collection<String> vibes) {
        if (vibes == null || vibes.isEmpty()) {
            return displayNameFor(DEFAULT_VIBE);
        }
        return vibes.stream()
            .map(VibeCatalog::displayNameFor)
            .filter(label -> label != null && !label.isBlank())
            .reduce((left, right) -> left + " + " + right)
            .orElse(displayNameFor(DEFAULT_VIBE));
    }

    private static Map<String, VibeDefinition> buildDefinitions() {
        Map<String, VibeDefinition> definitions = new LinkedHashMap<>();

        add(definitions, "coastal", "Coastal", VibeCategory.CORE_SCENERY, weights(0.90, 0.70, 0.30, 0.60, 0.45, 0.20));
        add(definitions, "mountain", "Mountain", VibeCategory.CORE_SCENERY, weights(0.20, 0.55, 0.90, 0.70, 0.80, 0.20));
        add(definitions, "countryside", "Countryside", VibeCategory.CORE_SCENERY, weights(0.40, 0.70, 0.45, 0.70, 0.60, 0.30));
        add(definitions, "riverside", "Riverside", VibeCategory.CORE_SCENERY, weights(0.85, 0.75, 0.35, 0.65, 0.45, 0.25));
        add(definitions, "forest", "Forest", VibeCategory.CORE_SCENERY, weights(0.30, 0.90, 0.45, 0.80, 0.45, 0.20));
        add(definitions, "open_roads", "Open Roads", VibeCategory.CORE_SCENERY, weights(0.25, 0.45, 0.35, 0.40, 0.90, 0.25));

        add(definitions, "relaxing", "Relaxing", VibeCategory.DRIVING_FEEL, weights(0.45, 0.65, 0.25, 0.85, 0.30, 0.25));
        add(definitions, "winding_roads", "Winding Roads", VibeCategory.DRIVING_FEEL, weights(0.35, 0.45, 0.65, 0.55, 0.95, 0.15));
        add(definitions, "smooth_cruise", "Smooth Cruise", VibeCategory.DRIVING_FEEL, weights(0.35, 0.50, 0.25, 0.60, 0.25, 0.20));
        add(definitions, "quiet", "Quiet", VibeCategory.DRIVING_FEEL, weights(0.30, 0.70, 0.35, 0.95, 0.35, 0.10));
        add(definitions, "hidden_gems", "Hidden Gems", VibeCategory.DRIVING_FEEL, weights(0.45, 0.70, 0.55, 0.80, 0.65, 0.45));
        add(definitions, "minimal_traffic", "Minimal Traffic", VibeCategory.DRIVING_FEEL, weights(0.25, 0.60, 0.30, 0.95, 0.40, 0.10));
        add(definitions, "loop_variety", "Loop Variety", VibeCategory.DRIVING_FEEL, weights(0.55, 0.60, 0.50, 0.55, 0.70, 0.35));

        add(definitions, "scenic", "Scenic", VibeCategory.TRIP_MOOD, weights(0.65, 0.70, 0.60, 0.65, 0.55, 0.30));
        add(definitions, "clear_my_head", "Clear My Head", VibeCategory.TRIP_MOOD, weights(0.35, 0.75, 0.35, 0.95, 0.25, 0.10));
        add(definitions, "date_night", "Date Night", VibeCategory.TRIP_MOOD, weights(0.75, 0.55, 0.45, 0.65, 0.35, 0.55));
        add(definitions, "sunday_cruise", "Sunday Cruise", VibeCategory.TRIP_MOOD, weights(0.35, 0.65, 0.30, 0.70, 0.45, 0.25));
        add(definitions, "adventure", "Adventure", VibeCategory.TRIP_MOOD, weights(0.40, 0.55, 0.90, 0.70, 0.90, 0.25));
        add(definitions, "photo_run", "Photo Run", VibeCategory.TRIP_MOOD, weights(0.75, 0.65, 0.75, 0.55, 0.60, 0.50));
        add(definitions, "photo_worthy", "Photo-Worthy", VibeCategory.TRIP_MOOD, weights(0.75, 0.65, 0.75, 0.55, 0.60, 0.50));
        add(definitions, "nature_escape", "Nature Escape", VibeCategory.TRIP_MOOD, weights(0.45, 0.90, 0.55, 0.90, 0.45, 0.15));
        add(definitions, "scenic_reset", "Scenic Reset", VibeCategory.TRIP_MOOD, weights(0.55, 0.70, 0.45, 0.80, 0.40, 0.20));
        add(definitions, "golden_hour", "Golden Hour", VibeCategory.TRIP_MOOD, weights(0.75, 0.50, 0.55, 0.55, 0.35, 0.35));
        add(definitions, "sunset", "Sunset", VibeCategory.TRIP_MOOD, weights(0.75, 0.50, 0.55, 0.55, 0.35, 0.35));
        add(definitions, "sunrise", "Sunrise", VibeCategory.TRIP_MOOD, weights(0.70, 0.55, 0.55, 0.60, 0.35, 0.30));

        return Collections.unmodifiableMap(definitions);
    }

    private static Map<String, String> buildAliases() {
        return Map.ofEntries(
            entry("open_road", "open_roads"),
            entry("openroad", "open_roads"),
            entry("openroads", "open_roads"),
            entry("winding", "winding_roads"),
            entry("winding_road", "winding_roads"),
            entry("twisty", "winding_roads"),
            entry("twisties", "winding_roads"),
            entry("fun_drive", "winding_roads"),
            entry("peaceful", "quiet"),
            entry("calm", "relaxing"),
            entry("minimaltraffic", "minimal_traffic"),
            entry("low_traffic", "minimal_traffic"),
            entry("no_traffic", "minimal_traffic"),
            entry("hidden_gem", "hidden_gems"),
            entry("loop_with_variety", "loop_variety"),
            entry("variety", "loop_variety"),
            entry("photo", "photo_worthy"),
            entry("photo_worthy", "photo_worthy"),
            entry("photoworthy", "photo_worthy"),
            entry("photo_op", "photo_worthy"),
            entry("photo_ops", "photo_worthy"),
            entry("photogenic", "photo_worthy"),
            entry("golden", "golden_hour"),
            entry("goldenhour", "golden_hour"),
            entry("sunrise_sunset", "golden_hour"),
            entry("sunrise_and_sunset", "golden_hour"),
            entry("nature", "nature_escape"),
            entry("nature_escaped", "nature_escape"),
            entry("clear_head", "clear_my_head"),
            entry("clear_my_mind", "clear_my_head"),
            entry("sunday_drive", "sunday_cruise")
        );
    }

    private static void add(Map<String, VibeDefinition> definitions,
                            String id,
                            String displayName,
                            VibeCategory category,
                            ComponentWeights weights) {
        definitions.put(id, new VibeDefinition(id, displayName, category, weights));
    }

    private static ComponentWeights weights(double water,
                                            double greenery,
                                            double elevation,
                                            double solitude,
                                            double curves,
                                            double poi) {
        return new ComponentWeights(water, greenery, elevation, solitude, curves, poi);
    }

    public enum VibeCategory {
        CORE_SCENERY,
        DRIVING_FEEL,
        TRIP_MOOD
    }

    public record VibeDefinition(String id, String displayName, VibeCategory category, ComponentWeights weights) {
    }

    public record ComponentWeights(double water,
                                   double greenery,
                                   double elevation,
                                   double solitude,
                                   double curves,
                                   double poi) {
        public double totalWeight() {
            return water + greenery + elevation + solitude + curves + poi;
        }
    }
}
