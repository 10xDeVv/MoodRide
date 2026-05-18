package com.moodride.routeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.geo.H3Utils;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.dto.RouteJobStatusResponse;
import com.moodride.routeapi.dto.RouteRatingRequest;
import com.moodride.routeapi.dto.RouteRatingResponse;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteSubmissionResponse;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.RouteWeightCalibrationRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RouteJobRepository jobRepository;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private RouteWeightCalibrationRepository calibrationRepository;

    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(
            jobRepository,
            routeRepository,
            calibrationRepository,
            scenicScoreTileRepository,
            kafkaTemplate,
            new ObjectMapper()
        );

        lenient().when(jobRepository.save(any(RouteJob.class))).thenAnswer(invocation -> {
            RouteJob job = invocation.getArgument(0);
            if (job.getId() == null) {
                job.setId(UUID.randomUUID());
            }
            return job;
        });
        lenient().when(calibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of());
        lenient().when(calibrationRepository.saveAll(anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());
    }

    @Test
    void submitRouteReturnsSpecAlignedStatusUrl() throws Exception {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal", "mountain"),
            null,
            null
        );

        RouteSubmissionResponse response = routeService.submitRoute(request);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.statusUrl()).isEqualTo("/routes/" + response.jobId());
        assertThat(response.wsChannel()).isEqualTo("job:" + response.jobId());

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getVibe()).isEqualTo("coastal");
        List<String> storedVibes = new ObjectMapper().readValue(saved.getValue().getVibesJson(), new TypeReference<>() {
        });
        assertThat(storedVibes).containsExactly("coastal", "mountain");
    }

    @Test
    void submitRouteNormalizesVibeAliases() throws Exception {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("Date Night", "winding roads", "Photo-Worthy"),
            null,
            Map.of()
        );

        routeService.submitRoute(request);

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getVibe()).isEqualTo("date_night");
        List<String> storedVibes = new ObjectMapper().readValue(saved.getValue().getVibesJson(), new TypeReference<>() {
        });
        assertThat(storedVibes).containsExactly("date_night", "winding_roads", "photo_worthy");
    }

    @Test
    void submitRouteRejectsInvalidVibe() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("party"),
            null,
            null
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid vibe");
    }

    @Test
    void submitRouteRejectsUnknownPreferenceKeys() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal"),
            null,
            Map.of("avoidTolls", false)
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("preferenceVector must use numeric values");
    }

    @Test
    void submitRouteRejectsMoreThanThreeVibes() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal", "mountain", "forest", "riverside"),
            null,
            null
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At most three vibes");
    }

    @Test
    void rateRoutePersistsRatingAndPublishesEvents() {
        UUID routeId = UUID.randomUUID();
        Route route = new Route();
        route.setId(routeId);
        route.setJobId(UUID.randomUUID());
        route.setUserId(UUID.randomUUID());

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RouteRatingResponse response = routeService.rateRoute(routeId, new RouteRatingRequest(5));

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.ratedAt()).isNotNull();

        ArgumentCaptor<Route> saved = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(saved.capture());
        assertThat(saved.getValue().getUserRating()).isEqualTo(5);
        assertThat(saved.getValue().getRatedAt()).isNotNull();

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("user.events.route_rated"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("user.events.drive_completed"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getRouteReturnsRichScenicMetadata() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setVibesJson("[\"coastal\",\"riverside\"]");
        job.setStartedAt(Instant.parse("2026-04-02T14:30:01Z"));
        job.setCompletedAt(Instant.parse("2026-04-02T14:30:05Z"));
        job.setTimeBudgetMinutes(90);

        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-122.6784, 45.5152),
            new Coordinate(-122.6801, 45.5189),
            new Coordinate(-122.7000, 45.5300)
        });

        Route route = new Route();
        route.setId(routeId);
        route.setJobId(jobId);
        route.setUserId(userId);
        route.setGeometry(lineString);
        route.setTotalDistanceKm(62.3);
        route.setEstimatedDurationMinutes(88);
        route.setScenicScore(0.785);
        route.setVibe("coastal");
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setExpiresAt(Instant.parse("2026-04-09T14:30:05Z"));

        RouteWaypoint first = new RouteWaypoint(route, 0, 45.5152, -122.6784, "Start the scenic loop", 12.4);
        RouteWaypoint middle = new RouteWaypoint(route, 1, 45.5189, -122.6801, "Continue along the ridge", 18.1);
        RouteWaypoint last = new RouteWaypoint(route, 2, 45.5300, -122.7000, "Arrive back at the start", 0.0);
        route.setWaypoints(List.of(first, middle, last));

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));

        RouteDetailResponse response = routeService.getRoute(routeId);

        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.qualityTier()).isEqualTo("PREMIUM");

        Map<String, Object> properties = castMap(response.geometry().get("properties"));
        assertThat((List<?>) properties.get("segmentScores")).isNotEmpty();
        assertThat((List<?>) properties.get("segmentColors")).isNotEmpty();
        assertThat(response.vibes()).containsExactly("coastal", "riverside");
        assertThat(response.scenicHighlights()).isNotEmpty();
        assertThat(response.routeOptions()).hasSize(1);
        assertThat(response.routeOptions().getFirst().profile()).isEqualTo("most_scenic");
        assertThat(response.computationTimeMs()).isEqualTo(4000);
    }

    @Test
    void getRouteJobStatusIncludesUpToThreeRouteOptions() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        Route option1 = new Route();
        option1.setId(UUID.randomUUID());
        option1.setJobId(jobId);
        option1.setRouteProfile("most_scenic");
        option1.setScenicScore(0.82);
        option1.setTotalDistanceKm(52.0);
        option1.setEstimatedDurationMinutes(89);
        option1.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        Route option2 = new Route();
        option2.setId(UUID.randomUUID());
        option2.setJobId(jobId);
        option2.setRouteProfile("balanced");
        option2.setScenicScore(0.75);
        option2.setTotalDistanceKm(47.5);
        option2.setEstimatedDurationMinutes(82);
        option2.setGeneratedAt(Instant.parse("2026-04-02T14:30:06Z"));

        Route option3 = new Route();
        option3.setId(UUID.randomUUID());
        option3.setJobId(jobId);
        option3.setRouteProfile("shorter");
        option3.setScenicScore(0.69);
        option3.setTotalDistanceKm(40.2);
        option3.setEstimatedDurationMinutes(70);
        option3.setGeneratedAt(Instant.parse("2026-04-02T14:30:07Z"));

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(option1, option2, option3));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.routeOptions()).hasSize(3);
        assertThat(response.routeOptions().get(0).profile()).isEqualTo("most_scenic");
        assertThat(response.routeOptions().get(1).profile()).isEqualTo("balanced");
        assertThat(response.routeOptions().get(2).profile()).isEqualTo("shorter");
        assertThat(response.routeId()).isEqualTo(option1.getId());
    }

    @Test
    void routeOptionExplanationRanksWeightedLiftInsteadOfRawAverage() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 51.1784, -115.5708, 90, "mountain");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-115.5708, 51.1784),
            new Coordinate(-115.6000, 51.1900)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.82);
        route.setTotalDistanceKm(52.0);
        route.setEstimatedDurationMinutes(89);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(51.1784, -115.5708, H3Utils.DEFAULT_RESOLUTION), 0.98, 0.72, 0.91, 0.78, 0.36, 0.12),
            scenicTile(H3Utils.getH3Index(51.1900, -115.6000, H3Utils.DEFAULT_RESOLUTION), 0.99, 0.70, 0.89, 0.79, 0.34, 0.10)
        );
        List<ScenicScoreTile> baselineTiles = List.of(
            scenicTile("baseline-a", 0.97, 0.38, 0.32, 0.74, 0.30, 0.08),
            scenicTile("baseline-b", 0.96, 0.40, 0.30, 0.72, 0.31, 0.09)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(baselineTiles);

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.leadingComponents().getFirst()).isEqualTo("elevation");
        assertThat(explanation.summary()).contains("vs area");
        assertThat(explanation.weightedContributions().get("elevation")).isGreaterThan(explanation.weightedContributions().get("water"));
        assertThat(explanation.componentLifts().get("water")).isLessThan(explanation.componentLifts().get("elevation"));
        assertThat(explanation.baselineTileCount()).isEqualTo(2);
    }

    @Test
    void getRouteJobStatusUsesPersistedProfilesOverGeneratedOrder() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        Route balanced = new Route();
        balanced.setId(UUID.randomUUID());
        balanced.setJobId(jobId);
        balanced.setRouteProfile("balanced");
        balanced.setScenicScore(0.75);
        balanced.setTotalDistanceKm(47.5);
        balanced.setEstimatedDurationMinutes(82);
        balanced.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        Route mostScenic = new Route();
        mostScenic.setId(UUID.randomUUID());
        mostScenic.setJobId(jobId);
        mostScenic.setRouteProfile("most_scenic");
        mostScenic.setScenicScore(0.82);
        mostScenic.setTotalDistanceKm(52.0);
        mostScenic.setEstimatedDurationMinutes(89);
        mostScenic.setGeneratedAt(Instant.parse("2026-04-02T14:30:06Z"));

        Route shorter = new Route();
        shorter.setId(UUID.randomUUID());
        shorter.setJobId(jobId);
        shorter.setRouteProfile("shorter");
        shorter.setScenicScore(0.69);
        shorter.setTotalDistanceKm(40.2);
        shorter.setEstimatedDurationMinutes(70);
        shorter.setGeneratedAt(Instant.parse("2026-04-02T14:30:07Z"));

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(balanced, mostScenic, shorter));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.routeId()).isEqualTo(mostScenic.getId());
        assertThat(response.routeOptions()).hasSize(3);
        assertThat(response.routeOptions().get(0).profile()).isEqualTo("most_scenic");
        assertThat(response.routeOptions().get(0).routeId()).isEqualTo(mostScenic.getId());
        assertThat(response.routeOptions().get(1).profile()).isEqualTo("balanced");
        assertThat(response.routeOptions().get(2).profile()).isEqualTo("shorter");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static ScenicScoreTile scenicTile(
        String h3Index,
        double water,
        double greenery,
        double elevation,
        double solitude,
        double curves,
        double poi
    ) {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setWaterScore(water);
        tile.setWaterProximity(water);
        tile.setGreenScore(greenery);
        tile.setNaturalLandUse(greenery);
        tile.setElevationScore(elevation);
        tile.setElevationVariance(elevation);
        tile.setSolitudeScore(solitude);
        tile.setTrafficSignalScore(solitude);
        tile.setCurveScore(curves);
        tile.setVisualComplexity(curves);
        tile.setPoiScore(poi);
        tile.setPoiDensity(poi);
        return tile;
    }
}
