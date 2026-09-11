package controller;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import service.CoursePushListener;
import service.CourseService;
import service.CourseSubscription;

public final class ScheduleControllerTest {
    private static final CourseTermView TERM = new CourseTermView(2026, 1, "2026-2027 秋学期");
    private static final CourseTermView OTHER_TERM =
            new CourseTermView(2025, 2, "2025-2026 春学期");

    public static void main(String[] args) {
        requestScheduleDataSendsServerTermAndWeek();
        staleScheduleDataCannotReplaceNewerResult();
        requestTermsIgnoresStaleTermLoads();
        scheduleFailureIsDeliveredThroughFxExecutor();
        System.out.println("ScheduleControllerTest: PASS");
    }

    private static void requestScheduleDataSendsServerTermAndWeek() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<ScheduleController.ScheduleData> rendered = new AtomicReference<>();

        service.scheduleResults.addLast(CompletableFuture.completedFuture(List.of(
                entry(1001L, "数据结构"))));
        service.noticeResults.addLast(CompletableFuture.completedFuture(List.of(
                new CourseNoticeView("2026-2027 秋学期", 3, "调课", "内容"))));
        controller.requestScheduleData(TERM, 3, rendered::set, error -> { });

        require(TERM.equals(service.lastTerm.get()), "server term must reach the service");
        require(Integer.valueOf(3).equals(service.lastWeek.get()), "week must reach the service");
        require(rendered.get() != null && rendered.get().getEntries().size() == 1
                        && rendered.get().getNotices().size() == 1,
                "schedule and notice results must be combined");
        require("数据结构".equals(rendered.get().getEntries().get(0).getCourseName()),
                "schedule entry must be preserved");
    }

    private static void staleScheduleDataCannotReplaceNewerResult() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<String> rendered = new AtomicReference<>();

        CompletableFuture<List<ScheduleEntryView>> olderSchedule = new CompletableFuture<>();
        CompletableFuture<List<CourseNoticeView>> olderNotices = new CompletableFuture<>();
        CompletableFuture<List<ScheduleEntryView>> newerSchedule = new CompletableFuture<>();
        CompletableFuture<List<CourseNoticeView>> newerNotices = new CompletableFuture<>();
        service.scheduleResults.addLast(olderSchedule);
        service.noticeResults.addLast(olderNotices);
        service.scheduleResults.addLast(newerSchedule);
        service.noticeResults.addLast(newerNotices);

        controller.requestScheduleData(TERM, 1,
                data -> rendered.set(data.getEntries().get(0).getCourseName()), error -> { });
        controller.requestScheduleData(TERM, 2,
                data -> rendered.set(data.getEntries().get(0).getCourseName()), error -> { });

        newerSchedule.complete(List.of(entry(2001L, "最新课表")));
        newerNotices.complete(Collections.emptyList());
        olderSchedule.complete(List.of(entry(1001L, "过期课表")));
        olderNotices.complete(Collections.emptyList());

        require("最新课表".equals(rendered.get()),
                "stale schedule data must not replace the latest result");
    }

    private static void requestTermsIgnoresStaleTermLoads() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<List<CourseTermView>> rendered = new AtomicReference<>();

        CompletableFuture<List<CourseTermView>> older = new CompletableFuture<>();
        CompletableFuture<List<CourseTermView>> latest = new CompletableFuture<>();
        service.termResults.addLast(older);
        service.termResults.addLast(latest);
        controller.requestTerms(rendered::set, error -> { });
        controller.requestTerms(rendered::set, error -> { });

        latest.complete(List.of(TERM));
        older.complete(List.of(OTHER_TERM));

        require(rendered.get().size() == 1 && TERM.equals(rendered.get().get(0)),
                "stale term load must not replace the latest server terms");
    }

    private static void scheduleFailureIsDeliveredThroughFxExecutor() {
        ControlledCourseService service = new ControlledCourseService();
        Deque<Runnable> fxActions = new ArrayDeque<>();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, fxActions::addLast);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CompletableFuture<List<ScheduleEntryView>> schedule = new CompletableFuture<>();
        service.scheduleResults.addLast(schedule);
        service.noticeResults.addLast(CompletableFuture.completedFuture(Collections.emptyList()));
        controller.requestScheduleData(TERM, 1, data -> { }, failure::set);
        schedule.completeExceptionally(new IllegalStateException("课表加载失败"));

        require(failure.get() == null,
                "failure must not mutate view state before the FX executor runs");
        fxActions.removeFirst().run();
        require(failure.get() != null
                        && String.valueOf(failure.get().getMessage()).contains("课表加载失败"),
                "failure must be delivered through the FX executor");
    }

    private static ScheduleEntryView entry(long offeringId, String name) {
        return new ScheduleEntryView(offeringId, "2026-2027 秋学期", "C" + offeringId,
                name, "教师", "教室", 1, 1, 2, 1, 16);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<List<ScheduleEntryView>>> scheduleResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<CourseNoticeView>>> noticeResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<CourseTermView>>> termResults =
                new ArrayDeque<>();
        private final AtomicReference<CourseTermView> lastTerm = new AtomicReference<>();
        private final AtomicReference<Integer> lastWeek = new AtomicReference<>();
        private final AtomicInteger ignores = new AtomicInteger();

        @Override public CompletableFuture<List<CourseTermView>> loadTerms() {
            return termResults.isEmpty()
                    ? CompletableFuture.completedFuture(List.of(TERM))
                    : termResults.removeFirst();
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
            ignores.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override public CourseSubscription subscribe(CoursePushListener listener) {
            return () -> { };
        }

        @Override public CompletableFuture<List<ScheduleEntryView>> loadSchedule(
                CourseTermView term, int week) {
            lastTerm.set(term);
            lastWeek.set(week);
            return scheduleResults.removeFirst();
        }

        @Override public CompletableFuture<List<CourseNoticeView>> loadNotices(
                CourseTermView term, int week) {
            lastTerm.set(term);
            lastWeek.set(week);
            return noticeResults.removeFirst();
        }

        @Override public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
