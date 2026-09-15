package controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import model.course.ScheduleDisplayKind;
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

    public static void main(String[] args) throws Exception {
        requestScheduleDataSendsServerTermAndWeek();
        staleScheduleDataCannotReplaceNewerResult();
        requestTermsIgnoresStaleTermLoads();
        scheduleFailureIsDeliveredThroughFxExecutor();
        adjustmentBlocksCarryTheirOwnStyleBadgeAndDetail();
        weekSpinnerAcceptsTypedWeekNumbers();
        typedWeekTextIsClampedToTheSpinnerBounds();
        System.out.println("ScheduleControllerTest: PASS");
    }

    /** 周次控件必须在视图里就可编辑，否则用户点进去也敲不进数字（输入约束由控制器再配置）。 */
    private static void weekSpinnerAcceptsTypedWeekNumbers() throws Exception {
        String view = readResource("/resources/fxml/ScheduleView.fxml");
        int spinnerId = view.indexOf("fx:id=\"weekSpinner\"");
        require(spinnerId >= 0, "the view must declare the week spinner: " + view);
        // 从标签开头取到标签结束，属性顺序换了也不影响这条断言
        String spinnerTag = view.substring(view.lastIndexOf('<', spinnerId),
                view.indexOf('>', spinnerId));
        require(spinnerTag.contains("editable=\"true\""),
                "the week spinner must accept typed input: " + spinnerTag);
    }

    /** 输入框文本 → 周次：空/非数字不改动，越界夹取到 1..20（边界与 Spinner 同一个来源）。 */
    private static void typedWeekTextIsClampedToTheSpinnerBounds() {
        require(ScheduleController.commitWeek("7", 3, 1, 20) == 7,
                "a typed week must be committed");
        require(ScheduleController.commitWeek(" 7 ", 3, 1, 20) == 7,
                "surrounding blanks must be tolerated");
        require(ScheduleController.commitWeek("3", 3, 1, 20) == 3,
                "an identical week must stay untouched so no reload is triggered");
        require(ScheduleController.commitWeek("", 3, 1, 20) == 3
                        && ScheduleController.commitWeek(null, 3, 1, 20) == 3,
                "an emptied editor must leave the week untouched");
        require(ScheduleController.commitWeek("abc", 3, 1, 20) == 3,
                "non-numeric text must leave the week untouched");
        require(ScheduleController.commitWeek("0", 3, 1, 20) == 1
                        && ScheduleController.commitWeek("99", 3, 1, 20) == 20,
                "out-of-range input must be clamped to the spinner bounds");
        require(ScheduleController.commitWeek("99999999999999", 3, 1, 20) == 20,
                "a number too large for int must clamp instead of throwing");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = ScheduleControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 两个位置读取同一份调课文案，因此从任一块打开详情都能看到完整说明。 */
    private static void adjustmentBlocksCarryTheirOwnStyleBadgeAndDetail() {
        ScheduleEntryView original = adjusted(ScheduleDisplayKind.ADJUSTED_ORIGINAL, 2, 1);
        ScheduleEntryView target = adjusted(ScheduleDisplayKind.ADJUSTED_TARGET, 5, 3);
        ScheduleEntryView plain = entry(2003L, "普通课程");

        require("course-adjusted-original".equals(
                        ScheduleController.adjustmentStyleClass(original))
                        && "course-adjusted-target".equals(
                        ScheduleController.adjustmentStyleClass(target))
                        && ScheduleController.adjustmentStyleClass(plain) == null,
                "only the adjusted halves must carry an adjustment style class");
        require("原安排".equals(ScheduleController.adjustmentBadge(original))
                        && "调课后".equals(ScheduleController.adjustmentBadge(target))
                        && ScheduleController.adjustmentBadge(plain) == null,
                "only the adjusted halves must carry a header badge");

        String plainDetail = ScheduleController.detailText(plain);
        require(!plainDetail.contains("原安排") && !plainDetail.contains("调课状态"),
                "a plain lesson must not gain adjustment lines: " + plainDetail);

        String detail = ScheduleController.detailText(target);
        String originalDetail = ScheduleController.detailText(original);
        require(detail.contains("调课状态：调课后")
                        && detail.contains("原安排：周二 第1-2节 Room")
                        && detail.contains("调整后：周五 第3-4节 Room B")
                        && detail.contains("调课原因：教师出差"),
                "the target detail must describe both arrangements: " + detail);
        require(originalDetail.contains("调课状态：原安排")
                        && originalDetail.contains("原安排：周二 第1-2节 Room")
                        && originalDetail.contains("调整后：周五 第3-4节 Room B")
                        && originalDetail.contains("调课原因：教师出差"),
                "the original detail must describe both arrangements too: " + originalDetail);
        require(originalDetail.contains("上课时间：周二 第 1-2 节")
                        && detail.contains("上课时间：周五 第 3-4 节"),
                "each block must report its own coordinates, observed " + originalDetail + " | "
                        + detail);
    }

    /** 调课对的两半属于同一个教学班，只有位置与展示角色不同。 */
    private static ScheduleEntryView adjusted(ScheduleDisplayKind kind, int day, int startPeriod) {
        return new ScheduleEntryView(2001L, "2026-2027 秋学期", "CS203", "数据结构", "张老师",
                "教四-201", day, startPeriod, 2, 1, 16, kind, "ADJ-2001", "周二 第1-2节 Room",
                "周五 第3-4节 Room B", "教师出差");
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
