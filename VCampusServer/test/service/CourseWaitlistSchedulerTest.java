package service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CourseWaitlistSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

    private CourseWaitlistSchedulerTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyRunOnceContainsJobFailure();
        verifyLifecycleIsIdempotentAndRunsImmediately();
        System.out.println("Course waitlist scheduler test passed.");
    }

    private static void verifyRunOnceContainsJobFailure() {
        FakeWaitlistService service = new FakeWaitlistService();
        service.failExpiryOnce = true;
        CourseWaitlistScheduler scheduler = new CourseWaitlistScheduler(service,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), 25);

        scheduler.runOnce(NOW);
        scheduler.runOnce(NOW.plusSeconds(1));

        require(service.expiryRuns.get() == 2,
                "a failed expiry job must not prevent a later cycle");
        require(service.vacancyRuns.get() == 2,
                "one job failure must not skip the other bounded job");
        require(service.lastLimit == 25, "scheduler must use the configured batch bound");
        scheduler.close();
    }

    private static void verifyLifecycleIsIdempotentAndRunsImmediately() throws Exception {
        FakeWaitlistService service = new FakeWaitlistService();
        CourseWaitlistScheduler scheduler = new CourseWaitlistScheduler(service,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), 10);

        scheduler.start();
        scheduler.start();
        require(service.firstRun.await(3, TimeUnit.SECONDS),
                "start must schedule an immediate overdue scan");
        scheduler.close();
        scheduler.close();

        require(service.expiryRuns.get() == 1,
                "idempotent start must not create a second scheduler loop");
        require(service.vacancyRuns.get() == 1,
                "the immediate cycle must include vacancy work");
    }

    private static final class FakeWaitlistService extends CourseWaitlistService {
        private final AtomicInteger expiryRuns = new AtomicInteger();
        private final AtomicInteger vacancyRuns = new AtomicInteger();
        private final CountDownLatch firstRun = new CountDownLatch(1);
        private volatile boolean failExpiryOnce;
        private volatile int lastLimit;

        @Override
        int expireOverdueOffers(Instant now, int limit) {
            lastLimit = limit;
            expiryRuns.incrementAndGet();
            if (failExpiryOnce) {
                failExpiryOnce = false;
                throw new IllegalStateException("expected test failure");
            }
            return 0;
        }

        @Override
        int advanceOpenVacancies(Instant now, int limit) {
            lastLimit = limit;
            vacancyRuns.incrementAndGet();
            firstRun.countDown();
            return 0;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
