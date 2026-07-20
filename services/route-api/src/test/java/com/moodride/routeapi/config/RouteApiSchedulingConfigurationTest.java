package com.moodride.routeapi.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.moodride.routeapi.cache.CacheWarmupScheduler;
import com.moodride.routeapi.service.RouteJobDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class RouteApiSchedulingConfigurationTest {

    @Test
    void blockedCacheWarmupCannotDelayDurableDispatchScheduling() throws Exception {
        RouteApiSchedulingConfiguration configuration = new RouteApiSchedulingConfiguration();
        ThreadPoolTaskScheduler dispatchScheduler =
            configuration.routeDispatchTaskScheduler(Duration.ofSeconds(2));
        ThreadPoolTaskScheduler warmupScheduler =
            configuration.cacheWarmupTaskScheduler(Duration.ofSeconds(2));
        dispatchScheduler.initialize();
        warmupScheduler.initialize();

        CountDownLatch warmupStarted = new CountDownLatch(1);
        CountDownLatch releaseWarmup = new CountDownLatch(1);
        CountDownLatch dispatchInvoked = new CountDownLatch(1);
        AtomicReference<String> warmupThread = new AtomicReference<>();
        AtomicReference<String> dispatchThread = new AtomicReference<>();
        ScheduledFuture<?> blockedWarmup = null;
        ScheduledFuture<?> dispatch = null;

        try {
            blockedWarmup = warmupScheduler.schedule(() -> {
                warmupThread.set(Thread.currentThread().getName());
                warmupStarted.countDown();
                try {
                    if (!releaseWarmup.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release cache warmup");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Cache warmup interrupted before release", exception);
                }
            }, Instant.now());
            assertThat(warmupStarted.await(1, TimeUnit.SECONDS)).isTrue();

            dispatch = dispatchScheduler.schedule(() -> {
                dispatchThread.set(Thread.currentThread().getName());
                dispatchInvoked.countDown();
            }, Instant.now());

            assertThat(dispatchInvoked.await(1, TimeUnit.SECONDS)).isTrue();
            dispatch.get(1, TimeUnit.SECONDS);
            assertThat(warmupThread.get()).startsWith("route-api-cache-warmup-scheduler-");
            assertThat(dispatchThread.get()).startsWith("route-api-dispatch-scheduler-");
        } finally {
            releaseWarmup.countDown();
            if (blockedWarmup != null) {
                blockedWarmup.get(1, TimeUnit.SECONDS);
            }
            warmupScheduler.shutdown();
            dispatchScheduler.shutdown();
        }
    }

    @Test
    void scheduledWorkUsesDistinctBoundedSchedulerLanes() throws Exception {
        RouteApiSchedulingConfiguration configuration = new RouteApiSchedulingConfiguration();
        ThreadPoolTaskScheduler dispatchScheduler =
            configuration.routeDispatchTaskScheduler(Duration.ofSeconds(2));
        ThreadPoolTaskScheduler warmupScheduler =
            configuration.cacheWarmupTaskScheduler(Duration.ofSeconds(2));
        List<ThreadPoolTaskScheduler> schedulers = List.of(dispatchScheduler, warmupScheduler);
        schedulers.forEach(ThreadPoolTaskScheduler::initialize);

        try {
            schedulers.forEach(scheduler -> {
                assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                assertThat(scheduler.getScheduledThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
                assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
                assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
                assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            });

            assertScheduledOn(
                RouteJobDispatchService.class,
                "redispatchDueRouteJobs",
                RouteApiSchedulingConfiguration.ROUTE_DISPATCH_TASK_SCHEDULER
            );
            assertScheduledOn(
                CacheWarmupScheduler.class,
                "warmCachesOnSchedule",
                RouteApiSchedulingConfiguration.CACHE_WARMUP_TASK_SCHEDULER
            );
        } finally {
            schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        }
    }

    private void assertScheduledOn(Class<?> type, String methodName, String expectedScheduler)
        throws NoSuchMethodException {
        Scheduled scheduled = type.getMethod(methodName).getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(expectedScheduler);
    }
}
