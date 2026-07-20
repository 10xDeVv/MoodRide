package com.moodride.routeworker.service;

import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.RouteJobTerminalEvent;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteJobLifecycleServiceTest {
    @Mock
    private RouteJobRepository jobRepository;
    @Mock
    private RouteJobTerminalEventRepository terminalEventRepository;

    @Test
    void primaryReadyRedeliveryWithActiveLeaseSkipsWithoutMutatingAndOriginalOwnerRemainsValid() {
        UUID activeLease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(activeLease);
        job.setStateRevision(8L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        Instant activeLeaseExpiry = job.getLeaseExpiresAt();
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult result = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.SKIP, result.action());
        assertNull(result.leaseToken());
        assertEquals(activeLease, job.getLeaseToken());
        assertEquals(activeLeaseExpiry, job.getLeaseExpiresAt());
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(8L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository, never()).saveAndFlush(job);

        RouteJobLifecycleService.LifecycleSnapshot completed = service.complete(job.getId(), activeLease);

        assertEquals(RouteJob.JobStatus.COMPLETED, completed.status());
        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void primaryReadyExpiredLeaseRequiresRecoveryBeforeRedeliveryCanClaim() {
        UUID expiredLease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(expiredLease);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        job.setStateRevision(8L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        Instant expiredAt = job.getLeaseExpiresAt();
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult duplicate = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.SKIP, duplicate.action());
        assertNull(duplicate.leaseToken());
        assertEquals(expiredLease, job.getLeaseToken());
        assertEquals(expiredAt, job.getLeaseExpiresAt());
        assertEquals(8L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository, never()).saveAndFlush(job);

        RouteJobLifecycleService.RecoveryResult recovery = service.recoverExpired(
            job.getId(), expiredLease, 3, "timed out", Instant.now()
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, recovery.action());
        assertNull(job.getLeaseToken());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()));
        assertEquals(9L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(2, job.getRetryCount());

        RouteJobLifecycleService.ClaimResult recoveredClaim = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.CLAIMED, recoveredClaim.action());
        assertNotEquals(expiredLease, recoveredClaim.leaseToken());
        assertEquals(recoveredClaim.leaseToken(), job.getLeaseToken());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()));
        assertEquals(9L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(2, job.getRetryCount());
        assertThrows(
            LeaseLostException.class,
            () -> service.complete(job.getId(), expiredLease)
        );
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        verify(jobRepository, times(2)).saveAndFlush(job);
    }

    @Test
    void primaryReadyRetryEventClaimsImmediatelyBeforeRedispatchDeadline() {
        RouteJob job = primaryReadyJob(null);
        Instant dispatchDeadline = Instant.now().plusSeconds(300);
        job.setLeaseExpiresAt(dispatchDeadline);
        job.setStateRevision(8L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult result = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.CLAIMED, result.action());
        assertEquals(result.leaseToken(), job.getLeaseToken());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()));
        assertTrue(job.getLeaseExpiresAt().isBefore(dispatchDeadline));
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(8L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void tokenNullPrimaryReadyRetryIsRedispatchedOnlyAfterDeadlineWithoutChargingAgain() {
        RouteJob job = primaryReadyJob(null);
        Instant dispatchDeadline = Instant.now().plusSeconds(30);
        job.setLeaseExpiresAt(dispatchDeadline);
        job.setStateRevision(8L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult beforeDeadline = service.recoverExpired(
            job.getId(), null, 3, "timed out", dispatchDeadline.minusSeconds(1)
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.NONE, beforeDeadline.action());
        assertEquals(dispatchDeadline, job.getLeaseExpiresAt());
        verify(jobRepository, never()).saveAndFlush(job);

        Instant afterDeadline = dispatchDeadline.plusSeconds(1);
        RouteJobLifecycleService.RecoveryResult redispatch = service.recoverExpired(
            job.getId(), null, 3, "timed out", afterDeadline
        );
        Instant rearmedDeadline = job.getLeaseExpiresAt();
        RouteJobLifecycleService.RecoveryResult repeatedScan = service.recoverExpired(
            job.getId(), null, 3, "timed out", afterDeadline.plusSeconds(1)
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, redispatch.action());
        assertEquals(RouteJobLifecycleService.RecoveryAction.NONE, repeatedScan.action());
        assertNull(job.getLeaseToken());
        assertTrue(rearmedDeadline.isAfter(dispatchDeadline));
        assertEquals(rearmedDeadline, job.getLeaseExpiresAt());
        assertEquals(8L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void pendingPrimaryReadyDispatchPreservesDispatcherBackoffAcrossRepeatedRecovery() {
        RouteJob job = primaryReadyJob(null);
        Instant expiredDispatchDeadline = Instant.now().minusSeconds(30);
        job.setLeaseExpiresAt(expiredDispatchDeadline);
        job.setStateRevision(8L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.hasPendingRetryDispatch(job.getId())).thenReturn(true);
        RouteJobLifecycleService service =
            new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult result = service.recoverExpired(
            job.getId(), null, 3, "timed out", Instant.now()
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.NONE, result.action());
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(expiredDispatchDeadline, job.getLeaseExpiresAt());
        assertEquals(8L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository).hasPendingRetryDispatch(job.getId());
        verify(jobRepository, never()).saveAndFlush(job);
        verify(jobRepository, never()).upsertRetryDispatch(any(), any());
    }

    @Test
    void staleLeaseCannotFinalizeOrMutateJob() {
        RouteJob job = primaryReadyJob(UUID.randomUUID());
        job.setStateRevision(4L);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        assertThrows(
            LeaseLostException.class,
            () -> service.complete(job.getId(), UUID.randomUUID())
        );

        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(4L, job.getStateRevision());
        assertFalse(job.isOptionsComplete());
        verify(jobRepository, never()).saveAndFlush(job);
    }

    @Test
    void exhaustedPostPrimaryRecoveryFinalizesUsablePartialResult() {
        UUID lease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(lease);
        job.setStateRevision(4L);
        job.setOptionRevision(1L);
        job.setOptionCount(1);
        job.setRetryCount(1);
        job.setMaxRetries(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult result = service.handleTransientFailure(
            job.getId(),
            lease,
            3,
            "Route generation timed out after retries"
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.FINALIZED, result.action());
        assertEquals(RouteJob.JobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getOptionCount());
        assertEquals(1L, job.getOptionRevision());
        assertEquals(5L, job.getStateRevision());
        assertTrue(job.isOptionsComplete());
        assertNull(job.getLeaseToken());
        assertNull(job.getLeaseExpiresAt());
        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void maxRetriesTwoAllowsTwoRetriesAfterInitialAttempt() {
        UUID initialLease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(initialLease);
        job.setOptionRevision(1L);
        job.setOptionCount(1);
        job.setMaxRetries(2);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult firstFailure = service.handleTransientFailure(
            job.getId(), initialLease, 3, "timed out"
        );
        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, firstFailure.action());
        assertEquals(1, job.getRetryCount());

        RouteJobLifecycleService.ClaimResult firstRetry = service.claim(job.getId(), 3);
        RouteJobLifecycleService.RecoveryResult secondFailure = service.handleTransientFailure(
            job.getId(), firstRetry.leaseToken(), 3, "timed out"
        );
        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, secondFailure.action());
        assertEquals(2, job.getRetryCount());

        RouteJobLifecycleService.ClaimResult secondRetry = service.claim(job.getId(), 3);
        RouteJobLifecycleService.RecoveryResult thirdFailure = service.handleTransientFailure(
            job.getId(), secondRetry.leaseToken(), 3, "timed out"
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.FINALIZED, thirdFailure.action());
        assertEquals(RouteJob.JobStatus.COMPLETED, job.getStatus());
        assertEquals(2, job.getRetryCount());
        assertEquals(1L, job.getOptionRevision());
        assertEquals(1, job.getOptionCount());
        verify(jobRepository, times(5)).saveAndFlush(job);
    }

    @Test
    void expiredPrePrimaryLeaseRequeuesAndInvalidatesOldOwner() {
        UUID lease = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setStartedAt(Instant.now().minusSeconds(120));
        job.setStateRevision(1L);
        job.setLeaseToken(lease);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult result = service.recoverExpired(
            job.getId(), lease, 3, "timed out", Instant.now()
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, result.action());
        assertEquals(RouteJob.JobStatus.QUEUED, job.getStatus());
        assertEquals(1, job.getRetryCount());
        assertEquals(2L, job.getStateRevision());
        assertNull(job.getLeaseToken());
        InOrder durableRetry = inOrder(jobRepository);
        durableRetry.verify(jobRepository).saveAndFlush(job);
        durableRetry.verify(jobRepository).upsertRetryDispatch(
            eq(job.getId()),
            any(Instant.class)
        );
    }


    @Test
    void expiredPrimaryLeasePreservesPrimaryAndPreparesContinuationRetry() {
        UUID lease = UUID.randomUUID();
        RouteJob job = primaryReadyJob(lease);
        UUID primaryRouteId = job.getRouteId();
        job.setStateRevision(4L);
        job.setOptionRevision(1L);
        job.setOptionCount(1);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult result = service.recoverExpired(
            job.getId(), lease, 3, "timed out", Instant.now()
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, result.action());
        assertEquals(RouteJob.JobStatus.PRIMARY_READY, job.getStatus());
        assertEquals(primaryRouteId, job.getRouteId());
        assertEquals(1L, job.getOptionRevision());
        assertEquals(1, job.getOptionCount());
        assertEquals(5L, job.getStateRevision());
        assertNull(job.getLeaseToken());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()));
        assertFalse(job.isOptionsComplete());
        verify(jobRepository).saveAndFlush(job);
        verify(jobRepository).upsertRetryDispatch(eq(job.getId()), any(Instant.class));
    }

    @Test
    void maxRetriesTwoAllowsTwoPrePrimaryRetriesThenFailsThirdAttempt() {
        UUID initialLease = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setStartedAt(Instant.now().minusSeconds(10));
        job.setLeaseToken(initialLease);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        job.setMaxRetries(2);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.RecoveryResult firstFailure = service.handleTransientFailure(
            job.getId(), initialLease, 3, "timed out"
        );
        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, firstFailure.action());
        assertEquals(1, job.getRetryCount());
        assertEquals(RouteJob.JobStatus.QUEUED, job.getStatus());

        RouteJobLifecycleService.ClaimResult firstRetry = service.claim(job.getId(), 3);
        RouteJobLifecycleService.RecoveryResult secondFailure = service.handleTransientFailure(
            job.getId(), firstRetry.leaseToken(), 3, "timed out"
        );
        assertEquals(RouteJobLifecycleService.RecoveryAction.RETRY, secondFailure.action());
        assertEquals(2, job.getRetryCount());
        assertEquals(RouteJob.JobStatus.QUEUED, job.getStatus());

        RouteJobLifecycleService.ClaimResult secondRetry = service.claim(job.getId(), 3);
        RouteJobLifecycleService.RecoveryResult thirdFailure = service.handleTransientFailure(
            job.getId(), secondRetry.leaseToken(), 3, "timed out"
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.FAILED, thirdFailure.action());
        assertEquals(RouteJob.JobStatus.FAILED, job.getStatus());
        assertEquals(2, job.getRetryCount());
        assertEquals("timed out", job.getFailureReason());
        verify(jobRepository, times(5)).saveAndFlush(job);
    }

    @Test
    void periodicRenewalKeepsLongHealthyAttemptOutOfWatchdogRecoveryWithoutChangingCounters() {
        Instant startedAt = Instant.parse("2026-07-19T12:00:00Z");
        MutableClock clock = new MutableClock(startedAt);
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStateRevision(6L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, clock);

        RouteJobLifecycleService.ClaimResult claim = service.claim(job.getId(), 3);
        long claimedStateRevision = job.getStateRevision();
        long claimedOptionRevision = job.getOptionRevision();
        int claimedOptionCount = job.getOptionCount();
        int claimedRetryCount = job.getRetryCount();

        clock.advance(Duration.ofSeconds(20));
        assertEquals(startedAt.plusSeconds(50), service.renewLease(job.getId(), claim.leaseToken()));
        RouteJobLifecycleService.RecoveryResult firstWatchdogScan = service.recoverExpired(
            job.getId(), claim.leaseToken(), 3, "timed out", startedAt.plusSeconds(35)
        );

        clock.advance(Duration.ofSeconds(20));
        assertEquals(startedAt.plusSeconds(70), service.renewLease(job.getId(), claim.leaseToken()));
        RouteJobLifecycleService.RecoveryResult scanAfterSixtyFiveSeconds = service.recoverExpired(
            job.getId(), claim.leaseToken(), 3, "timed out", startedAt.plusSeconds(65)
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.NONE, firstWatchdogScan.action());
        assertEquals(RouteJobLifecycleService.RecoveryAction.NONE, scanAfterSixtyFiveSeconds.action());
        assertEquals(RouteJob.JobStatus.PROCESSING, job.getStatus());
        assertEquals(claim.leaseToken(), job.getLeaseToken());
        assertEquals(startedAt.plusSeconds(70), job.getLeaseExpiresAt());
        assertEquals(claimedStateRevision, job.getStateRevision());
        assertEquals(claimedOptionRevision, job.getOptionRevision());
        assertEquals(claimedOptionCount, job.getOptionCount());
        assertEquals(claimedRetryCount, job.getRetryCount());
        verify(jobRepository, times(3)).saveAndFlush(job);
    }

    @Test
    void staleTokenCannotRenewOrMutateActiveLease() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        job.setStateRevision(5L);
        job.setOptionRevision(2L);
        job.setOptionCount(2);
        job.setRetryCount(1);
        Instant leaseExpiresAt = job.getLeaseExpiresAt();
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        assertThrows(LeaseLostException.class, () -> service.renewLease(job.getId(), UUID.randomUUID()));

        assertEquals(leaseToken, job.getLeaseToken());
        assertEquals(leaseExpiresAt, job.getLeaseExpiresAt());
        assertEquals(5L, job.getStateRevision());
        assertEquals(2L, job.getOptionRevision());
        assertEquals(2, job.getOptionCount());
        assertEquals(1, job.getRetryCount());
        verify(jobRepository, never()).saveAndFlush(job);
    }

    @Test
    void completedRedeliveryReturnsTerminalReplaySnapshotWithoutMutation() {
        RouteJob job = primaryReadyJob(null);
        Instant completedAt = Instant.parse("2026-07-19T12:30:00Z");
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setOptionsComplete(true);
        job.setStateRevision(9L);
        job.setOptionRevision(3L);
        job.setOptionCount(3);
        job.setRetryCount(2);
        job.setCompletedAt(completedAt);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult result = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.REPLAY_TERMINAL, result.action());
        assertNull(result.leaseToken());
        assertEquals(RouteJob.JobStatus.COMPLETED, result.state().status());
        assertEquals(job.getRouteId(), result.state().routeId());
        assertEquals(9L, result.state().stateRevision());
        assertEquals(3L, result.state().optionRevision());
        assertEquals(3, result.state().optionCount());
        assertTrue(result.state().optionsComplete());
        assertEquals(2, result.state().retryCount());
        assertEquals(completedAt, result.state().completedAt());
        assertNull(job.getLeaseToken());
        verify(jobRepository, never()).saveAndFlush(job);
    }

    @ParameterizedTest
    @EnumSource(
        value = RouteJob.JobStatus.class,
        names = {"FAILED", "TIMEOUT"}
    )
    void failedOrTimedOutRedeliveryReturnsTerminalReplaySnapshotWithoutMutation(
        RouteJob.JobStatus terminalStatus
    ) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(terminalStatus);
        job.setFailureReason("terminal failure");
        job.setStateRevision(6L);
        job.setRetryCount(2);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult result = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.REPLAY_TERMINAL, result.action());
        assertNull(result.leaseToken());
        assertEquals(terminalStatus, result.state().status());
        assertEquals("terminal failure", result.state().failureReason());
        assertEquals(6L, result.state().stateRevision());
        assertEquals(2, result.state().retryCount());
        assertNull(job.getLeaseToken());
        verify(jobRepository, never()).saveAndFlush(job);
    }

    @Test
    void completedRedeliveryWithoutCanonicalRouteSafelySkips() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.COMPLETED);
        job.setOptionsComplete(true);
        job.setStateRevision(4L);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.ClaimResult result = service.claim(job.getId(), 3);

        assertEquals(RouteJobLifecycleService.ClaimAction.SKIP, result.action());
        assertNull(result.leaseToken());
        assertEquals(4L, result.state().stateRevision());
        verify(jobRepository, never()).saveAndFlush(job);
    }

    @Test
    void failedPrimaryPublicationReleaseMakesImmediateRedeliveryClaimableAndFencesOldOwner() {
        UUID failedPublisherToken = UUID.randomUUID();
        RouteJob job = primaryReadyJob(failedPublisherToken);
        job.setStateRevision(4L);
        job.setOptionRevision(1L);
        job.setOptionCount(1);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RouteJobLifecycleService service = new RouteJobLifecycleService(jobRepository, terminalEventRepository, 30, Clock.systemUTC());

        RouteJobLifecycleService.LifecycleSnapshot abandoned =
            service.abandonPrimaryPublication(job.getId(), failedPublisherToken);
        RouteJobLifecycleService.ClaimResult redelivery = service.claim(job.getId(), 3);

        assertEquals(RouteJob.JobStatus.PRIMARY_READY, abandoned.status());
        assertNull(job.getFailureReason());
        assertEquals(RouteJobLifecycleService.ClaimAction.CLAIMED, redelivery.action());
        assertEquals(redelivery.leaseToken(), job.getLeaseToken());
        assertNotEquals(failedPublisherToken, redelivery.leaseToken());
        assertEquals(4L, job.getStateRevision());
        assertEquals(1L, job.getOptionRevision());
        assertEquals(1, job.getOptionCount());

        assertThrows(
            LeaseLostException.class,
            () -> service.abandonPrimaryPublication(job.getId(), failedPublisherToken)
        );
        assertEquals(redelivery.leaseToken(), job.getLeaseToken());
        verify(jobRepository, times(2)).saveAndFlush(job);
    }

    @Test
    void failedTransitionPersistsCompletionAndDlqIdentitiesWithLifecycleMutation() {
        UUID leaseToken = UUID.randomUUID();
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        job.setStateRevision(5L);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(terminalEventRepository.findById(anyString())).thenReturn(Optional.empty());
        RouteJobLifecycleService service = new RouteJobLifecycleService(
            jobRepository,
            terminalEventRepository,
            30,
            Clock.systemUTC()
        );

        RouteJobLifecycleService.RecoveryResult failed = service.handleNonRetryableFailure(
            job.getId(),
            leaseToken,
            "no feasible route"
        );

        assertEquals(RouteJobLifecycleService.RecoveryAction.FAILED, failed.action());
        ArgumentCaptor<RouteJobTerminalEvent> events =
            ArgumentCaptor.forClass(RouteJobTerminalEvent.class);
        InOrder persistedTogether = inOrder(jobRepository, terminalEventRepository);
        persistedTogether.verify(jobRepository).saveAndFlush(job);
        persistedTogether.verify(terminalEventRepository, times(2)).save(events.capture());
        RouteJobTerminalEvent completion = events.getAllValues().get(0);
        RouteJobTerminalEvent dlq = events.getAllValues().get(1);
        assertEquals(RouteJobTerminalEvent.EventType.COMPLETION, completion.getEventType());
        assertEquals(RouteJobTerminalEvent.EventType.DLQ, dlq.getEventType());
        assertEquals(job.getId() + ":6:COMPLETION", completion.getEventId());
        assertEquals(job.getId() + ":6:DLQ", dlq.getEventId());
        assertEquals(RouteJob.JobStatus.FAILED, completion.getTerminalStatus());
        assertEquals(RouteJob.JobStatus.FAILED, dlq.getTerminalStatus());
        assertNull(completion.getOriginalPayload());
        assertEquals(job.getId().toString(), dlq.getOriginalPayload());
        assertSame(job.getCompletedAt(), completion.getCreatedAt());
        assertSame(job.getCompletedAt(), dlq.getCreatedAt());
    }


    private RouteJob primaryReadyJob(UUID leaseToken) {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setRouteId(UUID.randomUUID());
        job.setPrimaryReadyAt(Instant.now().minusSeconds(10));
        job.setStatus(RouteJob.JobStatus.PRIMARY_READY);
        job.setLeaseToken(leaseToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        return job;
    }
    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) {
                throw new UnsupportedOperationException("Mutable test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

}
