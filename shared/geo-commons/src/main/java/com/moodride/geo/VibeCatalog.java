package com.moodride.geo;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Shared user-facing vibe taxonomy and backend contract.
 *
 * The UI can rename or alias vibes, but every supported vibe must resolve to a
 * profile here so route generation, availability, explanations, and evals use
 * the same intent instead of separate switches.
 */
public final class VibeCatalog {

    private static final String DEFAULT_VIBE = "countryside";
    private static final Map<String, VibeProfile> PROFILES = buildProfiles();
    private static final Map<String, String> ALIASES = buildAliases();

    private VibeCatalog() {
    }

    public static String defaultVibe() {
        return DEFAULT_VIBE;
    }

    public static Set<String> supportedVibes() {
        return PROFILES.keySet();
    }

    public static boolean isSupported(String rawVibe) {
        return normalizeIfSupported(rawVibe).isPresent();
    }

    public static Optional<String> normalizeIfSupported(String rawVibe) {
        String normalized = normalize(rawVibe);
        if (normalized.isBlank() || !PROFILES.containsKey(normalized)) {
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

    public static VibeProfile profileFor(String rawVibe) {
        String normalized = normalize(rawVibe);
        return PROFILES.getOrDefault(normalized, PROFILES.get(DEFAULT_VIBE));
    }

    public static List<VibeProfile> profilesFor(Collection<String> vibes) {
        if (vibes == null || vibes.isEmpty()) {
            return List.of(profileFor(DEFAULT_VIBE));
        }
        return vibes.stream()
            .map(VibeCatalog::profileFor)
            .distinct()
            .toList();
    }

    public static BlendedVibeProfile blendProfiles(Collection<String> vibes) {
        List<VibeProfile> profiles = profilesFor(vibes);
        double minBestFit = profiles.stream()
            .mapToDouble(VibeProfile::minBestFit)
            .average()
            .orElse(0.32);
        double minAverageFit = profiles.stream()
            .mapToDouble(VibeProfile::minAverageFit)
            .average()
            .orElse(0.26);
        boolean outwardRouting = profiles.stream().anyMatch(VibeProfile::outwardRouting);
        boolean strictIntent = profiles.stream().anyMatch(VibeProfile::strictIntent);
        List<String> targetComponents = profiles.stream()
            .flatMap(profile -> profile.targetComponents().stream())
            .distinct()
            .toList();
        List<String> antiComponents = profiles.stream()
            .flatMap(profile -> profile.antiComponents().stream())
            .distinct()
            .toList();
        return new BlendedVibeProfile(
            profiles,
            targetComponents,
            antiComponents,
            outwardRouting,
            strictIntent,
            minBestFit,
            minAverageFit
        );
    }

    public static ComponentWeights weightsFor(String rawVibe) {
        return profileFor(rawVibe).weights();
    }

    public static String displayNameFor(String rawVibe) {
        String normalized = normalize(rawVibe);
        VibeProfile profile = PROFILES.get(normalized);
        return profile == null ? rawVibe : profile.displayName();
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

    private static Map<String, VibeProfile> buildProfiles() {
        Map<String, VibeProfile> profiles = new LinkedHashMap<>();

        add(profiles, profile(
            "coastal", "Coastal", VibeCategory.CORE_SCENERY,
            weights(0.95, 0.55, 0.25, 0.45, 0.35, 0.25),
            List.of("water"),
            List.of(),
            false, true, 0.34, 0.27,
            "Water-led route with shoreline views and scenic pull-offs."
        ));
        add(profiles, profile(
            "mountain", "Mountain", VibeCategory.CORE_SCENERY,
            weights(0.15, 0.55, 0.95, 0.65, 0.85, 0.15),
            List.of("elevation", "curves"),
            List.of(),
            true, true, 0.34, 0.27,
            "Elevation-forward route with stronger terrain and winding segments."
        ));
        add(profiles, profile(
            "countryside", "Countryside", VibeCategory.CORE_SCENERY,
            weights(0.25, 0.75, 0.35, 0.90, 0.45, 0.20),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density"),
            true, true, 0.36, 0.29,
            "Quiet rural loop with open space and lighter urban density."
        ));
        add(profiles, profile(
            "riverside", "Riverside", VibeCategory.CORE_SCENERY,
            weights(0.90, 0.70, 0.25, 0.55, 0.35, 0.20),
            List.of("water", "greenery"),
            List.of(),
            false, true, 0.34, 0.27,
            "Water-following route with green edges and calmer scenery."
        ));
        add(profiles, profile(
            "forest", "Forest", VibeCategory.CORE_SCENERY,
            weights(0.20, 0.95, 0.35, 0.85, 0.35, 0.15),
            List.of("greenery", "solitude"),
            List.of("urban_penalty", "building_density"),
            true, true, 0.36, 0.29,
            "Green, tucked-away loop that favors tree cover and quieter surroundings."
        ));
        add(profiles, profile(
            "open_roads", "Open Roads", VibeCategory.CORE_SCENERY,
            weights(0.15, 0.45, 0.25, 0.90, 0.25, 0.05),
            List.of("open_space", "solitude"),
            List.of("urban_penalty", "building_density", "poi", "road_density"),
            true, true, 0.35, 0.28,
            "Open-space drive with lower-density roads and room to cruise."
        ));

        add(profiles, profile(
            "relaxing", "Relaxing", VibeCategory.DRIVING_FEEL,
            weights(0.35, 0.65, 0.20, 0.90, 0.20, 0.15),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density", "curves"),
            true, false, 0.34, 0.27,
            "Calmer loop with softer scenery and fewer intense road segments."
        ));
        add(profiles, profile(
            "winding_roads", "Winding Roads", VibeCategory.DRIVING_FEEL,
            weights(0.25, 0.40, 0.65, 0.45, 0.98, 0.10),
            List.of("curves", "elevation"),
            List.of(),
            true, true, 0.34, 0.27,
            "More engaging route with winding road segments and terrain changes."
        ));
        add(profiles, profile(
            "smooth_cruise", "Smooth Cruise", VibeCategory.DRIVING_FEEL,
            weights(0.35, 0.55, 0.20, 0.70, 0.15, 0.15),
            List.of("solitude", "greenery"),
            List.of("curves", "urban_penalty"),
            false, false, 0.31, 0.25,
            "Best for a smooth cruise with light curves and open space."
        ));
        add(profiles, profile(
            "quiet", "Quiet", VibeCategory.DRIVING_FEEL,
            weights(0.20, 0.65, 0.25, 0.98, 0.25, 0.05),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density", "poi"),
            true, true, 0.36, 0.29,
            "Quiet loop that favors lower-density scenery and fewer busy-feeling corridors."
        ));
        add(profiles, profile(
            "hidden_gems", "Hidden Gems", VibeCategory.DRIVING_FEEL,
            weights(0.40, 0.70, 0.45, 0.80, 0.55, 0.45),
            List.of("solitude", "poi", "curves"),
            List.of("urban_penalty"),
            true, false, 0.32, 0.26,
            "Less obvious route with scenic stops and more secluded segments."
        ));
        add(profiles, profile(
            "minimal_traffic", "Minimal Traffic", VibeCategory.DRIVING_FEEL,
            weights(0.15, 0.60, 0.25, 0.98, 0.25, 0.05),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density", "poi"),
            true, true, 0.36, 0.29,
            "Lower-density route aimed at calmer roads and lighter urban pressure."
        ));
        add(profiles, profile(
            "loop_variety", "Loop Variety", VibeCategory.DRIVING_FEEL,
            weights(0.55, 0.60, 0.50, 0.55, 0.70, 0.30),
            List.of("water", "greenery", "elevation", "solitude", "curves"),
            List.of(),
            false, false, 0.30, 0.24,
            "Mixed loop that trades a single strong signal for varied scenery."
        ));

        add(profiles, profile(
            "scenic", "Scenic", VibeCategory.TRIP_MOOD,
            weights(0.65, 0.70, 0.55, 0.65, 0.50, 0.25),
            List.of("water", "greenery", "elevation", "solitude", "curves"),
            List.of(),
            false, false, 0.30, 0.24,
            "Balanced scenic loop with multiple landscape signals."
        ));
        add(profiles, profile(
            "clear_my_head", "Clear My Head", VibeCategory.TRIP_MOOD,
            weights(0.25, 0.75, 0.25, 0.98, 0.20, 0.05),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density", "poi", "curves"),
            true, true, 0.36, 0.29,
            "Low-noise route built around quiet scenery and mental reset."
        ));
        add(profiles, profile(
            "date_night", "Date Night", VibeCategory.TRIP_MOOD,
            weights(0.80, 0.45, 0.50, 0.55, 0.25, 0.55),
            List.of("water", "elevation", "poi"),
            List.of(),
            false, false, 0.31, 0.25,
            "View-led route with memorable stops and evening-friendly scenery."
        ));
        add(profiles, profile(
            "sunday_cruise", "Sunday Cruise", VibeCategory.TRIP_MOOD,
            weights(0.25, 0.70, 0.25, 0.82, 0.35, 0.15),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density"),
            true, false, 0.34, 0.27,
            "Easy countryside-leaning loop for a relaxed weekend drive."
        ));
        add(profiles, profile(
            "adventure", "Adventure", VibeCategory.TRIP_MOOD,
            weights(0.30, 0.55, 0.90, 0.65, 0.95, 0.20),
            List.of("curves", "elevation"),
            List.of(),
            true, true, 0.34, 0.27,
            "More adventurous route with stronger elevation and winding segments."
        ));
        add(profiles, profile(
            "photo_run", "Photo Run", VibeCategory.TRIP_MOOD,
            weights(0.80, 0.60, 0.75, 0.45, 0.55, 0.55),
            List.of("water", "elevation", "poi"),
            List.of(),
            false, false, 0.31, 0.25,
            "Photo-friendly loop with viewpoints, water, terrain, or scenic stops."
        ));
        add(profiles, profile(
            "photo_worthy", "Photo-Worthy", VibeCategory.TRIP_MOOD,
            weights(0.80, 0.60, 0.75, 0.45, 0.55, 0.55),
            List.of("water", "elevation", "poi"),
            List.of(),
            false, false, 0.31, 0.25,
            "Photo-friendly loop with viewpoints, water, terrain, or scenic stops."
        ));
        add(profiles, profile(
            "nature_escape", "Nature Escape", VibeCategory.TRIP_MOOD,
            weights(0.35, 0.95, 0.45, 0.92, 0.35, 0.10),
            List.of("greenery", "solitude"),
            List.of("urban_penalty", "building_density"),
            true, true, 0.36, 0.29,
            "Nature-heavy loop that favors greenery, protected areas, and quieter edges."
        ));
        add(profiles, profile(
            "scenic_reset", "Scenic Reset", VibeCategory.TRIP_MOOD,
            weights(0.50, 0.75, 0.40, 0.85, 0.30, 0.15),
            List.of("greenery", "solitude", "water"),
            List.of("urban_penalty"),
            true, false, 0.33, 0.26,
            "Restorative scenic loop with calming natural signals."
        ));
        add(profiles, profile(
            "golden_hour", "Golden Hour", VibeCategory.TRIP_MOOD,
            weights(0.85, 0.45, 0.65, 0.45, 0.25, 0.35),
            List.of("water", "elevation"),
            List.of(),
            false, false, 0.31, 0.25,
            "Open-view route tuned for water, ridgelines, and golden-hour scenery."
        ));
        add(profiles, profile(
            "sunset", "Sunset", VibeCategory.TRIP_MOOD,
            weights(0.85, 0.45, 0.65, 0.45, 0.25, 0.35),
            List.of("water", "elevation"),
            List.of(),
            false, false, 0.31, 0.25,
            "Open-view route tuned for water, ridgelines, and sunset scenery."
        ));
        add(profiles, profile(
            "sunrise", "Sunrise", VibeCategory.TRIP_MOOD,
            weights(0.75, 0.55, 0.60, 0.55, 0.25, 0.25),
            List.of("water", "elevation"),
            List.of(),
            false, false, 0.31, 0.25,
            "Open-view route tuned for early light, water, and elevation."
        ));

        return Collections.unmodifiableMap(profiles);
    }

    private static Map<String, String> buildAliases() {
        return Map.ofEntries(
            entry("country", "countryside"),
            entry("rural", "countryside"),
            entry("coast", "coastal"),
            entry("coastak", "coastal"),
            entry("river", "riverside"),
            entry("woods", "forest"),
            entry("open_road", "open_roads"),
            entry("openroad", "open_roads"),
            entry("openroads", "open_roads"),
            entry("winding", "winding_roads"),
            entry("winding_road", "winding_roads"),
            entry("twisty", "winding_roads"),
            entry("twisties", "winding_roads"),
            entry("fun_drive", "winding_roads"),
            entry("cruise", "smooth_cruise"),
            entry("cruisek", "smooth_cruise"),
            entry("smooth", "smooth_cruise"),
            entry("peaceful", "quiet"),
            entry("calm", "relaxing"),
            entry("hide", "hidden_gems"),
            entry("hidden", "hidden_gems"),
            entry("hidden_gem", "hidden_gems"),
            entry("minimaltraffic", "minimal_traffic"),
            entry("low_traffic", "minimal_traffic"),
            entry("lowtraffic", "minimal_traffic"),
            entry("no_traffic", "minimal_traffic"),
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
            entry("sunday", "sunday_cruise"),
            entry("sunday_drive", "sunday_cruise")
        );
    }

    private static void add(Map<String, VibeProfile> profiles, VibeProfile profile) {
        profiles.put(profile.id(), profile);
    }

    private static VibeProfile profile(String id,
                                       String displayName,
                                       VibeCategory category,
                                       ComponentWeights weights,
                                       List<String> targetComponents,
                                       List<String> antiComponents,
                                       boolean outwardRouting,
                                       boolean strictIntent,
                                       double minBestFit,
                                       double minAverageFit,
                                       String explanationTemplate) {
        return new VibeProfile(
            id,
            displayName,
            category,
            weights,
            List.copyOf(targetComponents),
            List.copyOf(antiComponents),
            outwardRouting,
            strictIntent,
            minBestFit,
            minAverageFit,
            explanationTemplate
        );
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

    public record VibeProfile(String id,
                              String displayName,
                              VibeCategory category,
                              ComponentWeights weights,
                              List<String> targetComponents,
                              List<String> antiComponents,
                              boolean outwardRouting,
                              boolean strictIntent,
                              double minBestFit,
                              double minAverageFit,
                              String explanationTemplate) {
    }

    public record BlendedVibeProfile(List<VibeProfile> profiles,
                                     List<String> targetComponents,
                                     List<String> antiComponents,
                                     boolean outwardRouting,
                                     boolean strictIntent,
                                     double minBestFit,
                                     double minAverageFit) {
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
