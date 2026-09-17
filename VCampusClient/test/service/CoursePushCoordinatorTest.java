package service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CoursePushEventType;
import model.course.CoursePushEventView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleWeekView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;

public final class CoursePushCoordinatorTest {
    private static final CourseTermView TERM = new CourseTermView(2026, 1, "2026-2027 秋学期");
    private static final CourseTermView OTHER_TERM =
            new CourseTermView(2025, 2, "2025-2026 春学期");
    private static final Instant NOW = Instant.parse("2026-09-10T01:00:00Z");

    public static void main(String[] args) {
        acksBeforeNotifyingAndCoalescesDuplicates();
        failedAckIsRetriedOnRedeliveryOnly();
        unacknowledgedEventIsNotNotifiedOrCompleted();
        refreshFailureRetriesLocallyWithCappedBackoff();
        refreshBackoffIsCapped();
        completionRequiresAckAndRefresh();
        remainingTimeComesFromServerExpiresAt();
        termSwitchStopsLocalRetry();
        disposeCancelsRetryAndIsIdempotent();
        disposedCoordinatorIgnoresStaleCallbacks();
        reconnectRefreshesWithoutAckOrNotification();
        System.out.println("CoursePushCoordinatorTest: PASS");
    }

    private static void acksBeforeNotifyingAndCoalescesDuplicates() {
        Harness harness = new Harness();
        harness.ackFutures.addLast(new CompletableFuture<>());
        harness.deliver("7");

        require(harness.acks.size() == 1, "first delivery must start exactly one ACK");
        require(harness.notifications.get() == 0, "notification must wait for ACK success");
        require(harness.refreshes.get() == 0, "refresh must wait for ACK success");

        harness.deliver("7");
        require(harness.acks.size() == 1, "duplicate while ACK pending must coalesce");

        harness.acks.getLast().complete(null);
        require(harness.notifications.get() == 1, "ACK success must show one notification");
        require(harness.refreshes.get() == 1, "ACK success must start the authoritative refresh");

        harness.deliver("7");
        require(harness.acks.size() == 1, "duplicate after ACK must not re-ACK");
        require(harness.notifications.get() == 1, "duplicate must not repeat the notification");
    }

    private static void failedAckIsRetriedOnRedeliveryOnly() {
        Harness harness = new Harness();
        harness.ackFutures.addLast(new CompletableFuture<>());
        harness.deliver("8");
        harness.acks.getLast().completeExceptionally(new IllegalStateException("ack failed"));

        require(harness.notifications.get() == 0, "failed ACK must not show a notification");
        require(harness.refreshes.get() == 0, "failed ACK must not refresh");
        require(harness.scheduler.pending().isEmpty(),
                "failed ACK must not schedule a blind local retry");

        harness.ackFutures.addLast(new CompletableFuture<>());
        harness.deliver("8");
        require(harness.acks.size() == 2, "redelivery after ACK failure must retry the ACK");
        harness.acks.getLast().complete(null);
        require(harness.notifications.get() == 1, "the retried ACK must unlock the notification");
    }

    private static void unacknowledgedEventIsNotNotifiedOrCompleted() {
        Harness harness = new Harness();
        CompletableFuture<Void> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new SocketCourseService.CourseServiceException(
                protocol.MessageCode.CONFLICT, "课程事件未被确认"));
        harness.ackFutures.addLast(rejected);
        harness.deliver("17");

        require(harness.notifications.get() == 0,
                "an unacknowledged event must not notify the user");
        require(harness.refreshes.get() == 0,
                "an unacknowledged event must not start the authoritative refresh");

        harness.ackFutures.addLast(new CompletableFuture<>());
        harness.deliver("17");
        require(harness.acks.size() == 2,
                "an unacknowledged event must be ACKed again on redelivery");
        harness.acks.getLast().complete(null);
        require(harness.notifications.get() == 1,
                "only a truly acknowledged event may notify");
    }

    private static void refreshFailureRetriesLocallyWithCappedBackoff() {
        Harness harness = new Harness();
        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.deliver("9");
        harness.acks.getLast().complete(null);

        require(harness.notifications.get() == 1, "notification still happens after ACK");
        require(harness.refreshes.get() == 1, "first refresh must run after ACK");
        harness.failLastRefresh();

        require(!harness.scheduler.pending().isEmpty(), "refresh failure must schedule a retry");
        long firstDelay = harness.scheduler.pending().get(0).delayMillis;
        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.scheduler.runNext();
        harness.failLastRefresh();
        long secondDelay = harness.scheduler.pending().get(0).delayMillis;

        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.scheduler.runNext();
        harness.lastRefresh.get().complete(null);

        require(harness.refreshes.get() == 3, "each retry must re-run the refresh");
        require(firstDelay == 500L, "first retry delay must be 500ms");
        require(secondDelay == 1000L, "second retry delay must double to 1000ms");
        require(harness.scheduler.pending().isEmpty(),
                "successful refresh must stop further retries");
    }

    private static void refreshBackoffIsCapped() {
        Harness harness = new Harness();
        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.deliver("10");
        harness.acks.getLast().complete(null);
        harness.failLastRefresh();

        long lastDelay = -1L;
        for (int attempt = 0; attempt < 8; attempt++) {
            lastDelay = harness.scheduler.pending().get(0).delayMillis;
            harness.refreshFutures.addLast(new CompletableFuture<>());
            harness.scheduler.runNext();
            harness.failLastRefresh();
        }
        require(lastDelay == 8000L, "retry delay must be capped at 8000ms");
    }

    private static void completionRequiresAckAndRefresh() {
        Harness harness = new Harness();
        CompletableFuture<Void> refresh = new CompletableFuture<>();
        harness.refreshFutures.addLast(refresh);
        harness.deliver("11");
        harness.acks.getLast().complete(null);

        harness.deliver("11");
        require(harness.acks.size() == 1,
                "event with ACK done but refresh pending must still coalesce");

        refresh.complete(null);
        harness.deliver("11");
        require(harness.acks.size() == 1, "completed event must not be ACKed again");
        require(harness.notifications.get() == 1, "completed event must not notify again");
    }

    private static void remainingTimeComesFromServerExpiresAt() {
        Harness harness = new Harness();
        harness.deliver("12", NOW.plus(Duration.ofMinutes(5)));

        require(harness.lastRemaining.get() != null, "notification must carry remaining time");
        require(harness.lastRemaining.get().equals(Duration.ofMinutes(5)),
                "remaining time must be measured from the server expiresAt");
    }

    private static void termSwitchStopsLocalRetry() {
        Harness harness = new Harness();
        harness.activeTerm.set(TERM);
        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.deliver("13");
        harness.acks.getLast().complete(null);
        harness.failLastRefresh();
        require(!harness.scheduler.pending().isEmpty(), "refresh failure must schedule a retry");

        harness.activeTerm.set(OTHER_TERM);
        harness.scheduler.runNext();
        require(harness.refreshes.get() == 1, "a term switch must stop local refresh retries");
        require(harness.scheduler.pending().isEmpty(),
                "no further retry may be scheduled after a term switch");
    }

    private static void disposeCancelsRetryAndIsIdempotent() {
        Harness harness = new Harness();
        harness.refreshFutures.addLast(new CompletableFuture<>());
        harness.deliver("14");
        harness.acks.getLast().complete(null);
        harness.failLastRefresh();
        Task retry = harness.scheduler.pending().get(0);

        harness.coordinator.close();
        harness.coordinator.close();
        require(harness.subscriptionClosed.get() >= 1, "disposal must close the subscription");
        require(retry.cancelled, "disposal must cancel the pending retry");

        harness.scheduler.runNext();
        require(harness.refreshes.get() == 1, "a cancelled retry must not run after disposal");
    }

    private static void disposedCoordinatorIgnoresStaleCallbacks() {
        Harness harness = new Harness();
        harness.ackFutures.addLast(new CompletableFuture<>());
        harness.deliver("15");
        CompletableFuture<Void> pendingAck = harness.acks.getLast();

        harness.coordinator.close();
        pendingAck.complete(null);
        require(harness.notifications.get() == 0,
                "a disposed coordinator must not deliver stale UI notifications");
        require(harness.refreshes.get() == 0,
                "a disposed coordinator must not start a stale refresh");

        harness.deliver("16");
        require(harness.acks.size() == 1, "a disposed coordinator must ignore new events");
    }

    private static void reconnectRefreshesWithoutAckOrNotification() {
        Harness harness = new Harness();
        harness.coordinator.onReconnect();
        require(harness.refreshes.get() == 1, "reconnect must trigger the authoritative refresh");
        require(harness.acks.isEmpty(), "reconnect must not ACK any event");
        require(harness.notifications.get() == 0, "reconnect must not notify");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Harness {
        private final StubService service = new StubService();
        private final AtomicInteger notifications = new AtomicInteger();
        private final AtomicInteger refreshes = new AtomicInteger();
        private final Deque<CompletableFuture<Void>> ackFutures = new ArrayDeque<>();
        private final Deque<CompletableFuture<Void>> refreshFutures = new ArrayDeque<>();
        private final Deque<CompletableFuture<Void>> acks = new ArrayDeque<>();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final AtomicReference<CourseTermView> activeTerm = new AtomicReference<>(TERM);
        private final AtomicInteger subscriptionClosed = new AtomicInteger();
        private final AtomicReference<Duration> lastRemaining = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<Void>> lastRefresh =
                new AtomicReference<>();
        private final CoursePushCoordinator coordinator;

        private Harness() {
            service.onAck = eventId -> {
                CompletableFuture<Void> future = ackFutures.isEmpty()
                        ? CompletableFuture.completedFuture(null) : ackFutures.removeFirst();
                acks.addLast(future);
                return future;
            };
            service.onSubscribe = listener -> () -> subscriptionClosed.incrementAndGet();
            coordinator = new CoursePushCoordinator(
                    service,
                    (event, remaining) -> {
                        notifications.incrementAndGet();
                        lastRemaining.set(remaining);
                    },
                    () -> {
                        refreshes.incrementAndGet();
                        CompletableFuture<Void> future = refreshFutures.isEmpty()
                                ? CompletableFuture.completedFuture(null)
                                : refreshFutures.removeFirst();
                        lastRefresh.set(future);
                        return future;
                    },
                    Runnable::run,
                    scheduler,
                    activeTerm::get,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            coordinator.start();
        }

        private void deliver(String eventId) {
            deliver(eventId, NOW.plus(Duration.ofMinutes(5)));
        }

        private void deliver(String eventId, Instant expiresAt) {
            coordinator.onCourseEvent(new CoursePushEventView(eventId,
                    CoursePushEventType.WAITLIST_OFFERED, TERM, 1001L,
                    NOW.toString(), expiresAt.toString(), "候补席位"));
        }

        private void failLastRefresh() {
            lastRefresh.get().completeExceptionally(new IllegalStateException("refresh failed"));
        }
    }

    private static final class ManualScheduler implements CoursePushCoordinator.RetryScheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public CoursePushCoordinator.RetryHandle schedule(Runnable task, long delayMillis) {
            Task scheduled = new Task(task, delayMillis);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        private List<Task> pending() {
            return tasks;
        }

        private void runNext() {
            Task next = tasks.remove(0);
            if (!next.cancelled) {
                next.runnable.run();
            }
        }
    }

    private static final class Task {
        private final Runnable runnable;
        private final long delayMillis;
        private boolean cancelled;

        private Task(Runnable runnable, long delayMillis) {
            this.runnable = runnable;
            this.delayMillis = delayMillis;
        }
    }

    private static final class StubService implements CourseService {
        private Function<String, CompletableFuture<Void>> onAck =
                eventId -> CompletableFuture.completedFuture(null);
        private Function<CoursePushListener, CourseSubscription> onSubscribe = listener -> () -> { };

        @Override public CompletableFuture<List<CourseTermView>> loadTerms() {
            return CompletableFuture.completedFuture(List.of(TERM));
        }

        @Override public CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
                CourseTermView term, long courseId) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
                CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> addToPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> removeFromPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> selectOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> joinWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> cancelWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
                CourseTermView term, long offeringId, String operationId,
                WaitlistDecision decision) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> dropOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> ackCourseEvent(String eventId) {
            return onAck.apply(eventId);
        }

        @Override public CourseSubscription subscribe(CoursePushListener listener) {
            return onSubscribe.apply(listener);
        }

        @Override public CompletableFuture<ScheduleWeekView> loadSchedule(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(
                    new ScheduleWeekView(1, List.of(), List.of(), List.of()));
        }

        @Override public CompletableFuture<List<CourseNoticeView>> loadNotices(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
