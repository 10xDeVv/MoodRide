package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteJobRepository extends JpaRepository<RouteJob, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RouteJob j where j.id = :jobId")
    Optional<RouteJob> findByIdForUpdate(@Param("jobId") UUID jobId);

    @Query("""
        select j from RouteJob j
        where j.status in :statuses
          and (
              (j.leaseExpiresAt is not null and j.leaseExpiresAt < :now)
              or (j.leaseExpiresAt is null and j.startedAt < :legacyCutoffTime)
          )
        """)
    List<RouteJob> findExpiredActiveJobs(
        @Param("statuses") Collection<RouteJob.JobStatus> statuses,
        @Param("now") Instant now,
        @Param("legacyCutoffTime") Instant legacyCutoffTime
    );
    @Modifying
    @Query(value = """
        INSERT INTO route_job_dispatches (
            job_id,
            created_at,
            next_attempt_at,
            attempt_count,
            sent_at,
            lease_token,
            lease_expires_at,
            last_error
        )
        VALUES (:jobId, :now, :now, 0, NULL, NULL, NULL, NULL)
        ON CONFLICT (job_id) DO UPDATE SET
            created_at = EXCLUDED.created_at,
            next_attempt_at = EXCLUDED.next_attempt_at,
            attempt_count = 0,
            sent_at = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            last_error = NULL
        """, nativeQuery = true)
    void upsertRetryDispatch(
        @Param("jobId") UUID jobId,
        @Param("now") Instant now
    );

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM route_job_dispatches
            WHERE job_id = :jobId
              AND sent_at IS NULL
        )
        """, nativeQuery = true)
    boolean hasPendingRetryDispatch(@Param("jobId") UUID jobId);

}
