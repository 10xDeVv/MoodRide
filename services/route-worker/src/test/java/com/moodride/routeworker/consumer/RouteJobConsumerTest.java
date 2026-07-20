package com.moodride.routeworker.consumer;

import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.Route;
import com.moodride.routeworker.algorithm.NoFeasibleRouteException;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.producer.RouteJobTerminalEventPublisher;
import com.moodride.routeworker.service.LeaseLostException;
import com.moodride.routeworker.service.RouteGenerationService;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import com.moodride.routeworker.service.RouteJobHeartbeatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteJobConsumerTest {
    @Mock
    private RouteJobLifecycleService lifecycleService;
    @Mock
    private RouteJobHeartbeatService heartbeatService;
    @Mock
    private RouteJobHeartbeatService.Heartbeat heartbeat;
    @Mock
    private RouteGenerationService routeGenerationService;
    @Mock
    private RouteCompletionProducer completionProducer;
    @Mock
    private RouteJobTerminalEventPublisher terminalEventPublisher;
    @Mock
    private RouteJobDlqProducer dlqProducer;
    @Mock
    private Acknowledgment acknowledgment;

    @Test
    void primaryReadyDuplicateWithActiveLeaseAcknowledgesWithoutStartingAnotherWorker() {
        UUID activeLease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(activeLease);
        RouteJobLifecycleService.LifecycleSnapshot primaryState = snapshot(job, 4L, 1L, 1, false);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.SKIP,
                job,
                null,
                primaryState
            )
        );
        RouteJobConsumer consumer = new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        verify(acknowledgment).acknowledge();
        verifyNoInteractions(
            heartbeatService,
            routeGenerationService,
            completionProducer
        );
    }

    @Test
    void primaryReadyDuplicateWithExpiredLeaseAcknowledgesWithoutReclaimingOrDispatching() {
        UUID expiredLease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(expiredLease);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        RouteJobLifecycleService.LifecycleSnapshot primaryState = snapshot(job, 4L, 1L, 1, false);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.SKIP,
                job,
                null,
                primaryState
            )
        );
        RouteJobConsumer consumer = new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);
        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        verify(acknowledgment, times(2)).acknowledge();
        verifyNoInteractions(
            heartbeatService,
            routeGenerationService,
            completionProducer
        );
    }


    @Test
    @SuppressWarnings("unchecked")
    void primaryReadyNoLeaseRecoveryClaimResumesAndPublishesCommittedRevisions() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = primaryReadyJob(null);
        RouteJobLifecycleService.LifecycleSnapshot primaryState = snapshot(job, 4L, 1L, 1, false);
        RouteJobLifecycleService.LifecycleSnapshot completedState = snapshot(job, 5L, 3L, 3, true);
        Route primaryRoute = primaryRoute(job);
        RouteGenerationService.RouteGenerationResult primary =
            new RouteGenerationService.RouteGenerationResult(primaryRoute, List.of(), primaryState);
        RouteJobLifecycleService.ClaimResult claim = new RouteJobLifecycleService.ClaimResult(
            RouteJobLifecycleService.ClaimAction.CLAIMED,
            job,
            leaseToken,
            primaryState
        );
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(claim);
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenAnswer(invocation -> {
                Consumer<RouteGenerationService.RouteGenerationResult> callback = invocation.getArgument(2);
                callback.accept(primary);
                return primary;
            });
        when(lifecycleService.complete(job.getId(), leaseToken)).thenReturn(completedState);

        RouteJobConsumer consumer = new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);
        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder processingOrder = inOrder(
            heartbeatService,
            routeGenerationService,
            lifecycleService,
            completionProducer,
            terminalEventPublisher,
            acknowledgment,
            heartbeat
        );
        processingOrder.verify(heartbeatService).start(job.getId(), leaseToken);
        processingOrder.verify(routeGenerationService).processRoute(eq(job), eq(leaseToken), any(Consumer.class));
        processingOrder.verify(completionProducer).publishPrimaryReady(
            primaryState.jobId(),
            primaryState.userId(),
            primaryRoute.getTotalDistanceKm(),
            primaryRoute.getId(),
            primaryRoute.getEstimatedDurationMinutes(),
            primaryRoute.getScenicScore(),
            primary.waypoints(),
            primaryState
        );
        processingOrder.verify(heartbeat).requireActive();
        processingOrder.verify(lifecycleService).complete(job.getId(), leaseToken);
        processingOrder.verify(terminalEventPublisher).publishPending(
            completedState.jobId(),
            completedState.stateRevision()
        );
        processingOrder.verify(acknowledgment).acknowledge();
        processingOrder.verify(heartbeat).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void prePrimaryTransientFailurePersistsRetryIntentBeforeAcknowledgingOriginal() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        RouteJobLifecycleService.LifecycleSnapshot processing = new RouteJobLifecycleService.LifecycleSnapshot(
            job.getId(), job.getUserId(), null, RouteJob.JobStatus.PROCESSING,
            1L, 0L, 0, false, null, 0, null
        );
        RouteJobLifecycleService.LifecycleSnapshot queued = new RouteJobLifecycleService.LifecycleSnapshot(
            job.getId(), job.getUserId(), null, RouteJob.JobStatus.QUEUED,
            2L, 0L, 0, false, null, 1, null
        );
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.CLAIMED,
                job,
                leaseToken,
                processing
            )
        );
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenThrow(new IllegalStateException("transient"));
        when(lifecycleService.handleTransientFailure(
            job.getId(), leaseToken, 3, "Route generation failed after retries"
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.RETRY,
            queued
        ));
        RouteJobConsumer consumer = new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder durableRetryBeforeAck = inOrder(lifecycleService, acknowledgment);
        durableRetryBeforeAck.verify(lifecycleService).handleTransientFailure(
            job.getId(), leaseToken, 3, "Route generation failed after retries"
        );
        durableRetryBeforeAck.verify(acknowledgment).acknowledge();
        verify(heartbeat).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryIntentPersistenceFailureLeavesOriginalDeliveryUnacknowledged() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        RouteJobLifecycleService.LifecycleSnapshot processing =
            new RouteJobLifecycleService.LifecycleSnapshot(
                job.getId(),
                job.getUserId(),
                null,
                RouteJob.JobStatus.PROCESSING,
                1L,
                0L,
                0,
                false,
                null,
                0,
                null
            );
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.CLAIMED,
                job,
                leaseToken,
                processing
            )
        );
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenThrow(new IllegalStateException("transient"));
        when(lifecycleService.handleTransientFailure(
            job.getId(), leaseToken, 3, "Route generation failed after retries"
        )).thenThrow(new IllegalStateException("dispatch table unavailable"));
        RouteJobConsumer consumer = new RouteJobConsumer(
            lifecycleService,
            heartbeatService,
            routeGenerationService,
            completionProducer,
            terminalEventPublisher,
            dlqProducer,
            3
        );

        assertThrows(
            IllegalStateException.class,
            () -> consumer.consumeRouteJob(job.getId().toString(), acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
        verify(heartbeat).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatLeaseLossStopsFailureMutationAndAlwaysClosesAttempt() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        RouteJobLifecycleService.LifecycleSnapshot processing = new RouteJobLifecycleService.LifecycleSnapshot(
            job.getId(), job.getUserId(), null, RouteJob.JobStatus.PROCESSING,
            1L, 0L, 0, false, null, 0, null
        );
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.CLAIMED,
                job,
                leaseToken,
                processing
            )
        );
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenThrow(new IllegalStateException("planning interrupted after lease loss"));
        doThrow(new LeaseLostException(job.getId())).when(heartbeat).requireActive();
        RouteJobConsumer consumer = new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);

        assertThrows(
            LeaseLostException.class,
            () -> consumer.consumeRouteJob(job.getId().toString(), acknowledgment)
        );

        verify(lifecycleService, never()).handleTransientFailure(
            job.getId(), leaseToken, 3, "Route generation failed after retries"
        );
        verifyNoInteractions(completionProducer, dlqProducer, acknowledgment);
        verify(heartbeat).close();
    }

    @Test
    void newlyFailedClaimPublishesFailureThenDlqBeforeAcknowledgingInput() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.FAILED);
        job.setFailureReason("Exceeded maximum retry attempts: 4");
        RouteJobLifecycleService.LifecycleSnapshot failedState =
            RouteJobLifecycleService.snapshot(job);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.FAILED,
                job,
                null,
                failedState
            )
        );
        RouteJobConsumer consumer = consumer();

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder terminalDispatchBeforeAck = inOrder(terminalEventPublisher, acknowledgment);
        terminalDispatchBeforeAck.verify(terminalEventPublisher).publishPending(
            failedState.jobId(),
            failedState.stateRevision()
        );
        terminalDispatchBeforeAck.verify(acknowledgment).acknowledge();
        verifyNoInteractions(
            heartbeatService,
            routeGenerationService,
            completionProducer
        );
    }

    @Test
    void completedRedeliveryDispatchesDurableOutboxBeforeAcknowledging() {
        RouteJob job = primaryReadyJob(null);
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setOptionsComplete(true);
        RouteJobLifecycleService.LifecycleSnapshot completedState =
            snapshot(job, 9L, 3L, 3, true);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.REPLAY_TERMINAL,
                job,
                null,
                completedState
            )
        );
        RouteJobConsumer consumer = consumer();

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder replayBeforeAck = inOrder(terminalEventPublisher, acknowledgment);
        replayBeforeAck.verify(terminalEventPublisher).publishPending(
            completedState.jobId(),
            completedState.stateRevision()
        );
        replayBeforeAck.verify(acknowledgment).acknowledge();
        verify(routeGenerationService, never()).processRoute(
            any(RouteJob.class),
            any(UUID.class),
            any(Consumer.class)
        );
        verifyNoInteractions(heartbeatService, completionProducer, dlqProducer);
    }

    @Test
    void failedTerminalRedeliveryDispatchesDurableOutboxBeforeAcknowledging() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.FAILED);
        job.setFailureReason("no feasible route");
        RouteJobLifecycleService.LifecycleSnapshot failedState =
            RouteJobLifecycleService.snapshot(job);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.REPLAY_TERMINAL,
                job,
                null,
                failedState
            )
        );
        RouteJobConsumer consumer = consumer();

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder replayBeforeAck = inOrder(terminalEventPublisher, acknowledgment);
        replayBeforeAck.verify(terminalEventPublisher).publishPending(
            failedState.jobId(),
            failedState.stateRevision()
        );
        replayBeforeAck.verify(acknowledgment).acknowledge();
        verify(lifecycleService).claim(job.getId(), 3);
        verifyNoMoreInteractions(lifecycleService);
        verifyNoInteractions(
            heartbeatService,
            routeGenerationService,
            completionProducer
        );
    }



    @Test
    @SuppressWarnings("unchecked")
    void primaryReadyPublicationFailureStopsHeartbeatAndReleasesOwnershipBeforeRedelivery() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = primaryReadyJob(leaseToken);
        RouteJobLifecycleService.LifecycleSnapshot primaryState =
            snapshot(job, 4L, 1L, 1, false);
        Route primaryRoute = primaryRoute(job);
        RouteGenerationService.RouteGenerationResult primary =
            new RouteGenerationService.RouteGenerationResult(primaryRoute, List.of(), primaryState);
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.CLAIMED,
                job,
                leaseToken,
                primaryState
            )
        );
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenAnswer(invocation -> {
                Consumer<RouteGenerationService.RouteGenerationResult> callback =
                    invocation.getArgument(2);
                callback.accept(primary);
                return primary;
            });
        RouteCompletionProducer.PublicationException publicationFailure =
            new RouteCompletionProducer.PublicationException(
                "broker unavailable",
                new IllegalStateException("broker unavailable")
            );
        doThrow(publicationFailure).when(completionProducer).publishPrimaryReady(
            primaryState.jobId(),
            primaryState.userId(),
            primaryRoute.getTotalDistanceKm(),
            primaryRoute.getId(),
            primaryRoute.getEstimatedDurationMinutes(),
            primaryRoute.getScenicScore(),
            primary.waypoints(),
            primaryState
        );
        RouteJobConsumer consumer = consumer();

        assertThrows(
            RouteCompletionProducer.PublicationException.class,
            () -> consumer.consumeRouteJob(job.getId().toString(), acknowledgment)
        );

        verify(lifecycleService, never()).handleTransientFailure(
            job.getId(),
            leaseToken,
            3,
            "Route generation failed after retries"
        );
        InOrder releaseOrder = inOrder(lifecycleService, heartbeat);
        releaseOrder.verify(lifecycleService).claim(job.getId(), 3);
        releaseOrder.verify(heartbeat).close();
        releaseOrder.verify(lifecycleService).abandonPrimaryPublication(job.getId(), leaseToken);
        releaseOrder.verify(heartbeat).close();
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(terminalEventPublisher, dlqProducer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonRetryableFailurePublishesFailureThenDlqBeforeAcknowledgingInput() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        RouteJobLifecycleService.LifecycleSnapshot processing =
            new RouteJobLifecycleService.LifecycleSnapshot(
                job.getId(),
                job.getUserId(),
                null,
                RouteJob.JobStatus.PROCESSING,
                1L,
                0L,
                0,
                false,
                null,
                0,
                null
            );
        String failureReason = "no feasible route";
        RouteJobLifecycleService.LifecycleSnapshot failed =
            new RouteJobLifecycleService.LifecycleSnapshot(
                job.getId(),
                job.getUserId(),
                null,
                RouteJob.JobStatus.FAILED,
                2L,
                0L,
                0,
                false,
                failureReason,
                0,
                Instant.now()
            );
        when(lifecycleService.claim(job.getId(), 3)).thenReturn(
            new RouteJobLifecycleService.ClaimResult(
                RouteJobLifecycleService.ClaimAction.CLAIMED,
                job,
                leaseToken,
                processing
            )
        );
        when(heartbeatService.start(job.getId(), leaseToken)).thenReturn(heartbeat);
        when(routeGenerationService.processRoute(eq(job), eq(leaseToken), any(Consumer.class)))
            .thenThrow(new NoFeasibleRouteException(failureReason));
        when(lifecycleService.handleNonRetryableFailure(
            job.getId(),
            leaseToken,
            failureReason
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.FAILED,
            failed
        ));
        RouteJobConsumer consumer = consumer();

        consumer.consumeRouteJob(job.getId().toString(), acknowledgment);

        InOrder terminalDispatchBeforeAck = inOrder(terminalEventPublisher, acknowledgment);
        terminalDispatchBeforeAck.verify(terminalEventPublisher).publishPending(
            failed.jobId(),
            failed.stateRevision()
        );
        terminalDispatchBeforeAck.verify(acknowledgment).acknowledge();
        verifyNoInteractions(completionProducer);
        verify(heartbeat).close();
    }

    private RouteJobConsumer consumer() {
        return new RouteJobConsumer(lifecycleService, heartbeatService, routeGenerationService, completionProducer, terminalEventPublisher, dlqProducer, 3);
    }

    private Route primaryRoute(RouteJob job) {
        Route route = new Route();
        route.setId(job.getRouteId());
        route.setJobId(job.getId());
        route.setUserId(job.getUserId());
        route.setTotalDistanceKm(18.2);
        route.setEstimatedDurationMinutes(31);
        route.setScenicScore(0.88);
        return route;
    }

    private RouteJob primaryReadyJob(UUID leaseToken) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setRouteId(UUID.randomUUID());
        job.setPrimaryReadyAt(Instant.now().minusSeconds(10));
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        return job;
    }

    private RouteJobLifecycleService.LifecycleSnapshot snapshot(
        RouteJob job,
        long stateRevision,
        long optionRevision,
        int optionCount,
        boolean optionsComplete
    ) {
        return new RouteJobLifecycleService.LifecycleSnapshot(
            job.getId(),
            job.getUserId(),
            job.getRouteId(),
            optionsComplete ? RouteJob.JobStatus.COMPLETED : RouteJob.JobStatus.PRIMARY_READY,
            stateRevision,
            optionRevision,
            optionCount,
            optionsComplete,
            null,
            job.getRetryCount(),
            optionsComplete ? Instant.now() : null
        );
    }
}
