package com.moodride.routeapi.repository;

import com.moodride.datamodels.ScenicScoreTile;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScenicScoreTileRepository extends JpaRepository<ScenicScoreTile, String> {

    @Query(
        value = """
            SELECT *
            FROM scenic_score_tiles
            WHERE ST_DWithin(
                geometry::geography,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radiusMeters
            )
            ORDER BY scenic_score DESC
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<ScenicScoreTile> findTopScenicRegionsNearPoint(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusMeters") double radiusMeters,
        @Param("limit") int limit
    );

    @Query(
        value = """
            SELECT *
            FROM scenic_score_tiles
            WHERE scoring_version = :scoringVersion
            ORDER BY scenic_score DESC
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<ScenicScoreTile> findTopByScenicScore(
        @Param("scoringVersion") String scoringVersion,
        @Param("limit") int limit
    );

    @Query(
        value = """
            SELECT *
            FROM scenic_score_tiles
            WHERE ST_DWithin(
                geometry::geography,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radiusMeters
            )
            ORDER BY geometry <-> ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<ScenicScoreTile> findScenicTilesNearPoint(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusMeters") double radiusMeters,
        @Param("limit") int limit
    );

    List<ScenicScoreTile> findByH3IndexIn(Collection<String> h3Indexes);
}
