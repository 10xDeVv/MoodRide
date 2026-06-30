package com.moodride.routeworker.repository;

import com.moodride.datamodels.RoadSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
    @Query(value = """
        SELECT *
        FROM road_segments
        WHERE geometry IS NOT NULL
          AND ST_DWithin(
              geometry::geography,
              ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
              :radiusMeters
          )
        ORDER BY
          CASE lower(COALESCE(road_type, 'unknown'))
            WHEN 'residential' THEN 0
            WHEN 'living_street' THEN 1
            WHEN 'unclassified' THEN 2
            WHEN 'tertiary' THEN 3
            WHEN 'secondary' THEN 4
            WHEN 'primary' THEN 7
            WHEN 'trunk' THEN 9
            WHEN 'motorway' THEN 10
            ELSE 5
          END,
          COALESCE(curvature, 0) DESC,
          COALESCE(length_meters, 0) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<RoadSegment> findAnchorCandidatesNear(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusMeters") double radiusMeters,
        @Param("limit") int limit
    );
}
