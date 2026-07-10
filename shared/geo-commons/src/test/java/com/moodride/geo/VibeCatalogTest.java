package com.moodride.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VibeCatalogTest {

    @Test
    void normalizesLegacyAliasesToNineCanonicalVibes() {
        assertEquals("countryside", VibeCatalog.normalize("country"));
        assertEquals("relaxing", VibeCatalog.normalize("low traffic"));
        assertEquals("relaxing", VibeCatalog.normalize("quiet"));
        assertEquals("relaxing", VibeCatalog.normalize("cruise"));
        assertEquals("adventure", VibeCatalog.normalize("photo"));
        assertEquals("nature_escape", VibeCatalog.normalize("forest"));
        assertEquals("winding_roads", VibeCatalog.normalize("winding"));
    }

    @Test
    void exposesOnlyNineCanonicalVibes() {
        assertEquals(Set.of(
            "coastal",
            "mountain",
            "countryside",
            "riverside",
            "nature_escape",
            "open_roads",
            "adventure",
            "relaxing",
            "winding_roads"
        ), VibeCatalog.supportedVibes());
    }

    @Test
    void blendsProfilesIntoSharedTargetAndAntiComponents() {
        VibeCatalog.BlendedVibeProfile profile = VibeCatalog.blendProfiles(List.of("relaxing", "open_roads"));

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
