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
            List.of("water", "bridge_coastal"),
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
            "countryside", "Country", VibeCategory.CORE_SCENERY,
            weights(0.25, 0.75, 0.35, 0.90, 0.45, 0.20),
            List.of("solitude", "greenery", "open_space"),
            List.of("urban_penalty", "building_density", "road_stress"),
            true, true, 0.36, 0.29,
            "Quiet rural loop with open space and lighter urban density."
        ));
        add(profiles, profile(
            "riverside", "Riverside", VibeCategory.CORE_SCENERY,
            weights(0.90, 0.70, 0.25, 0.55, 0.35, 0.20),
            List.of("water", "bridge_coastal", "greenery"),
            List.of(),
            false, true, 0.34, 0.27,
            "Water-following route with green edges and calmer scenery."
        ));
        add(profiles, profile(
            "nature_escape", "Nature", VibeCategory.CORE_SCENERY,
            weights(0.35, 0.95, 0.45, 0.92, 0.35, 0.10),
            List.of("tree_canopy", "greenery", "solitude"),
            List.of("urban_penalty", "building_density", "road_stress"),
            true, true, 0.36, 0.29,
            "Nature-heavy loop that favors greenery, tree cover, and quieter edges."
        ));
        add(profiles, profile(
            "open_roads", "Open Road", VibeCategory.CORE_SCENERY,
            weights(0.15, 0.45, 0.25, 0.90, 0.25, 0.05),
            List.of("open_space", "solitude"),
            List.of("urban_penalty", "building_density", "poi", "road_density", "road_stress"),
            true, true, 0.35, 0.28,
            "Open-space drive with lower-density roads and room to cruise."
        ));
        add(profiles, profile(
            "adventure", "Adventure", VibeCategory.DRIVING_FEEL,
            weights(0.30, 0.55, 0.90, 0.40, 0.95, 0.20),
            List.of("curves", "elevation"),
            List.of(),
            true, true, 0.34, 0.27,
            "More adventurous route with stronger elevation and winding segments."
        ));
        add(profiles, profile(
            "relaxing", "Relaxing", VibeCategory.DRIVING_FEEL,
            weights(0.30, 0.70, 0.25, 0.96, 0.22, 0.10),
            List.of("solitude", "greenery"),
            List.of("urban_penalty", "building_density", "poi", "curves", "road_stress"),
            true, true, 0.36, 0.29,
            "Calmer loop with quiet scenery, greenery, and fewer intense road segments."
        ));
        add(profiles, profile(
            "winding_roads", "Winding", VibeCategory.DRIVING_FEEL,
            weights(0.25, 0.40, 0.65, 0.45, 0.98, 0.10),
            List.of("curves", "elevation"),
            List.of(),
            true, true, 0.34, 0.27,
            "More engaging route with winding road segments and terrain changes."
        ));

        return Collections.unmodifiableMap(profiles);
    }

    private static Map<String, String> buildAliases() {
        return Map.ofEntries(
            entry("country", "countryside"),
            entry("rural", "countryside"),
            entry("countryside", "countryside"),
            entry("sunday", "countryside"),
            entry("sunday_drive", "countryside"),
            entry("sunday_cruise", "countryside"),
            entry("scenic", "countryside"),
            entry("loop_with_variety", "countryside"),
            entry("variety", "countryside"),
            entry("coast", "coastal"),
            entry("coastak", "coastal"),
            entry("river", "riverside"),
            entry("forest", "nature_escape"),
            entry("woods", "nature_escape"),
            entry("nature", "nature_escape"),
            entry("nature_escaped", "nature_escape"),
            entry("open_road", "open_roads"),
            entry("openroad", "open_roads"),
            entry("openroads", "open_roads"),
            entry("winding", "winding_roads"),
            entry("winding_road", "winding_roads"),
            entry("twisty", "winding_roads"),
            entry("twisties", "winding_roads"),
            entry("fun_drive", "winding_roads"),
            entry("quiet", "relaxing"),
            entry("peaceful", "relaxing"),
            entry("calm", "relaxing"),
            entry("clear_head", "relaxing"),
            entry("clear_my_head", "relaxing"),
            entry("clear_my_mind", "relaxing"),
            entry("smooth", "relaxing"),
            entry("smooth_cruise", "relaxing"),
            entry("cruise", "relaxing"),
            entry("cruisek", "relaxing"),
            entry("minimaltraffic", "relaxing"),
            entry("minimal_traffic", "relaxing"),
            entry("low_traffic", "relaxing"),
            entry("lowtraffic", "relaxing"),
            entry("no_traffic", "relaxing"),
            entry("hide", "adventure"),
            entry("hidden", "adventure"),
            entry("hidden_gem", "adventure"),
            entry("hidden_gems", "adventure"),
            entry("photo", "adventure"),
            entry("photo_run", "adventure"),
            entry("photo_worthy", "adventure"),
            entry("photoworthy", "adventure"),
            entry("photo_op", "adventure"),
            entry("photo_ops", "adventure"),
            entry("photogenic", "adventure"),
            entry("date_night", "adventure"),
            entry("golden", "adventure"),
            entry("golden_hour", "adventure"),
            entry("sunset", "adventure"),
            entry("sunrise", "adventure"),
            entry("sunrise_sunset", "adventure"),
            entry("sunrise_and_sunset", "adventure"),
            entry("scenic_reset", "relaxing")
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
