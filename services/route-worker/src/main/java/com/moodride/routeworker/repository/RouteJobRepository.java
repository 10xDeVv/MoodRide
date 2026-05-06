package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RouteJobRepository extends JpaRepository<RouteJob, UUID> {
    List<RouteJob> findByStatusAndStartedAtBefore(RouteJob.JobStatus status, Instant cutoffTime);
}
