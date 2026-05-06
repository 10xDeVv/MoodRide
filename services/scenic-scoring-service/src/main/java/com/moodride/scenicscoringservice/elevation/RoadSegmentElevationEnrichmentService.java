package com.moodride.scenicscoringservice.elevation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RoadSegmentElevationEnrichmentService {

    private final JdbcTemplate jdbcTemplate;
    private final OpenTopoDataClient openTopoDataClient;
    private final OpenTopoDataProperties properties;

    public RoadSegmentElevationEnrichmentService(
            JdbcTemplate jdbcTemplate,
            OpenTopoDataClient openTopoDataClient,
            OpenTopoDataProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.openTopoDataClient = openTopoDataClient;
        this.properties = properties;
    }

    public int enrichMissingElevation() {
        int segmentBatchSize = Math.max(1, properties.getSegmentBatchSize());
        List<SegmentSample> segments = jdbcTemplate.query(
                """
                SELECT id,
                       ST_Y(ST_StartPoint(geometry)) AS start_lat,
                       ST_X(ST_StartPoint(geometry)) AS start_lon,
                       ST_Y(ST_EndPoint(geometry)) AS end_lat,
                       ST_X(ST_EndPoint(geometry)) AS end_lon
                FROM road_segments
                WHERE COALESCE(elevation_change, 0.0) = 0.0
                ORDER BY id
                LIMIT ?
                """,
                (rs, rowNum) -> new SegmentSample(
                        rs.getLong("id"),
                        rs.getDouble("start_lat"),
                        rs.getDouble("start_lon"),
                        rs.getDouble("end_lat"),
                        rs.getDouble("end_lon")
                ),
                segmentBatchSize
        );

        if (segments.isEmpty()) {
            return 0;
        }

        List<OpenTopoDataClient.LatLon> points = new ArrayList<>();
        for (SegmentSample segment : segments) {
            points.add(new OpenTopoDataClient.LatLon(segment.startLat(), segment.startLon()));
            points.add(new OpenTopoDataClient.LatLon(segment.endLat(), segment.endLon()));
        }

        List<Double> elevations = openTopoDataClient.fetchElevations(points);
        if (elevations.size() < points.size()) {
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        int updated = 0;
        for (int i = 0; i < segments.size(); i++) {
            Double startElevation = elevations.get(i * 2);
            Double endElevation = elevations.get(i * 2 + 1);
            if (startElevation == null || endElevation == null) {
                continue;
            }

            double elevationChange = Math.abs(endElevation - startElevation);
            updates.add(new Object[]{elevationChange, segments.get(i).id()});
            updated++;
        }

        if (!updates.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "UPDATE road_segments SET elevation_change = ?, last_updated = CURRENT_TIMESTAMP WHERE id = ?",
                    updates
            );
        }

        return updated;
    }

    private record SegmentSample(long id, double startLat, double startLon, double endLat, double endLon) {
    }
}

