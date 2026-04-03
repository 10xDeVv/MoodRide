package com.moodride.routeapi.repository;

import com.moodride.datamodels.RoadSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.locationtech.jts.geom.Geometry;
import java.util.List;

@Repository
public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
    @Query(value = "SELECT * FROM road_segments WHERE ST_DWithin(geometry, :point, :distanceMeters)", nativeQuery = true)
    List<RoadSegment> findNearby(@Param("point") Geometry point, @Param("distanceMeters") double distance);
    
    List<RoadSegment> findByH3TileIndex(String h3Index);
}
