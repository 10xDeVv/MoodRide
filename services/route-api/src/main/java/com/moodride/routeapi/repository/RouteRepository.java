package com.moodride.routeapi.repository;

import com.moodride.datamodels.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    List<Route> findByJobIdOrderByGeneratedAtAsc(UUID jobId);
    @Query("""
        select route.routeProfile as profile,
               route.id as routeId,
               route.scenicScore as scenicScore,
               route.totalDistanceKm as totalDistanceKm,
               route.estimatedDurationMinutes as estimatedDurationMinutes,
               route.generatedAt as generatedAt
        from Route route
        where route.jobId = :jobId
        order by route.generatedAt, route.id
        """)
    List<RouteOptionSummary> findOptionSummariesByJobId(@Param("jobId") UUID jobId);


    List<Route> findTop1000ByOrderByGeneratedAtDesc();
    interface RouteOptionSummary {
        String getProfile();
        UUID getRouteId();
        double getScenicScore();
        double getTotalDistanceKm();
        int getEstimatedDurationMinutes();
        Instant getGeneratedAt();
    }

}
