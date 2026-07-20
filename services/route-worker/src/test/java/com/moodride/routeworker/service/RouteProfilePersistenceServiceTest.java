package com.moodride.routeworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.routeworker.algorithm.RouteCandidate;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RouteDurationCalibrationRepository;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteRepository;
import com.moodride.routeworker.repository.RouteWaypointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteProfilePersistenceServiceTest {
    @Mock
    private RouteJobRepository jobRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private RouteWaypointRepository waypointRepository;
    @Mock
    private RouteDurationCalibrationRepository calibrationRepository;

    private RouteProfilePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new RouteProfilePersistenceService(
            jobRepository,
            routeRepository,
            waypointRepository,
            calibrationRepository,
            new ObjectMapper(),
            30
        );
    }

    @Test
    void redeliveryReturnsExistingPrimaryWithoutDuplicatingOrReplacingIt() {
        UUID lease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(lease);
        Route existing = route(job, job.getRouteId(), RouteProfilePersistenceService.PRIMARY_PROFILE);
        List<RouteWaypoint> existingWaypoints = List.of(
            new RouteWaypoint(existing, 0, 45.51, -122.67, "Arrive", 0.0)
        );
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(routeRepository.findById(job.getRouteId())).thenReturn(Optional.of(existing));
        when(waypointRepository.findByRouteIdOrderByWaypointOrderAsc(existing.getId()))
            .thenReturn(existingWaypoints);

        RouteProfilePersistenceService.PersistedProfile result = service.persistProfile(
            job.getId(),
            lease,
            candidate(0.99),
            RouteProfilePersistenceService.PRIMARY_PROFILE,
            Instant.now()
        );

        assertSame(existing, result.route());
        assertFalse(result.inserted());
        assertEquals(existing.getId(), job.getRouteId());
        assertEquals(1L, job.getOptionRevision());
        assertEquals(1, job.getOptionCount());
        verify(routeRepository, never()).saveAndFlush(any(Route.class));
        verify(waypointRepository, never()).saveAllAndFlush(any());
    }


    @Test
    void missingAlternativeCommitsOnceAndPreservesPrimaryRouteIdentity() {
        UUID lease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(lease);
        UUID primaryRouteId = job.getRouteId();
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfile(job.getId(), "balanced"))
            .thenReturn(Optional.empty());
        when(routeRepository.saveAndFlush(any(Route.class))).thenAnswer(invocation -> {
            Route saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(routeRepository.countByJobIdAndRouteProfileIsNotNull(job.getId())).thenReturn(2L);

        RouteProfilePersistenceService.PersistedProfile result = service.persistProfile(
            job.getId(),
            lease,
            candidate(0.82),
            "balanced",
            Instant.now()
        );

        assertTrue(result.inserted());
        assertEquals("balanced", result.route().getRouteProfile());
        assertEquals(primaryRouteId, job.getRouteId());
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(4L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        verify(waypointRepository).saveAllAndFlush(any());
        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void staleLeaseCannotInsertAlternativeOrAdvanceRevisions() {
        RouteJob job = primaryReadyJob(UUID.randomUUID());
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));

        assertThrows(
            LeaseLostException.class,
            () -> service.persistProfile(
                job.getId(),
                UUID.randomUUID(),
                candidate(0.82),
                "balanced",
                Instant.now()
            )
        );

        assertEquals(1L, job.getOptionRevision());
        assertEquals(1, job.getOptionCount());
        verify(routeRepository, never()).findByJobIdAndRouteProfile(any(), any());
        verify(routeRepository, never()).saveAndFlush(any(Route.class));
        verify(jobRepository, never()).saveAndFlush(job);
    }


    private RouteJob primaryReadyJob(UUID leaseToken) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setRouteId(UUID.randomUUID());
        job.setPrimaryReadyAt(Instant.now().minusSeconds(10));
        job.setStateRevision(4L);
        job.setOptionRevision(1L);
        job.setOptionCount(1);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        return job;
    }

    private Route route(RouteJob job, UUID routeId, String profile) {
        Route route = new Route(
            job.getId(),
            job.getUserId(),
            new GeometryFactory().createLineString(),
            job.getVibe()
        );
        route.setId(routeId);
        route.setRouteProfile(profile);
        route.setTotalDistanceKm(10.0);
        route.setEstimatedDurationMinutes(20);
        route.setScenicScore(0.8);
        return route;
    }

    private RouteCandidate candidate(double score) {
        return new RouteCandidate(
            List.of(new RoadNode(45.51, -122.67), new RoadNode(45.52, -122.68)),
            score,
            12.0,
            24,
            "beam-v2",
            12,
            Map.of()
        );
    }
}
