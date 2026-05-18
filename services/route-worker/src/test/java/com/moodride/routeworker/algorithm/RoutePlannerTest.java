package com.moodride.routeworker.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.geo.H3Utils;
import com.moodride.routeworker.config.ApplicationConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteWeightCalibrationRepository;
import com.moodride.routeworker.repository.ScenicScoreTileRepository;
import com.moodride.routeworker.service.OsrmTripClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlannerTest {

    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;

    @Mock
    private OsrmTripClient osrmTripClient;

    @Mock
    private RouteWeightCalibrationRepository routeWeightCalibrationRepository;

    private RoutePlanner routePlanner;

    @BeforeEach
    void setUp() {
        ApplicationConfiguration config = new ApplicationConfiguration();
        config.setH3Resolution(9);
        config.setTileSelectionRingMin(1);
        config.setTileSelectionRingMax(2);
        config.setTileSelectionLimit(20);
        config.setSectorCount(6);
        config.setCorridorSampleMeters(500);
        config.setMaxDurationOverrunRatio(1.0);
        routePlanner = new RoutePlanner(
            scenicScoreTileRepository,
            routeWeightCalibrationRepository,
            osrmTripClient,
            config,
            new ObjectMapper()
        );
        when(routeWeightCalibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of());
    }

    @Test
    void generateRouteUsesSyntheticHybridWhenTileRingHasNoCandidates() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(25)));

        RouteCandidate candidate = routePlanner.generateRoute(sampleJob(45));

        assertThat(candidate.getAlgorithmVersion()).isEqualTo("hybrid_osrm_v1");
        assertThat(candidate.getBeamCandidates()).isNull();
        assertThat(candidate.getWaypoints()).hasSizeGreaterThan(1);
        verify(osrmTripClient, atLeastOnce()).requestRoundTrip(anyList(), eq(RouteMode.DRIVE));
    }

    @Test
    void generateRouteRejectsOverBudgetHybridCandidatesWhenNoInBudgetOptionExists() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(22)));

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(15)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 15 minutes");
    }

    @Test
    void generateRouteThrowsWhenNoHybridCandidateCanBeProduced() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routePlanner.generateRoute(sampleJob(45)))
            .isInstanceOf(NoFeasibleRouteException.class)
            .hasMessageContaining("No feasible route found within 45 minutes");
    }

    @Test
    void generateRouteOptionsReturnsThreeDistinctProfilesWhenCandidatesExist() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RoadNode> variant = invocation.getArgument(0);
            int durationMinutes = variant.size() * 8;
            double distanceKm = variant.size() * 2.5;
            return Optional.of(new OsrmTripClient.TripResult(variant, distanceKm, durationMinutes));
        });

        List<RouteCandidate> options = routePlanner.generateRouteOptions(sampleJob(45));

        assertThat(options).hasSize(3);
        assertThat(options.stream().map(RouteCandidate::getAlgorithmVersion).collect(Collectors.toSet()))
            .containsExactly("hybrid_osrm_v1");
        assertThat(options.stream().map(candidate -> candidate.getWaypoints().size()).collect(Collectors.toSet()))
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(options.stream().map(RouteCandidate::getTotalScenicScore).collect(Collectors.toSet()))
            .hasSizeGreaterThan(1);
        double maxScenicScore = options.stream()
            .mapToDouble(RouteCandidate::getTotalScenicScore)
            .max()
            .orElse(0.0);
        assertThat(options.getFirst().getTotalScenicScore()).isEqualTo(maxScenicScore);
    }

    @Test
    void generateRouteFallsBackToDefaultH3ResolutionForScenicScoring() {
        when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> indexes = invocation.getArgument(0);
            boolean includesDefaultResolution = indexes.stream()
                .anyMatch(index -> H3Utils.getResolution(index) == H3Utils.DEFAULT_RESOLUTION);
            if (!includesDefaultResolution) {
                return List.of();
            }

            ScenicScoreTile tile = scenicTile(indexes.iterator().next(), 46.10, -64.77);
            return List.of(tile);
        });
        when(osrmTripClient.requestRoundTrip(anyList(), eq(RouteMode.DRIVE))).thenReturn(Optional.of(defaultTrip(25)));

        RouteCandidate candidate = routePlanner.generateRoute(sampleJob(45));

        assertThat(candidate.getTotalScenicScore()).isGreaterThan(0.30);
        verify(scenicScoreTileRepository, atLeastOnce()).findByH3IndexIn(anyCollection());
    }

    private RouteJob sampleJob(int timeBudgetMinutes) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 46.0945, -64.7809, timeBudgetMinutes, "coastal");
        job.setId(UUID.randomUUID());
        return job;
    }

    private OsrmTripClient.TripResult defaultTrip(int durationMinutes) {
        return new OsrmTripClient.TripResult(
            List.of(
                new RoadNode(46.0945, -64.7809),
                new RoadNode(46.1030, -64.7600),
                new RoadNode(46.0945, -64.7809)
            ),
            12.5,
            durationMinutes
        );
    }

    private ScenicScoreTile scenicTile(String h3Index, double latitude, double longitude) {
        GeometryFactory geometryFactory = new GeometryFactory();
        double latOffset = 0.01;
        double lngOffset = 0.01;

        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setGeometry(geometryFactory.createPolygon(new Coordinate[] {
            new Coordinate(longitude - lngOffset, latitude - latOffset),
            new Coordinate(longitude + lngOffset, latitude - latOffset),
            new Coordinate(longitude + lngOffset, latitude + latOffset),
            new Coordinate(longitude - lngOffset, latitude + latOffset),
            new Coordinate(longitude - lngOffset, latitude - latOffset)
        }));
        tile.setScenicScore(0.90);
        tile.setWaterScore(0.95);
        tile.setGreenScore(0.90);
        tile.setElevationScore(0.80);
        tile.setSolitudeScore(0.85);
        tile.setCurveScore(0.88);
        tile.setPoiScore(0.75);
        tile.setLastScored(Instant.now());
        return tile;
    }
}
