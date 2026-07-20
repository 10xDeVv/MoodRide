package com.moodride.routeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteMode;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.routeapi.dto.PrimaryRouteResponse;
import com.moodride.routeapi.exception.RouteNotFoundException;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrimaryRouteServiceTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteJobRepository jobRepository;

    private PrimaryRouteService primaryRouteService;

    @BeforeEach
    void setUp() {
        primaryRouteService = new PrimaryRouteService(routeRepository, jobRepository, new ObjectMapper());
    }

    @Test
    void primaryReadyReturnsEveryPersistedCoordinateAndLightweightMetadata() {
        UUID routeId = UUID.randomUUID();
        UUID balancedRouteId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-19T10:00:01Z");
        Instant expiresAt = Instant.parse("2026-07-20T10:00:01Z");

        Route route = primaryRoute(routeId, jobId, createdAt, expiresAt);
        route.setWaypoints(List.of(
            new RouteWaypoint(route, 0, 45.5152, -122.6784, "sample one", 4.0),
            new RouteWaypoint(route, 1, 45.5300, -122.7000, "sample two", 0.0)
        ));

        RouteJob job = publishedJob(jobId, routeId, RouteJob.JobStatus.PRIMARY_READY);
        job.setVibesJson("[\"coastal\",\"Riverside\",\"coastal\"]");
        job.setAlgorithmVersion("beam-v4");
        job.setStartedAt(Instant.parse("2026-07-19T10:00:00Z"));
        job.setPrimaryReadyAt(Instant.parse("2026-07-19T10:00:01.250Z"));
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setOptionsComplete(false);

        RouteRepository.RouteOptionSummary primary = optionSummary(
            "most_scenic", routeId, 0.8125, 41.75, 62
        );
        RouteRepository.RouteOptionSummary balanced = optionSummary(
            "balanced", balancedRouteId, 0.7425, 36.25, 55
        );

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findOptionSummariesByJobId(jobId)).thenReturn(List.of(primary, balanced));

        PrimaryRouteResponse response = primaryRouteService.getPrimaryRoute(routeId);

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.profile()).isEqualTo("most_scenic");
        assertThat(response.scenicScore()).isEqualTo(81.25);
        assertThat(response.totalDistanceKm()).isEqualTo(41.75);
        assertThat(response.estimatedDurationMinutes()).isEqualTo(62);
        assertThat(response.timeBudgetMinutes()).isEqualTo(75);
        assertThat(response.routeMode()).isEqualTo("drive");
        assertThat(response.startLat()).isEqualTo(45.5152);
        assertThat(response.startLng()).isEqualTo(-122.6784);
        assertThat(response.vibes()).containsExactly("coastal", "riverside");
        assertThat(response.algorithmVersion()).isEqualTo("beam-v4");
        assertThat(response.computationTimeMs()).isEqualTo(1_250);
        assertThat(response.optionRevision()).isEqualTo(2L);
        assertThat(response.optionCount()).isEqualTo(2);
        assertThat(response.optionsComplete()).isFalse();
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.expiresAt()).isEqualTo(expiresAt);

        Map<String, Object> lineString = map(response.geometry().get("geometry"));
        assertThat(response.geometry().get("type")).isEqualTo("Feature");
        assertThat(lineString.get("type")).isEqualTo("LineString");
        assertThat(list(lineString.get("coordinates"))).containsExactly(
            List.of(-122.6784, 45.5152),
            List.of(-122.6801, 45.5189),
            List.of(-122.6905, 45.5255),
            List.of(-122.7000, 45.5300)
        );
        assertThat(map(response.geometry().get("properties"))).isEmpty();

        assertThat(response.routeOptions())
            .extracting(option -> option.profile())
            .containsExactly("most_scenic", "balanced");
        assertThat(response.routeOptions().getFirst().scenicScore()).isEqualTo(81.25);
        assertThat(response.routeOptions().get(1).routeUrl()).isEqualTo("/routes/route/" + balancedRouteId);

        verify(routeRepository).findById(routeId);
        verify(jobRepository).findById(jobId);
        verify(routeRepository).findOptionSummariesByJobId(jobId);
        verifyNoMoreInteractions(routeRepository, jobRepository);
    }

    @Test
    void completedPrimaryIsAvailableAndUsesCompletionTimeForLegacyRows() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(
            routeId,
            jobId,
            Instant.parse("2026-07-19T10:00:04Z"),
            Instant.parse("2026-07-20T10:00:04Z")
        );
        RouteJob job = publishedJob(jobId, routeId, RouteJob.JobStatus.COMPLETED);
        job.setStartedAt(Instant.parse("2026-07-19T10:00:00Z"));
        job.setCompletedAt(Instant.parse("2026-07-19T10:00:04Z"));
        job.setOptionRevision(3L);
        job.setOptionCount(3);
        job.setOptionsComplete(true);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findOptionSummariesByJobId(jobId)).thenReturn(List.of());

        PrimaryRouteResponse response = primaryRouteService.getPrimaryRoute(routeId);

        assertThat(response.computationTimeMs()).isEqualTo(4_000);
        assertThat(response.optionRevision()).isEqualTo(3L);
        assertThat(response.optionCount()).isEqualTo(3);
        assertThat(response.optionsComplete()).isTrue();
    }

    @Test
    void completedLegacyJobWithoutStoredPrimaryIdUsesStatusCompatibleFallback() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(
            routeId,
            jobId,
            Instant.parse("2026-07-19T10:00:04Z"),
            Instant.parse("2026-07-20T10:00:04Z")
        );
        route.setRouteProfile(null);
        Route balanced = primaryRoute(
            UUID.randomUUID(),
            jobId,
            Instant.parse("2026-07-19T10:00:03Z"),
            Instant.parse("2026-07-20T10:00:03Z")
        );
        balanced.setRouteProfile("balanced");
        RouteJob job = publishedJob(jobId, routeId, RouteJob.JobStatus.COMPLETED);
        job.setRouteId(null);
        RouteRepository.RouteOptionSummary legacySummary = optionSummary(
            null,
            routeId,
            route.getScenicScore(),
            route.getTotalDistanceKm(),
            route.getEstimatedDurationMinutes()
        );
        RouteRepository.RouteOptionSummary balancedSummary = optionSummary(
            "balanced",
            balanced.getId(),
            balanced.getScenicScore(),
            balanced.getTotalDistanceKm(),
            balanced.getEstimatedDurationMinutes()
        );

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of(balanced, route));
        when(routeRepository.findOptionSummariesByJobId(jobId))
            .thenReturn(List.of(balancedSummary, legacySummary));

        PrimaryRouteResponse response = primaryRouteService.getPrimaryRoute(routeId);

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.profile()).isEqualTo("most_scenic");
        assertThat(response.routeOptions())
            .extracting(option -> option.profile())
            .containsExactly("most_scenic", "balanced");
        assertThat(response.routeOptions().getFirst().routeId()).isEqualTo(routeId);
        verify(routeRepository).findByJobIdOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void prePrimaryJobIsRejectedBeforeOptionLookup() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(routeId, jobId, Instant.now(), Instant.now().plusSeconds(3_600));
        RouteJob job = publishedJob(jobId, routeId, RouteJob.JobStatus.PROCESSING);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> primaryRouteService.getPrimaryRoute(routeId))
            .isInstanceOf(RouteNotFoundException.class)
            .hasMessage("Route not found: " + routeId);

        verify(routeRepository, never()).findOptionSummariesByJobId(jobId);
    }

    @Test
    void nonPrimaryRouteForPublishedJobIsRejectedBeforeOptionLookup() {
        UUID requestedRouteId = UUID.randomUUID();
        UUID committedPrimaryRouteId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(requestedRouteId, jobId, Instant.now(), Instant.now().plusSeconds(3_600));
        RouteJob job = publishedJob(jobId, committedPrimaryRouteId, RouteJob.JobStatus.PRIMARY_READY);

        when(routeRepository.findById(requestedRouteId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> primaryRouteService.getPrimaryRoute(requestedRouteId))
            .isInstanceOf(RouteNotFoundException.class);

        verify(routeRepository, never()).findOptionSummariesByJobId(jobId);
    }

    @Test
    void missingPersistedGeometryNeverFallsBackToWaypointSamples() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(routeId, jobId, Instant.now(), Instant.now().plusSeconds(3_600));
        route.setGeometry(null);
        route.setWaypoints(List.of(
            new RouteWaypoint(route, 0, 45.5152, -122.6784, "sample one", 4.0),
            new RouteWaypoint(route, 1, 45.5300, -122.7000, "sample two", 0.0)
        ));
        RouteJob job = publishedJob(jobId, routeId, RouteJob.JobStatus.PRIMARY_READY);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> primaryRouteService.getPrimaryRoute(routeId))
            .isInstanceOf(RouteNotFoundException.class);

        verify(routeRepository, never()).findOptionSummariesByJobId(jobId);
    }

    @Test
    void repeatableReadPinsLifecycleAndOptionSummariesAcrossAlternativeCommit() throws Exception {
        UUID routeId = UUID.randomUUID();
        UUID balancedRouteId = UUID.randomUUID();
        UUID shorterRouteId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Route route = primaryRoute(
            routeId,
            jobId,
            Instant.parse("2026-07-19T10:00:01Z"),
            Instant.parse("2026-07-20T10:00:01Z")
        );

        RouteJob primaryReadyJob = publishedJob(jobId, routeId, RouteJob.JobStatus.PRIMARY_READY);
        primaryReadyJob.setOptionRevision(1L);
        primaryReadyJob.setOptionCount(1);
        primaryReadyJob.setOptionsComplete(false);
        RouteRepository.RouteOptionSummary primary = optionSummary(
            "most_scenic", routeId, 0.8125, 41.75, 62
        );

        RouteJob completedJob = publishedJob(jobId, routeId, RouteJob.JobStatus.COMPLETED);
        completedJob.setOptionRevision(3L);
        completedJob.setOptionCount(3);
        completedJob.setOptionsComplete(true);
        RouteRepository.RouteOptionSummary balanced = optionSummary(
            "balanced", balancedRouteId, 0.7425, 36.25, 55
        );
        RouteRepository.RouteOptionSummary shorter = optionSummary(
            "shorter", shorterRouteId, 0.6325, 31.25, 49
        );

        RouteSnapshot beforeAlternativeCommit = new RouteSnapshot(
            route, primaryReadyJob, List.of(primary)
        );
        RouteSnapshot afterAlternativeCommit = new RouteSnapshot(
            route, completedJob, List.of(primary, balanced, shorter)
        );
        SnapshotTransactionManager transactionManager =
            new SnapshotTransactionManager(beforeAlternativeCommit);
        CountDownLatch jobRead = new CountDownLatch(1);
        CountDownLatch alternativeCommitted = new CountDownLatch(1);

        when(routeRepository.findById(routeId))
            .thenAnswer(invocation -> Optional.of(transactionManager.currentSnapshot().route()));
        when(jobRepository.findById(jobId)).thenAnswer(invocation -> {
            RouteJob jobAtQuerySnapshot = transactionManager.currentSnapshot().job();
            jobRead.countDown();
            assertThat(alternativeCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(jobAtQuerySnapshot);
        });
        when(routeRepository.findOptionSummariesByJobId(jobId))
            .thenAnswer(invocation -> transactionManager.currentSnapshot().options());

        PrimaryRouteService target =
            new PrimaryRouteService(routeRepository, jobRepository, new ObjectMapper());
        TransactionInterceptor transactionInterceptor = new TransactionInterceptor(
            transactionManager,
            new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactionInterceptor);
        PrimaryRouteService transactionalService =
            (PrimaryRouteService) proxyFactory.getProxy();

        ExecutorService alternativeExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> alternativeCommit = alternativeExecutor.submit(() -> {
                if (!jobRead.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Primary read did not reach the job snapshot");
                }
                transactionManager.commitAlternative(afterAlternativeCommit);
                alternativeCommitted.countDown();
                return null;
            });

            PrimaryRouteResponse duringCommit = transactionalService.getPrimaryRoute(routeId);
            alternativeCommit.get(5, TimeUnit.SECONDS);

            assertThat(transactionManager.observedIsolation())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(transactionManager.observedReadOnly()).isTrue();
            assertThat(duringCommit.optionRevision()).isEqualTo(1L);
            assertThat(duringCommit.optionCount()).isEqualTo(1);
            assertThat(duringCommit.optionsComplete()).isFalse();
            assertThat(duringCommit.routeOptions())
                .extracting(option -> option.profile())
                .containsExactly("most_scenic");
            assertThat(duringCommit.routeOptions()).hasSize(duringCommit.optionCount());

            PrimaryRouteResponse afterCommit = transactionalService.getPrimaryRoute(routeId);

            assertThat(afterCommit.optionRevision()).isEqualTo(3L);
            assertThat(afterCommit.optionCount()).isEqualTo(3);
            assertThat(afterCommit.optionsComplete()).isTrue();
            assertThat(afterCommit.routeOptions())
                .extracting(option -> option.profile())
                .containsExactly("most_scenic", "balanced", "shorter");
            assertThat(afterCommit.routeOptions()).hasSize(afterCommit.optionCount());
        } finally {
            alternativeExecutor.shutdownNow();
        }
    }

    private Route primaryRoute(UUID routeId, UUID jobId, Instant createdAt, Instant expiresAt) {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        LineString geometry = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-122.6784, 45.5152),
            new Coordinate(-122.6801, 45.5189),
            new Coordinate(-122.6905, 45.5255),
            new Coordinate(-122.7000, 45.5300)
        });

        Route route = new Route();
        route.setId(routeId);
        route.setJobId(jobId);
        route.setGeometry(geometry);
        route.setRouteProfile("most_scenic");
        route.setRouteMode(RouteMode.DRIVE);
        route.setVibe("coastal");
        route.setScenicScore(0.8125);
        route.setTotalDistanceKm(41.75);
        route.setEstimatedDurationMinutes(62);
        route.setGeneratedAt(createdAt);
        route.setExpiresAt(expiresAt);
        return route;
    }

    private RouteJob publishedJob(UUID jobId, UUID routeId, RouteJob.JobStatus status) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.5152, -122.6784, 75, "coastal");
        job.setId(jobId);
        job.setRouteId(routeId);
        job.setStatus(status);
        return job;
    }

    private RouteRepository.RouteOptionSummary optionSummary(String profile,
                                                              UUID routeId,
                                                              double scenicScore,
                                                              double distanceKm,
                                                              int durationMinutes) {
        RouteRepository.RouteOptionSummary summary = mock(RouteRepository.RouteOptionSummary.class);
        when(summary.getProfile()).thenReturn(profile);
        when(summary.getRouteId()).thenReturn(routeId);
        when(summary.getScenicScore()).thenReturn(scenicScore);
        when(summary.getTotalDistanceKm()).thenReturn(distanceKm);
        when(summary.getEstimatedDurationMinutes()).thenReturn(durationMinutes);
        when(summary.getGeneratedAt()).thenReturn(Instant.EPOCH);
        return summary;
    }

    private record RouteSnapshot(
        Route route,
        RouteJob job,
        List<RouteRepository.RouteOptionSummary> options
    ) {}

    private static final class SnapshotTransactionManager implements PlatformTransactionManager {
        private final AtomicReference<RouteSnapshot> committedSnapshot;
        private final ThreadLocal<RouteSnapshot> transactionSnapshot = new ThreadLocal<>();
        private final ThreadLocal<Boolean> repeatableRead = new ThreadLocal<>();
        private final AtomicInteger observedIsolation =
            new AtomicInteger(TransactionDefinition.ISOLATION_DEFAULT);
        private final AtomicBoolean observedReadOnly = new AtomicBoolean();

        private SnapshotTransactionManager(RouteSnapshot initialSnapshot) {
            this.committedSnapshot = new AtomicReference<>(initialSnapshot);
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            observedIsolation.set(definition.getIsolationLevel());
            observedReadOnly.set(definition.isReadOnly());
            boolean pinsSnapshot =
                definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ;
            repeatableRead.set(pinsSnapshot);
            if (pinsSnapshot) {
                transactionSnapshot.set(committedSnapshot.get());
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            clearTransaction();
        }

        @Override
        public void rollback(TransactionStatus status) {
            clearTransaction();
        }

        private RouteSnapshot currentSnapshot() {
            if (Boolean.TRUE.equals(repeatableRead.get())) {
                return transactionSnapshot.get();
            }
            return committedSnapshot.get();
        }

        private void commitAlternative(RouteSnapshot alternativeSnapshot) {
            committedSnapshot.set(alternativeSnapshot);
        }

        private int observedIsolation() {
            return observedIsolation.get();
        }

        private boolean observedReadOnly() {
            return observedReadOnly.get();
        }

        private void clearTransaction() {
            transactionSnapshot.remove();
            repeatableRead.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> list(Object value) {
        return (List<List<Double>>) value;
    }
}
