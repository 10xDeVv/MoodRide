package com.moodride.routeworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteJobHeartbeatServiceTest {
    @Mock
    private RouteJobLifecycleService lifecycleService;
    @Mock
    private ScheduledExecutorService scheduler;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    @Test
    void startsImmediatelyOnSharedSchedulerAndCloseStopsRenewalAfterCompletion() {
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        ArgumentCaptor<Runnable> renewalTask = scheduledTask();
        RouteJobHeartbeatService service = service();

        RouteJobHeartbeatService.Heartbeat heartbeat = service.start(jobId, leaseToken);

        verify(scheduler).scheduleWithFixedDelay(
            renewalTask.capture(),
            eq(0L),
            eq(10_000L),
            eq(TimeUnit.MILLISECONDS)
        );
        renewalTask.getValue().run();
        renewalTask.getValue().run();
        assertDoesNotThrow(heartbeat::requireActive);
        verify(lifecycleService, times(2)).renewLease(jobId, leaseToken);

        heartbeat.close();
        renewalTask.getValue().run();

        verify(scheduledFuture).cancel(false);
        verify(lifecycleService, times(2)).renewLease(jobId, leaseToken);
    }

    @Test
    void leaseLossStopsFurtherRenewalAndIsReportedToWorkerAttempt() {
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        ArgumentCaptor<Runnable> renewalTask = scheduledTask();
        LeaseLostException leaseLoss = new LeaseLostException(jobId);
        doThrow(leaseLoss).when(lifecycleService).renewLease(jobId, leaseToken);
        RouteJobHeartbeatService.Heartbeat heartbeat = service().start(jobId, leaseToken);
        verify(scheduler).scheduleWithFixedDelay(
            renewalTask.capture(),
            eq(0L),
            eq(10_000L),
            eq(TimeUnit.MILLISECONDS)
        );

        renewalTask.getValue().run();
        renewalTask.getValue().run();

        assertThrows(LeaseLostException.class, heartbeat::requireActive);
        verify(lifecycleService).renewLease(jobId, leaseToken);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void nullDispatchTokenCannotStartWorkerHeartbeat() {
        RouteJobHeartbeatService service = service();

        assertThrows(NullPointerException.class, () -> service.start(UUID.randomUUID(), null));

        verify(scheduler, never()).scheduleWithFixedDelay(
            any(Runnable.class),
            anyLong(),
            anyLong(),
            any(TimeUnit.class)
        );
        verify(lifecycleService, never()).renewLease(any(), any());
    }

    @Test
    void shutdownCancelsActiveHeartbeatsAndRejectsNewOnes() throws InterruptedException {
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        scheduledTask();
        when(scheduler.awaitTermination(5_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        RouteJobHeartbeatService service = service();
        service.start(jobId, leaseToken);

        service.shutdown();

        verify(scheduledFuture).cancel(false);
        verify(scheduler).shutdown();
        verify(scheduler).awaitTermination(5_000L, TimeUnit.MILLISECONDS);
        verify(scheduler, never()).shutdownNow();
        assertThrows(
            IllegalStateException.class,
            () -> service.start(UUID.randomUUID(), UUID.randomUUID())
        );
    }

    private ArgumentCaptor<Runnable> scheduledTask() {
        doReturn(scheduledFuture).when(scheduler).scheduleWithFixedDelay(
            any(Runnable.class),
            eq(0L),
            eq(10_000L),
            eq(TimeUnit.MILLISECONDS)
        );
        return ArgumentCaptor.forClass(Runnable.class);
    }

    private RouteJobHeartbeatService service() {
        return new RouteJobHeartbeatService(lifecycleService, scheduler, Duration.ofSeconds(10));
    }
}
