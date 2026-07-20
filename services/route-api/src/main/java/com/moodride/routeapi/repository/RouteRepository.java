package com.moodride.routeapi.repository;

import com.moodride.datamodels.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findTopByJobIdOrderByGeneratedAtAsc(UUID jobId);

    List<Route> findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(UUID jobId);
    @Query("""
        select route.routeProfile as profile,
               route.id as routeId,
               route.scenicScore as scenicScore,
               route.totalDistanceKm as totalDistanceKm,
               route.estimatedDurationMinutes as estimatedDurationMinutes
        from Route route
        where route.jobId = :jobId
          and route.routeProfile is not null
        order by case route.routeProfile
                     when 'most_scenic' then 0
                     when 'balanced' then 1
                     when 'shorter' then 2
                     else 3
                 end,
                 route.generatedAt,
                 route.id
        """)
    List<RouteOptionSummary> findOptionSummariesByJobId(@Param("jobId") UUID jobId);


    List<Route> findTop1000ByOrderByGeneratedAtDesc();
    interface RouteOptionSummary {
        String getProfile();
        UUID getRouteId();
        double getScenicScore();
        double getTotalDistanceKm();
        int getEstimatedDurationMinutes();
    }

}
