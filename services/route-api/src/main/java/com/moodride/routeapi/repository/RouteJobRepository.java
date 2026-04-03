package com.moodride.routeapi.repository;

import com.moodride.datamodels.RouteJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.List;

@Repository
public interface RouteJobRepository extends JpaRepository<RouteJob, UUID> {
    List<RouteJob> findByUserIdOrderBySubmittedAtDesc(UUID userId);
}
