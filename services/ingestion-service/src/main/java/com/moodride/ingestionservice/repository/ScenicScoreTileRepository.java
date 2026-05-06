package com.moodride.ingestionservice.repository;

import com.moodride.datamodels.ScenicScoreTile;
import org.locationtech.jts.geom.Polygon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@NoRepositoryBean
public interface ScenicScoreTileRepository extends JpaRepository<ScenicScoreTile, String> {

    /**
     * Find tiles with scenic score above threshold.
     */
    List<ScenicScoreTile> findByScenicScoreGreaterThan(double minScore);

    /**
     * Find tiles within a bounding box.
     */
    @Query(value = """
        SELECT * FROM scenic_score_tiles 
        WHERE ST_Intersects(geometry, ST_MakeEnvelope(?1, ?2, ?3, ?4, 4326))
        ORDER BY scenic_score DESC
        """, nativeQuery = true)
    List<ScenicScoreTile> findWithinBoundingBox(double minLon, double minLat, double maxLon, double maxLat);

    /**
     * Count tiles by scenic score range.
     */
    long countByScenicScoreBetween(double minScore, double maxScore);

    /**
     * Find top N highest-scoring tiles.
     */
    @Query(value = """
        SELECT * FROM scenic_score_tiles 
        ORDER BY scenic_score DESC 
        LIMIT :limit
        """, nativeQuery = true)
    List<ScenicScoreTile> findTopByScore(int limit);
}

