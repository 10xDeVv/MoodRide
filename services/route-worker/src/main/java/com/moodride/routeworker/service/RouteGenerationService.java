package com.moodride.routeworker.service;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.algorithm.RoutePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class RouteGenerationService {
    private static final Logger logger = LoggerFactory.getLogger(RouteGenerationService.class);
    private static final List<String> ROUTE_OPTION_PROFILES = List.of(
        RouteProfilePersistenceService.PRIMARY_PROFILE,
        "balanced",
        "shorter"
    );

    private final RoutePlanner routePlanner;
    private final RouteProfilePersistenceService persistenceService;

    public RouteGenerationService(
        RoutePlanner routePlanner,
        RouteProfilePersistenceService persistenceService
    ) {
        this.routePlanner = routePlanner;
        this.persistenceService = persistenceService;
    }

    public RouteGenerationResult processRoute(RouteJob job) {
        return processRoute(job, job.getLeaseToken(), null);
    }

    public RouteGenerationResult processRoute(
        RouteJob job,
        Consumer<RouteGenerationResult> primaryReadyConsumer
    ) {
        return processRoute(job, job.getLeaseToken(), primaryReadyConsumer);
    }

    public RouteGenerationResult processRoute(
        RouteJob job,
        UUID expectedLeaseToken,
        Consumer<RouteGenerationResult> primaryReadyConsumer
    ) {
        long processStartedNanos = System.nanoTime();
        List<RouteCandidate> candidates = routePlanner.generateRouteOptions(job);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No route candidates generated for job " + job.getId());
        }

        Instant generatedAtBase = Instant.now();
        int persistedProfiles = Math.min(candidates.size(), ROUTE_OPTION_PROFILES.size());
        RouteGenerationResult primaryResult = null;
        int insertedProfiles = 0;

        for (int index = 0; index < persistedProfiles; index++) {
            RouteProfilePersistenceService.PersistedProfile persisted = persistenceService.persistProfile(
                job.getId(),
                expectedLeaseToken,
                candidates.get(index),
                ROUTE_OPTION_PROFILES.get(index),
                generatedAtBase.plusMillis(index)
            );
            if (persisted.inserted()) {
                insertedProfiles++;
            }
            if (index == 0) {
                primaryResult = toGenerationResult(persisted);
                if (primaryReadyConsumer != null) {
                    primaryReadyConsumer.accept(primaryResult);
                }
            }
        }

        logger.info(
            "Route job {} persistence completed totalMs={} candidateCount={} profileCount={} insertedProfiles={}",
            job.getId(),
            elapsedMillis(processStartedNanos),
            candidates.size(),
            persistedProfiles,
            insertedProfiles
        );
        return primaryResult;
    }

    public RouteGenerationResult loadPrimary(UUID jobId) {
        return toGenerationResult(persistenceService.loadPrimary(jobId));
    }

    private RouteGenerationResult toGenerationResult(
        RouteProfilePersistenceService.PersistedProfile persisted
    ) {
        return new RouteGenerationResult(
            persisted.route(),
            persisted.waypoints(),
            persisted.state()
        );
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record RouteGenerationResult(
        Route route,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
    }
}
