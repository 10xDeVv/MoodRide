package com.moodride.routeworker.config;

import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.producer.RouteJobTerminalEventPublisher;
import com.moodride.routeworker.repository.RouteJobRepository;
import com.moodride.routeworker.repository.RouteJobTerminalEventRepository;
import com.moodride.routeworker.scheduler.GraphCacheWarmupScheduler;
import com.moodride.routeworker.scheduler.ScenicTileWarmupScheduler;
import com.moodride.routeworker.service.RouteGenerationService;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import com.moodride.routeworker.watchdog.TimeoutWatchdog;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteWorkerSchedulingConfigurationTest {

    @Test
    void blockedTerminalRedispatchDoesNotPreventWatchdogScheduledInvocationOrOverlapClaims()
        throws Exception {
        RouteWorkerSchedulingConfiguration configuration = new RouteWorkerSchedulingConfiguration();
        ThreadPoolTaskScheduler terminalScheduler =
            configuration.terminalEventTaskScheduler(Duration.ofSeconds(2));
        ThreadPoolTaskScheduler watchdogScheduler =
            configuration.watchdogTaskScheduler(Duration.ofSeconds(2));
        terminalScheduler.initialize();
        watchdogScheduler.initialize();

        CountDownLatch terminalClaimStarted = new CountDownLatch(1);
        CountDownLatch releaseTerminalClaim = new CountDownLatch(1);
        CountDownLatch watchdogInvoked = new CountDownLatch(1);
        AtomicInteger claimInvocations = new AtomicInteger();
        AtomicReference<String> terminalThread = new AtomicReference<>();
        AtomicReference<String> watchdogThread = new AtomicReference<>();

        RouteJobTerminalEventRepository eventRepository = mock(RouteJobTerminalEventRepository.class);
        when(eventRepository.lockOldestDue(any(Instant.class))).thenAnswer(invocation -> {
            terminalThread.set(Thread.currentThread().getName());
            claimInvocations.incrementAndGet();
            terminalClaimStarted.countDown();
            if (!releaseTerminalClaim.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release blocked terminal redispatch");
            }
            return Optional.empty();
        });
        RouteJobTerminalEventPublisher publisher = publisher(eventRepository);

        RouteJobRepository jobRepository = mock(RouteJobRepository.class);
        when(jobRepository.findExpiredActiveJobs(any(), any(), any())).thenAnswer(invocation -> {
            watchdogThread.set(Thread.currentThread().getName());
            watchdogInvoked.countDown();
            return List.of();
        });
        TimeoutWatchdog watchdog = new TimeoutWatchdog(
            jobRepository,
            mock(RouteJobLifecycleService.class),
            publisher,
            30,
            3
        );

        ScheduledFuture<?> blockedRedispatch = null;
        ScheduledFuture<?> queuedRedispatch = null;
        ScheduledFuture<?> watchdogScan = null;
        try {
            blockedRedispatch = terminalScheduler.schedule(publisher::redispatchPending, Instant.now());
            assertTrue(terminalClaimStarted.await(1, TimeUnit.SECONDS));

            queuedRedispatch = terminalScheduler.schedule(publisher::redispatchPending, Instant.now());
            watchdogScan = watchdogScheduler.schedule(watchdog::checkForTimedOutJobs, Instant.now());

            assertTrue(watchdogInvoked.await(1, TimeUnit.SECONDS));
            watchdogScan.get(1, TimeUnit.SECONDS);
            assertEquals(1, claimInvocations.get());
            assertEquals(1, terminalScheduler.getActiveCount());
            assertEquals(1, terminalScheduler.getScheduledThreadPoolExecutor().getQueue().size());
            assertTrue(terminalThread.get().startsWith("route-terminal-scheduler-"));
            assertTrue(watchdogThread.get().startsWith("route-watchdog-scheduler-"));
        } finally {
            releaseTerminalClaim.countDown();
            if (blockedRedispatch != null) {
                blockedRedispatch.get(1, TimeUnit.SECONDS);
            }
            if (queuedRedispatch != null) {
                queuedRedispatch.get(1, TimeUnit.SECONDS);
            }
            terminalScheduler.shutdown();
            watchdogScheduler.shutdown();
        }

        assertEquals(2, claimInvocations.get());
    }

    @Test
    void schedulerPoolsAreBoundedAbortRejectedWorkAndOwnEveryScheduledLane() throws Exception {
        RouteWorkerSchedulingConfiguration configuration = new RouteWorkerSchedulingConfiguration();
        ThreadPoolTaskScheduler terminalScheduler =
            configuration.terminalEventTaskScheduler(Duration.ofSeconds(2));
        ThreadPoolTaskScheduler watchdogScheduler =
            configuration.watchdogTaskScheduler(Duration.ofSeconds(2));
        ThreadPoolTaskScheduler cacheScheduler =
            configuration.cacheWarmupTaskScheduler(Duration.ofSeconds(2));
        List<ThreadPoolTaskScheduler> schedulers =
            List.of(terminalScheduler, watchdogScheduler, cacheScheduler);
        schedulers.forEach(ThreadPoolTaskScheduler::initialize);

        try {
            assertSchedulerBoundary(terminalScheduler, RouteWorkerSchedulingConfiguration.TERMINAL_EVENT_POOL_SIZE);
            assertSchedulerBoundary(watchdogScheduler, RouteWorkerSchedulingConfiguration.WATCHDOG_POOL_SIZE);
            assertSchedulerBoundary(cacheScheduler, RouteWorkerSchedulingConfiguration.CACHE_WARMUP_POOL_SIZE);

            assertScheduledOn(
                RouteJobTerminalEventPublisher.class,
                "redispatchPending",
                RouteWorkerSchedulingConfiguration.TERMINAL_EVENT_TASK_SCHEDULER
            );
            assertScheduledOn(
                TimeoutWatchdog.class,
                "checkForTimedOutJobs",
                RouteWorkerSchedulingConfiguration.WATCHDOG_TASK_SCHEDULER
            );
            assertScheduledOn(
                ScenicTileWarmupScheduler.class,
                "warmRecentRouteRegions",
                RouteWorkerSchedulingConfiguration.CACHE_WARMUP_TASK_SCHEDULER
            );
            assertScheduledOn(
                GraphCacheWarmupScheduler.class,
                "warmGraphCache",
                RouteWorkerSchedulingConfiguration.CACHE_WARMUP_TASK_SCHEDULER
            );
        } finally {
            schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        }

        assertThrows(
            RejectedExecutionException.class,
            () -> terminalScheduler.getScheduledThreadPoolExecutor().execute(() -> { })
        );
    }

    @Test
    void shutdownInterruptsBlockedScheduledWorkAndDoesNotRunDelayedTasks() throws Exception {
        RouteWorkerSchedulingConfiguration configuration = new RouteWorkerSchedulingConfiguration();
        ThreadPoolTaskScheduler scheduler =
            configuration.terminalEventTaskScheduler(Duration.ofSeconds(2));
        scheduler.initialize();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        scheduler.execute(() -> {
            taskStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        ScheduledFuture<?> delayed = scheduler.schedule(
            () -> { throw new AssertionError("Delayed task ran during shutdown"); },
            Instant.now().plusSeconds(60)
        );
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS));

        scheduler.shutdown();

        assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS));
        assertTrue(scheduler.getScheduledThreadPoolExecutor().isShutdown());
        assertFalse(delayed.isDone() && !delayed.isCancelled());
    }

    private void assertSchedulerBoundary(ThreadPoolTaskScheduler scheduler, int expectedPoolSize) {
        assertEquals(expectedPoolSize, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertInstanceOf(
            ThreadPoolExecutor.AbortPolicy.class,
            scheduler.getScheduledThreadPoolExecutor().getRejectedExecutionHandler()
        );
        assertTrue(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy());
    }

    private void assertScheduledOn(Class<?> type, String methodName, String expectedScheduler)
        throws NoSuchMethodException {
        Scheduled scheduled = type.getMethod(methodName).getAnnotation(Scheduled.class);
        assertEquals(expectedScheduler, scheduled.scheduler());
    }

    private RouteJobTerminalEventPublisher publisher(
        RouteJobTerminalEventRepository eventRepository
    ) {
        return new RouteJobTerminalEventPublisher(
            eventRepository,
            mock(RouteJobRepository.class),
            mock(RouteGenerationService.class),
            mock(RouteCompletionProducer.class),
            mock(RouteJobDlqProducer.class),
            new ImmediateTransactionManager(),
            true,
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            25,
            Duration.ofSeconds(1),
            Duration.ofMinutes(1)
        );
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
