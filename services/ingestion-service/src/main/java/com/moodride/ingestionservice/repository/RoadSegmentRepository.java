package com.moodride.ingestionservice.repository;

import com.moodride.datamodels.RoadSegment;
import org.locationtech.jts.geom.Envelope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {

    /**
     * Find road segment by OSM way ID.
     */
    Optional<RoadSegment> findByOsmWayId(Long osmWayId);

    /**
     * Find all road segments within a bounding box using spatial index.
     */
    @Query(value = """
        SELECT * FROM road_segments 
        WHERE ST_Intersects(geometry, ST_MakeEnvelope(?1, ?2, ?3, ?4, 4326))
        """, nativeQuery = true)
    List<RoadSegment> findWithinBoundingBox(double minLon, double minLat, double maxLon, double maxLat);

    /**
     * Count road segments by road type.
     */
    long countByRoadType(String roadType);

    /**
     * Find road segments by H3 tile index.
     */
    List<RoadSegment> findByH3TileIndex(String h3TileIndex);
}

