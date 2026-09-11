package service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CourseWaitlistScheduler implements AutoCloseable {
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);
    private static final int DEFAULT_BATCH_LIMIT = 100;

    private final CourseWaitlistService service;
    private final Clock clock;
    private final Duration interval;
    private final int batchLimit;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CourseWaitlistScheduler(CourseWaitlistService service) {
        this(service, Clock.systemUTC(), DEFAULT_INTERVAL, DEFAULT_BATCH_LIMIT);
    }

    CourseWaitlistScheduler(CourseWaitlistService service, Clock clock,
                            Duration interval, int batchLimit) {
        if (service == null || clock == null || interval == null) {
            throw new IllegalArgumentException("scheduler dependencies must not be null");
        }
        if (interval.isZero() || interval.isNegative() || batchLimit <= 0) {
            throw new IllegalArgumentException("scheduler interval and batch limit must be positive");
        }
        this.service = service;
        this.clock = clock;
        this.interval = interval;
        this.batchLimit = batchLimit;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "course-waitlist-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        executor.scheduleAtFixedRate(() -> runOnce(clock.instant()), 0,
                interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void runOnce(Instant now) {
        try {
            service.expireOverdueOffers(now, batchLimit);
        } catch (RuntimeException failure) {
            System.err.println("候补超时扫描失败: " + failure.getMessage());
        }
        try {
            service.advanceOpenVacancies(now, batchLimit);
        } catch (RuntimeException failure) {
            System.err.println("候补空位扫描失败: " + failure.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }
}
