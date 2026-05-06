package com.moodride.routeapi.repository;

import com.moodride.datamodels.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findTopByJobIdOrderByGeneratedAtAsc(UUID jobId);

    List<Route> findByJobIdOrderByGeneratedAtAsc(UUID jobId);

    List<Route> findTop1000ByOrderByGeneratedAtDesc();
}
