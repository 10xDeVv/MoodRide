package com.moodride.routeapi.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import com.moodride.routeapi.dto.ScenicRegionsResponse;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenicRegionServiceTest {
    private static final String SCORING_VERSION = "3.7-active-test";


    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;

    @Test
    void getScenicRegionsRanksByVibeSpecificScore() {
        GeometryFactory geometryFactory = new GeometryFactory();
        Polygon polygon = geometryFactory.createPolygon(new Coordinate[] {
            new Coordinate(-122.80, 45.40),
            new Coordinate(-122.75, 45.40),
            new Coordinate(-122.75, 45.45),
            new Coordinate(-122.80, 45.45),
            new Coordinate(-122.80, 45.40)
        });

        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index("872a1070bffffff");
        tile.setGeometry(polygon);
        tile.setWaterProximity(0.95);
        tile.setElevationVariance(0.20);
        tile.setNaturalLandUse(0.15);
        tile.setRoadDensity(0.10);
        tile.setTrafficSignalScore(0.85);
        tile.setPoiDensity(0.25);
        tile.setVisualComplexity(0.70);
        tile.setScenicScore(0.88);
        tile.setWaterScore(0.95);
        tile.setElevationScore(0.20);
        tile.setGreenScore(0.15);
        tile.setSolitudeScore(0.85);
        tile.setCurveScore(0.70);
        tile.setPoiScore(0.25);
        tile.setScoringVersion(SCORING_VERSION);

        when(scenicScoreTileRepository.findTopScenicRegionsNearPoint(
            SCORING_VERSION, 45.50, -122.70, 50000.0, 75
        )).thenReturn(List.of(tile));

        ScenicRegionService service = new ScenicRegionService(
            scenicScoreTileRepository,
            scenicCacheConfiguration()
        );
        ScenicRegionsResponse coastal = service.getScenicRegions(45.50, -122.70, 50, 25, "coastal");
        ScenicRegionsResponse mountain = service.getScenicRegions(45.50, -122.70, 50, 25, "mountain");
        ScenicRegionsResponse riverside = service.getScenicRegions(45.50, -122.70, 50, 25, "riverside");

        assertThat(coastal.regions()).hasSize(1);
        assertThat(coastal.regions().getFirst().compositeScore()).isGreaterThan(mountain.regions().getFirst().compositeScore());
        assertThat(riverside.regions()).hasSize(1);
        assertThat(coastal.boundingBox()).isNotNull();
        assertThat(coastal.totalRegions()).isEqualTo(1);
    }

    @Test
    void getScenicRegionsBoostsParkTilesForVibe() {
        GeometryFactory geometryFactory = new GeometryFactory();
        Polygon polygon = geometryFactory.createPolygon(new Coordinate[] {
            new Coordinate(-122.80, 45.40),
            new Coordinate(-122.75, 45.40),
            new Coordinate(-122.75, 45.45),
            new Coordinate(-122.80, 45.45),
            new Coordinate(-122.80, 45.40)
        });

        ScenicScoreTile base = new ScenicScoreTile();
        base.setH3Index("872a1070bfffff0");
        base.setGeometry(polygon);
        base.setWaterScore(0.40);
        base.setGreenScore(0.55);
        base.setElevationScore(0.35);
        base.setSolitudeScore(0.45);
        base.setCurveScore(0.30);
        base.setPoiScore(0.25);
        base.setScenicScore(0.45);
        base.setScoringVersion(SCORING_VERSION);

        ScenicScoreTile park = new ScenicScoreTile();
        park.setH3Index("872a1070bfffff1");
        park.setGeometry(polygon);
        park.setWaterScore(0.40);
        park.setGreenScore(0.55);
        park.setElevationScore(0.35);
        park.setSolitudeScore(0.45);
        park.setCurveScore(0.30);
        park.setPoiScore(0.25);
        park.setParkScore(1.0);
        park.setScenicScore(0.45);
        park.setScoringVersion(SCORING_VERSION);

        when(scenicScoreTileRepository.findTopScenicRegionsNearPoint(
            SCORING_VERSION, 45.50, -122.70, 50000.0, 75
        )).thenReturn(List.of(base, park));

        ScenicRegionService service = new ScenicRegionService(
            scenicScoreTileRepository,
            scenicCacheConfiguration()
        );
        ScenicRegionsResponse response = service.getScenicRegions(45.50, -122.70, 50, 25, "scenic");

        assertThat(response.regions()).hasSize(2);
        assertThat(response.regions().getFirst().h3Index()).isEqualTo("872a1070bfffff1");
        assertThat(response.regions().getFirst().compositeScore())
            .isGreaterThan(response.regions().get(1).compositeScore());
    }

    @Test
    void getScenicRegionsNeverAdvertisesInactiveScoringVersion() {
        GeometryFactory geometryFactory = new GeometryFactory();
        Polygon polygon = geometryFactory.createPolygon(new Coordinate[] {
            new Coordinate(-122.80, 45.40),
            new Coordinate(-122.75, 45.40),
            new Coordinate(-122.75, 45.45),
            new Coordinate(-122.80, 45.45),
            new Coordinate(-122.80, 45.40)
        });
        ScenicScoreTile active = new ScenicScoreTile();
        active.setH3Index("872a1070bfffff2");
        active.setGeometry(polygon);
        active.setScenicScore(0.60);
        active.setScoringVersion(SCORING_VERSION);
        ScenicScoreTile inactive = new ScenicScoreTile();
        inactive.setH3Index("872a1070bfffff3");
        inactive.setGeometry(polygon);
        inactive.setScenicScore(0.99);
        inactive.setScoringVersion("previous-release");

        when(scenicScoreTileRepository.findTopScenicRegionsNearPoint(
            SCORING_VERSION, 45.50, -122.70, 50000.0, 75
        )).thenReturn(List.of(inactive, active));

        ScenicRegionService service = new ScenicRegionService(
            scenicScoreTileRepository,
            scenicCacheConfiguration()
        );

        ScenicRegionsResponse response = service.getScenicRegions(
            45.50, -122.70, 50, 25, "scenic"
        );

        assertThat(response.regions())
            .extracting(region -> region.h3Index())
            .containsExactly(active.getH3Index());
    }

    private ScenicCacheConfiguration scenicCacheConfiguration() {
        ScenicCacheConfiguration configuration = new ScenicCacheConfiguration();
        configuration.setScenicScoringVersion(SCORING_VERSION);
        return configuration;
    }
}
