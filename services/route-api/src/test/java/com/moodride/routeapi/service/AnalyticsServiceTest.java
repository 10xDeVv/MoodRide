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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private AnalyticsEventRepository eventRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(eventRepository, jdbcTemplate, new ObjectMapper(), "test-analytics-secret");
    }

    @Test
    void recordEventPersistsAnonymousRouteAnalytics() {
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
        assertThat(event.getVibesJson()).contains("coastal", "scenic");
        assertThat(event.getTimeBudgetMinutes()).isEqualTo(60);
        assertThat(event.getTimeBudgetBucket()).isEqualTo(60);
        assertThat(event.getRegionKey()).isEqualTo("grid:43.5:-79.5");
        assertThat(event.getRouteCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo("completed");
        assertThat(event.getDurationMs()).isEqualTo(12_500L);
        assertThat(event.getScenicScore()).isEqualTo(0.72);
        assertThat(event.getMetadataJson()).contains("mobile");
        verify(jdbcTemplate, times(2)).update(any(String.class), any(Object[].class));
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
}
