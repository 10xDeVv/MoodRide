package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteJobStatusResponseCompatibilityTest {

    @Test
    void serializationRetainsLegacyRouteOptionsAlongsideLifecycleFields() {
        UUID jobId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant primaryReadyAt = Instant.parse("2026-07-20T10:00:01Z");
        RouteOptionResponse option = new RouteOptionResponse(
            "most_scenic",
            routeId,
            "/routes/route/" + routeId,
            81.25,
            Map.of("final_score", 0.8125),
            41.75,
            62,
            null
        );
        RouteJobStatusResponse response = new RouteJobStatusResponse(
            jobId,
            "PRIMARY_READY",
            routeId,
            "/routes/route/" + routeId,
            List.of(option),
            primaryReadyAt,
            2L,
            1L,
            1,
            false,
            null,
            Instant.parse("2026-07-20T10:00:00Z"),
            Instant.parse("2026-07-20T10:00:00.100Z"),
            null,
            null,
            3,
            0,
            2,
            "drive",
            null,
            null,
            List.of(),
            List.of()
        );

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);

        assertThat(json.path("routeOptions").isArray()).isTrue();
        assertThat(json.path("routeOptions")).hasSize(1);
        assertThat(json.path("routeOptions").get(0).path("profile").asText())
            .isEqualTo("most_scenic");
        assertThat(json.path("routeOptions").get(0).path("routeId").asText())
            .isEqualTo(routeId.toString());
        assertThat(json.path("primaryReadyAt").asLong()).isEqualTo(primaryReadyAt.getEpochSecond());
        assertThat(json.path("stateRevision").asLong()).isEqualTo(2L);
        assertThat(json.path("optionRevision").asLong()).isEqualTo(1L);
        assertThat(json.path("optionCount").asInt()).isEqualTo(1);
        assertThat(json.path("optionsComplete").asBoolean()).isFalse();
    }
}
