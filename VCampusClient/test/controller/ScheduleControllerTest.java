package controller;

import dto.course.CoursePeriodDTO;
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
import javafx.scene.control.SpinnerValueFactory;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;
import model.course.ScheduleWeekView;
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
        explicitWeekLoadsScheduleAndNoticesInParallel();
        backToCurrentWeekAsksForTheWeekTheServerResolved();
        currentWeekButtonFollowsTheServerWeekRange();
        currentWeekButtonSitsRightOfTheSpinner();
        weekRangeComesFromTheServerInsteadOfAClientConstant();
        staleScheduleDataCannotReplaceNewerResult();
        requestTermsIgnoresStaleTermLoads();
        scheduleFailureIsDeliveredThroughFxExecutor();
        adjustmentBlocksCarryTheirOwnStyleBadgeAndDetail();
        weekSpinnerAcceptsTypedWeekNumbers();
        weekSpinnerArrowsRunBackwards();
        typedWeekTextIsClampedToTheSpinnerBounds();
        periodRowsComeFromTheDictionary();
        gappedPeriodDictionarySnapsToTheNextRow();
        theViewDeclaresNoColumnGeometry();
        System.out.println("ScheduleControllerTest: PASS");
    }

    /**
     * 明确给了周次时，课表与通知必须**并行**发出：通知查哪一周是已知的，让它等课表的往返等于把
     * 一次页面加载的延迟翻倍。用可控 future 门控——课表还挂着没回来时通知就已经请求了；串行实现
     * （先等课表再发通知）在这一刻 {@code lastNoticeWeek} 必然是 null。
     */
    private static void explicitWeekLoadsScheduleAndNoticesInParallel() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<ScheduleController.ScheduleData> rendered = new AtomicReference<>();

        CompletableFuture<ScheduleWeekView> schedule = new CompletableFuture<>();
        service.scheduleResults.addLast(schedule);
        service.noticeResults.addLast(CompletableFuture.completedFuture(List.of(
                new CourseNoticeView("2026-2027 秋学期", 3, "调课", "内容"))));
        controller.requestScheduleData(TERM, 3, rendered::set, error -> { });

        require(Integer.valueOf(3).equals(service.lastNoticeWeek.get()),
                "明确周次时通知必须与课表并行发出，实际 lastNoticeWeek="
                        + service.lastNoticeWeek.get());
        require(rendered.get() == null,
                "两个请求都回来之前不得渲染半张表");

        schedule.complete(week(3, List.of(entry(1001L, "数据结构"))));
        require(rendered.get() != null && rendered.get().getSchedule().getWeek() == 3
                        && rendered.get().getNotices().size() == 1,
                "课表回来之后两半必须合成一次渲染");
    }

    /**
     * 「回到本周」= 请求里不带周次，周次由服务端按教学日历与系统时钟决定。调课通知是按周发表的，
     * 因此它必须等课表回来、用服务端解析出的那一周去查，而不是客户端自己猜一个周号。
     */
    private static void backToCurrentWeekAsksForTheWeekTheServerResolved() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<ScheduleController.ScheduleData> rendered = new AtomicReference<>();

        service.scheduleResults.addLast(CompletableFuture.completedFuture(
                week(8, List.of(entry(1001L, "数据结构")))));
        service.noticeResults.addLast(CompletableFuture.completedFuture(List.of(
                new CourseNoticeView("2026-2027 秋学期", 8, "调课", "内容"))));
        controller.requestScheduleData(TERM, null, rendered::set, error -> { });

        require(service.lastScheduleWeek.get() == null,
                "“回到本周”不得带上周次，实际 " + service.lastScheduleWeek.get());
        require(Integer.valueOf(8).equals(service.lastNoticeWeek.get()),
                "通知必须按服务端解析出的那一周去查，实际 " + service.lastNoticeWeek.get());
        require(rendered.get() != null && rendered.get().getSchedule().getWeek() == 8
                        && rendered.get().getNotices().size() == 1,
                "解析出的周与它的通知必须一起交给渲染");
    }

    /**
     * 「回到本周」的可用性只由服务端响应决定：没有响应（未加载/加载中/加载失败）或今天不在学期内
     * （{@code currentWeek} 为 null）时不可用，与教师端 {@code canGoCurrent()} 同义。
     */
    private static void currentWeekButtonFollowsTheServerWeekRange() {
        require(!ScheduleController.canGoCurrent(null),
                "还没有响应时“回到本周”必须禁用");
        require(!ScheduleController.canGoCurrent(
                        new ScheduleWeekView(3, List.of(), List.of(), List.of())),
                "服务端没有给出当前周（今天不在学期的教学周内）时必须禁用");
        require(ScheduleController.canGoCurrent(week(3, List.of())),
                "服务端给出当前周后“回到本周”必须可用");
    }

    /** 按钮紧跟在周次控件右侧、且仍在撑开的 Region 之前（「刷新」继续留在最右）。 */
    private static void currentWeekButtonSitsRightOfTheSpinner() throws Exception {
        String view = readResource("/resources/fxml/ScheduleView.fxml");
        int spinner = view.indexOf("fx:id=\"weekSpinner\"");
        int button = view.indexOf("fx:id=\"currentWeekButton\"");
        require(spinner >= 0 && button > spinner,
                "「回到本周」必须紧跟周次控件之后，实际 spinner=" + spinner + " button=" + button);
        String buttonTag = view.substring(view.lastIndexOf('<', button), view.indexOf('>', button));
        require(buttonTag.contains("text=\"回到本周\"")
                        && buttonTag.contains("onAction=\"#handleBackToCurrentWeek\""),
                "按钮文案与动作必须就位：" + buttonTag);
        int grower = view.indexOf("HBox.hgrow=\"ALWAYS\"");
        require(grower > button,
                "按钮必须在撑开的 Region 之前，否则会被推到工具栏最右而不是周次控件旁边");
    }

    /**
     * 周范围不再由客户端写死：控件范围与初值都来自服务端响应（1..20 的硬编码随本需求删除）。
     * 这一条是源码级断言——真控件要起 JavaFX 工具包才能建，而本套件是无工具包运行的。
     */
    private static void weekRangeComesFromTheServerInsteadOfAClientConstant() throws Exception {
        String source = readResource("/controller/ScheduleController.java");
        require(!source.contains("valueFactory(1, 20, 3)"),
                "周次控件的范围必须来自服务端，不得再写死 (1, 20, 3)");
        require(source.contains("getMinWeek()") && source.contains("getMaxWeek()")
                        && source.contains("getCurrentWeek()"),
                "周范围与当前周必须读取响应里的三个字段");
    }

    /**
     * 节次字典有缺口时（一天 1、2、4 节），落在缺口里的第 3 节必须吸附到第 3 行（第 4 节那行），
     * 比所有节次都大时夹到表尾——两条都绝不返回 {@code -1}：负行号会被 JavaFX 的
     * {@code GridPane.add(node, column, row)} 拒绝，整张表画不出来。规则与教师端 `:538-544` 同构。
     */
    private static void gappedPeriodDictionarySnapsToTheNextRow() {
        List<Integer> gapped = List.of(1, 2, 4);
        require(ScheduleController.rowIndexOf(gapped, 3) == 3,
                "缺口里的第 3 节必须吸附到第 4 节那一行（行号 3），实际 "
                        + ScheduleController.rowIndexOf(gapped, 3));
        require(ScheduleController.rowIndexOf(gapped, 5) == 3,
                "比所有节次都大时必须夹到表尾而不是负行号，实际 "
                        + ScheduleController.rowIndexOf(gapped, 5));
        require(ScheduleController.rowIndexOf(gapped, 1) == 1
                        && ScheduleController.rowIndexOf(List.of(), 5) == 0,
                "第 1 节仍在第 1 行；空字典只可能是表头行 0");
    }

    /**
     * 列几何与行一样不能写死在视图里：{@code ScheduleView.fxml} 里一条列约束都不许有，列必须由
     * {@code renderSchedule} 按教学日数量重建（教师端的 FXML 同样是裸 {@code GridPane}）。
     * 这里只钉视图这一半——控制器那一半要真实 JavaFX 工具包才能断言。
     */
    private static void theViewDeclaresNoColumnGeometry() throws Exception {
        String view = readResource("/resources/fxml/ScheduleView.fxml");
        require(!view.contains("ColumnConstraints") && !view.contains("<columnConstraints"),
                "视图不得声明列约束：写死的列数（1 条节次列 + 5 条日期列）与『星期列也由服务端驱动』"
                        + "相矛盾，实际视图里仍然写着 " + view);
    }

    /**
     * 节次行来自服务端教学日历的节次字典，而不是客户端常量：同一节次跨多个教学日只占一行，
     * 没有字典时一行都不画（旧实现恒定 13 行，正是缺陷 1 的客户端一半）。
     */
    static void periodRowsComeFromTheDictionary() {
        List<Integer> rows = ScheduleController.periodNumbers(List.of(
                new CoursePeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00"),
                new CoursePeriodDTO("2026-09-14", 2, "08:55:00", "09:40:00"),
                new CoursePeriodDTO("2026-09-15", 1, "08:00:00", "08:45:00"),
                new CoursePeriodDTO("2026-09-15", 2, "08:55:00", "09:40:00")));
        require(rows.equals(List.of(1, 2)),
                "两天的同一节次必须并成一行，实际 " + rows);
        require(ScheduleController.periodNumbers(List.of()).isEmpty(),
                "没有节次字典时不得回退到硬编码 13 行");
        require(ScheduleController.periodHeader(3,
                        new CoursePeriodDTO("2026-09-14", 3, "10:00:00", "10:45:00"))
                        .equals("第 3 节 10:00:00-10:45:00"),
                "节次行头必须带上定宽时刻");
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

    /**
     * 箭头方向与学生端旧控件相反（R4）：向上箭头（{@code increment}，也是键盘 ↑）退一周，
     * 向下箭头（{@code decrement}，也是键盘 ↓）进一周；到边也一样，不绕回另一头。
     *
     * <p>箭头按钮与键盘 ↑/↓ 最后都调用 value factory 的 {@code increment}/{@code decrement}
     * （{@code SpinnerBehavior} 与箭头按钮都走 {@code Spinner.increment/decrement}），因此这里直接
     * 断言那条共用路径；手输数字的语义不变（{@link #typedWeekTextIsClampedToTheSpinnerBounds}）。
     */
    private static void weekSpinnerArrowsRunBackwards() {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = WeekSpinner.valueFactory(1, 20, 8);

        factory.increment(1);
        require(factory.getValue() == 7,
                "the up arrow must step one week back, saw " + factory.getValue());
        factory.decrement(1);
        require(factory.getValue() == 8,
                "the down arrow must step one week forward, saw " + factory.getValue());
        factory.decrement(2);
        require(factory.getValue() == 10,
                "the down arrow must step by the number of steps, saw " + factory.getValue());

        factory.setValue(20);
        factory.decrement(1);
        require(factory.getValue() == 20,
                "the last week must not walk past maxWeek, saw " + factory.getValue());
        factory.setValue(1);
        factory.increment(1);
        require(factory.getValue() == 1,
                "the first week must not walk before minWeek, saw " + factory.getValue());
    }

    /** 输入框文本 → 周次：空/非数字不改动，越界夹取到 1..20（边界与 Spinner 同一个来源）。 */
    private static void typedWeekTextIsClampedToTheSpinnerBounds() {
        require(WeekSpinner.commitWeek("7", 3, 1, 20) == 7,
                "a typed week must be committed");
        require(WeekSpinner.commitWeek(" 7 ", 3, 1, 20) == 7,
                "surrounding blanks must be tolerated");
        require(WeekSpinner.commitWeek("3", 3, 1, 20) == 3,
                "an identical week must stay untouched so no reload is triggered");
        require(WeekSpinner.commitWeek("", 3, 1, 20) == 3
                        && WeekSpinner.commitWeek(null, 3, 1, 20) == 3,
                "an emptied editor must leave the week untouched");
        require(WeekSpinner.commitWeek("abc", 3, 1, 20) == 3,
                "non-numeric text must leave the week untouched");
        require(WeekSpinner.commitWeek("0", 3, 1, 20) == 1
                        && WeekSpinner.commitWeek("99", 3, 1, 20) == 20,
                "out-of-range input must be clamped to the spinner bounds");
        require(WeekSpinner.commitWeek("99999999999999", 3, 1, 20) == 20,
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

        service.scheduleResults.addLast(CompletableFuture.completedFuture(week(3, List.of(
                entry(1001L, "数据结构")))));
        service.noticeResults.addLast(CompletableFuture.completedFuture(List.of(
                new CourseNoticeView("2026-2027 秋学期", 3, "调课", "内容"))));
        controller.requestScheduleData(TERM, 3, rendered::set, error -> { });

        require(TERM.equals(service.lastTerm.get()), "server term must reach the service");
        require(Integer.valueOf(3).equals(service.lastScheduleWeek.get()),
                "an explicit week must reach the schedule service");
        require(Integer.valueOf(3).equals(service.lastNoticeWeek.get()),
                "notices must be queried for the same explicit week");
        require(rendered.get() != null && rendered.get().getSchedule().getEntries().size() == 1
                        && rendered.get().getNotices().size() == 1,
                "schedule and notice results must be combined");
        require("数据结构".equals(
                        rendered.get().getSchedule().getEntries().get(0).getCourseName()),
                "schedule entry must be preserved");
    }

    private static void staleScheduleDataCannotReplaceNewerResult() {
        ControlledCourseService service = new ControlledCourseService();
        ScheduleController controller = new ScheduleController(
                service, (title, message) -> { }, (title, message) -> { }, Runnable::run);
        AtomicReference<String> rendered = new AtomicReference<>();

        CompletableFuture<ScheduleWeekView> olderSchedule = new CompletableFuture<>();
        CompletableFuture<List<CourseNoticeView>> olderNotices = new CompletableFuture<>();
        CompletableFuture<ScheduleWeekView> newerSchedule = new CompletableFuture<>();
        CompletableFuture<List<CourseNoticeView>> newerNotices = new CompletableFuture<>();
        service.scheduleResults.addLast(olderSchedule);
        service.noticeResults.addLast(olderNotices);
        service.scheduleResults.addLast(newerSchedule);
        service.noticeResults.addLast(newerNotices);

        controller.requestScheduleData(TERM, 1, data -> rendered.set(
                data.getSchedule().getEntries().get(0).getCourseName()), error -> { });
        controller.requestScheduleData(TERM, 2, data -> rendered.set(
                data.getSchedule().getEntries().get(0).getCourseName()), error -> { });

        newerSchedule.complete(week(2, List.of(entry(2001L, "最新课表"))));
        newerNotices.complete(Collections.emptyList());
        olderSchedule.complete(week(1, List.of(entry(1001L, "过期课表"))));
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

        CompletableFuture<ScheduleWeekView> schedule = new CompletableFuture<>();
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

    /**
     * 只装课次的一周：这些用例只读取回的数据、不画网格，几何由其他的渲染用例负责。周范围照服务端
     * 的形状给（教学周 1..16，当前周 8），因此“回到本周”在这些夹具里是可用状态。
     */
    private static ScheduleWeekView week(int week, List<ScheduleEntryView> entries) {
        return new ScheduleWeekView(week, 1, 16, 8, List.of(), List.of(), entries);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<ScheduleWeekView>> scheduleResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<CourseNoticeView>>> noticeResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<CourseTermView>>> termResults =
                new ArrayDeque<>();
        private final AtomicReference<CourseTermView> lastTerm = new AtomicReference<>();
        private final AtomicReference<Integer> lastScheduleWeek = new AtomicReference<>();
        private final AtomicReference<Integer> lastNoticeWeek = new AtomicReference<>();
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

        @Override public CompletableFuture<ScheduleWeekView> loadSchedule(
                CourseTermView term, Integer week) {
            lastTerm.set(term);
            lastScheduleWeek.set(week);
            return scheduleResults.removeFirst();
        }

        @Override public CompletableFuture<List<CourseNoticeView>> loadNotices(
                CourseTermView term, int week) {
            lastTerm.set(term);
            lastNoticeWeek.set(week);
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
