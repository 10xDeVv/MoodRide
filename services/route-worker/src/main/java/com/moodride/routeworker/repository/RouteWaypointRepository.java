package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteWaypoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface RouteWaypointRepository extends JpaRepository<RouteWaypoint, UUID> {
    List<RouteWaypoint> findByRouteIdOrderByWaypointOrderAsc(UUID routeId);
}
