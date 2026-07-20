package com.moodride.routeworker.watchdog;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.producer.RouteJobTerminalEventPublisher;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TimeoutWatchdogTest {
    @Mock
    private RouteJobRepository jobRepository;
    @Mock
    private RouteJobTerminalEventRepository terminalEventRepository;
    @Mock
    private RouteJobLifecycleService lifecycleService;
    @Mock
    private RouteJobTerminalEventPublisher terminalEventPublisher;

    @Test
    void expiredPrePrimaryLeaseSchedulesDurableRetry() {
        RouteJob observed = activeJob(RouteJob.JobStatus.PROCESSING, null);
        RouteJobLifecycleService.LifecycleSnapshot queued = snapshot(
            observed,
            RouteJob.JobStatus.QUEUED,
            null,
            false
        );
        when(jobRepository.findExpiredActiveJobs(any(), any(), any())).thenReturn(List.of(observed));
        when(lifecycleService.recoverExpired(
            eq(observed.getId()), eq(observed.getLeaseToken()), anyInt(), anyString(), any()
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.RETRY,
            queued
        ));

        watchdog().checkForTimedOutJobs();

        verifyNoInteractions(terminalEventPublisher);
    }

    @Test
    void expiredPrimaryReadyLeaseSchedulesContinuationRetry() {
        UUID primaryRouteId = UUID.randomUUID();
        RouteJob observed = activeJob(RouteJob.JobStatus.PRIMARY_READY, primaryRouteId);
        RouteJobLifecycleService.LifecycleSnapshot primaryReady = snapshot(
            observed,
            RouteJob.JobStatus.PRIMARY_READY,
            primaryRouteId,
            false
        );
        when(jobRepository.findExpiredActiveJobs(any(), any(), any())).thenReturn(List.of(observed));
        when(lifecycleService.recoverExpired(
            eq(observed.getId()), eq(observed.getLeaseToken()), anyInt(), anyString(), any()
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.RETRY,
            primaryReady
        ));

        watchdog().checkForTimedOutJobs();

        verifyNoInteractions(terminalEventPublisher);
    }


    @Test
    void renewedLeaseWinsWatchdogScanRaceWithoutDispatchingRetryOrChangingCounters() {
        RouteJob persisted = activeJob(RouteJob.JobStatus.PROCESSING, null);
        persisted.setLeaseExpiresAt(Instant.now().plusSeconds(5));
        persisted.setStateRevision(7L);
        persisted.setOptionRevision(2L);
        persisted.setOptionCount(2);
        persisted.setRetryCount(1);
        RouteJob staleObservation = activeJob(RouteJob.JobStatus.PROCESSING, null);
        staleObservation.setId(persisted.getId());
        staleObservation.setLeaseToken(persisted.getLeaseToken());
        staleObservation.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        when(jobRepository.findByIdForUpdate(persisted.getId())).thenReturn(Optional.of(persisted));
        when(jobRepository.findExpiredActiveJobs(any(), any(), any())).thenReturn(List.of(staleObservation));
        RouteJobLifecycleService realLifecycle =
            new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30);
        TimeoutWatchdog watchdog = new TimeoutWatchdog(jobRepository, realLifecycle, terminalEventPublisher, 30, 3);

        Instant renewedUntil = realLifecycle.renewLease(persisted.getId(), persisted.getLeaseToken());
        watchdog.checkForTimedOutJobs();

        assertTrue(renewedUntil.isAfter(Instant.now().plusSeconds(20)));
        assertEquals(renewedUntil, persisted.getLeaseExpiresAt());
        assertEquals(RouteJob.JobStatus.PROCESSING, persisted.getStatus());
        assertEquals(7L, persisted.getStateRevision());
        assertEquals(2L, persisted.getOptionRevision());
        assertEquals(2, persisted.getOptionCount());
        assertEquals(1, persisted.getRetryCount());
        verify(jobRepository).saveAndFlush(persisted);
    }


    @Test
    void exhaustedPrimaryRecoveryDispatchesDurableCompletionOutbox() {
        UUID primaryRouteId = UUID.randomUUID();
        RouteJob observed = activeJob(RouteJob.JobStatus.PRIMARY_READY, primaryRouteId);
        RouteJobLifecycleService.LifecycleSnapshot completed = snapshot(
            observed,
            RouteJob.JobStatus.COMPLETED,
            primaryRouteId,
            true
        );
        when(jobRepository.findExpiredActiveJobs(any(), any(), any())).thenReturn(List.of(observed));
        when(lifecycleService.recoverExpired(
            eq(observed.getId()), eq(observed.getLeaseToken()), anyInt(), anyString(), any()
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.FINALIZED,
            completed
        ));

        watchdog().checkForTimedOutJobs();

        verify(terminalEventPublisher).publishPending(
            completed.jobId(),
            completed.stateRevision()
        );
    }

    @Test
    void failedRecoveryDispatchesDurableFailureAndDlqOutbox(
        CapturedOutput output
    ) {
        RouteJob observed = activeJob(RouteJob.JobStatus.PROCESSING, null);
        String timeoutReason = "Route generation timed out after retries";
        RouteJobLifecycleService.LifecycleSnapshot failed =
            new RouteJobLifecycleService.LifecycleSnapshot(
                observed.getId(),
                observed.getUserId(),
                null,
                RouteJob.JobStatus.FAILED,
                5L,
                0L,
                0,
                false,
                timeoutReason,
                observed.getRetryCount(),
                Instant.now()
            );
        when(jobRepository.findExpiredActiveJobs(any(), any(), any()))
            .thenReturn(List.of(observed));
        when(lifecycleService.recoverExpired(
            eq(observed.getId()), eq(observed.getLeaseToken()), anyInt(), anyString(), any()
        )).thenReturn(new RouteJobLifecycleService.RecoveryResult(
            RouteJobLifecycleService.RecoveryAction.FAILED,
            failed
        ));

        watchdog().checkForTimedOutJobs();

        verify(terminalEventPublisher).publishPending(failed.jobId(), failed.stateRevision());
        assertTrue(output.getAll().contains(
            "Route job " + observed.getId() + " timeout handled action=FAILED"
        ));
    }


    private TimeoutWatchdog watchdog() {
        return new TimeoutWatchdog(jobRepository, lifecycleService, terminalEventPublisher, 30, 3);
    }

    private RouteJob activeJob(RouteJob.JobStatus status, UUID routeId) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(status);
        job.setRouteId(routeId);
        job.setStartedAt(Instant.now().minusSeconds(120));
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        job.setOptionCount(routeId == null ? 0 : 1);
        job.setOptionRevision(routeId == null ? 0L : 1L);
        return job;
    }


    private RouteJobLifecycleService.LifecycleSnapshot snapshot(
        RouteJob job,
        RouteJob.JobStatus status,
        UUID routeId,
        boolean optionsComplete
    ) {
        return new RouteJobLifecycleService.LifecycleSnapshot(
            job.getId(),
            job.getUserId(),
            routeId,
            status,
            5L,
            job.getOptionRevision(),
            job.getOptionCount(),
            optionsComplete,
            null,
            job.getRetryCount(),
            optionsComplete ? Instant.now() : null
        );
    }

}
