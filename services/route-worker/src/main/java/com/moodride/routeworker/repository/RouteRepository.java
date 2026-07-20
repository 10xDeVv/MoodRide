package com.moodride.routeworker.repository;

import com.moodride.datamodels.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findByJobIdAndRouteProfile(UUID jobId, String routeProfile);

    long countByJobIdAndRouteProfileIsNotNull(UUID jobId);
}
