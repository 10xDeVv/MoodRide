package com.moodride.routeworker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Isolates blocking scheduled work so terminal-event broker waits cannot starve lease recovery.
 */
@Configuration(proxyBeanMethods = false)
public class RouteWorkerSchedulingConfiguration {
    public static final String TERMINAL_EVENT_TASK_SCHEDULER = "terminalEventTaskScheduler";
    public static final String WATCHDOG_TASK_SCHEDULER = "watchdogTaskScheduler";
    public static final String CACHE_WARMUP_TASK_SCHEDULER = "cacheWarmupTaskScheduler";

    static final int TERMINAL_EVENT_POOL_SIZE = 1;
    static final int WATCHDOG_POOL_SIZE = 1;
    static final int CACHE_WARMUP_POOL_SIZE = 1;

    @Bean(name = TERMINAL_EVENT_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler terminalEventTaskScheduler(
        @Value("${moodride.scheduling.shutdown-await:15s}") Duration shutdownAwait
    ) {
        return scheduler("route-terminal-scheduler-", TERMINAL_EVENT_POOL_SIZE, shutdownAwait);
    }

    @Bean(name = WATCHDOG_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler watchdogTaskScheduler(
        @Value("${moodride.scheduling.shutdown-await:15s}") Duration shutdownAwait
    ) {
        return scheduler("route-watchdog-scheduler-", WATCHDOG_POOL_SIZE, shutdownAwait);
    }

    @Bean(name = CACHE_WARMUP_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler cacheWarmupTaskScheduler(
        @Value("${moodride.scheduling.shutdown-await:15s}") Duration shutdownAwait
    ) {
        return scheduler("route-cache-warmup-scheduler-", CACHE_WARMUP_POOL_SIZE, shutdownAwait);
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
            throw new IllegalArgumentException("Scheduler shutdown await duration must be between 1s and 2147483647s");
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
