package com.moodride.routeapi.config;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Keeps durable dispatch recovery independent from slower cache maintenance.
 */
@Configuration(proxyBeanMethods = false)
public class RouteApiSchedulingConfiguration {

    public static final String ROUTE_DISPATCH_TASK_SCHEDULER = "routeDispatchTaskScheduler";
    public static final String CACHE_WARMUP_TASK_SCHEDULER = "cacheWarmupTaskScheduler";

    static final int ROUTE_DISPATCH_POOL_SIZE = 1;
    static final int CACHE_WARMUP_POOL_SIZE = 1;

    @Bean(name = ROUTE_DISPATCH_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler routeDispatchTaskScheduler(
        @Value("${moodride.scheduling.shutdown-await:15s}") Duration shutdownAwait
    ) {
        return scheduler("route-api-dispatch-scheduler-", ROUTE_DISPATCH_POOL_SIZE, shutdownAwait);
    }

    @Bean(name = CACHE_WARMUP_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler cacheWarmupTaskScheduler(
        @Value("${moodride.scheduling.shutdown-await:15s}") Duration shutdownAwait
    ) {
        return scheduler("route-api-cache-warmup-scheduler-", CACHE_WARMUP_POOL_SIZE, shutdownAwait);
    }

    private ThreadPoolTaskScheduler scheduler(
        String threadNamePrefix,
        int poolSize,
        Duration shutdownAwait
    ) {
        if (shutdownAwait.isNegative() || shutdownAwait.isZero()) {
            throw new IllegalArgumentException("Scheduler shutdown await duration must be positive");
        }
        long shutdownAwaitSeconds = shutdownAwait.toSeconds();
        if (shutdownAwaitSeconds < 1 || shutdownAwaitSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Scheduler shutdown await duration must be between 1s and 2147483647s"
            );
        }

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds((int) shutdownAwaitSeconds);
        return scheduler;
    }
}
