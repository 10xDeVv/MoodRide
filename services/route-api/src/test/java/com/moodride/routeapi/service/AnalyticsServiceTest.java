package com.moodride.routeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.AnalyticsEvent;
import com.moodride.routeapi.dto.AnalyticsEventRequest;
import com.moodride.routeapi.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private AnalyticsEventRepository eventRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        analyticsService = new AnalyticsService(eventRepository, jdbcTemplate, objectMapper, "test-analytics-secret");
    }

    @Test
    void recordEventPersistsAnonymousRouteAnalyticsAsCompleteJson() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(eventRepository.save(any(AnalyticsEvent.class))).thenAnswer(invocation -> {
            AnalyticsEvent event = invocation.getArgument(0);
            event.setId(UUID.randomUUID());
            event.setCreatedAt(Instant.parse("2026-06-30T04:00:00Z"));
            return event;
        });

        analyticsService.recordEvent(new AnalyticsEventRequest(
            "anon-session-1",
            "anon-client-1",
            "Route_Option_Selected",
            jobId,
            routeId,
            "Most_Scenic",
            "Drive",
            List.of("coastal", "scenic"),
            60,
            "grid:43.5:-79.5",
            3,
            "COMPLETED",
            12_500L,
            0.72,
            Map.of("surface", "mobile")
        ));

        ArgumentCaptor<AnalyticsEvent> saved = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(eventRepository).save(saved.capture());
        AnalyticsEvent event = saved.getValue();
        assertThat(event.getAnonymousSessionId()).isNotEqualTo("anon-session-1");
        assertThat(event.getAnonymousSessionId()).isNotEqualTo("anon-client-1");
        assertThat(event.getAnonymousSessionId()).hasSize(64);
        assertThat(event.getAnonymousClientHash()).isEqualTo(event.getAnonymousSessionId());
        assertThat(event.getEventName()).isEqualTo("route_option_selected");
        assertThat(event.getJobId()).isEqualTo(jobId);
        assertThat(event.getRouteId()).isEqualTo(routeId);
        assertThat(event.getRouteProfile()).isEqualTo("most_scenic");
        assertThat(event.getRouteMode()).isEqualTo("drive");
        assertThat(objectMapper.readTree(event.getVibesJson()))
            .isEqualTo(objectMapper.valueToTree(List.of("coastal", "countryside")));
        assertThat(event.getTimeBudgetMinutes()).isEqualTo(60);
        assertThat(event.getTimeBudgetBucket()).isEqualTo(60);
        assertThat(event.getRegionKey()).isEqualTo("grid:43.5:-79.5");
        assertThat(event.getRouteCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo("completed");
        assertThat(event.getDurationMs()).isEqualTo(12_500L);
        assertThat(event.getScenicScore()).isEqualTo(0.72);
        assertThat(objectMapper.readTree(event.getMetadataJson()))
            .isEqualTo(objectMapper.valueToTree(Map.of("surface", "mobile")));
        verify(jdbcTemplate, times(2)).update(any(String.class), any(Object[].class));
    }

    @Test
    void recordEventAcceptsFirstLatencyMilestonesWithoutChangingTerminalGenerationRollups() {
        List<String> eventNames = List.of(
            "route_generation_primary_ready",
            "route_results_committed",
            "route_map_painted",
            "route_generation_completed",
            "route_generation_failed"
        );
        when(eventRepository.save(any(AnalyticsEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (String eventName : eventNames) {
            analyticsService.recordEvent(new AnalyticsEventRequest(
                "anon-session-1",
                "anon-client-" + eventName,
                eventName,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "drive",
                List.of("scenic"),
                60,
                "grid:43.5:-79.5",
                1,
                "COMPLETED",
                9_000L,
                0.78,
                Map.of("source", "test")
            ));
        }

        ArgumentCaptor<AnalyticsEvent> saved = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(eventRepository, times(eventNames.size())).save(saved.capture());
        assertThat(saved.getAllValues())
            .extracting(AnalyticsEvent::getEventName)
            .containsExactlyElementsOf(eventNames);

        ArgumentCaptor<Object[]> rollupArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(eventNames.size())).update(any(String.class), rollupArguments.capture());
        assertThat(rollupArguments.getAllValues().subList(0, 3))
            .allSatisfy(arguments -> {
                assertThat(arguments[11]).isEqualTo(0.0);
                assertThat(arguments[12]).isEqualTo(0L);
                assertThat(arguments[13]).isEqualTo(0L);
                assertThat(arguments[14]).isEqualTo(0L);
                assertThat(arguments[15]).isEqualTo(0.0);
                assertThat(arguments[16]).isEqualTo(0L);
            });
        assertThat(rollupArguments.getAllValues().subList(3, 5))
            .allSatisfy(arguments -> {
                assertThat(arguments[11]).isEqualTo(9_000.0);
                assertThat(arguments[12]).isEqualTo(1L);
                assertThat(arguments[13]).isEqualTo(1L);
                assertThat(arguments[14]).isEqualTo(1L);
                assertThat(arguments[15]).isEqualTo(0.78);
                assertThat(arguments[16]).isEqualTo(1L);
            });
    }

    @Test
    void recordEventRejectsMoreThanThreeVibesBeforePersistence() {
        AnalyticsEventRequest request = validRequest(
            List.of("coastal", "mountain", "relaxing", "adventure"),
            Map.of("source", "test")
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At most three vibes");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void recordEventRejectsUnsupportedVibeBeforePersistence() {
        AnalyticsEventRequest request = validRequest(
            List.of("coastal", "anything-goes"),
            Map.of("source", "test")
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported analytics vibe");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void recordEventRejectsOversizedMetadataValueBeforePersistence() {
        AnalyticsEventRequest request = validRequest(
            List.of("scenic"),
            Map.of("payload", "x".repeat(1_001))
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata value exceeds maximum size");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void recordEventRejectsNestedMetadataWithTooManyItemsBeforePersistence() {
        AnalyticsEventRequest request = validRequest(
            List.of("scenic"),
            Map.of("items", IntStream.range(0, 51).boxed().toList())
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata contains too many items");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void recordEventRejectsMetadataThatIsNestedTooDeeplyBeforePersistence() {
        AnalyticsEventRequest request = validRequest(
            List.of("scenic"),
            Map.of("one", Map.of("two", Map.of("three", Map.of("four", Map.of("five", "value")))))
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata exceeds maximum nesting depth");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void recordEventRejectsBoundedMetadataThatExceedsStoredJsonLimit() {
        String value = "x".repeat(900);
        AnalyticsEventRequest request = validRequest(
            List.of("scenic"),
            Map.of("one", value, "two", value, "three", value, "four", value, "five", value)
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata exceeds maximum serialized size");
        verifyNoInteractions(eventRepository, jdbcTemplate);
    }

    @Test
    void constructorRejectsBlankAnalyticsSecret() {
        assertThatThrownBy(() -> new AnalyticsService(eventRepository, jdbcTemplate, objectMapper, " "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("hash-secret must be configured");
    }

    @Test
    void recordEventRejectsUnsupportedEventNames() {
        AnalyticsEventRequest request = new AnalyticsEventRequest(
            "anon-session-1",
            "anon-client-1",
            "unknown_event",
            null,
            null,
            null,
            "drive",
            List.of("scenic"),
            60,
            "grid:43.5:-79.5",
            null,
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> analyticsService.recordEvent(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported analytics event");
    }

    private AnalyticsEventRequest validRequest(List<String> vibes, Map<String, Object> metadata) {
        return new AnalyticsEventRequest(
            "anon-session-1",
            "anon-client-1",
            "route_generation_primary_ready",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "drive",
            vibes,
            60,
            "grid:43.5:-79.5",
            1,
            "completed",
            500L,
            null,
            metadata
        );
    }
}
