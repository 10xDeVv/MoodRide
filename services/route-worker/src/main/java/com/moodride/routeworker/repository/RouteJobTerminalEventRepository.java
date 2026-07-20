package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteJobTerminalEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteJobTerminalEventRepository
    extends JpaRepository<RouteJobTerminalEvent, String> {

    @Query(value = """
        SELECT e.*
        FROM route_job_terminal_events e
        WHERE e.job_id = :jobId
          AND e.state_revision = :stateRevision
          AND e.delivered_at IS NULL
          AND e.next_attempt_at <= :now
          AND (e.lease_expires_at IS NULL OR e.lease_expires_at <= :now)
        ORDER BY e.event_type ASC
        FOR UPDATE OF e SKIP LOCKED
        """, nativeQuery = true)
    List<RouteJobTerminalEvent> lockPublishableForTerminal(
        @Param("jobId") UUID jobId,
        @Param("stateRevision") long stateRevision,
        @Param("now") Instant now
    );

    @Query(value = """
        SELECT e.*
        FROM route_job_terminal_events e
        WHERE e.delivered_at IS NULL
          AND e.next_attempt_at <= :now
          AND (e.lease_expires_at IS NULL OR e.lease_expires_at <= :now)
        ORDER BY e.next_attempt_at ASC, e.created_at ASC, e.event_id ASC
        LIMIT 1
        FOR UPDATE OF e SKIP LOCKED
        """, nativeQuery = true)
    Optional<RouteJobTerminalEvent> lockOldestDue(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from RouteJobTerminalEvent event where event.eventId = :eventId")
    Optional<RouteJobTerminalEvent> lockByEventId(@Param("eventId") String eventId);
}
