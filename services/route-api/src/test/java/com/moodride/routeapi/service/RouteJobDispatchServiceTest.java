package com.moodride.routeapi.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.routeapi.dispatch.RouteJobDispatch;
import com.moodride.routeapi.repository.RouteJobDispatchRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteJobDispatchServiceTest {

    @Mock
    private RouteJobDispatchRepository dispatchRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private RouteJobDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = newDispatchService(5);
    }

    @Test
    void enqueuePersistsRecoveryEligibilityAfterTheStaleWindow() {
        UUID jobId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-19T12:00:00Z");

        dispatchService.enqueue(jobId, createdAt);

        ArgumentCaptor<RouteJobDispatch> saved = ArgumentCaptor.forClass(RouteJobDispatch.class);
        verify(dispatchRepository).save(saved.capture());
        assertThat(saved.getValue().getJobId()).isEqualTo(jobId);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(createdAt.plusSeconds(60));
        assertThat(saved.getValue().getAttemptCount()).isZero();
        assertThat(saved.getValue().getSentAt()).isNull();
    }

    @Test
    void immediateDispatchClaimsBeforeSendAndMarksSentOnlyAfterAcknowledgment() throws Exception {
        UUID jobId = UUID.randomUUID();
        RouteJobDispatch dispatch = pendingDispatch(jobId, Instant.parse("2026-07-19T12:00:00Z"));
        CompletableFuture<SendResult<String, String>> acknowledgment = new CompletableFuture<>();
        when(dispatchRepository.lockPublishableByJobId(any(UUID.class), any(Instant.class)))
            .thenReturn(Optional.of(dispatch));
        when(dispatchRepository.lockByJobId(jobId)).thenReturn(Optional.of(dispatch));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acknowledgment);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> publication = executor.submit(() -> dispatchService.publishCommitted(jobId));

            verify(kafkaTemplate, timeout(1_000)).send(
                RouteJobEvent.TOPIC,
                jobId.toString(),
                jobId.toString()
            );
            assertThat(publication.isDone()).isFalse();
            assertThat(dispatch.getAttemptCount()).isEqualTo(1);
            assertThat(dispatch.getLeaseToken()).isNotNull();
            assertThat(dispatch.getSentAt()).isNull();

            acknowledgment.complete(null);
            publication.get(1, TimeUnit.SECONDS);

            assertThat(dispatch.getSentAt()).isNotNull();
            assertThat(dispatch.getLeaseToken()).isNull();
            assertThat(dispatch.getLeaseExpiresAt()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void brokerExceptionPersistsAttemptAndBackoffWithoutMarkingSent() {
        UUID jobId = UUID.randomUUID();
        Instant originalNextAttempt = Instant.parse("2026-07-19T12:01:00Z");
        RouteJobDispatch dispatch = pendingDispatch(jobId, originalNextAttempt.minusSeconds(60));
        when(dispatchRepository.lockPublishableByJobId(any(UUID.class), any(Instant.class)))
            .thenReturn(Optional.of(dispatch));
        when(dispatchRepository.lockByJobId(jobId)).thenReturn(Optional.of(dispatch));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        Instant beforeFailure = Instant.now();

        dispatchService.publishCommitted(jobId);

        assertThat(dispatch.getAttemptCount()).isEqualTo(1);
        assertThat(dispatch.getSentAt()).isNull();
        assertThat(dispatch.getLeaseToken()).isNull();
        assertThat(dispatch.getNextAttemptAt()).isAfterOrEqualTo(beforeFailure.plusSeconds(30));
        assertThat(dispatch.getLastError()).contains("broker unavailable");
    }

    @Test
    void recoveryClaimsOldestOneAtATimeAndContinuesAfterIndividualFailure() {
        UUID firstJobId = UUID.randomUUID();
        UUID secondJobId = UUID.randomUUID();
        RouteJobDispatch first = pendingDispatch(firstJobId, Instant.parse("2026-07-19T11:00:00Z"));
        RouteJobDispatch second = pendingDispatch(secondJobId, Instant.parse("2026-07-19T11:01:00Z"));
        Map<UUID, RouteJobDispatch> dispatches = Map.of(firstJobId, first, secondJobId, second);
        when(dispatchRepository.lockOldestDue(any(Instant.class)))
            .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(dispatchRepository.lockByJobId(any(UUID.class)))
            .thenAnswer(invocation -> Optional.ofNullable(dispatches.get(invocation.getArgument(0))));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("first publish failed")),
                CompletableFuture.completedFuture(null)
            );

        dispatchService.redispatchDueRouteJobs();

        InOrder publicationOrder = inOrder(kafkaTemplate);
        publicationOrder.verify(kafkaTemplate).send(
            RouteJobEvent.TOPIC,
            firstJobId.toString(),
            firstJobId.toString()
        );
        publicationOrder.verify(kafkaTemplate).send(
            RouteJobEvent.TOPIC,
            secondJobId.toString(),
            secondJobId.toString()
        );
        verify(dispatchRepository, times(3)).lockOldestDue(any(Instant.class));
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getSentAt()).isNull();
        assertThat(first.getLastError()).contains("first publish failed");
        assertThat(second.getAttemptCount()).isEqualTo(1);
        assertThat(second.getSentAt()).isNotNull();
    }

    @Test
    void terminalOrOtherwiseIneligibleStaleDispatchIsNotPublished() {
        UUID jobId = UUID.randomUUID();
        when(dispatchRepository.lockPublishableByJobId(any(UUID.class), any(Instant.class)))
            .thenReturn(Optional.empty());

        dispatchService.publishCommitted(jobId);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void interruptedAcknowledgmentLeavesRetryableDispatchAndRestoresInterruptStatus() {
        UUID jobId = UUID.randomUUID();
        RouteJobDispatch dispatch = pendingDispatch(jobId, Instant.parse("2026-07-19T12:00:00Z"));
        when(dispatchRepository.lockPublishableByJobId(any(UUID.class), any(Instant.class)))
            .thenReturn(Optional.of(dispatch));
        when(dispatchRepository.lockByJobId(jobId)).thenReturn(Optional.of(dispatch));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());

        try {
            Thread.currentThread().interrupt();

            dispatchService.publishCommitted(jobId);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(dispatch.getSentAt()).isNull();
            assertThat(dispatch.getLeaseToken()).isNull();
            assertThat(dispatch.getLastError()).contains("Interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void brokerAcknowledgmentTimeoutLeavesDispatchDueForBackoffRetry() {
        UUID jobId = UUID.randomUUID();
        RouteJobDispatch dispatch =
            pendingDispatch(jobId, Instant.parse("2026-07-19T12:00:00Z"));
        when(dispatchRepository.lockPublishableByJobId(any(UUID.class), any(Instant.class)))
            .thenReturn(Optional.of(dispatch));
        when(dispatchRepository.lockByJobId(jobId)).thenReturn(Optional.of(dispatch));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());
        RouteJobDispatchService timeoutService = new RouteJobDispatchService(
            dispatchRepository,
            kafkaTemplate,
            new ImmediateTransactionManager(),
            true,
            Duration.ofMinutes(1),
            Duration.ofSeconds(30),
            Duration.ofMillis(1),
            5,
            Duration.ofSeconds(30),
            Duration.ofMinutes(5)
        );
        Instant beforeTimeout = Instant.now();

        timeoutService.publishCommitted(jobId);

        assertThat(dispatch.getSentAt()).isNull();
        assertThat(dispatch.getLeaseToken()).isNull();
        assertThat(dispatch.getLeaseExpiresAt()).isNull();
        assertThat(dispatch.getAttemptCount()).isEqualTo(1);
        assertThat(dispatch.getNextAttemptAt()).isAfterOrEqualTo(
            beforeTimeout.plusSeconds(30)
        );
        assertThat(dispatch.getLastError()).contains("Timed out");
    }

    private RouteJobDispatchService newDispatchService(int batchSize) {
        return new RouteJobDispatchService(
            dispatchRepository,
            kafkaTemplate,
            new ImmediateTransactionManager(),
            true,
            Duration.ofMinutes(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            batchSize,
            Duration.ofSeconds(30),
            Duration.ofMinutes(5)
        );
    }

    private RouteJobDispatch pendingDispatch(UUID jobId, Instant createdAt) {
        return new RouteJobDispatch(jobId, createdAt, createdAt.plusSeconds(60));
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
