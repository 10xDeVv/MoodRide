package com.moodride.routeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.routeapi.cache.CacheKeySchema;
import com.moodride.routeapi.cache.CacheNames;
import com.fasterxml.jackson.core.type.TypeReference;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteWaypoint;
import com.moodride.datamodels.RouteWeightCalibration;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.geo.H3Utils;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.dto.RouteJobStatusResponse;
import com.moodride.routeapi.dto.RouteRatingRequest;
import com.moodride.routeapi.dto.RouteRatingResponse;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteSubmissionResponse;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.RouteWeightCalibrationRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RouteJobRepository jobRepository;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private RouteWeightCalibrationRepository calibrationRepository;

    @Mock
    private ScenicScoreTileRepository scenicScoreTileRepository;

    @Mock
    private RouteJobDispatchService dispatchService;


    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = newRouteService();

        lenient().when(jobRepository.save(any(RouteJob.class))).thenAnswer(invocation -> {
            RouteJob job = invocation.getArgument(0);
            if (job.getId() == null) {
                job.setId(UUID.randomUUID());
            }
            return job;
        });
        lenient().when(calibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of());
        lenient().when(calibrationRepository.saveAll(anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(List.of());
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void submitRouteReturnsSpecAlignedStatusUrl() throws Exception {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal", "mountain"),
            null,
            null
        );

        RouteSubmissionResponse response = routeService.submitRoute(request);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.statusUrl()).isEqualTo("/routes/" + response.jobId());
        assertThat(response.wsChannel()).isEqualTo("job:" + response.jobId());

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getVibe()).isEqualTo("coastal");
        List<String> storedVibes = new ObjectMapper().readValue(saved.getValue().getVibesJson(), new TypeReference<>() {
        });
        assertThat(storedVibes).containsExactly("coastal", "mountain");

        verify(dispatchService).enqueue(
            response.jobId(),
            saved.getValue().getSubmittedAt()
        );
        verify(dispatchService).publishCommitted(response.jobId());
    }

    @Test
    void submitRouteCreatesDispatchBeforePublishingOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            RouteSubmissionResponse response = routeService.submitRoute(routeRequest());

            verify(dispatchService).enqueue(
                response.jobId(),
                response.queuedAt()
            );
            verify(dispatchService, never()).publishCommitted(any(UUID.class));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            verify(dispatchService).publishCommitted(response.jobId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void postCommitPublicationFailureReturnsOriginalAcceptedJob() {
        RecordingCommitTransactionManager transactionManager =
            new RecordingCommitTransactionManager();
        doAnswer(invocation -> {
            assertThat(transactionManager.commitCompleted()).isTrue();
            throw new IllegalStateException("broker unavailable");
        }).when(dispatchService).publishCommitted(any(UUID.class));
        RouteService transactionalService = transactionalService(transactionManager);

        RouteSubmissionResponse response = transactionalService.submitRoute(routeRequest());

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository, times(1)).save(saved.capture());
        assertThat(response.jobId()).isEqualTo(saved.getValue().getId());
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(dispatchService, times(1)).enqueue(
            response.jobId(),
            response.queuedAt()
        );
        verify(dispatchService, times(1)).publishCommitted(response.jobId());
    }

    @Test
    void immediatePublicationFailureReturnsOriginalAcceptedJob() {
        doThrow(new IllegalStateException("broker unavailable"))
            .when(dispatchService).publishCommitted(any(UUID.class));

        RouteSubmissionResponse response = routeService.submitRoute(routeRequest());

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository, times(1)).save(saved.capture());
        assertThat(response.jobId()).isEqualTo(saved.getValue().getId());
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(dispatchService, times(1)).enqueue(
            response.jobId(),
            response.queuedAt()
        );
        verify(dispatchService, times(1)).publishCommitted(response.jobId());
    }

    @Test
    void submitRouteNormalizesVibeAliases() throws Exception {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("Date Night", "winding roads", "Photo-Worthy"),
            null,
            Map.of()
        );

        routeService.submitRoute(request);

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getVibe()).isEqualTo("adventure");
        List<String> storedVibes = new ObjectMapper().readValue(saved.getValue().getVibesJson(), new TypeReference<>() {
        });
        assertThat(storedVibes).containsExactly("adventure", "winding_roads");
    }

    @Test
    void submitRouteRejectsInvalidVibe() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("party"),
            null,
            null
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid vibe");
    }

    @Test
    void submitRouteRejectsUnknownPreferenceKeys() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal"),
            null,
            Map.of("avoidTolls", false)
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("preferenceVector must use numeric values");
    }

    @Test
    void submitRouteRejectsMoreThanThreeVibes() {
        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal", "mountain", "nature", "riverside"),
            null,
            null
        );

        assertThatThrownBy(() -> routeService.submitRoute(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At most three vibes");
    }

    @Test
    void rateRoutePersistsRatingAndPublishesEvents() {
        UUID routeId = UUID.randomUUID();
        Route route = new Route();
        route.setId(routeId);
        route.setJobId(UUID.randomUUID());
        route.setUserId(UUID.randomUUID());

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RouteRatingResponse response = routeService.rateRoute(routeId, new RouteRatingRequest(5));

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.ratedAt()).isNotNull();

        ArgumentCaptor<Route> saved = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(saved.capture());
        assertThat(saved.getValue().getUserRating()).isEqualTo(5);
        assertThat(saved.getValue().getRatedAt()).isNotNull();

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("user.events.route_rated"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("user.events.drive_completed"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rateRoutePersistsFeedbackTagsAndAppliesTagCalibration() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 60, "scenic");
        job.setId(jobId);
        job.setVibesJson("[\"scenic\"]");

        Route route = new Route();
        route.setId(routeId);
        route.setJobId(jobId);
        route.setUserId(userId);
        route.setVibe("scenic");

        RouteWeightCalibration calibration = new RouteWeightCalibration("scenic");

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(calibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of(calibration));

        RouteRatingResponse response = routeService.rateRoute(
            routeId,
            new RouteRatingRequest(4, List.of("too urban", "loved-water", "ignored_tag", "too_boring", "too_long", "too_short"))
        );

        assertThat(response.feedbackTags()).containsExactly("too_urban", "loved_water", "too_boring", "too_long");

        ArgumentCaptor<Route> savedRoute = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(savedRoute.capture());
        assertThat(savedRoute.getValue().getFeedbackTagsJson()).contains("too_urban", "loved_water", "too_boring", "too_long");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RouteWeightCalibration>> savedCalibrations = ArgumentCaptor.forClass(List.class);
        verify(calibrationRepository).saveAll(savedCalibrations.capture());
        RouteWeightCalibration savedCalibration = savedCalibrations.getValue().getFirst();
        assertThat(savedCalibration.getSolitudeMultiplier()).isGreaterThan(1.0);
        assertThat(savedCalibration.getWaterMultiplier()).isGreaterThan(1.0);
        assertThat(savedCalibration.getCurvesMultiplier()).isGreaterThan(1.0);
        assertThat(savedCalibration.getPoiMultiplier()).isLessThan(1.0);
        assertThat(savedCalibration.getSampleCount()).isEqualTo(1);
    }

    @Test
    void submitRouteAppliesLearnedCalibrationToStoredPreferenceVector() throws Exception {
        RouteWeightCalibration calibration = new RouteWeightCalibration("scenic");
        calibration.setWaterMultiplier(1.30);
        calibration.setGreeneryMultiplier(1.0);
        calibration.setElevationMultiplier(1.0);
        calibration.setSolitudeMultiplier(0.80);
        calibration.setCurvesMultiplier(1.0);
        calibration.setPoiMultiplier(1.0);
        lenient().when(calibrationRepository.findByVibeIn(anyCollection())).thenReturn(List.of(calibration));

        RouteRequest request = new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("scenic"),
            null,
            Map.of(
                "water", 1.0,
                "greenery", 1.0,
                "elevation", 1.0,
                "solitude", 1.0,
                "curves", 1.0,
                "poi", 1.0
            )
        );

        routeService.submitRoute(request);

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        Map<String, Double> storedPreferences = new ObjectMapper().readValue(
            saved.getValue().getPreferenceVector(),
            new TypeReference<>() {
            }
        );

        assertThat(storedPreferences.get("water")).isGreaterThan(storedPreferences.get("greenery"));
        assertThat(storedPreferences.get("solitude")).isLessThan(storedPreferences.get("greenery"));
    }

    @Test
    void getRouteReturnsRichScenicMetadata() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setVibesJson("[\"coastal\",\"riverside\"]");
        job.setStartedAt(Instant.parse("2026-04-02T14:30:01Z"));
        job.setCompletedAt(Instant.parse("2026-04-02T14:30:05Z"));
        job.setTimeBudgetMinutes(90);
        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);

        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-122.6784, 45.5152),
            new Coordinate(-122.6801, 45.5189),
            new Coordinate(-122.7000, 45.5300)
        });

        Route route = new Route();
        route.setId(routeId);
        route.setJobId(jobId);
        route.setUserId(userId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setTotalDistanceKm(62.3);
        route.setEstimatedDurationMinutes(88);
        route.setScenicScore(0.785);
        route.setScoreBreakdownJson("""
            {"final_score":0.785,"landscape_score":0.72,"vibe_fit_score":0.81,"urban_penalty":0.08}
            """);
        route.setVibe("coastal");
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setExpiresAt(Instant.parse("2026-04-09T14:30:05Z"));

        RouteWaypoint first = new RouteWaypoint(route, 0, 45.5152, -122.6784, "Start the scenic loop", 12.4);
        RouteWaypoint middle = new RouteWaypoint(route, 1, 45.5189, -122.6801, "Continue along the ridge", 18.1);
        RouteWaypoint last = new RouteWaypoint(route, 2, 45.5300, -122.7000, "Arrive back at the start", 0.0);
        route.setWaypoints(List.of(first, middle, last));

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));

        RouteDetailResponse response = routeService.getRoute(routeId);

        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.qualityTier()).isEqualTo("PREMIUM");

        Map<String, Object> properties = castMap(response.geometry().get("properties"));
        assertThat((List<?>) properties.get("segmentScores")).isNotEmpty();
        assertThat((List<?>) properties.get("segmentColors")).isNotEmpty();
        assertThat(response.vibes()).containsExactly("coastal", "riverside");
        assertThat(response.scenicHighlights()).isNotEmpty();
        assertThat(response.routeOptions()).hasSize(1);
        assertThat(response.routeOptions().getFirst().profile()).isEqualTo("most_scenic");
        assertThat(response.optionRevision()).isEqualTo(1);
        assertThat(response.optionCount()).isEqualTo(1);
        assertThat(response.optionsComplete()).isTrue();
        assertThat(response.scoreBreakdown())
            .containsEntry("final_score", 0.785)
            .containsEntry("urban_penalty", 0.08);
        assertThat(response.routeOptions().getFirst().scoreBreakdown())
            .containsEntry("vibe_fit_score", 0.81);
        assertThat(response.computationTimeMs()).isEqualTo(4000);
    }

    @Test
    void getRoutePrefersStoredGeometryOverSparseWaypoints() {
        UUID routeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);

        GeometryFactory geometryFactory = new GeometryFactory();
        LineString fullGeometry = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-122.6784, 45.5152),
            new Coordinate(-122.6801, 45.5189),
            new Coordinate(-122.6900, 45.5240),
            new Coordinate(-122.7000, 45.5300)
        });

        Route route = new Route();
        route.setId(routeId);
        route.setJobId(jobId);
        route.setUserId(userId);
        route.setGeometry(fullGeometry);
        route.setTotalDistanceKm(62.3);
        route.setEstimatedDurationMinutes(88);
        route.setScenicScore(0.785);
        route.setVibe("coastal");
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setWaypoints(List.of(
            new RouteWaypoint(route, 0, 45.5152, -122.6784, "Start", 62.3),
            new RouteWaypoint(route, 1, 45.5300, -122.7000, "Arrive", 0.0)
        ));

        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));

        RouteDetailResponse response = routeService.getRoute(routeId);

        Map<String, Object> geometry = castMap(response.geometry().get("geometry"));
        assertThat((List<?>) geometry.get("coordinates")).hasSize(4);
        assertThat(response.startLat()).isEqualTo(45.5152);
        assertThat(response.startLng()).isEqualTo(-122.6784);
    }

    @Test
    void getRouteExcludesLegacyNullProfileRowsFromRouteOptions() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GeometryFactory geometryFactory = new GeometryFactory();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 60, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);

        Route primary = route(
            jobId, "most_scenic", geometryFactory, -122.6784, 45.5152, -122.7000, 45.5300, 0.78, 58, 24.0
        );
        Route legacyUnprofiled = route(
            jobId, null, geometryFactory, -122.6784, 45.5152, -122.6900, 45.5200, 0.69, 52, 21.0
        );

        when(routeRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of(primary, legacyUnprofiled));

        RouteDetailResponse response = routeService.getRoute(primary.getId());

        assertThat(response.routeOptions())
            .extracting(option -> option.profile())
            .containsExactly("most_scenic");
        assertThat(response.routeOptions())
            .noneMatch(option -> option.routeId().equals(legacyUnprofiled.getId()));
        assertThat(response.routeOptions()).hasSize(response.optionCount());
        verify(routeRepository).findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void getRouteRepeatableReadPinsLifecycleAndRichOptionsAcrossAlternativeCommit() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GeometryFactory geometryFactory = new GeometryFactory();
        Route primary = route(
            jobId, "most_scenic", geometryFactory, -79.3832, 43.6532, -79.3300, 43.6600, 0.78, 58, 24.0
        );
        Route balanced = route(
            jobId, "balanced", geometryFactory, -79.3832, 43.6532, -79.3400, 43.6650, 0.76, 55, 22.0
        );
        Route shorter = route(
            jobId, "shorter", geometryFactory, -79.3832, 43.6532, -79.3500, 43.6500, 0.74, 48, 18.0
        );

        RouteJob primaryReadyJob = new RouteJob(userId, 43.6532, -79.3832, 60, "coastal");
        primaryReadyJob.setId(jobId);
        primaryReadyJob.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        primaryReadyJob.setOptionRevision(1);
        primaryReadyJob.setOptionCount(1);
        primaryReadyJob.setOptionsComplete(false);

        RouteJob completedJob = new RouteJob(userId, 43.6532, -79.3832, 60, "coastal");
        completedJob.setId(jobId);
        completedJob.setStatus(RouteJob.JobStatus.COMPLETED);
        completedJob.setOptionRevision(3);
        completedJob.setOptionCount(3);
        completedJob.setOptionsComplete(true);

        RichRouteSnapshot beforeAlternativeCommit = new RichRouteSnapshot(
            primary, primaryReadyJob, List.of(primary)
        );
        RichRouteSnapshot afterAlternativeCommit = new RichRouteSnapshot(
            primary, completedJob, List.of(primary, balanced, shorter)
        );
        RichSnapshotTransactionManager transactionManager =
            new RichSnapshotTransactionManager(beforeAlternativeCommit);
        CountDownLatch jobRead = new CountDownLatch(1);
        CountDownLatch alternativeCommitted = new CountDownLatch(1);

        when(routeRepository.findById(primary.getId()))
            .thenAnswer(ignored -> Optional.of(transactionManager.currentSnapshot().route()));
        when(jobRepository.findById(jobId)).thenAnswer(ignored -> {
            RouteJob jobAtSnapshot = transactionManager.currentSnapshot().job();
            jobRead.countDown();
            assertThat(alternativeCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(jobAtSnapshot);
        });
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenAnswer(ignored -> transactionManager.currentSnapshot().options());

        TransactionInterceptor transactionInterceptor = new TransactionInterceptor(
            transactionManager,
            new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(routeService);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactionInterceptor);
        RouteService transactionalService = (RouteService) proxyFactory.getProxy();

        ExecutorService alternativeExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> alternativeCommit = alternativeExecutor.submit(() -> {
                if (!jobRead.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Rich route read did not reach the job snapshot");
                }
                transactionManager.commitAlternative(afterAlternativeCommit);
                alternativeCommitted.countDown();
                return null;
            });

            RouteDetailResponse duringCommit = transactionalService.getRoute(primary.getId());
            alternativeCommit.get(5, TimeUnit.SECONDS);

            assertThat(transactionManager.observedIsolation())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(transactionManager.observedReadOnly()).isTrue();
            assertThat(duringCommit.optionRevision()).isEqualTo(1);
            assertThat(duringCommit.optionCount()).isEqualTo(1);
            assertThat(duringCommit.optionsComplete()).isFalse();
            assertThat(duringCommit.routeOptions())
                .extracting(option -> option.profile())
                .containsExactly("most_scenic");
            assertThat(duringCommit.routeOptions()).hasSize(duringCommit.optionCount());

            RouteDetailResponse afterCommit = transactionalService.getRoute(primary.getId());

            assertThat(afterCommit.optionRevision()).isEqualTo(3);
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

    @Test
    void getRouteJobStatusProjectsCommittedPrimaryLifecycleWithoutRouteLookups() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-04-02T14:30:00Z");
        Instant startedAt = Instant.parse("2026-04-02T14:30:01Z");
        Instant primaryReadyAt = Instant.parse("2026-04-02T14:30:05Z");

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setRouteId(routeId);
        job.setSubmittedAt(queuedAt);
        job.setStartedAt(startedAt);
        job.setPrimaryReadyAt(primaryReadyAt);
        job.setStateRevision(2);
        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(false);
        job.setRetryCount(1);

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("PRIMARY_READY");
        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.primaryReadyAt()).isEqualTo(primaryReadyAt);
        assertThat(response.stateRevision()).isEqualTo(2);
        assertThat(response.optionRevision()).isEqualTo(1);
        assertThat(response.optionCount()).isEqualTo(1);
        assertThat(response.optionsComplete()).isFalse();
        assertThat(response.queuedAt()).isEqualTo(queuedAt);
        assertThat(response.startedAt()).isEqualTo(startedAt);
        assertThat(response.completedAt()).isNull();
        assertThat(response.failedAt()).isNull();
        assertThat(response.estimatedRemainingSeconds()).isEqualTo(3);
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.maxRetries()).isEqualTo(2);
        assertThat(response.routeMode()).isEqualTo("drive");
        verifyNoInteractions(routeRepository, scenicScoreTileRepository);
    }

    @Test
    void getRouteJobStatusProjectsCompletedOptionSetLifecycle() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant primaryReadyAt = Instant.parse("2026-04-02T14:30:05Z");
        Instant completedAt = Instant.parse("2026-04-02T14:30:08Z");

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setRouteId(routeId);
        job.setPrimaryReadyAt(primaryReadyAt);
        job.setCompletedAt(completedAt);
        job.setStateRevision(4);
        job.setOptionRevision(3);
        job.setOptionCount(3);
        job.setOptionsComplete(true);

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + routeId);
        assertThat(response.primaryReadyAt()).isEqualTo(primaryReadyAt);
        assertThat(response.stateRevision()).isEqualTo(4);
        assertThat(response.optionRevision()).isEqualTo(3);
        assertThat(response.optionCount()).isEqualTo(3);
        assertThat(response.optionsComplete()).isTrue();
        assertThat(response.completedAt()).isEqualTo(completedAt);
        assertThat(response.estimatedRemainingSeconds()).isNull();
        verifyNoInteractions(routeRepository, scenicScoreTileRepository);
    }

    @Test
    void getRouteJobStatusFallsBackToLegacyPersistedRouteForCompletedJobWithoutRouteId() {
        UUID jobId = UUID.randomUUID();
        UUID legacyRouteId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-04-02T14:30:08Z");

        RouteJob job = new RouteJob(UUID.randomUUID(), 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setCompletedAt(completedAt);
        job.setStateRevision(3);
        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);

        Route legacyRoute = new Route();
        legacyRoute.setId(legacyRouteId);
        legacyRoute.setJobId(jobId);
        legacyRoute.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of());
        when(routeRepository.findTopByJobIdOrderByGeneratedAtAsc(jobId))
            .thenReturn(Optional.of(legacyRoute));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.routeId()).isEqualTo(legacyRouteId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + legacyRouteId);
        assertThat(response.stateRevision()).isEqualTo(3);
        assertThat(response.optionRevision()).isEqualTo(1);
        assertThat(response.optionCount()).isEqualTo(1);
        assertThat(response.optionsComplete()).isTrue();
        assertThat(response.completedAt()).isEqualTo(completedAt);
        verify(routeRepository).findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId);
        verify(routeRepository).findTopByJobIdOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void getRouteJobStatusPrefersProfiledPrimaryForPrimaryReadyJobWithoutRouteId() {
        UUID jobId = UUID.randomUUID();
        UUID balancedRouteId = UUID.randomUUID();
        UUID primaryRouteId = UUID.randomUUID();
        Instant primaryReadyAt = Instant.parse("2026-04-02T14:30:05Z");

        RouteJob job = new RouteJob(UUID.randomUUID(), 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setPrimaryReadyAt(primaryReadyAt);
        job.setStateRevision(2);
        job.setOptionRevision(1);
        job.setOptionCount(1);

        Route balanced = new Route();
        balanced.setId(balancedRouteId);
        balanced.setJobId(jobId);
        balanced.setRouteProfile("balanced");
        balanced.setGeneratedAt(Instant.parse("2026-04-02T14:30:03Z"));
        Route primary = new Route();
        primary.setId(primaryRouteId);
        primary.setJobId(jobId);
        primary.setRouteProfile("most_scenic");
        primary.setGeneratedAt(primaryReadyAt);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of(balanced, primary));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("PRIMARY_READY");
        assertThat(response.routeId()).isEqualTo(primaryRouteId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + primaryRouteId);
        assertThat(response.primaryReadyAt()).isEqualTo(primaryReadyAt);
        assertThat(response.stateRevision()).isEqualTo(2);
        assertThat(response.optionRevision()).isEqualTo(1);
        assertThat(response.optionCount()).isEqualTo(1);
        assertThat(response.optionsComplete()).isFalse();
        verify(routeRepository).findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId);
        verify(routeRepository, never()).findTopByJobIdOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void getRouteJobStatusUsesChronologicalFallbackWhenProfilesHaveNoPrimary() {
        UUID jobId = UUID.randomUUID();
        UUID legacyRouteId = UUID.randomUUID();
        Instant primaryReadyAt = Instant.parse("2026-04-02T14:30:05Z");

        RouteJob job = new RouteJob(UUID.randomUUID(), 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setPrimaryReadyAt(primaryReadyAt);
        job.setStateRevision(2);
        job.setOptionRevision(1);
        job.setOptionCount(1);

        Route balanced = new Route();
        balanced.setId(UUID.randomUUID());
        balanced.setJobId(jobId);
        balanced.setRouteProfile("balanced");
        balanced.setGeneratedAt(primaryReadyAt);
        Route legacyRoute = new Route();
        legacyRoute.setId(legacyRouteId);
        legacyRoute.setJobId(jobId);
        legacyRoute.setGeneratedAt(primaryReadyAt.minusSeconds(1));

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of(balanced));
        when(routeRepository.findTopByJobIdOrderByGeneratedAtAsc(jobId))
            .thenReturn(Optional.of(legacyRoute));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("PRIMARY_READY");
        assertThat(response.routeId()).isEqualTo(legacyRouteId);
        assertThat(response.routeUrl()).isEqualTo("/routes/route/" + legacyRouteId);
        assertThat(response.stateRevision()).isEqualTo(2);
        assertThat(response.optionRevision()).isEqualTo(1);
        assertThat(response.optionCount()).isEqualTo(1);
        assertThat(response.optionsComplete()).isFalse();
        assertThat(response.estimatedRemainingSeconds()).isEqualTo(3);
        verify(routeRepository).findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId);
        verify(routeRepository).findTopByJobIdOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void getRouteJobStatusAddsGuidanceForUnavailableVibeFailures() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 51.0447, -114.0719, 60, "countryside");
        job.setId(jobId);
        job.markFailed("No strong Country route found near this start within your 60-minute budget. Try a larger time budget, a less urban start point, or Country/Open Road.");

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.reason()).isEqualTo(job.getFailureReason());
        assertThat(response.failedAt()).isEqualTo(job.getFailedAt());
        assertThat(response.completedAt()).isEqualTo(job.getCompletedAt());
        assertThat(response.estimatedRemainingSeconds()).isNull();
        assertThat(response.failureCode()).isEqualTo("vibe_unavailable");
        assertThat(response.userMessage()).contains("No strong Country route found");
        assertThat(response.suggestedVibes()).contains("open_roads", "relaxing");
        assertThat(response.suggestedActions()).contains("Try Country", "Try Open Road", "Increase time budget to 90 minutes");
        verifyNoInteractions(routeRepository, scenicScoreTileRepository);
    }

    @Test
    void routeOptionExplanationRanksWeightedLiftInsteadOfRawAverage() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 51.1784, -115.5708, 90, "mountain");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-115.5708, 51.1784),
            new Coordinate(-115.6000, 51.1900)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.82);
        route.setTotalDistanceKm(52.0);
        route.setEstimatedDurationMinutes(89);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(51.1784, -115.5708, H3Utils.DEFAULT_RESOLUTION), 0.98, 0.72, 0.91, 0.78, 0.36, 0.12),
            scenicTile(H3Utils.getH3Index(51.1900, -115.6000, H3Utils.DEFAULT_RESOLUTION), 0.99, 0.70, 0.89, 0.79, 0.34, 0.10)
        );
        List<ScenicScoreTile> baselineTiles = List.of(
            scenicTile("baseline-a", 0.97, 0.38, 0.32, 0.74, 0.30, 0.08),
            scenicTile("baseline-b", 0.96, 0.40, 0.30, 0.72, 0.31, 0.09)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(route.getId())).thenReturn(Optional.of(route));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(baselineTiles);

        RouteDetailResponse response = routeService.getRoute(route.getId());

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.leadingComponents().getFirst()).isEqualTo("elevation");
        assertThat(explanation.summary()).contains("Best scenic match nearby");
        assertThat(explanation.humanReasons()).isNotEmpty();
        assertThat(explanation.contractFlags())
            .containsEntry("time_budget_fit", true)
            .containsEntry("elevation_curve_share_ok", true)
            .containsEntry("scenic_peak_ok", true);
        assertThat(explanation.contractWarnings()).doesNotContain("Route lacks a strong scenic stretch.");
        assertThat(explanation.weightedContributions().get("elevation")).isGreaterThan(explanation.weightedContributions().get("water"));
        assertThat(explanation.componentLifts().get("water")).isLessThan(explanation.componentLifts().get("elevation"));
        assertThat(explanation.baselineTileCount()).isEqualTo(2);
    }

    @Test
    void routeOptionExplanationSplitsCorridorAndEdgeUrbanPressure() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 49.2827, -123.1207, 60, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-123.1207, 49.2827),
            new Coordinate(-123.1500, 49.3000)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.78);
        route.setTotalDistanceKm(36.0);
        route.setEstimatedDurationMinutes(59);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setScoreBreakdownJson("""
            {
              "final_score":0.78,
              "scenic_moments_score":0.72,
              "water_corridor_share":0.64,
              "urban_penalty":0.20,
              "start_end_penalty":0.74,
              "corridor_urban_pressure":0.20,
              "edge_urban_pressure":0.74
            }
            """);

        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(49.2827, -123.1207, H3Utils.DEFAULT_RESOLUTION), 0.78, 0.42, 0.48, 0.55, 0.36, 0.20),
            scenicTile(H3Utils.getH3Index(49.3000, -123.1500, H3Utils.DEFAULT_RESOLUTION), 0.76, 0.44, 0.46, 0.57, 0.34, 0.18)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(route.getId())).thenReturn(Optional.of(route));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(routeTiles);

        RouteDetailResponse response = routeService.getRoute(route.getId());

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.contractFlags())
            .containsEntry("urban_pressure_ok", true)
            .containsEntry("corridor_urban_pressure_ok", true)
            .containsEntry("edge_urban_pressure_ok", false);
        assertThat(explanation.contractWarnings())
            .contains("Route starts or ends in a more urban area.")
            .doesNotContain("Route corridor has more urban pressure than expected.");
    }

    @Test
    void routeOptionExplanationReportsTreeCanopyContractForNatureRoutes() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 49.2827, -123.1207, 60, "nature");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-123.1207, 49.2827),
            new Coordinate(-123.1500, 49.3000)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.74);
        route.setTotalDistanceKm(34.0);
        route.setEstimatedDurationMinutes(57);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setScoreBreakdownJson("""
            {
              "final_score":0.74,
              "scenic_moments_score":0.62,
              "tree_canopy_score":0.34,
              "quiet_corridor_share":0.42,
              "corridor_urban_pressure":0.22,
              "edge_urban_pressure":0.35
            }
            """);

        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(49.2827, -123.1207, H3Utils.DEFAULT_RESOLUTION), 0.38, 0.56, 0.44, 0.62, 0.30, 0.12),
            scenicTile(H3Utils.getH3Index(49.3000, -123.1500, H3Utils.DEFAULT_RESOLUTION), 0.36, 0.58, 0.46, 0.64, 0.32, 0.14)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(route.getId())).thenReturn(Optional.of(route));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(routeTiles);

        RouteDetailResponse response = routeService.getRoute(route.getId());

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.contractFlags()).containsEntry("tree_canopy_ok", true);
        assertThat(explanation.contractWarnings()).doesNotContain("Forest/nature vibe has weak tree-canopy signal.");
        assertThat(explanation.humanReasons())
            .anyMatch(reason -> reason.contains("tree-covered corridors"));
    }

    @Test
    void routeOptionExplanationTreatsPhotoAliasAsAdventureContract() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 46.8139, -71.2080, 60, "photo");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-71.2080, 46.8139),
            new Coordinate(-71.2300, 46.8300)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.70);
        route.setTotalDistanceKm(28.0);
        route.setEstimatedDurationMinutes(55);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        route.setScoreBreakdownJson("""
            {
              "final_score":0.70,
              "photo_peak_score":0.18,
              "scenic_moments_score":0.46,
              "scenic_poi_score":0.44,
              "corridor_urban_pressure":0.26,
              "curve_elevation_corridor_share":0.35,
              "edge_urban_pressure":0.32
            }
            """);

        ScenicScoreTile firstTile = scenicTile(H3Utils.getH3Index(46.8139, -71.2080, H3Utils.DEFAULT_RESOLUTION), 0.38, 0.48, 0.58, 0.44, 0.42, 0.18);
        firstTile.setScenicPoiScore(0.44);
        ScenicScoreTile secondTile = scenicTile(H3Utils.getH3Index(46.8300, -71.2300, H3Utils.DEFAULT_RESOLUTION), 0.40, 0.46, 0.56, 0.42, 0.40, 0.16);
        secondTile.setScenicPoiScore(0.42);
        List<ScenicScoreTile> routeTiles = List.of(firstTile, secondTile);

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(route.getId())).thenReturn(Optional.of(route));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(routeTiles);

        RouteDetailResponse response = routeService.getRoute(route.getId());

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.contractFlags()).containsEntry("elevation_curve_share_ok", true);
        assertThat(explanation.contractWarnings()).doesNotContain("Mountain or winding vibe has weak elevation/curve share.");
        assertThat(explanation.humanReasons())
            .anyMatch(reason -> reason.contains("rolling") || reason.contains("curvy"));
    }

    @Test
    void routeOptionExplanationKeepsPoiAsSupportingSignalWhenNotRequested() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 43.6532, -79.3832, 60, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        LineString lineString = geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(-79.3832, 43.6532),
            new Coordinate(-79.3300, 43.6600)
        });

        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile("most_scenic");
        route.setGeometry(lineString);
        route.setScenicScore(0.78);
        route.setTotalDistanceKm(24.0);
        route.setEstimatedDurationMinutes(58);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));

        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(43.6532, -79.3832, H3Utils.DEFAULT_RESOLUTION), 0.22, 0.42, 0.20, 0.38, 0.28, 1.00),
            scenicTile(H3Utils.getH3Index(43.6600, -79.3300, H3Utils.DEFAULT_RESOLUTION), 0.20, 0.44, 0.22, 0.36, 0.30, 0.98)
        );
        List<ScenicScoreTile> baselineTiles = List.of(
            scenicTile("baseline-c", 0.02, 0.40, 0.20, 0.35, 0.25, 0.96),
            scenicTile("baseline-d", 0.02, 0.42, 0.21, 0.37, 0.25, 0.95)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(route.getId())).thenReturn(Optional.of(route));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(List.of(route));
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(baselineTiles);

        RouteDetailResponse response = routeService.getRoute(route.getId());

        var explanation = response.routeOptions().getFirst().explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.leadingComponents().getFirst()).isEqualTo("water");
        assertThat(explanation.leadingComponents().getFirst()).isNotEqualTo("poi");
        assertThat(explanation.weightedContributions().get("poi")).isGreaterThan(explanation.weightedContributions().get("water"));
    }

    @Test
    void routeOptionExplanationsDiversifyLeadingComponentsWhenAllOptionsMatch() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 43.6532, -79.3832, 60, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.COMPLETED);

        job.setOptionRevision(3);
        job.setOptionCount(3);
        job.setOptionsComplete(true);
        GeometryFactory geometryFactory = new GeometryFactory();
        List<Route> routes = List.of(
            route(jobId, "most_scenic", geometryFactory, -79.3832, 43.6532, -79.3300, 43.6600, 0.78, 58, 24.0),
            route(jobId, "balanced", geometryFactory, -79.3832, 43.6532, -79.3400, 43.6650, 0.76, 55, 22.0),
            route(jobId, "shorter", geometryFactory, -79.3832, 43.6532, -79.3500, 43.6500, 0.74, 48, 18.0)
        );
        List<ScenicScoreTile> routeTiles = List.of(
            scenicTile(H3Utils.getH3Index(43.6532, -79.3832, H3Utils.DEFAULT_RESOLUTION), 1.00, 0.42, 0.48, 0.55, 0.36, 0.20),
            scenicTile(H3Utils.getH3Index(43.6600, -79.3300, H3Utils.DEFAULT_RESOLUTION), 0.98, 0.44, 0.46, 0.57, 0.34, 0.18)
        );
        List<ScenicScoreTile> baselineTiles = List.of(
            scenicTile("baseline-e", 0.97, 0.40, 0.44, 0.50, 0.30, 0.18),
            scenicTile("baseline-f", 0.96, 0.42, 0.43, 0.51, 0.31, 0.19)
        );

        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findById(routes.getFirst().getId())).thenReturn(Optional.of(routes.getFirst()));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenReturn(routes);
        lenient().when(scenicScoreTileRepository.findByH3IndexIn(anyCollection())).thenReturn(routeTiles);
        lenient().when(scenicScoreTileRepository.findScenicTilesNearPoint(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(baselineTiles);

        RouteDetailResponse response = routeService.getRoute(routes.getFirst().getId());

        assertThat(response.routeOptions()).hasSize(3);
        assertThat(response.routeOptions().stream()
            .map(option -> option.explanation().leadingComponents().getFirst())
            .toList())
            .doesNotHaveDuplicates();
        assertThat(response.routeOptions().stream()
            .map(option -> option.explanation().summary())
            .toList())
            .containsExactly(
                "Best scenic match nearby, with the strongest waterfront signal plus quiet support.",
                "Balanced trades peak scenic intensity for a cleaner loop shape and less urban pressure, while keeping quiet character in the route.",
                "Shorter keeps the best available winding road feel in a 48-minute drive, with less time commitment than the other options."
            );
    }

    @SuppressWarnings("unchecked")
    @Test
    void incompleteRichReadDoesNotFreezeFinalOptionSetInCache() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GeometryFactory geometryFactory = new GeometryFactory();

        RouteJob job = new RouteJob(userId, 43.6532, -79.3832, 60, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setOptionRevision(1);
        job.setOptionCount(1);
        job.setOptionsComplete(false);

        Route primary = route(
            jobId, "most_scenic", geometryFactory, -79.3832, 43.6532, -79.3300, 43.6600, 0.78, 58, 24.0
        );
        List<Route> visibleRoutes = new ArrayList<>(List.of(primary));
        when(routeRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId)).thenAnswer(ignored -> List.copyOf(visibleRoutes));

        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheNames.ROUTE_DETAILS_V2);
        CacheInterceptor cacheInterceptor = new CacheInterceptor();
        cacheInterceptor.setCacheManager(cacheManager);
        cacheInterceptor.setCacheOperationSource(new AnnotationCacheOperationSource());
        cacheInterceptor.afterPropertiesSet();
        cacheInterceptor.afterSingletonsInstantiated();
        ProxyFactory proxyFactory = new ProxyFactory(routeService);
        proxyFactory.addAdvice(cacheInterceptor);
        RouteService cachedRouteService = (RouteService) proxyFactory.getProxy();
        Cache richDetailCache = cacheManager.getCache(CacheNames.ROUTE_DETAILS_V2);
        String cacheKey = CacheKeySchema.routeDetailV2(primary.getId());

        RouteDetailResponse primaryStage = cachedRouteService.getRoute(primary.getId());

        assertThat(primaryStage.routeOptions()).hasSize(1);
        assertThat(primaryStage.optionRevision()).isEqualTo(1);
        assertThat(primaryStage.optionCount()).isEqualTo(1);
        assertThat(primaryStage.optionsComplete()).isFalse();
        assertThat(richDetailCache).isNotNull();
        assertThat(richDetailCache.get(cacheKey)).isNull();

        visibleRoutes.add(route(
            jobId, "balanced", geometryFactory, -79.3832, 43.6532, -79.3400, 43.6650, 0.76, 55, 22.0
        ));
        visibleRoutes.add(route(
            jobId, "shorter", geometryFactory, -79.3832, 43.6532, -79.3500, 43.6500, 0.74, 48, 18.0
        ));
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setOptionRevision(3);
        job.setOptionCount(3);
        job.setOptionsComplete(true);

        RouteDetailResponse completed = cachedRouteService.getRoute(primary.getId());

        assertThat(completed.optionRevision()).isEqualTo(3);
        assertThat(completed.optionCount()).isEqualTo(3);
        assertThat(completed.optionsComplete()).isTrue();
        assertThat(completed.routeOptions())
            .extracting(option -> option.profile())
            .containsExactly("most_scenic", "balanced", "shorter");
        Map<Object, Object> nativeCache = (Map<Object, Object>) richDetailCache.getNativeCache();
        assertThat(nativeCache).containsEntry(cacheKey, completed);

        assertThat(cachedRouteService.getRoute(primary.getId())).isSameAs(completed);
        verify(routeRepository, times(2)).findById(primary.getId());
        verify(routeRepository, times(2)).findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId);
    }

    @Test
    void getRouteJobStatusDoesNotInferPrimaryFromUncommittedRouteRows() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RouteJob job = new RouteJob(userId, 45.5152, -122.6784, 90, "coastal");
        job.setId(jobId);
        job.setStatus(RouteJob.JobStatus.PROCESSING);

        Route uncommittedRoute = new Route();
        uncommittedRoute.setId(UUID.randomUUID());
        uncommittedRoute.setJobId(jobId);
        uncommittedRoute.setRouteProfile("most_scenic");
        lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        lenient().when(routeRepository.findByJobIdAndRouteProfileIsNotNullOrderByGeneratedAtAsc(jobId))
            .thenReturn(List.of(uncommittedRoute));

        RouteJobStatusResponse response = routeService.getRouteJobStatus(jobId);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.routeId()).isNull();
        assertThat(response.routeUrl()).isNull();
        assertThat(response.primaryReadyAt()).isNull();
        assertThat(response.stateRevision()).isZero();
        assertThat(response.optionRevision()).isZero();
        assertThat(response.optionCount()).isZero();
        assertThat(response.optionsComplete()).isFalse();
        assertThat(response.estimatedRemainingSeconds()).isEqualTo(3);
        verifyNoInteractions(routeRepository, scenicScoreTileRepository);
    }

    private RouteService newRouteService() {
        return new RouteService(
            jobRepository,
            routeRepository,
            calibrationRepository,
            scenicScoreTileRepository,
            new RouteFailureGuidanceService(new ObjectMapper()),
            kafkaTemplate,
            dispatchService,
            new ObjectMapper()
        );
    }

    private RouteRequest routeRequest() {
        return new RouteRequest(
            UUID.randomUUID(),
            45.5152,
            -122.6784,
            90,
            List.of("coastal"),
            null,
            null
        );
    }

    private RouteService transactionalService(PlatformTransactionManager transactionManager) {
        TransactionInterceptor transactionInterceptor = new TransactionInterceptor(
            transactionManager,
            new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(routeService);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactionInterceptor);
        return (RouteService) proxyFactory.getProxy();
    }

    private static final class RecordingCommitTransactionManager
        extends AbstractPlatformTransactionManager {
        private final AtomicBoolean commitCompleted = new AtomicBoolean();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCompleted.set(true);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        private boolean commitCompleted() {
            return commitCompleted.get();
        }
    }

    private record RichRouteSnapshot(
        Route route,
        RouteJob job,
        List<Route> options
    ) {}

    private static final class RichSnapshotTransactionManager implements PlatformTransactionManager {
        private final AtomicReference<RichRouteSnapshot> committedSnapshot;
        private final ThreadLocal<RichRouteSnapshot> transactionSnapshot = new ThreadLocal<>();
        private final ThreadLocal<Boolean> repeatableRead = new ThreadLocal<>();
        private final AtomicInteger observedIsolation =
            new AtomicInteger(TransactionDefinition.ISOLATION_DEFAULT);
        private final AtomicBoolean observedReadOnly = new AtomicBoolean();

        private RichSnapshotTransactionManager(RichRouteSnapshot initialSnapshot) {
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

        private RichRouteSnapshot currentSnapshot() {
            if (Boolean.TRUE.equals(repeatableRead.get())) {
                return transactionSnapshot.get();
            }
            return committedSnapshot.get();
        }

        private void commitAlternative(RichRouteSnapshot alternativeSnapshot) {
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
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Route route(UUID jobId,
                               String profile,
                               GeometryFactory geometryFactory,
                               double startLng,
                               double startLat,
                               double endLng,
                               double endLat,
                               double scenicScore,
                               int durationMinutes,
                               double distanceKm) {
        Route route = new Route();
        route.setId(UUID.randomUUID());
        route.setJobId(jobId);
        route.setRouteProfile(profile);
        route.setGeometry(geometryFactory.createLineString(new Coordinate[] {
            new Coordinate(startLng, startLat),
            new Coordinate(endLng, endLat)
        }));
        route.setScenicScore(scenicScore);
        route.setTotalDistanceKm(distanceKm);
        route.setEstimatedDurationMinutes(durationMinutes);
        route.setGeneratedAt(Instant.parse("2026-04-02T14:30:05Z"));
        return route;
    }

    private static ScenicScoreTile scenicTile(
        String h3Index,
        double water,
        double greenery,
        double elevation,
        double solitude,
        double curves,
        double poi
    ) {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(h3Index);
        tile.setWaterScore(water);
        tile.setWaterProximity(water);
        tile.setGreenScore(greenery);
        tile.setNaturalLandUse(greenery);
        tile.setElevationScore(elevation);
        tile.setElevationVariance(elevation);
        tile.setSolitudeScore(solitude);
        tile.setTrafficSignalScore(solitude);
        tile.setCurveScore(curves);
        tile.setVisualComplexity(curves);
        tile.setPoiScore(poi);
        tile.setPoiDensity(poi);
        return tile;
    }
}
