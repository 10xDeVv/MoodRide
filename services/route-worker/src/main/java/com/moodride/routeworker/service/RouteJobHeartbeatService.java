package com.moodride.routeworker.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RouteJobHeartbeatService {
    private static final Logger logger = LoggerFactory.getLogger(RouteJobHeartbeatService.class);
    private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(100);
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(5);

    private final RouteJobLifecycleService lifecycleService;
    private final ScheduledExecutorService scheduler;
    private final Duration heartbeatInterval;
    private final Set<ManagedHeartbeat> activeHeartbeats = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private boolean running = true;

    @Autowired
    public RouteJobHeartbeatService(
        RouteJobLifecycleService lifecycleService,
        @Value("${route.generation.timeout.seconds:30}") int leaseSeconds,
        @Value("${moodride.kafka.listener.concurrency:3}") int workerConcurrency
    ) {
        this(
            lifecycleService,
            createScheduler(Math.max(1, workerConcurrency)),
            deriveHeartbeatInterval(leaseSeconds)
        );
    }

    RouteJobHeartbeatService(
        RouteJobLifecycleService lifecycleService,
        ScheduledExecutorService scheduler,
        Duration heartbeatInterval
    ) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.heartbeatInterval = requirePositive(heartbeatInterval);
    }

    public Heartbeat start(UUID jobId, UUID leaseToken) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(leaseToken, "leaseToken");

        synchronized (lifecycleMonitor) {
            if (!running) {
                throw new IllegalStateException("Route job heartbeat scheduler is shutting down");
            }

            ManagedHeartbeat heartbeat = new ManagedHeartbeat(jobId, leaseToken);
            activeHeartbeats.add(heartbeat);
            try {
                ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    heartbeat::renew,
                    0L,
                    heartbeatInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                );
                heartbeat.attach(future);
                return heartbeat;
            } catch (RuntimeException exception) {
                heartbeat.close();
                throw exception;
            }
        }
    }

    @PreDestroy
    void shutdown() {
        List<ManagedHeartbeat> heartbeats;
        synchronized (lifecycleMonitor) {
            if (!running) {
                return;
            }
            running = false;
            heartbeats = List.copyOf(activeHeartbeats);
        }

        heartbeats.forEach(ManagedHeartbeat::close);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledExecutorService createScheduler(int threadCount) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            threadCount,
            new HeartbeatThreadFactory()
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static Duration deriveHeartbeatInterval(int leaseSeconds) {
        long leaseMillis = Duration.ofSeconds(Math.max(1, leaseSeconds)).toMillis();
        return Duration.ofMillis(Math.max(MINIMUM_INTERVAL.toMillis(), leaseMillis / 3L));
    }

    private static Duration requirePositive(Duration interval) {
        Objects.requireNonNull(interval, "heartbeatInterval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        }
        return interval;
    }

    public interface Heartbeat extends AutoCloseable {
        void requireActive();

        @Override
        void close();
    }

    private final class ManagedHeartbeat implements Heartbeat {
        private final UUID jobId;
        private final UUID leaseToken;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<LeaseLostException> leaseLoss = new AtomicReference<>();
        private volatile ScheduledFuture<?> future;

        private ManagedHeartbeat(UUID jobId, UUID leaseToken) {
            this.jobId = jobId;
            this.leaseToken = leaseToken;
        }

        private void attach(ScheduledFuture<?> scheduledFuture) {
            future = scheduledFuture;
            if (stopped.get()) {
                scheduledFuture.cancel(false);
            }
        }

        private void renew() {
            if (stopped.get()) {
                return;
            }
            try {
                lifecycleService.renewLease(jobId, leaseToken);
            } catch (LeaseLostException exception) {
                leaseLoss.compareAndSet(null, exception);
                logger.info("Route job {} heartbeat stopped after lease loss", jobId);
                close();
            } catch (RuntimeException exception) {
                logger.warn("Route job {} heartbeat renewal failed: {}", jobId, exception.getMessage());
            }
        }

        @Override
        public void requireActive() {
            LeaseLostException exception = leaseLoss.get();
            if (exception != null) {
                throw exception;
            }
        }

        @Override
        public void close() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            activeHeartbeats.remove(this);
            ScheduledFuture<?> scheduledFuture = future;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }
    }

    private static final class HeartbeatThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "route-job-heartbeat-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
