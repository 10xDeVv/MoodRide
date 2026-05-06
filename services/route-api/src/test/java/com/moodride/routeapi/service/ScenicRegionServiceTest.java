package com.moodride.routeapi.service;

import com.moodride.datamodels.ScenicScoreTile;
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

        when(scenicScoreTileRepository.findTopScenicRegionsNearPoint(45.50, -122.70, 50000.0, 75))
            .thenReturn(List.of(tile));

        ScenicRegionService service = new ScenicRegionService(scenicScoreTileRepository);
        ScenicRegionsResponse coastal = service.getScenicRegions(45.50, -122.70, 50, 25, "coastal");
        ScenicRegionsResponse mountain = service.getScenicRegions(45.50, -122.70, 50, 25, "mountain");

        assertThat(coastal.regions()).hasSize(1);
        assertThat(coastal.regions().getFirst().compositeScore()).isGreaterThan(mountain.regions().getFirst().compositeScore());
        assertThat(coastal.boundingBox()).isNotNull();
        assertThat(coastal.totalRegions()).isEqualTo(1);
    }
}
