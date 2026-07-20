ALTER TABLE road_segments
    ADD COLUMN IF NOT EXISTS stable_identity_key varchar(64)
        GENERATED ALWAYS AS (
            osm_way_id::text || ':' ||
            CASE
                WHEN ST_SnapToGrid(geometry, 0.0000001) IS NULL
                  OR ST_IsEmpty(ST_SnapToGrid(geometry, 0.0000001))
                  OR ST_NPoints(ST_SnapToGrid(geometry, 0.0000001)) < 2
                THEN md5(ST_AsEWKB(ST_SnapToGrid(
                    ST_LineInterpolatePoint(geometry, 0.5),
                    0.0000001
                ), 'XDR'))
                ELSE LEAST(
                    md5(ST_AsEWKB(ST_SnapToGrid(geometry, 0.0000001), 'XDR')),
                    md5(ST_AsEWKB(
                        ST_Reverse(ST_SnapToGrid(geometry, 0.0000001)),
                        'XDR'
                    ))
                )
            END
        ) STORED,
    ADD COLUMN IF NOT EXISTS stored_geometry_canonical_forward boolean
        GENERATED ALWAYS AS (
            CASE
                WHEN ST_SnapToGrid(geometry, 0.0000001) IS NULL
                  OR ST_IsEmpty(ST_SnapToGrid(geometry, 0.0000001))
                  OR ST_NPoints(ST_SnapToGrid(geometry, 0.0000001)) < 2
                THEN true
                ELSE md5(ST_AsEWKB(ST_SnapToGrid(geometry, 0.0000001), 'XDR'))
                    <= md5(ST_AsEWKB(
                        ST_Reverse(ST_SnapToGrid(geometry, 0.0000001)),
                        'XDR'
                    ))
            END
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_road_segments_stable_identity
    ON road_segments (stable_identity_key);
