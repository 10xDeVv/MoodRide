package com.moodride.scenicscoringservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TrafficRefreshQueueService {

    private final JdbcTemplate jdbcTemplate;
    private final int debounceSeconds;

    public TrafficRefreshQueueService(
            JdbcTemplate jdbcTemplate,
            @Value("${moodride.traffic.refresh.debounce-seconds:30}") int debounceSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.debounceSeconds = debounceSeconds;
    }

    @Transactional
    public int enqueue(String eventId, String source, List<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return 0;
        }

        Integer insertedEvent = jdbcTemplate.queryForObject(
                """
                INSERT INTO processed_kafka_events (event_id, source, processed_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING 1
                """,
                Integer.class,
                eventId,
                source
        );

        if (insertedEvent == null) {
            return 0;
        }

        int queued = 0;
        for (String h3 : normalize(h3Indexes)) {
            queued += jdbcTemplate.update(
                    """
                    INSERT INTO scenic_refresh_tile_queue (h3_index, source, last_event_at, not_before, state, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + (? || ' seconds')::interval, 'PENDING', CURRENT_TIMESTAMP)
                    ON CONFLICT (h3_index) DO UPDATE SET
                        source = EXCLUDED.source,
                        last_event_at = EXCLUDED.last_event_at,
                        not_before = EXCLUDED.not_before,
                        state = 'PENDING',
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    h3,
                    source,
                    debounceSeconds
            );
        }

        return queued;
    }

    @Transactional
    public List<String> claimReadyTiles(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        String sql = """
                WITH candidate AS (
                    SELECT h3_index
                    FROM scenic_refresh_tile_queue
                    WHERE state = 'PENDING'
                      AND not_before <= CURRENT_TIMESTAMP
                    ORDER BY not_before ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE scenic_refresh_tile_queue q
                SET state = 'PROCESSING',
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate c
                WHERE q.h3_index = c.h3_index
                RETURNING q.h3_index
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("h3_index"), limit);
    }

    @Transactional
    public void markProcessed(List<String> h3Indexes) {
        for (String h3 : normalize(h3Indexes)) {
            jdbcTemplate.update("DELETE FROM scenic_refresh_tile_queue WHERE h3_index = ?", h3);
        }
    }

    @Transactional
    public void releaseForRetry(List<String> h3Indexes) {
        for (String h3 : normalize(h3Indexes)) {
            jdbcTemplate.update(
                    """
                    UPDATE scenic_refresh_tile_queue
                    SET state = 'PENDING',
                        not_before = CURRENT_TIMESTAMP + INTERVAL '15 seconds',
                        retry_count = retry_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE h3_index = ?
                    """,
                    h3
            );
        }
    }

    private List<String> normalize(List<String> h3Indexes) {
        List<String> normalized = new ArrayList<>();
        if (h3Indexes == null) {
            return normalized;
        }
        for (String value : h3Indexes) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }
}

