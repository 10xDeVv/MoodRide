package com.moodride.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VibeCatalogTest {

    @Test
    void normalizesFrontendAliasesToBackendVibes() {
        assertEquals("countryside", VibeCatalog.normalize("country"));
        assertEquals("minimal_traffic", VibeCatalog.normalize("low traffic"));
        assertEquals("smooth_cruise", VibeCatalog.normalize("cruise"));
        assertEquals("photo_worthy", VibeCatalog.normalize("photo"));
    }

    @Test
    void blendsProfilesIntoSharedTargetAndAntiComponents() {
        VibeCatalog.BlendedVibeProfile profile = VibeCatalog.blendProfiles(List.of("quiet", "open_roads"));

        assertTrue(profile.targetComponents().contains("solitude"));
        assertTrue(profile.targetComponents().contains("greenery"));
        assertTrue(profile.targetComponents().contains("open_space"));
        assertTrue(profile.antiComponents().contains("urban_penalty"));
        assertTrue(profile.antiComponents().contains("building_density"));
        assertTrue(profile.antiComponents().contains("poi"));
        assertTrue(profile.antiComponents().contains("road_density"));
        assertTrue(profile.outwardRouting());
        assertTrue(profile.strictIntent());
    }
}
