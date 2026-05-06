package com.moodride.ingestionservice.integration;

import com.moodride.datamodels.RoadSegment;
import com.moodride.ingestionservice.IngestionServiceApplication;
import com.moodride.ingestionservice.repository.RoadSegmentRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        classes = IngestionServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.batch.job.enabled=false"
        }
)
class Phase1IngestionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("moodride_test")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("sql/test-init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RoadSegmentRepository roadSegmentRepository;

    @Test
        void roadSegmentRepositoryPersistsRoadSegmentWithH3Index() {
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString wayGeometry = geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(-122.6784, 45.5152),
                new Coordinate(-122.6801, 45.5189),
                new Coordinate(-122.6825, 45.5213)
        });

                RoadSegment segment = new RoadSegment(987654321L, wayGeometry, "872830828ffffff", 123.45, 60);
                segment.setRoadType("secondary");
                segment.setSurface("asphalt");
                segment.setCurvature(0.18);
                segment.setElevationChange(4.2);

                assertNotNull(segment, "Road segment should be created");
        assertNotNull(segment.getGeometry(), "Road segment geometry should be present");
        assertEquals("LineString", segment.getGeometry().getGeometryType(), "Geometry must be LINESTRING");
        assertNotNull(segment.getH3TileIndex(), "H3 index should be generated");
        assertFalse(segment.getH3TileIndex().isBlank(), "H3 index should not be blank");

        roadSegmentRepository.saveAndFlush(segment);

        RoadSegment persisted = roadSegmentRepository.findByOsmWayId(987654321L)
                .orElseThrow(() -> new AssertionError("Persisted road segment not found"));

        assertEquals("LineString", persisted.getGeometry().getGeometryType());
        assertNotNull(persisted.getH3TileIndex());
        assertFalse(persisted.getH3TileIndex().isBlank());
    }
}

