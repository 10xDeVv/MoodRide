package com.moodride.routeapi.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.moodride.routeapi.dispatch.RouteJobDispatch;

import jakarta.persistence.LockModeType;

@Repository
public interface RouteJobDispatchRepository extends JpaRepository<RouteJobDispatch, UUID> {

    @Query(value = """
        SELECT d.*
        FROM route_job_dispatches d
        JOIN route_jobs j ON j.id = d.job_id
        WHERE d.job_id = :jobId
          AND d.sent_at IS NULL
          AND j.status IN ('QUEUED', 'PRIMARY_READY')
          AND (d.lease_expires_at IS NULL OR d.lease_expires_at <= :now)
        FOR UPDATE OF d SKIP LOCKED
        """, nativeQuery = true)
    Optional<RouteJobDispatch> lockPublishableByJobId(
        @Param("jobId") UUID jobId,
        @Param("now") Instant now
    );

    @Query(value = """
        SELECT d.*
        FROM route_job_dispatches d
        JOIN route_jobs j ON j.id = d.job_id
        WHERE d.sent_at IS NULL
          AND d.next_attempt_at <= :now
          AND j.status IN ('QUEUED', 'PRIMARY_READY')
          AND (d.lease_expires_at IS NULL OR d.lease_expires_at <= :now)
        ORDER BY d.created_at ASC, d.job_id ASC
        LIMIT 1
        FOR UPDATE OF d SKIP LOCKED
        """, nativeQuery = true)
    Optional<RouteJobDispatch> lockOldestDue(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select dispatch from RouteJobDispatch dispatch where dispatch.jobId = :jobId")
    Optional<RouteJobDispatch> lockByJobId(@Param("jobId") UUID jobId);
}
