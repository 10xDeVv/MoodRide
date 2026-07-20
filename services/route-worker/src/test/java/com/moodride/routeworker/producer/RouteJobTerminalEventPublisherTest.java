package com.moodride.routeworker.producer;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteJobTerminalEvent;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import com.moodride.routeworker.service.RouteGenerationService;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteJobTerminalEventPublisherTest {

    @Mock
    private RouteJobTerminalEventRepository eventRepository;
    @Mock
    private RouteJobRepository jobRepository;
    @Mock
    private RouteGenerationService routeGenerationService;
    @Mock
    private RouteCompletionProducer completionProducer;
    @Mock
    private RouteJobDlqProducer dlqProducer;

    private RouteJobTerminalEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = newPublisher();
    }

    @Test
    void completionPublicationFailureRemainsPendingAndLaterRecoveryMarksBrokerAckDelivered() {
        RouteJob job = completedJob();
        RouteJobLifecycleService.LifecycleSnapshot state = RouteJobLifecycleService.snapshot(job);
        RouteJobTerminalEvent event = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null
        );
        RouteGenerationService.RouteGenerationResult primary = primary(job, state);
        when(eventRepository.lockPublishableForTerminal(
            eq(job.getId()), eq(job.getStateRevision()), any(Instant.class)
        )).thenReturn(List.of(event));
        when(eventRepository.lockOldestDue(any(Instant.class)))
            .thenReturn(Optional.of(event), Optional.empty());
        when(eventRepository.lockByEventId(event.getEventId())).thenReturn(Optional.of(event));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(routeGenerationService.loadPrimary(job.getId())).thenReturn(primary);
        RouteCompletionProducer.PublicationException brokerFailure =
            new RouteCompletionProducer.PublicationException(
                "broker unavailable",
                new IllegalStateException("broker unavailable")
            );
        doThrow(brokerFailure).doNothing().when(completionProducer).publishCompletion(
            eq(job.getId()),
            eq(job.getUserId()),
            anyDouble(),
            eq(job.getRouteId()),
            anyInt(),
            anyDouble(),
            anyList(),
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(event.getEventId())
        );

        publisher.publishPending(job.getId(), job.getStateRevision());

        assertEquals(1, event.getAttemptCount());
        assertNull(event.getDeliveredAt());
        assertNull(event.getLeaseToken());
        assertNotNull(event.getLastError());
        Instant scheduledRetry = event.getNextAttemptAt();

        publisher.redispatchPending();

        assertEquals(2, event.getAttemptCount());
        assertNotNull(event.getDeliveredAt());
        assertNull(event.getLeaseToken());
        assertNull(event.getLeaseExpiresAt());
        assertNull(event.getLastError());
        assertEquals(scheduledRetry, event.getNextAttemptAt());
        verify(completionProducer, times(2)).publishCompletion(
            eq(job.getId()),
            eq(job.getUserId()),
            anyDouble(),
            eq(job.getRouteId()),
            anyInt(),
            anyDouble(),
            anyList(),
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(event.getEventId())
        );
    }

    @Test
    void failureNotificationAndDlqFailuresEachRetryWithStableBoundedIdentity() {
        RouteJob job = failedJob();
        Instant createdAt = job.getCompletedAt();
        RouteJobTerminalEvent completion = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null
        );
        RouteJobTerminalEvent dlq = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.DLQ,
            job.getId().toString()
        );
        Map<String, RouteJobTerminalEvent> events = Map.of(
            completion.getEventId(), completion,
            dlq.getEventId(), dlq
        );
        when(eventRepository.lockPublishableForTerminal(
            eq(job.getId()), eq(job.getStateRevision()), any(Instant.class)
        )).thenReturn(List.of(completion, dlq));
        when(eventRepository.lockOldestDue(any(Instant.class)))
            .thenReturn(Optional.of(completion), Optional.of(dlq), Optional.empty());
        when(eventRepository.lockByEventId(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(events.get(invocation.getArgument(0)))
        );
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        doThrow(new RouteCompletionProducer.PublicationException(
            "notification unavailable",
            new IllegalStateException("notification unavailable")
        )).doNothing().when(completionProducer).publishFailure(
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(completion.getEventId())
        );
        doThrow(new RouteJobDlqProducer.PublicationException(
            "DLQ unavailable",
            new IllegalStateException("DLQ unavailable")
        )).doNothing().when(dlqProducer).publishToDlq(
            eq(dlq.getEventId()),
            eq(job.getId()),
            eq(job.getFailureReason()),
            eq(job.getId().toString()),
            eq(createdAt)
        );

        publisher.publishPending(job.getId(), job.getStateRevision());

        assertNull(completion.getDeliveredAt());
        assertNull(dlq.getDeliveredAt());
        assertNotNull(completion.getLastError());
        assertNotNull(dlq.getLastError());

        publisher.redispatchPending();

        assertNotNull(completion.getDeliveredAt());
        assertNotNull(dlq.getDeliveredAt());
        assertEquals(job.getId() + ":" + job.getStateRevision() + ":COMPLETION", completion.getEventId());
        assertEquals(job.getId() + ":" + job.getStateRevision() + ":DLQ", dlq.getEventId());
        verify(completionProducer, times(2)).publishFailure(
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(completion.getEventId())
        );
        verify(dlqProducer, times(2)).publishToDlq(
            eq(dlq.getEventId()),
            eq(job.getId()),
            eq(job.getFailureReason()),
            eq(job.getId().toString()),
            eq(createdAt)
        );
    }

    @Test
    void brokerAcknowledgmentPrecedesTokenFencedDeliveredMark() {
        RouteJob job = failedJob();
        RouteJobTerminalEvent completion = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null
        );
        when(eventRepository.lockPublishableForTerminal(
            eq(job.getId()), eq(job.getStateRevision()), any(Instant.class)
        )).thenReturn(List.of(completion));
        when(eventRepository.lockByEventId(completion.getEventId()))
            .thenReturn(Optional.of(completion));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        publisher.publishPending(job.getId(), job.getStateRevision());

        InOrder acknowledgmentBeforeMark = inOrder(completionProducer, eventRepository);
        acknowledgmentBeforeMark.verify(completionProducer).publishFailure(
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(completion.getEventId())
        );
        acknowledgmentBeforeMark.verify(eventRepository).lockByEventId(completion.getEventId());
        assertNotNull(completion.getDeliveredAt());
    }

    @Test
    void staleDeliveryTokenCannotMarkAcknowledgedEventDelivered() {
        RouteJob job = failedJob();
        RouteJobTerminalEvent event = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null
        );
        UUID currentToken = UUID.randomUUID();
        event.claim(currentToken, Instant.now().plusSeconds(30));

        assertFalse(event.markDelivered(UUID.randomUUID(), Instant.now()));
        assertNull(event.getDeliveredAt());
        assertEquals(currentToken, event.getLeaseToken());
        assertTrue(event.markDelivered(currentToken, Instant.now()));
        assertNotNull(event.getDeliveredAt());
    }

    @Test
    void secondReplicaCannotPublishRowsHiddenBySkipLockedClaim() {
        RouteJob job = failedJob();
        RouteJobTerminalEvent event = terminalEvent(
            job,
            RouteJobTerminalEvent.EventType.COMPLETION,
            null
        );
        when(eventRepository.lockPublishableForTerminal(
            eq(job.getId()), eq(job.getStateRevision()), any(Instant.class)
        )).thenReturn(List.of(event), List.of());
        when(eventRepository.lockByEventId(event.getEventId())).thenReturn(Optional.of(event));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        RouteJobTerminalEventPublisher secondReplica = newPublisher();

        publisher.publishPending(job.getId(), job.getStateRevision());
        secondReplica.publishPending(job.getId(), job.getStateRevision());

        verify(completionProducer).publishFailure(
            any(RouteJobLifecycleService.LifecycleSnapshot.class),
            eq(event.getEventId())
        );
        verify(dlqProducer, never()).publishToDlq(
            anyString(), any(UUID.class), anyString(), anyString(), any(Instant.class)
        );
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getDeliveredAt());
    }

    private RouteJobTerminalEventPublisher newPublisher() {
        return new RouteJobTerminalEventPublisher(
            eventRepository,
            jobRepository,
            routeGenerationService,
            completionProducer,
            dlqProducer,
            new ImmediateTransactionManager(),
            true,
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            10,
            Duration.ofSeconds(1),
            Duration.ofMinutes(1)
        );
    }

    private RouteJob completedJob() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setRouteId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setStateRevision(9L);
        job.setOptionRevision(3L);
        job.setOptionCount(3);
        job.setOptionsComplete(true);
        job.setCompletedAt(Instant.parse("2026-07-19T12:00:00Z"));
        return job;
    }

    private RouteJob failedJob() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.FAILED);
        job.setFailureReason("terminal failure");
        job.setStateRevision(6L);
        job.setCompletedAt(Instant.parse("2026-07-19T12:00:00Z"));
        return job;
    }

    private RouteJobTerminalEvent terminalEvent(
        RouteJob job,
        RouteJobTerminalEvent.EventType eventType,
        String originalPayload
    ) {
        return new RouteJobTerminalEvent(
            job.getId(),
            job.getStateRevision(),
            eventType,
            job.getStatus(),
            originalPayload,
            job.getCompletedAt()
        );
    }

    private RouteGenerationService.RouteGenerationResult primary(
        RouteJob job,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
        Route route = new Route();
        route.setId(job.getRouteId());
        route.setJobId(job.getId());
        route.setUserId(job.getUserId());
        route.setTotalDistanceKm(18.2);
        route.setEstimatedDurationMinutes(31);
        route.setScenicScore(0.88);
        return new RouteGenerationService.RouteGenerationResult(route, List.of(), state);
    }

    private static final class ImmediateTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
