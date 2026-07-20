package com.moodride.routeworker.service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RoadSegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoadSegmentAnchorServiceTest {

    private static final String SCENIC_VERSION = "scenic-v2";
    private static final String ROAD_REVISION = "roads-fingerprint-v2";
    private static final String ANCHOR_SCHEMA = "v1";
    private static final String H3_INDEX = "872a1070bffffff";

    @Mock
    private RoadSegmentRepository roadSegmentRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache roadSegmentCache;
    @Mock
    private RouteGenerationMetricsService metricsService;

    private RoadSegmentAnchorService service;

    @BeforeEach
    void setUp() {
        lenient().when(cacheManager.getCache(CacheNames.ROAD_SEGMENTS)).thenReturn(roadSegmentCache);
        ScenicCacheConfiguration configuration = new ScenicCacheConfiguration();
        configuration.setScenicScoringVersion(SCENIC_VERSION);
        configuration.setRoadDatasetRevision(ROAD_REVISION);
        configuration.setRoadAnchorCacheSchema(ANCHOR_SCHEMA);
        service = new RoadSegmentAnchorService(
            roadSegmentRepository,
            cacheManager,
            metricsService,
            configuration
        );
    }

    @Test
    void readsOnlyTheCompositeActiveIdentityKey() {
        RoadSegmentAnchorService.RoadAnchor cached =
            new RoadSegmentAnchorService.RoadAnchor(45.25, -66.50);
        when(roadSegmentCache.get(activeKey(), RoadSegmentAnchorService.RoadAnchor.class))
            .thenReturn(cached);

        RoadNode anchor = service.anchorFor(activeTile(), new RoadNode(45.0, -66.0));

        assertEquals(new RoadNode(45.25, -66.50), anchor);
        verify(roadSegmentCache).get(activeKey(), RoadSegmentAnchorService.RoadAnchor.class);
        verifyNoInteractions(roadSegmentRepository);
    }

    @Test
    void repositoryFailureIsNotCachedAndNextLookupRecoversFromPostgres() {
        RoadNode fallback = new RoadNode(45.0, -66.0);
        RoadSegment recoveredSegment = roadSegment();
        when(roadSegmentRepository.findAnchorCandidatesNear(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenThrow(new IllegalStateException("temporary database error"))
            .thenReturn(List.of(recoveredSegment));

        assertEquals(fallback, service.anchorFor(activeTile(), fallback));
        verify(roadSegmentCache, never()).put(any(), any());
        clearInvocations(roadSegmentCache);

        assertEquals(new RoadNode(45.5, -65.5), service.anchorFor(activeTile(), fallback));
        verify(roadSegmentRepository, times(2))
            .findAnchorCandidatesNear(anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(roadSegmentCache).put(
            activeKey(),
            new RoadSegmentAnchorService.RoadAnchor(45.5, -65.5)
        );
    }

    @Test
    void emptyCandidateFallbackIsNotStoredForSevenDays() {
        RoadNode fallback = new RoadNode(45.0, -66.0);
        when(roadSegmentRepository.findAnchorCandidatesNear(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());

        assertEquals(fallback, service.anchorFor(activeTile(), fallback));
        assertEquals(fallback, service.anchorFor(activeTile(), fallback));

        verify(roadSegmentRepository, times(2))
            .findAnchorCandidatesNear(anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(roadSegmentCache, never()).put(any(), any());
    }

    @Test
    void tileFromAnotherScenicReleaseCannotPopulateActiveAnchorNamespace() {
        ScenicScoreTile stale = activeTile();
        stale.setScoringVersion("scenic-v1");
        RoadNode fallback = new RoadNode(45.0, -66.0);

        assertEquals(fallback, service.anchorFor(stale, fallback));

        verifyNoInteractions(roadSegmentRepository, cacheManager, roadSegmentCache);
    }

    private static ScenicScoreTile activeTile() {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(H3_INDEX);
        tile.setScoringVersion(SCENIC_VERSION);
        tile.setScenicScore(0.8);
        tile.setGreenScore(0.7);
        tile.setSolitudeScore(0.6);
        tile.setCurveScore(0.5);
        tile.setWaterVisibilityScore(0.4);
        tile.setRoadStressScore(0.2);
        return tile;
    }

    private static RoadSegment roadSegment() {
        RoadSegment segment = new RoadSegment();
        segment.setGeometry(new GeometryFactory().createLineString(new Coordinate[] {
            new Coordinate(-66.0, 45.0),
            new Coordinate(-65.5, 45.5),
            new Coordinate(-65.0, 46.0)
        }));
        segment.setRoadType("residential");
        segment.setSpeedLimitKmh(40);
        segment.setLengthMeters(1000.0);
        segment.setCurvature(0.5);
        segment.setElevationChange(20.0);
        return segment;
    }

    private static String activeKey() {
        return CacheKeySchema.roadAnchor(
            SCENIC_VERSION,
            ROAD_REVISION,
            ANCHOR_SCHEMA,
            H3_INDEX
        );
    }
}
