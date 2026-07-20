package com.moodride.routeworker.service;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.algorithm.RoutePlanner;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteGenerationServiceTest {

    @Test
    void publishesCommittedPrimaryBeforePersistingAlternatives() {
        RoutePlanner routePlanner = mock(RoutePlanner.class);
        RouteProfilePersistenceService persistenceService = mock(RouteProfilePersistenceService.class);
        @SuppressWarnings("unchecked")
        Consumer<RouteGenerationService.RouteGenerationResult> primaryReadyConsumer = mock(Consumer.class);
        RouteGenerationService service = new RouteGenerationService(routePlanner, persistenceService);

        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = mock(RouteJob.class);
        when(job.getId()).thenReturn(jobId);

        RouteCandidate primaryCandidate = new RouteCandidate(List.of(), 0.9, 42.0, 58);
        RouteCandidate balancedCandidate = new RouteCandidate(List.of(), 0.7, 39.0, 54);
        RouteCandidate shorterCandidate = new RouteCandidate(List.of(), 0.5, 31.0, 45);
        when(routePlanner.generateRouteOptions(job)).thenReturn(List.of(
            primaryCandidate,
            balancedCandidate,
            shorterCandidate
        ));

        Route primaryRoute = mock(Route.class);
        RouteGenerationService.RouteGenerationResult expectedPrimary = result(jobId, primaryRoute, 1, 1, false);
        when(persistenceService.persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(primaryCandidate),
            eq(RouteProfilePersistenceService.PRIMARY_PROFILE),
            any(Instant.class)
        )).thenReturn(persisted(expectedPrimary, true));
        when(persistenceService.persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(balancedCandidate),
            eq("balanced"),
            any(Instant.class)
        )).thenReturn(persisted(result(jobId, primaryRoute, 2, 2, false), true));
        when(persistenceService.persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(shorterCandidate),
            eq("shorter"),
            any(Instant.class)
        )).thenReturn(persisted(result(jobId, primaryRoute, 3, 3, true), true));

        RouteGenerationService.RouteGenerationResult actual = service.processRoute(
            job,
            leaseToken,
            primaryReadyConsumer
        );

        assertSame(expectedPrimary.route(), actual.route());
        assertSame(expectedPrimary.state(), actual.state());

        InOrder order = inOrder(routePlanner, persistenceService, primaryReadyConsumer);
        order.verify(routePlanner).generateRouteOptions(job);
        order.verify(persistenceService).persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(primaryCandidate),
            eq(RouteProfilePersistenceService.PRIMARY_PROFILE),
            any(Instant.class)
        );
        order.verify(primaryReadyConsumer).accept(actual);
        order.verify(persistenceService).persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(balancedCandidate),
            eq("balanced"),
            any(Instant.class)
        );
        order.verify(persistenceService).persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(shorterCandidate),
            eq("shorter"),
            any(Instant.class)
        );
    }

    @Test
    void stopsBeforeAlternativesWhenPrimaryPublicationFails() {
        RoutePlanner routePlanner = mock(RoutePlanner.class);
        RouteProfilePersistenceService persistenceService = mock(RouteProfilePersistenceService.class);
        @SuppressWarnings("unchecked")
        Consumer<RouteGenerationService.RouteGenerationResult> primaryReadyConsumer = mock(Consumer.class);
        RouteGenerationService service = new RouteGenerationService(routePlanner, persistenceService);

        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = mock(RouteJob.class);
        when(job.getId()).thenReturn(jobId);

        RouteCandidate primaryCandidate = new RouteCandidate(List.of(), 0.9, 42.0, 58);
        RouteCandidate balancedCandidate = new RouteCandidate(List.of(), 0.7, 39.0, 54);
        when(routePlanner.generateRouteOptions(job)).thenReturn(List.of(primaryCandidate, balancedCandidate));
        when(persistenceService.persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(primaryCandidate),
            eq(RouteProfilePersistenceService.PRIMARY_PROFILE),
            any(Instant.class)
        )).thenReturn(persisted(result(jobId, mock(Route.class), 1, 1, false), true));
        RuntimeException publicationFailure = new RuntimeException("PRIMARY_READY unavailable");
        org.mockito.Mockito.doThrow(publicationFailure).when(primaryReadyConsumer).accept(any());

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> service.processRoute(job, leaseToken, primaryReadyConsumer)
        );

        assertSame(publicationFailure, thrown);
        verify(persistenceService, never()).persistProfile(
            eq(jobId),
            eq(leaseToken),
            same(balancedCandidate),
            eq("balanced"),
            any(Instant.class)
        );
    }

    private RouteGenerationService.RouteGenerationResult result(
        UUID jobId,
        Route route,
        long optionRevision,
        int optionCount,
        boolean optionsComplete
    ) {
        return new RouteGenerationService.RouteGenerationResult(
            route,
            List.of(),
            new RouteJobLifecycleService.LifecycleSnapshot(
                jobId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                optionsComplete ? RouteJob.JobStatus.COMPLETED : RouteJob.JobStatus.PRIMARY_READY,
                optionRevision,
                optionRevision,
                optionCount,
                optionsComplete,
                null,
                0,
                optionsComplete ? Instant.now() : null
            )
        );
    }

    private RouteProfilePersistenceService.PersistedProfile persisted(
        RouteGenerationService.RouteGenerationResult result,
        boolean inserted
    ) {
        return new RouteProfilePersistenceService.PersistedProfile(
            result.route(),
            result.waypoints(),
            inserted,
            result.state()
        );
    }
}
