package com.moodride.routeapi.repository;

import com.moodride.datamodels.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findByJobId(UUID jobId);
}
