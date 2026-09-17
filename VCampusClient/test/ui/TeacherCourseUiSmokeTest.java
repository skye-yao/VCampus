package ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import service.MockTeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台 GUI 冒烟：用真实 JavaFX 工具包装入真实教师外壳，由 {@link MockTeacherCourseService}
 * 驱动，走一遍“（进入即停）教学课程表（周视图 / 卡片详情 / 周导航 / 铺满与自适应）→ 教学班列表 →
 * 四 Tab 详情 → 退回列表 → 空名单教学班”的真实交互并逐步截图。
 *
 * <p>这里刻意不解析 XML 代替加载：断言全部打在真实节点上（表格行数、Tab 文本、占位符可见性、
 * 入口按钮的启用/禁用、课表网格的列数/行数/卡片数/几何、角标的宽与高），既能抓住只在加载期才暴露
 * 的 FXML 属性错误，也能证明 DOM 解析看不见的布局与绑定确实生效。任一步失败都以退出码 1 结束，
 * 不会被脚本当成通过。
 *
 * <p>用真实点击而不是直接调控制器：教学课程表入口是分阶段交付的产物（T4 曾禁用、T5 接通），
 * 只有真的点下去才能证明它现在是可用的；课次详情弹窗也必须由真实的卡片点击打开，才能验证它是
 * {@code WINDOW_MODAL} + {@code show()}（不阻塞）而不是挂住整个冒烟流程。
 */
public final class TeacherCourseUiSmokeTest {
    private static final String VIEW_PATH =
            "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final double WIDTH = 860.0;
    private static final double HEIGHT = 580.0;
    /** 每步之间等待界面落定的时间；Mock 的 Future 已完成，只需等待一个脉冲。 */
    private static final double SETTLE_MILLIS = 200.0;

    private static final String SPRING_OFFERING_WITH_ROSTER = "CS203-01";
    private static final String SPRING_OFFERING_WITHOUT_ROSTER = "CS301-01";
    private static final String LONG_NAME_FRAGMENT = "欧阳阿依古丽";
    /** 课次详情弹窗的 Stage 标题（= TeacherCourseDetailDialogController.TITLE）。 */
    private static final String DIALOG_TITLE = "课程详情";
    /** 调课表单弹窗的 Stage 标题（= TeacherAdjustmentDialogController.TITLE）。 */
    private static final String ADJUSTMENT_TITLE = "申请调课";
    /** Mock 周 8 的跨周原位置卡片对应的课程；点它打开详情再“查看教学班”。 */
    private static final String CROSS_WEEK_COURSE_NAME = "数据结构与算法基础";
    /** 跨周原位置（第 8 周周二）的教室，也就是那块灰显提示上仍然写着的原地点。 */
    private static final String CROSS_WEEK_LOCATION = "A-101";
    /** 跨周新位置（第 9 周周三）的教室：调课后那块是真实占用，地点必须是新的。 */
    private static final String CROSS_WEEK_NEW_LOCATION = "B-203";
    /** 跨周移动后两个块各自的样式类；普通课次一个都不带。 */
    private static final String ORIGINAL_BLOCK_CLASS = "teacher-schedule-adjusted-original";
    private static final String TARGET_BLOCK_CLASS = "teacher-schedule-adjusted-target";
    /** Mock 周 8 里唯一还能申请调课的课次（9203，未调整的周六第 12-13 节）。 */
    private static final String ADJUSTABLE_COURSE_NAME = "操作系统原理";
    private static final String CROSS_WEEK_TARGET_DATE = "2026-11-02";
    private static final String SAME_WEEK_TARGET_DATE = "2026-10-26";
    private static final String CONFLICT_MESSAGE_FRAGMENT = "任课教师在该时间已有其他课程";
    private static final String SUBMITTED_HINT_FRAGMENT = "调课申请已提交";
    private static final String WITHDRAWN_STATUS_FRAGMENT = "已撤销";
    /** 待撤销的 PENDING 夹具（MockTeacherCourseService 的 9405）。 */
    private static final String PENDING_REQUEST_ID = "9405";
    private static final String WITHDRAWN_REQUEST_ID = "9404";
    /** Mock 教学日历的周次范围与本周：周次控件（Spinner）的范围与值都来自这三个字段。 */
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 16;
    private static final int CURRENT_WEEK = 8;
    private static final String EMPTY_WEEK_TEXT = "本周没有课程";
    private static final int WEEK_EIGHT_CARDS = 4;
    private static final int WEEK_NINE_CARDS = 1;
    /** Mock 日历：16 周 × 6 个教学日；周 1 起自 2026-09-07。 */
    private static final int CALENDAR_CHOICES = 96;
    /** 调课原因上限 500 字符；长原因用例要证明多行文本完整保留。 */
    private static final String LONG_REASON =
            "教师因参加全国课程建设研讨会需要出差，随行还有两位助教；会议日程与本周课程冲突，"
            + "已与教学班学生代表协商改期，并确认目标教室在没有其他课程占用，"
            + "希望教务处审批后把本次课次调整到新的教学日，后续如有变动会第一时间重新提交申请。";
    /** T6 成绩夹具：CS203-01 草稿（每五人缺一个实验分）、CS301-01 空班、CS204-01 已驳回、CS352-01 待审核。 */
    private static final String GRADE_DRAFT_OFFERING = "CS203-01";
    /** 成绩教学班列表的学期：Mock 的 2025/3（春学期）里放着草稿与空班两个夹具。 */
    private static final int GRADE_TERM_YEAR = 2025;
    private static final int GRADE_TERM_SEMESTER = 3;
    private static final String GRADE_EMPTY_OFFERING = "CS301-01";
    private static final String GRADE_REJECTED_OFFERING = "CS204-01";
    private static final String GRADE_PENDING_OFFERING = "CS352-01";
    private static final String AUTUMN_TERM_LABEL = "2025-2026 秋学期";
    /** = TeacherGradeBookController.LEAVE_PROMPT_TEXT 的对话框标题与正文片段。 */
    private static final String LEAVE_PROMPT_TITLE = "未保存的成绩";
    private static final String LEAVE_PROMPT_FRAGMENT = "未保存的修改";
    /** T5 验证的导入区：三个入口的文案，以及异常明细弹窗的 FXML / 标题 / 提示片段。 */
    private static final String TEMPLATE_BUTTON_TEXT = "下载成绩模板";
    /** 成绩录入页的导出是「导出成绩」；教学班详情页的名单导出按钮仍叫「导出」（它导的是名单）。 */
    private static final String EXPORT_BUTTON_TEXT = "导出成绩";
    private static final String IMPORT_BUTTON_TEXT = "导入 Excel";
    private static final String IMPORT_FEEDBACK_VIEW =
            "/resources/fxml/TeacherGradeImportFeedback.fxml";
    private static final String IMPORT_FEEDBACK_TITLE = "导入异常明细";
    /**
     * 未绑定宿主时 {@code Feedback.render()} 走的那一句：真实流程里弹窗总是先 bind 到导入控制器，
     * 这里的加载守卫拿到的是没有预览、也没有宿主的空态。
     */
    private static final String IMPORT_FEEDBACK_HINT_TEXT = "异常行已全部解决，可以回到成绩表确认导入。";
    /** Mock 的被驳回批次审核意见与待审核批次号。 */
    private static final String REVIEW_COMMENT_FRAGMENT = "总分与平时分不一致";
    private static final String PENDING_SUBMISSION_ID = "9601";
    /** 成绩更正表单：资源路径、标题与「原分数」行的措辞（与控制器常量逐字一致）。 */
    private static final String CORRECTION_VIEW = "/resources/fxml/TeacherGradeCorrectionDialog.fxml";
    private static final String CORRECTION_TITLE = "申请更正成绩";
    private static final String CORRECTION_ORIGINAL_PREFIX = "　原分数 ";
    private static final String CORRECTION_PLACEHOLDER = "—";
    /** = TeacherGradeCorrectionDialogController.MAX_REASON_LENGTH：更正原因的上界（服务端同宽）。 */
    private static final int CORRECTION_MAX_REASON_LENGTH = 500;
    /**
     * 「我的申请」入口的文案。逐字等于 FXML 里的静态文案与
     * {@code TeacherCourseManagementController.APPLICATIONS_ENTRY_TEXT}（那两个都是包私有的，
     * 冒烟测试在 {@code ui} 包里读不到，因此这里各写一份并在下面用真实按钮文案断言）。
     */
    private static final String APPLICATIONS_ENTRY = "我的申请";
    /** = TeacherGradeBookController 的未录入占位符与单元格样式类，界面上必须真的画出来。 */
    private static final String PLACEHOLDER = "—";
    private static final String GRADE_CELL_ERROR_CLASS = "teacher-course-grade-cell-error";
    private static final String GRADE_CELL_DISABLED_CLASS = "teacher-course-grade-cell-disabled";
    private static final String[] FILES = {
            "offering-list.png", "detail-basic-info.png", "detail-roster-page1.png",
            "roster-page2.png", "detail-schedule.png", "detail-grades.png", "roster-empty.png",
            "schedule-entry.png", "schedule-week8.png", "schedule-card-detail.png",
            "schedule-week9.png", "schedule-empty-week.png",
            // T5 的四张主题截图（跨周 / 冲突 / 撤销 / 长原因）。
            "adjustment-dialog-cross-week.png", "adjustment-dialog-conflict.png",
            "adjustment-dialog-long-reason.png", "adjustment-applications-withdrawn.png",
            // T6 的八张成绩录入截图（列表 / 部分填写 / 非法值 / 灰列 / 未保存提示 / 确认提交 / 只读 / 驳回）。
            "grade-offering-list.png", "gradebook-partial.png", "gradebook-invalid-cell.png",
            "gradebook-disabled-column.png", "gradebook-unsaved-prompt.png",
            "gradebook-submit-confirm.png", "gradebook-read-only.png", "gradebook-rejected.png",
            // Excel 式录入的五张：单击即编辑（整段选中）/ Enter 下移 / Tab 右移 / Esc 还原 / 2×2 批量粘贴。
            "gradebook-cell-editing.png", "gradebook-navigate-enter.png",
            "gradebook-navigate-tab.png", "gradebook-esc-reverted.png",
            "gradebook-paste-block.png", "gradebook-inline-error.png",
            // T5 的导入证据：异常明细弹窗的真实加载（空态）。预览红框与异常姓名要有一份真实服务端
            // 预览才画得出来，那部分由无工具包的控制器用例与服务端 E2E 覆盖。
            "gradebook-import-feedback.png",
            // T4 追加的验收截图：跨周的原位置灰块与新位置、以及更正表单上的「原分数 / 拟修改」对照。
            "schedule-cross-week-original.png", "schedule-week9-target.png",
            "gradebook-correction-dialog.png"
    };
    private static final Path OUTPUT = Path.of(".codex-tmp", "teacher");

    static void prepareOutputDirectory() throws IOException {
        Files.createDirectories(OUTPUT);
        for (String file : FILES) {
            Files.deleteIfExists(OUTPUT.resolve(file));
        }
    }

    public static void main(String[] args) throws Exception {
        TeacherCourseServices.install(new MockTeacherCourseService());
        prepareOutputDirectory();
        Application.launch(SnapshotApplication.class, args);
    }

    /** 逐步驱动真实界面的快照应用。 */
    public static final class SnapshotApplication extends Application {
        private final List<Runnable> steps = new ArrayList<>();
        private Parent root;
        private Stage primaryStage;
        private int stepIndex;
        /** T4 的更正表单夹具：打开时装配，关闭时清空。 */
        private CorrectionFixture correctionTarget;
        /**
         * 装配完成时的窗口外框尺寸。{@code Scene} 是 860x580，而 {@code Stage} 的外框比它多出标题栏与
         * 边框，所以「还原成 860x580」必须还原到这两个实测值，不能拿场景尺寸去 {@code setWidth}——
         * 那样每还原一次场景就缩小一圈，后半程的截图会悄悄小于 860x580。
         */
        private double frameWidth;
        private double frameHeight;
        /** 确认框可能晚一个脉冲才建窗，重新排队的次数上限。 */
        private int leavePromptAttempts;

        @Override
        public void start(Stage stage) throws Exception {
            TeacherCourseUiPreview.requireMockService(TeacherCourseServices.current());
            var resource = getClass().getResource(VIEW_PATH);
            if (resource == null) {
                throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
            }
            this.primaryStage = stage;
            root = FXMLLoader.load(resource);
            stage.setTitle("教务管理系统 - 教师工作台冒烟");
            stage.setScene(new Scene(root, WIDTH, HEIGHT));
            stage.setResizable(false);
            stage.show();
            // 场景就是 860x580（Scene 的构造参数），下面两个是含边框的外框尺寸：后面的窗口缩放
            // 一律按外框来，还原时才回得到同一个场景尺寸。
            frameWidth = stage.getWidth();
            frameHeight = stage.getHeight();
            planSteps();
            advance();
        }

        // ---------------------------------------------------------------- 步骤编排

        /**
         * 顺序即语义：每一步都在前一步的界面落定之后运行，触发加载的动作与断言其结果的步骤分开，
         * 因此每个断言看到的都是加载完成后的界面。
         */
        private void planSteps() {
            // Task 3：装配完成就直接停“教学课程表”，不再先停那个只写着“已接入”的占位首页。
            // 这一步不打任何点击，读的就是工作台装配之后的界面。
            steps.add(() -> {
                requireWindowSize("进入工作台");
                require(effectivelyVisible(requireNode("#schedulePage", Parent.class, "课表子页")),
                        "进入工作台必须直接显示教学课程表");
                require(!requireNode("#homePanel", VBox.class, "首页提示区").isVisible(),
                        "进入工作台不得先停占位首页");
                require(entryButton("教学课程表").getStyleClass()
                                .contains("teacher-course-entry-active"),
                        "首屏的高亮必须落在教学课程表入口上，实际 "
                                + entryButton("教学课程表").getStyleClass());
                require(shownWeek() == CURRENT_WEEK,
                        "首屏应已经画出本周（第 " + CURRENT_WEEK + " 周），实际 " + shownWeek());
                snapshot("schedule-entry.png");
            });
            steps.add(() -> {
                require(!entryButton("教学班").isDisabled(),
                        "教学班入口在 T5 接通后必须可用");
                require(!entryButton("教学课程表").isDisabled(),
                        "教学课程表入口在 T4 接通后必须可用");
                require(!entryButton("我的申请").isDisabled(),
                        "我的申请入口在 T5 接通后必须可用");
                // 成绩录入在 T5 接通：工作台不再有分阶段占位，四个入口全部可用。
                for (String entry : List.of("成绩录入")) {
                    require(!entryButton(entry).isDisabled(),
                            entry + "入口在 T5 接通后必须可用");
                }
            });

            // 教学课程表：真实点击进入，先看 Mock 第 8 周（含周末课、第 13 节、原位置提示与调课后卡片）。
            steps.add(() -> entryButton("教学课程表").fire());
            steps.add(() -> {
                GridPane grid = requireNode("#scheduleGrid", GridPane.class, "课表网格");
                require(effectivelyVisible(grid), "点击教学课程表后课表网格必须可见");
                require(!requireNode("#homePanel", VBox.class, "首页提示区").isVisible(),
                        "进入课表页后首页提示区必须被替换掉");
                // 右上入口高亮只能在真实工具包里断言：无工具包的控制器测试连 Button 都造不出来
                // （Control 的静态初始化要求 Toolkit 已启动），所以这条守卫只能留在这里。
                require(entryButton("教学课程表").getStyleClass()
                                .contains("teacher-course-entry-active"),
                        "当前页对应的右上入口必须带高亮样式（教学课程表），实际 "
                                + entryButton("教学课程表").getStyleClass());
                for (String other : List.of("教学班", "成绩录入", "我的申请")) {
                    require(!entryButton(other).getStyleClass()
                                    .contains("teacher-course-entry-active"),
                            other + " 不是当前页，不该带高亮样式");
                }
                require(grid.getColumnConstraints().size() == 8,
                        "网格应为 1 个节次列 + 7 个日期列，实际 "
                                + grid.getColumnConstraints().size() + " 列");

                List<String> headers = nodeTexts(".teacher-schedule-header");
                require(headers.size() == 8,
                        "列头应有 8 个（节次 + 7 天），实际 " + headers);
                require(headers.contains("节次"), "网格必须保留节次列头，实际 " + headers);
                List<String> dayHeaders = new ArrayList<>(headers);
                dayHeaders.remove("节次");
                List<String> expectedDays = new ArrayList<>(List.of(
                        "周一 10-26", "周二 10-27", "周三 10-28", "周四 10-29",
                        "周五 10-30", "周六 10-31", "周日 11-01"));
                Collections.sort(dayHeaders);
                Collections.sort(expectedDays);
                require(dayHeaders.equals(expectedDays),
                        "Mock 第 8 周必须画出 7 个日期列（含非教学日的第 7 天），实际 " + dayHeaders);

                List<Node> nonTeaching = new ArrayList<>();
                for (Node node : root.lookupAll(".teacher-schedule-non-teaching")) {
                    if (node.getStyleClass().contains("teacher-schedule-header")
                            || node.getStyleClass().contains("teacher-schedule-cell")) {
                        nonTeaching.add(node);
                    }
                }
                require(nonTeaching.stream().anyMatch(node ->
                                "周日 11-01".equals(labelTextOf(node))),
                        "非教学日的第 7 天仍要占一列，列头带 teacher-schedule-non-teaching");

                List<String> periods = nodeTexts(".teacher-schedule-period-label");
                require(periods.size() == 13,
                        "节次行应是本周出现过的节次并集（13 节），实际 " + periods);
                require(periods.contains("第 13 节 18:00:00-18:45:00"),
                        "第 13 节的时间必须是定宽 HH:mm:ss，实际 " + periods);
                for (String text : periods) {
                    require(text.matches("第 [0-9]+ 节 [0-9]{2}:[0-9]{2}:[0-9]{2}-"
                                    + "[0-9]{2}:[0-9]{2}:[0-9]{2}"),
                            "节次行头必须是 第 N 节 HH:mm:ss-HH:mm:ss，实际 " + text);
                }

                require(shownWeek() == CURRENT_WEEK,
                        "周次控件应停在第 " + CURRENT_WEEK + " 周，实际 " + shownWeek());
                require(cards().size() == WEEK_EIGHT_CARDS,
                        "第 8 周应有 " + WEEK_EIGHT_CARDS + " 张卡片，实际 " + cards().size());
                List<String> badges = nodeTexts(".teacher-schedule-badge");
                require(badges.contains("原安排") && badges.contains("调课后"),
                        "第 8 周必须同时出现 原安排 与 调课后 角标，实际 " + badges);
                requireVerticalBadges();
                requireCrossWeekOriginalBlock();
                // 范围来自响应里的 minWeek/maxWeek，控件因此可用（没有范围时它是禁用的）。
                require(!weekSpinner().isDisabled(),
                        "第 8 周已加载，周次控件必须可用");
                require(weekRange().getMin() == MIN_WEEK && weekRange().getMax() == MAX_WEEK,
                        "周次范围必须是服务端的 minWeek..maxWeek，实际 "
                                + weekRange().getMin() + ".." + weekRange().getMax());
                requireViewportReset("渲染第 8 周");
                snapshot("schedule-week8.png");
            });
            steps.add(() -> snapshotNode(cardForCourse(CROSS_WEEK_COURSE_NAME),
                    "schedule-cross-week-original.png"));

            // ------------------------------------------------------------ 铺满 / 自适应（Task 2）
            // 用户要的是“跟着窗口变”：默认 860x580 横向装得下，网格宽度被拉到视口宽度（不再停在
            // 自身 pref 尺寸），纵向 13 节装不下，于是按最小高度渲染并滚动；窗口拉大后两个方向都铺满
            // （行高跟着长），拉窄到装不下时回落到最小宽度 + 滚动，而不是把列压到读不出来。
            steps.add(() -> requireGridFillsViewport("860x580 默认窗口"));
            steps.add(() -> resizeWindow(1180.0, 940.0));
            steps.add(() -> {
                requireGridFillsViewport("放大到 1180x940");
                // 纵向铺满的实证：节次行自己长高了（行约束不再被 max=44 钉死），而不是被别的
                // 节点把网格撑大。节次标签带 vgrow + maxSize=MAX，高度就是那一行的高度。
                Node periodLabel = root.lookup(".teacher-schedule-period-label");
                require(periodLabel != null && periodLabel.getLayoutBounds().getHeight() > 44.0,
                        "窗口变高时节次行必须跟着长高，实际 "
                                + (periodLabel == null
                                        ? "没有节次行"
                                        : periodLabel.getLayoutBounds().getHeight()));
            });
            steps.add(() -> resizeWindow(620.0, 420.0));
            steps.add(this::requireGridScrollsInASmallWindow);
            steps.add(() -> resizeWindow(WIDTH, HEIGHT));
            steps.add(() -> {
                requireWindowSize("窗口还原后");
                requireGridFillsViewport("还原 860x580");
                requireViewportReset("窗口还原后");
            });

            // 用户把这一周滚到底（看清第 13 节）后再离开：离开前的滚动位置不得被之后的每一周继承。
            steps.add(() -> scrollGridToBottom("第 8 周滚到底"));

            // 卡片详情：点跨周原位置那张卡片，用弹窗自己的 Scene root 截图。
            steps.add(() -> cardForCourse(CROSS_WEEK_COURSE_NAME).fire());
            steps.add(() -> {
                Stage dialog = requireDialogStage();
                Parent dialogRoot = dialog.getScene().getRoot();
                require(labelIn(dialogRoot, "#courseNameLine").contains(CROSS_WEEK_COURSE_NAME),
                        "弹窗必须展示课程名，实际 " + labelIn(dialogRoot, "#courseNameLine"));
                require(labelIn(dialogRoot, "#locationLine").contains(CROSS_WEEK_LOCATION),
                        "弹窗必须展示上课地点，实际 " + labelIn(dialogRoot, "#locationLine"));
                require(labelIn(dialogRoot, "#timeLine").contains("第 1-2 节"),
                        "弹窗必须展示节次，实际 " + labelIn(dialogRoot, "#timeLine"));
                Button request = requireIn(dialogRoot, "#requestAdjustmentButton", Button.class,
                        "申请调课按钮");
                require(request.isDisabled(),
                        "已有生效调课的课次不能再申请调课（该卡片是跨周原位置提示）");
                require(labelIn(dialogRoot, "#adjustmentHintLabel").contains("已有生效调课"),
                        "禁用的调课入口必须说明原因，实际 "
                                + labelIn(dialogRoot, "#adjustmentHintLabel"));
                Button openOffering = requireIn(dialogRoot, "#openOfferingButton", Button.class,
                        "查看教学班按钮");
                require(!openOffering.isDisabled(), "查看教学班必须可用");
                snapshotNode(dialogRoot, "schedule-card-detail.png");
            });

            // 查看教学班：弹窗关闭并切到该 offering 的详情页。
            steps.add(() -> requireIn(dialogStageRoot(), "#openOfferingButton", Button.class,
                    "查看教学班按钮").fire());
            steps.add(() -> {
                require(dialogStageOrNull() == null, "点查看教学班后弹窗必须关闭");
                require(labelText("#detailTitleLabel").contains(SPRING_OFFERING_WITH_ROSTER),
                        "工作台应切到 " + SPRING_OFFERING_WITH_ROSTER + " 的详情页，实际 "
                                + labelText("#detailTitleLabel"));
            });

            // 回到课表：周导航（跨周调入的第 9 周与无课的第 5 周）。方向按 R4 反着来：
            // 向下箭头（这里就是往下点）= 往后一周，向上箭头 = 往前一周。
            steps.add(() -> entryButton("教学课程表").fire());
            steps.add(() -> {
                require(shownWeek() == CURRENT_WEEK,
                        "回到课表应恢复第 8 周，实际 " + shownWeek());
                requireViewportReset("从教学班详情返回课表");
            });
            steps.add(this::pressWeekDown);
            steps.add(() -> {
                require(shownWeek() == CURRENT_WEEK + 1,
                        "向下箭头应到第 9 周（向下 = 往后一周），实际 " + shownWeek());
                require(cards().size() == WEEK_NINE_CARDS,
                        "第 9 周只应剩跨周调入的 " + WEEK_NINE_CARDS + " 张卡片，实际 "
                                + cards().size());
                requireCrossWeekTargetBlock();
                requireViewportReset("切到第 9 周");
                snapshot("schedule-week9.png");
            });
            steps.add(() -> snapshotNode(cardForCourse(CROSS_WEEK_COURSE_NAME),
                    "schedule-week9-target.png"));
            steps.add(this::pressWeekUp);
            steps.add(() -> {
                require(shownWeek() == CURRENT_WEEK,
                        "向上箭头应回到第 8 周（向上 = 往前一周），实际 " + shownWeek());
                require(cards().size() == WEEK_EIGHT_CARDS,
                        "第 8 周应恢复 " + WEEK_EIGHT_CARDS + " 张卡片，实际 " + cards().size());
            });

            // 同一周再滚到底一次，接着连按三次向上箭头去无课周。
            steps.add(() -> scrollGridToBottom("第 8 周再滚到底"));

            // 无课周：卡片为 0，但 7 列日期、13 行节次与空态文案仍在。
            steps.add(this::pressWeekUp);
            steps.add(this::pressWeekUp);
            steps.add(this::pressWeekUp);
            steps.add(() -> {
                require(shownWeek() == CURRENT_WEEK - 3,
                        "三次向上箭头后应为第 5 周，实际 " + shownWeek());
                require(cards().isEmpty(), "Mock 第 5 周没有课程，实际 " + cards().size() + " 张卡片");
                require(nodeTexts(".teacher-schedule-header").size() == 8,
                        "无课周仍必须画出节次列 + 7 个日期列");
                require(nodeTexts(".teacher-schedule-period-label").size() == 13,
                        "无课周仍必须画出 13 行节次");
                Label empty = requireIn(scheduleScope(), "#emptyLabel", Label.class, "空态文案");
                require(empty.isVisible() && EMPTY_WEEK_TEXT.equals(empty.getText()),
                        "无课周必须显示 " + EMPTY_WEEK_TEXT + "，实际 " + empty.getText());
                requireViewportReset("切到第 5 周");
                snapshot("schedule-empty-week.png");
            });

            // 回到本周：Mock 的 currentWeek 是第 8 周。
            steps.add(() -> button("#currentWeekButton", "回到本周按钮").fire());
            steps.add(() -> require(shownWeek() == CURRENT_WEEK,
                    "回到本周应恢复第 " + CURRENT_WEEK + " 周，实际 " + shownWeek()));

            // 教学班列表：沿用既有的四 Tab 详情流程。
            steps.add(() -> entryButton("教学班").fire());
            steps.add(() -> {
                TableView<?> table = table("#offeringTable", "教学班列表");
                require(table.getItems().size() == 2,
                        "春季学期应列出 2 个教学班，实际 " + table.getItems().size());
                require(SPRING_OFFERING_WITH_ROSTER.equals(offeringAt(table, 0).getOfferingCode()),
                        "第一行应为 " + SPRING_OFFERING_WITH_ROSTER);
                require(SPRING_OFFERING_WITHOUT_ROSTER.equals(offeringAt(table, 1).getOfferingCode()),
                        "第二行应为 " + SPRING_OFFERING_WITHOUT_ROSTER);
                snapshot("offering-list.png");
            });

            // 打开有人数的教学班：四 Tab 逐一加载并截图。
            steps.add(() -> detailButtonForRow(0).fire());
            steps.add(() -> {
                TabPane tabs = tabs();
                require(tabTexts(tabs).equals(
                                List.of("基本信息", "学生名单", "上课安排", "成绩情况")),
                        "详情页应有四个 Tab，实际 " + tabTexts(tabs));
                require(bodyLines("#basicBody") == 9,
                        "基本信息应有 9 行，实际 " + bodyLines("#basicBody"));
                require(labelText("#detailTitleLabel").contains(SPRING_OFFERING_WITH_ROSTER),
                        "详情标题应指向 " + SPRING_OFFERING_WITH_ROSTER
                                + "，实际 " + labelText("#detailTitleLabel"));
                snapshot("detail-basic-info.png");
            });
            steps.add(() -> tabs().getSelectionModel().select(1));
            steps.add(() -> {
                TableView<?> roster = table("#rosterTable", "学生名单");
                require(roster.getItems().size() == 20,
                        "名单第 1 页应有 20 行，实际 " + roster.getItems().size());
                require(roster.getItems().stream()
                                .anyMatch(row -> rosterName(row).contains(LONG_NAME_FRAGMENT)),
                        "超长姓名应出现在名单第 1 页");
                require(labelText("#rosterPageLabel").contains("第 1/2 页"),
                        "名单应报告 2 页，实际 " + labelText("#rosterPageLabel"));
                snapshot("detail-roster-page1.png");
            });
            steps.add(() -> button("#nextRosterPageButton", "名单下一页按钮").fire());
            steps.add(() -> {
                TableView<?> roster = table("#rosterTable", "学生名单");
                require(roster.getItems().size() == 7,
                        "名单第 2 页应剩下 7 行，实际 " + roster.getItems().size());
                require(labelText("#rosterPageLabel").contains("第 2/2 页"),
                        "名单应报告第 2 页，实际 " + labelText("#rosterPageLabel"));
                snapshot("roster-page2.png");
            });
            steps.add(() -> tabs().getSelectionModel().select(2));
            steps.add(() -> {
                require(bodyLines("#scheduleBody") == 1,
                        "上课安排应有 1 行正式安排，实际 " + bodyLines("#scheduleBody"));
                snapshot("detail-schedule.png");
            });
            steps.add(() -> tabs().getSelectionModel().select(3));
            steps.add(() -> {
                require(bodyLines("#gradeBody") == 5,
                        "成绩情况应有 5 行只读说明，实际 " + bodyLines("#gradeBody"));
                snapshot("detail-grades.png");
            });

            // 退回列表后打开空名单教学班，验证空状态占位符。
            steps.add(() -> button("#backToOfferingsButton", "返回教学班列表按钮").fire());
            steps.add(() -> {
                TableView<?> table = table("#offeringTable", "教学班列表");
                require(table.getItems().size() == 2, "返回后应恢复教学班列表");
            });
            steps.add(() -> detailButtonForRow(1).fire());
            steps.add(() -> tabs().getSelectionModel().select(1));
            steps.add(() -> {
                TableView<?> roster = table("#rosterTable", "学生名单");
                require(roster.getItems().isEmpty(),
                        "空班名单应为空，实际 " + roster.getItems().size() + " 行");
                Node placeholder = roster.getPlaceholder();
                require(placeholder != null && placeholder.isVisible(),
                        "空班名单必须显示空状态占位符");
                snapshot("roster-empty.png");
            });

            // ------------------------------------------------------------ T5：调课表单
            // 从周 8 里唯一未调整的课次（9203 操作系统原理）打开调课表单：跨周无冲突 → 长原因 →
            // 同周冲突，最后真的提交一次，验证表单与课次详情弹窗的内联提示。
            steps.add(() -> entryButton("教学课程表").fire());
            steps.add(() -> {
                require(shownWeek() == CURRENT_WEEK,
                        "回到课表后应恢复第 " + CURRENT_WEEK + " 周，实际 " + shownWeek());
            });
            steps.add(() -> cardForCourse(ADJUSTABLE_COURSE_NAME).fire());
            steps.add(() -> {
                Parent dialogRoot = dialogStageRoot();
                Button request = requireIn(dialogRoot, "#requestAdjustmentButton", Button.class,
                        "申请调课按钮");
                require(!request.isDisabled(),
                        "未调整的课次必须可以申请调课，实际按钮被禁用");
                request.fire();
            });
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                require(labelIn(adjustmentRoot, "#originalLine").contains("第 8 周")
                                && labelIn(adjustmentRoot, "#originalLine").contains("第 12-13 节")
                                && labelIn(adjustmentRoot, "#originalLine").contains("C-301"),
                        "调课表单必须写清原安排，实际 "
                                + labelIn(adjustmentRoot, "#originalLine"));
                ComboBox<?> dateCombo = requireIn(adjustmentRoot, "#dateCombo", ComboBox.class,
                        "新日期下拉");
                require(dateCombo.getItems().size() == CALENDAR_CHOICES,
                        "日期选项必须来自教学日历（" + CALENDAR_CHOICES + " 个教学日），实际 "
                                + dateCombo.getItems().size());
                require(requireIn(adjustmentRoot, "#submitButton", Button.class, "提交按钮")
                                .isDisabled(),
                        "未选择目标前提交必须禁用");
            });
            steps.add(() -> selectAdjustmentDate(CROSS_WEEK_TARGET_DATE));
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                require(labelIn(adjustmentRoot, "#previewStatusLabel").contains("选择完整"),
                        "只选日期时表单不完整，必须提示先选节次，实际 "
                                + labelIn(adjustmentRoot, "#previewStatusLabel"));
            });
            steps.add(() -> selectAdjustmentPeriods(1, 2));
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                require(labelIn(adjustmentRoot, "#targetWeekLine").contains("第 9 周")
                                && labelIn(adjustmentRoot, "#targetWeekLine").contains("跨周"),
                        "目标周行必须写出目标周与跨周关系，实际 "
                                + labelIn(adjustmentRoot, "#targetWeekLine"));
                Label status = requireIn(adjustmentRoot, "#previewStatusLabel", Label.class,
                        "预检查结果");
                require(status.getText().contains("没有冲突"),
                        "跨周空闲目标必须预检查通过，实际 " + status.getText());
                require(requireIn(adjustmentRoot, "#submitButton", Button.class, "提交按钮")
                                .isDisabled(),
                        "预检查通过但原因还是空的时候提交必须禁用");
            });
            steps.add(() -> {
                TextArea reason = requireIn(adjustmentStageRoot(), "#reasonArea", TextArea.class,
                        "调课原因");
                reason.setText("教师出差");
            });
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                require(!requireIn(adjustmentRoot, "#submitButton", Button.class, "提交按钮")
                                .isDisabled(),
                        "预检查通过且原因非空时必须可以提交");
                snapshotNode(adjustmentRoot, "adjustment-dialog-cross-week.png");
            });
            steps.add(() -> {
                TextArea reason = requireIn(adjustmentStageRoot(), "#reasonArea", TextArea.class,
                        "调课原因");
                reason.setText(LONG_REASON);
            });
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                TextArea reason = requireIn(adjustmentRoot, "#reasonArea", TextArea.class,
                        "调课原因");
                require(LONG_REASON.equals(reason.getText()),
                        "长原因必须完整保留，实际长度 " + reason.getText().length());
                require(!requireIn(adjustmentRoot, "#submitButton", Button.class, "提交按钮")
                                .isDisabled(),
                        "编辑原因不会使预检查失效，提交保持可用");
                snapshotNode(adjustmentRoot, "adjustment-dialog-long-reason.png");
            });
            steps.add(() -> selectAdjustmentDate(SAME_WEEK_TARGET_DATE));
            steps.add(() -> {
                Parent adjustmentRoot = adjustmentStageRoot();
                require(labelIn(adjustmentRoot, "#targetWeekLine").contains("同周"),
                        "同周目标必须标出同周关系，实际 "
                                + labelIn(adjustmentRoot, "#targetWeekLine"));
                VBox conflicts = requireIn(adjustmentRoot, "#previewConflictRows", VBox.class,
                        "冲突列表");
                require(!conflicts.getChildren().isEmpty(),
                        "Mock 的同周目标与 9201 冲突，必须显示冲突行");
                require(nodeTextsIn(adjustmentRoot, ".teacher-schedule-dialog-adjustment-line")
                                .stream().anyMatch(text -> text.contains(CONFLICT_MESSAGE_FRAGMENT)),
                        "冲突行必须以服务端文案说明冲突原因");
                Node conflictRow = conflicts.getChildren().get(0);
                require(conflictRow instanceof Label conflictLabel
                                && conflictLabel.getHeight()
                                        >= conflictLabel.prefHeight(480.0) - 1.0,
                        "冲突行必须按换行后的高度完整显示，不能被裁成省略号，实际高度 "
                                + conflictRow.getLayoutBounds().getHeight() + "，需要 "
                                + ((Label) conflictRow).prefHeight(480.0));
                require(requireIn(adjustmentRoot, "#submitButton", Button.class, "提交按钮")
                                .isDisabled(),
                        "存在冲突时提交必须禁用");
                snapshotNode(adjustmentRoot, "adjustment-dialog-conflict.png");
            });
            // 改回跨周目标 → 无冲突 → 真正提交一次（Mock 会写入一条 PENDING 申请）。
            steps.add(() -> selectAdjustmentDate(CROSS_WEEK_TARGET_DATE));
            steps.add(() -> {
                Button submit = requireIn(adjustmentStageRoot(), "#submitButton", Button.class,
                        "提交按钮");
                require(!submit.isDisabled(), "跨周目标必须重新变为可提交");
                submit.fire();
            });
            steps.add(() -> {
                require(adjustmentStageOrNull() == null, "提交成功后调课表单必须关闭");
                Parent dialogRoot = dialogStageRoot();
                require(labelIn(dialogRoot, "#adjustmentHintLabel").contains(SUBMITTED_HINT_FRAGMENT),
                        "课次详情必须给出提交成功的内联提示，实际 "
                                + labelIn(dialogRoot, "#adjustmentHintLabel"));
                require(requireIn(dialogRoot, "#requestAdjustmentButton", Button.class,
                                "申请调课按钮").isDisabled(),
                        "提交过的课次不能再重复申请调课");
            });
            steps.add(() -> requireIn(dialogStageRoot(), "#closeButton", Button.class, "关闭按钮")
                    .fire());
            steps.add(() -> {
                require(dialogStageOrNull() == null, "关闭课次详情后弹窗必须消失");
                require(Window.getWindows().size() == 1, "调课流程结束后不能留下任何弹窗");
            });

            // ------------------------------------------------------------ T5：我的申请
            // 进入“我的申请”：列表里有刚提交的申请与 Mock 的 PENDING 夹具；撤销 9405 后详情立即
            // 显示已撤销，PENDING 列表不再包含它，切到已撤销筛选能查到它。
            steps.add(() -> entryButton("我的申请").fire());
            steps.add(() -> {
                require(effectivelyVisible(applicationsScope()),
                        "进入我的申请后子页必须可见");
                ComboBox<?> statusFilter = requireIn(applicationsScope(),
                        "#applicationStatusFilter", ComboBox.class, "状态筛选");
                require(statusFilter.getItems().equals(List.of("待审批", "已通过", "已驳回", "已撤销")),
                        "我的申请必须支持四态筛选，实际 " + statusFilter.getItems());
                VBox list = requireIn(applicationsScope(), "#applicationList", VBox.class,
                        "申请列表");
                require(list.getChildren().size() == 2,
                        "PENDING 列表应包含刚提交的申请与 Mock 夹具（2 条），实际 "
                                + list.getChildren().size());
                require(list.getChildren().get(0).getLayoutBounds().getHeight() < 140
                                && list.getChildren().get(0).getLayoutBounds().getHeight() > 0,
                        "列表行必须按内容排版，不能被换行标签撑成整屏，实际行高 "
                                + list.getChildren().get(0).getLayoutBounds().getHeight());
                // 入口角标：两行都还没读过（服务端算出的 unread，客户端不自己猜）。
                requireUnreadBadge(2);
            });
            steps.add(() -> applicationRow(PENDING_REQUEST_ID).fire());
            steps.add(() -> {
                String title = labelIn(applicationsScope(), "#applicationDetailTitleLabel");
                require(title.contains(PENDING_REQUEST_ID) && title.contains("待审批"),
                        "选中的申请必须加载详情，实际 " + title);
                Button withdraw = requireIn(applicationsScope(), "#applicationWithdrawButton",
                        Button.class, "撤销按钮");
                require(withdraw.isVisible() && !withdraw.isDisabled(),
                        "PENDING 申请必须提供可用的撤销入口");
            });
            // 详情加载后页面会为这一行发一次「标记已读」，服务端回执之后重新查询列表：
            // 角标必须跟着服务端的真实状态落到 1（刚读过的那条不再计数）。
            steps.add(() -> requireUnreadBadge(1));
            steps.add(() -> requireIn(applicationsScope(), "#applicationWithdrawButton",
                    Button.class, "撤销按钮").fire());
            steps.add(() -> {
                Button confirm = requireIn(applicationsScope(), "#applicationConfirmWithdrawButton",
                        Button.class, "确认撤销按钮");
                require(confirm.isVisible(), "第一次点击撤销必须只进入确认态");
                confirm.fire();
            });
            steps.add(() -> {
                String title = labelIn(applicationsScope(), "#applicationDetailTitleLabel");
                require(title.contains(WITHDRAWN_STATUS_FRAGMENT),
                        "撤销成功后详情必须显示已撤销，实际 " + title);
                String feedback = labelIn(applicationsScope(), "#applicationFeedbackLabel");
                require(feedback.contains(WITHDRAWN_STATUS_FRAGMENT),
                        "撤销成功必须给出内联提示，实际 " + feedback);
                require(!requireIn(applicationsScope(), "#applicationWithdrawButton", Button.class,
                                "撤销按钮").isVisible(),
                        "终态申请必须只读，不能再撤销");
                snapshot("adjustment-applications-withdrawn.png");
            });
            steps.add(() -> selectComboValue(applicationsScope(), "#applicationStatusFilter",
                    "已撤销"));
            steps.add(() -> {
                VBox list = requireIn(applicationsScope(), "#applicationList", VBox.class,
                        "申请列表");
                require(list.getChildren().size() == 2,
                        "已撤销列表应包含 9404 与刚撤销的 9405，实际 " + list.getChildren().size());
                require(applicationRowOrNull(WITHDRAWN_REQUEST_ID) != null
                                && applicationRowOrNull(PENDING_REQUEST_ID) != null,
                        "撤销必须真实改变查询快照（已撤销列表里出现 9404 与 9405）");
                // 切筛选就是换一次查询：角标跟着这一页的未读条数走。9405 的那次撤销是页面自己做的，
                // 撤销后面板上的详情被刷新，新到达的终态随之被标成已读，于是这一页只剩 9404 未读。
                requireUnreadBadge(1);
            });

            // ------------------------------------------------------------ T6：成绩录入
            // 真实加载 TeacherGradeView/TeacherGradeBookView（两个新 FXML 的转义与绑定只有真实工具包能证明），
            // 依次走：成绩列表 → 草稿班（部分填写 / 非法值 / 灰列 / 未保存提示）→ 提交后的只读页 → 被驳回页。
            steps.add(() -> entryButton("成绩录入").fire());
            steps.add(() -> {
                require(effectivelyVisible(requireNode("#gradesPage", Parent.class, "成绩录入页")),
                        "进入成绩录入后成绩列表页必须可见");
                TableView<?> list = table("#gradeOfferingTable", "成绩列表");
                require(list.getItems().size() == 2,
                        "2025/3 应列出 2 个教学班，实际 " + list.getItems().size());
                require(cellText(list, 0, 0).contains(GRADE_DRAFT_OFFERING)
                                && "草稿".equals(cellText(list, 0, 3))
                                && "24 人".equals(cellText(list, 0, 4))
                                && "5 人".equals(cellText(list, 0, 5)),
                        "第一行必须是 CS203-01 的草稿与录入进度，实际 " + rowCells(list, 0));
                require(cellText(list, 1, 0).contains(GRADE_EMPTY_OFFERING),
                        "第二行必须是空班 CS301-01，实际 " + rowCells(list, 1));
                // 操作列是按钮（没有单元格数据），所以按状态反查它的“录入成绩”按钮并确认真实可用。
                require(!gradeEntryButtonForState("草稿").isDisabled(),
                        "草稿行必须带可用的录入成绩按钮");
                snapshot("grade-offering-list.png");
            });
            steps.add(() -> gradeEntryButtonForState("草稿").fire());
            steps.add(() -> {
                require(effectivelyVisible(requireNode("#gradeBookPage", Parent.class, "成绩编辑表")),
                        "点录入成绩后成绩编辑表必须可见");
                require(!effectivelyVisible(requireNode("#gradesPage", Parent.class, "成绩录入页")),
                        "进入成绩编辑表后成绩列表页必须被替换掉");
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(book.getItems().size() == 24,
                        "草稿班应有 24 名正常修读学生，实际 " + book.getItems().size());
                require(labelText("#gradeBookStateLabel").contains("草稿")
                                && labelText("#gradeBookStateLabel").contains("v4"),
                        "状态行必须显示草稿与版本，实际 " + labelText("#gradeBookStateLabel"));
                require(labelText("#gradeBookSchemeLabel").contains("权重合计：10000/10000")
                                && labelText("#gradeBookSchemeLabel").contains("已配齐"),
                        "方案行必须显示已配齐的权重，实际 " + labelText("#gradeBookSchemeLabel"));
                require(!button("#gradeBookSaveButton", "保存草稿").isDisabled()
                                && !button("#gradeBookSubmitButton", "提交成绩").isDisabled(),
                        "草稿状态下保存与提交必须可用");
                require(emptyCellsIn(book, 4) > 0 && emptyCellsIn(book, 2) == 0,
                        "部分填写：实验列必须有留空的单元格、平时列不应有，实际 "
                                + emptyCellsIn(book, 4) + "/" + emptyCellsIn(book, 2));
                require("总评".equals(book.getColumns().get(6).getText())
                                && PLACEHOLDER.equals(cellText(book, 3, 6))
                                && !PLACEHOLDER.equals(cellText(book, 0, 6)),
                        "缺分行的总评必须显示占位符而不是伪造的数字，实际 "
                                + cellText(book, 3, 6) + "/" + cellText(book, 0, 6));
                snapshot("gradebook-partial.png");
            });

            // ------------------------------------------------------------ T5：导入区接线
            // 真实工具包里断言导入区已经接上处理器：FXML 里写错的 fx:id/onAction 只会让按钮变成
            // 没有反应的摆件（无工具包的控制器测试连 Button 都造不出来）。
            // 下面那条「未预览时三个按钮不可见」方向是反的：它们在 FXML 里本来就是
            // visible="false"/managed="false"，所以它只能抓住「控制器在启动时错把它们点亮」；
            // 「预览到达后显示出来、确认按钮可用」那条方向由无工具包的 TeacherGradeImportControllerTest
            // 覆盖（mock 给不出 issues，这里造不出来，也不伪造）。
            steps.add(() -> {
                Button template = button("#gradeBookDownloadTemplateButton", "下载成绩模板按钮");
                Button export = button("#gradeBookExportGradesButton", "导出成绩按钮");
                Button importButton = button("#gradeBookImportButton", "导入 Excel 按钮");
                require(TEMPLATE_BUTTON_TEXT.equals(template.getText())
                                && EXPORT_BUTTON_TEXT.equals(export.getText())
                                && IMPORT_BUTTON_TEXT.equals(importButton.getText()),
                        "三个导入入口的文案必须与交付一致，实际 " + template.getText() + "/"
                                + export.getText() + "/" + importButton.getText());
                require(!template.isDisabled() && !export.isDisabled() && !importButton.isDisabled(),
                        "草稿页上的下载模板/导出成绩/导入 Excel 必须可用");
                require(template.getOnAction() != null && export.getOnAction() != null
                                && importButton.getOnAction() != null,
                        "三个导入入口必须真的接上处理器（FXML 的 onAction）");
                for (String hidden : List.of("#gradeBookImportSummaryLabel",
                        "#gradeBookImportIssuesButton", "#gradeBookCancelImportButton",
                        "#gradeBookConfirmImportButton")) {
                    require(!requireNode(hidden, Node.class, hidden).isVisible(),
                            hidden + " 在没有导入预览时必须隐藏，实际可见");
                }
            });

            // ------------------------------------------------ Excel 式录入：单击即编辑、输入即生效
            // 单击（不是双击）就进入编辑，并且打开时整段选中——下一次敲键直接覆盖旧值。
            steps.add(() -> clickCell(table("#gradeBookTable", "成绩表"), 0, 2));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 0, 2);
                require(editor != null && editor.isVisible(),
                        "单击单元格必须直接出现输入框（不需要双击）");
                require("70".equals(editor.getText()),
                        "编辑器必须显示这一格的原文，实际 " + editor.getText());
                require(editor.getLength() > 0 && editor.getSelection().getLength() == editor.getLength(),
                        "打开编辑时必须整段选中（下一次敲键直接覆盖），实际选中 "
                                + editor.getSelection().getLength() + "/" + editor.getLength());
                require(Window.getWindows().size() == 1,
                        "进入编辑不得弹出任何对话框，实际 " + Window.getWindows().size() + " 个窗口");
                snapshot("gradebook-cell-editing.png");
            });
            // 选中即输入：直接敲数字必须覆盖原来的 70，而不是拼成 709。
            steps.add(() -> typeChar(table("#gradeBookTable", "成绩表"), "9"));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 0, 2);
                require(editor != null && "9".equals(editor.getText()),
                        "直接敲数字必须覆盖原值，实际 "
                                + (editor == null ? "编辑器不见了" : editor.getText()));
                require("9".equals(cellText(book, 0, 2)),
                        "输入必须实时写入当前单元格，实际 " + cellText(book, 0, 2));
                require(!PLACEHOLDER.equals(cellText(book, 0, 6)),
                        "总评必须跟着实时输入立刻更新，实际 " + cellText(book, 0, 6));
                // 改回一个正常分数，后面的导航断言才有稳定的期望值。
                editor.selectAll();
                editor.replaceSelection("70");
            });

            // Enter：完成当前输入并下移到下一行同列（不再需要按回车确认）。
            steps.add(() -> pressKey(editorIn(table("#gradeBookTable", "成绩表"), 0, 2),
                    KeyCode.ENTER, false, false));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 1, 2);
                require(editor != null && "71".equals(editor.getText()),
                        "Enter 必须完成输入并移到下一行同列，实际 "
                                + (editor == null ? "编辑器没有下移" : editor.getText()));
                require(editorIn(book, 0, 2) == null, "原来的编辑器必须已经收起来");
                snapshot("gradebook-navigate-enter.png");
            });

            // Tab：移到右边一格。
            steps.add(() -> pressKey(editorIn(table("#gradeBookTable", "成绩表"), 1, 2),
                    KeyCode.TAB, false, false));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 1, 3);
                require(editor != null && "66".equals(editor.getText()),
                        "Tab 必须移到右边一格，实际 "
                                + (editor == null ? "没有落点" : editor.getText()));
                snapshot("gradebook-navigate-tab.png");
            });

            // Esc：撤销本次修改，恢复修改前的成绩。
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 1, 3);
                editor.selectAll();
                editor.replaceSelection("55");
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require("55".equals(cellText(book, 1, 3)),
                        "输入必须实时写入（模型里已经是 55），实际 " + cellText(book, 1, 3));
                pressKey(editorIn(book, 1, 3), KeyCode.ESCAPE, false, false);
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(editorIn(book, 1, 3) == null, "Esc 之后必须收起编辑器");
                require("66".equals(cellText(book, 1, 3)),
                        "Esc 必须把这一格恢复成修改前的成绩，实际 " + cellText(book, 1, 3));
                require("66".equals(renderedCellText(book, 1, 3)),
                        "Esc 之后界面必须重新画出原来的成绩，实际 "
                                + renderedCellText(book, 1, 3));
                snapshot("gradebook-esc-reverted.png");
            });

            // 方向键：编辑器收起后焦点回到表格，↓ 下移一行、← 左移一格（整段选中时 ← 换格子）。
            steps.add(() -> pressKey(table("#gradeBookTable", "成绩表"), KeyCode.DOWN, false, false));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 2, 3);
                require(editor != null && "67".equals(editor.getText()),
                        "↓ 必须下移一行并进入编辑，实际 "
                                + (editor == null ? "没有落点" : editor.getText()));
                pressKey(editor, KeyCode.LEFT, false, false);
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 2, 2);
                require(editor != null && "72".equals(editor.getText()),
                        "← 在整段选中时必须移到左边一格，实际 "
                                + (editor == null ? "没有落点" : editor.getText()));
            });

            // 批量粘贴：从当前格开始把 2×2 块向右下铺开（直接从 Excel 复制一列/一片的场景）。
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                setClipboard("50\t60\r\n51\t61\r\n");
                pressKey(editorIn(book, 2, 2), KeyCode.V, false, true);
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require("50".equals(cellText(book, 2, 2)) && "60".equals(cellText(book, 2, 3))
                                && "51".equals(cellText(book, 3, 2))
                                && "61".equals(cellText(book, 3, 3)),
                        "2×2 粘贴必须向右下铺开，实际 " + cellText(book, 2, 2) + "/"
                                + cellText(book, 2, 3) + "/" + cellText(book, 3, 2) + "/"
                                + cellText(book, 3, 3));
                require("77".equals(cellText(book, 2, 4)),
                        "粘贴不得碰到块右边的格子，实际 " + cellText(book, 2, 4));
                // 粘贴改到的格子必须真的在界面上重画出来，而不是只在模型里改了值。
                require("60".equals(renderedCellText(book, 2, 3))
                                && "51".equals(renderedCellText(book, 3, 2))
                                && "61".equals(renderedCellText(book, 3, 3)),
                        "粘贴之后界面必须重新画出这些值，实际 " + renderedCellText(book, 2, 3) + "/"
                                + renderedCellText(book, 3, 2) + "/"
                                + renderedCellText(book, 3, 3));
                require(editorIn(book, 2, 2) != null,
                        "粘贴之后必须回到起点格继续录入，而不是把编辑器丢掉");
                snapshot("gradebook-paste-block.png");
            });

            // 异常成绩即时校验：满分 100 分时输入 105，在本格直接提示错误，不弹窗打断连续录入。
            // 表格按行虚拟化：把第一行滚回视口再单击（真实用户也要先滚到那一行才点得到）。
            steps.add(() -> table("#gradeBookTable", "成绩表").scrollTo(0));
            steps.add(() -> clickCell(table("#gradeBookTable", "成绩表"), 0, 2));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 0, 2);
                require(editor != null, "第二次单击必须同样进入编辑");
                editor.selectAll();
                editor.replaceSelection("105");
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(styledCellsIn(book, 2, GRADE_CELL_ERROR_CLASS) == 1,
                        "超界分数必须把该单元格标红，实际标红 "
                                + styledCellsIn(book, 2, GRADE_CELL_ERROR_CLASS) + " 格");
                require("105".equals(cellText(book, 0, 2)),
                        "超界分数必须原样留在格子里，实际 " + cellText(book, 0, 2));
                Tooltip tip = tooltipOf(cellAt(book, 0, 2));
                require(tip != null && tip.getText().contains("0..100"),
                        "非法格必须就地说明原因（气泡提示），实际 "
                                + (tip == null ? "没有提示" : tip.getText()));
                require(Window.getWindows().size() == 1,
                        "异常成绩不得弹窗打断录入，实际 " + Window.getWindows().size() + " 个窗口");
                snapshot("gradebook-inline-error.png");
                button("#gradeBookSaveButton", "保存草稿").fire();
            });
            steps.add(() -> {
                String feedback = labelText("#gradeBookFeedbackLabel");
                require(feedback.contains("存在非法输入") && feedback.contains("平时"),
                        "保存必须被本地校验挡住并指出是哪一格，实际 " + feedback);
                require(labelText("#gradeBookStateLabel").contains("v4"),
                        "被挡下的保存不得改变版本，实际 " + labelText("#gradeBookStateLabel"));
                snapshot("gradebook-invalid-cell.png");
            });

            // 修正后保存成功：非法样式消失、版本前进，用户输入的内容被服务端快照确认。
            steps.add(() -> clickCell(table("#gradeBookTable", "成绩表"), 0, 2));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                TextField editor = editorIn(book, 0, 2);
                require(editor != null, "第二次编辑必须同样出现输入框");
                editor.selectAll();
                editor.replaceSelection("71.5");
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(styledCellsIn(book, 2, GRADE_CELL_ERROR_CLASS) == 0,
                        "修正后的单元格不得再标红");
                Button export = button("#gradeBookExportGradesButton", "导出成绩按钮");
                Button template = button("#gradeBookDownloadTemplateButton", "下载成绩模板按钮");
                require(!export.isDisabled(), "保存之前导出成绩必须是可用的");
                button("#gradeBookSaveButton", "保存草稿").fire();
                // fire() 只是把保存请求发出去：响应经 fxExecutor（Platform.runLater）回到 FX 线程，
                // 所以这一刻保存确实还在途。在途期间导出成绩必须禁用——导出的是服务端那份**已保存
                // 的草稿**，此刻它还没收到这一次写入，允许导出就会拿到一份与屏幕上不一样的数字。
                // 模板下载不动：空白模板是静态内容，不是在途保存的快照，用不着跟着一起禁用。
                require(export.isDisabled(),
                        "保存请求在途时导出成绩必须禁用（白名单：屏幕上这份还没落到服务端）");
                require(!template.isDisabled(),
                        "模板下载是静态内容，不得跟着在途保存一起禁用");
            });
            steps.add(() -> {
                require(labelText("#gradeBookFeedbackLabel").contains("成绩草稿已保存"),
                        "修正后的保存必须成功，实际 " + labelText("#gradeBookFeedbackLabel"));
                require(labelText("#gradeBookStateLabel").contains("v5"),
                        "保存成功后版本必须前进到 v5，实际 " + labelText("#gradeBookStateLabel"));
            });

            // 状态提示的落点与消退：与三个导入入口同排（按钮行里、撑开导入态的 Region 之前），
            // 新提示立刻可见，3 秒后渐变淡出并隐藏，隐藏时不透明度复位（下一次提示从全不透明开始）。
            steps.add(() -> {
                Label feedback = requireNode("#gradeBookFeedbackLabel", Label.class, "状态提示");
                require(feedback.getParent() instanceof HBox,
                        "状态提示必须与三个导入入口同排，实际父节点 "
                                + feedback.getParent().getClass().getSimpleName());
                Pane row = (Pane) feedback.getParent();
                require(row.getChildren().indexOf(feedback)
                                > row.getChildren().indexOf(
                                        requireNode("#gradeBookImportButton", Button.class, "导入 Excel")),
                        "状态提示必须排在「导入 Excel」之后");
                require(row.getChildren().indexOf(feedback)
                                < row.getChildren().indexOf(requireNode("#gradeBookImportSummaryLabel",
                                        Label.class, "导入摘要")),
                        "状态提示必须在导入态之前（右侧留给摘要/异常明细/取消/确认）");
                require(feedback.isVisible() && feedback.isManaged(),
                        "刚到达的提示必须立刻可见");
                require(feedback.getOpacity() == 1.0,
                        "刚到达的提示必须是全不透明，实际 " + feedback.getOpacity());
                snapshot("gradebook-feedback-row.png");
            });
            // 消退是真实时间驱动的：这里让出 FX 线程一小段真实时间（`settle` 的脉冲随后把动画
            // 时钟推到结束），下一步断言标签已经自己隐藏。
            steps.add(() -> sleepQuietly(3600));
            steps.add(() -> {
                Label feedback = requireNode("#gradeBookFeedbackLabel", Label.class, "状态提示");
                require(!feedback.isVisible() && !feedback.isManaged(),
                        "3 秒之后提示必须自己消退隐藏（不是一直挂着）");
                require(feedback.getOpacity() == 1.0,
                        "消退结束后不透明度必须复位，实际 " + feedback.getOpacity());
                require(labelText("#gradeBookFeedbackLabel").contains("成绩草稿已保存"),
                        "消退只隐藏标签，那句话本身不能被抹掉，实际 "
                                + labelText("#gradeBookFeedbackLabel"));
            });

            // 灰列：禁用一列组成 → 该列整体置灰、权重输入禁用、权重合计变成未配齐，且点不进编辑器。
            steps.add(() -> requireNode("#gradeBookExperimentEnabled", CheckBox.class, "实验启用开关")
                    .setSelected(false));
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(styledCellsIn(book, 4, GRADE_CELL_DISABLED_CLASS) > 0,
                        "禁用组成的整列必须置灰，实际置灰 "
                                + styledCellsIn(book, 4, GRADE_CELL_DISABLED_CLASS) + " 格");
                require(requireNode("#gradeBookExperimentWeight", TextField.class, "实验权重")
                                .isDisabled(),
                        "禁用组成的权重输入必须不可编辑");
                require(labelText("#gradeBookSchemeLabel").contains("权重合计：8000/10000")
                                && labelText("#gradeBookSchemeLabel").contains("未配齐"),
                        "禁用后权重合计必须变成未配齐的 8000，实际 "
                                + labelText("#gradeBookSchemeLabel"));
                clickCell(book, 0, 4);
            });
            steps.add(() -> {
                TableView<?> book = table("#gradeBookTable", "成绩表");
                require(editorIn(book, 0, 4) == null,
                        "禁用的成绩列不得打开编辑器（打开了也接收不了输入）");
                snapshot("gradebook-disabled-column.png");
            });

            // 未保存提示：有改动时离开先弹确认框，取消后必须停在原页且保留编辑内容。
            // 确认框在自己的嵌套事件循环里渲染并阻塞调用方，所以先排队处理任务，再触发导航。
            steps.add(() -> {
                leavePromptAttempts = 0;
                Platform.runLater(this::captureAndRefuseLeavePrompt);
                entryButton("教学班").fire();
            });
            steps.add(() -> {
                require(effectivelyVisible(requireNode("#gradeBookPage", Parent.class, "成绩编辑表")),
                        "被拒绝的离开必须停在成绩表页面");
                require(!effectivelyVisible(requireNode("#offeringsPage", Parent.class, "教学班页")),
                        "被拒绝的离开不得切换到教学班页");
                require(!requireNode("#gradeBookExperimentEnabled", CheckBox.class, "实验启用开关")
                                .isSelected(),
                        "被拒绝的离开不得丢掉未保存的编辑（实验仍是禁用态）");
                require(Window.getWindows().size() == 1,
                        "确认框作答后不能留下多余窗口，实际 " + Window.getWindows().size() + " 个");
            });

            // 补齐权重（禁用实验后 40/20/-/40）并真实提交：二次确认 → 只读的待审核页。
            steps.add(() -> requireNode("#gradeBookDailyWeight", TextField.class, "平时权重")
                    .setText("40"));
            steps.add(() -> requireNode("#gradeBookFinaltermWeight", TextField.class, "期末权重")
                    .setText("40"));
            steps.add(() -> {
                require(labelText("#gradeBookSchemeLabel").contains("权重合计：10000/10000")
                                && labelText("#gradeBookSchemeLabel").contains("已配齐"),
                        "禁用实验后 40/20/-/40 必须重新配齐，实际 "
                                + labelText("#gradeBookSchemeLabel"));
                button("#gradeBookSubmitButton", "提交成绩").fire();
            });
            steps.add(() -> {
                Button confirm = button("#gradeBookConfirmSubmitButton", "确认提交");
                require(confirm.isVisible() && !confirm.isDisabled(),
                        "第一次点击提交必须只进入可用的确认态");
                require(!labelText("#gradeBookFeedbackLabel").contains("成绩批次已提交"),
                        "进入确认态时不得已经提交");
                snapshot("gradebook-submit-confirm.png");
                confirm.fire();
            });
            steps.add(() -> {
                require(labelText("#gradeBookFeedbackLabel").contains("成绩批次已提交"),
                        "确认提交后必须提交成功，实际 " + labelText("#gradeBookFeedbackLabel"));
                // 上一条提示已经消退过：新提示必须把标签重新点亮（隐藏状态与不透明度都被复位），
                // 而不是继承上一轮消退后的“看不见”。
                Label feedback = requireNode("#gradeBookFeedbackLabel", Label.class, "状态提示");
                require(feedback.isVisible() && feedback.getOpacity() == 1.0,
                        "消退之后到达的新提示必须重新可见，实际可见 " + feedback.isVisible()
                                + "／不透明度 " + feedback.getOpacity());
                require(labelText("#gradeBookStateLabel").contains("已提交待审核")
                                && labelText("#gradeBookStateLabel").contains("v6"),
                        "提交成功后必须进入待审核只读态，实际 " + labelText("#gradeBookStateLabel"));
                Label notice = requireNode("#gradeBookNoticeLabel", Label.class, "只读提示");
                require(notice.isVisible() && notice.getText().contains("只读状态")
                                && notice.getText().contains(PENDING_SUBMISSION_ID),
                        "只读提示必须写明状态与批次，实际 " + notice.getText());
                require(button("#gradeBookSaveButton", "保存草稿").isDisabled()
                                && button("#gradeBookSubmitButton", "提交成绩").isDisabled(),
                        "只读状态下保存与提交必须禁用");
                require(requireNode("#gradeBookDailyEnabled", CheckBox.class, "平时启用开关")
                                .isDisabled()
                                && requireNode("#gradeBookDailyWeight", TextField.class, "平时权重")
                                .isDisabled(),
                        "只读状态下方案开关与权重输入必须不可编辑");
                require(table("#gradeBookTable", "成绩表").getItems().size() == 24,
                        "提交后的成绩表必须仍然显示整份名单");
                snapshot("gradebook-read-only.png");
            });

            // 被驳回页：切到 2025/2，被驳回的批次可以继续编辑，且必须一直显示管理员的审核意见。
            steps.add(() -> entryButton("教学班").fire());
            steps.add(() -> entryButton("成绩录入").fire());
            steps.add(() -> selectComboValue(requireNode("#gradesPage", Parent.class, "成绩录入页"),
                    "#gradeTermFilter", AUTUMN_TERM_LABEL));
            steps.add(() -> {
                TableView<?> list = table("#gradeOfferingTable", "成绩列表");
                require(list.getItems().size() == 2,
                        "2025/2 应列出 2 个教学班，实际 " + list.getItems().size());
                require(cellText(list, 0, 0).contains(GRADE_REJECTED_OFFERING)
                                && "审核未通过（已驳回）".equals(cellText(list, 0, 3))
                                && cellText(list, 1, 0).contains(GRADE_PENDING_OFFERING)
                                && "已提交待审核".equals(cellText(list, 1, 3)),
                        "2025/2 必须同时给出被驳回与待审核两行，实际 " + rowCells(list, 0) + "/"
                                + rowCells(list, 1));
            });
            steps.add(() -> gradeEntryButtonForState("审核未通过（已驳回）").fire());
            steps.add(() -> {
                require(labelText("#gradeBookStateLabel").contains("审核未通过"),
                        "被驳回批次必须显示驳回状态，实际 " + labelText("#gradeBookStateLabel"));
                Label notice = requireNode("#gradeBookNoticeLabel", Label.class, "批次提示");
                require(notice.isVisible() && notice.getText().contains("上一次提交未通过")
                                && notice.getText().contains(REVIEW_COMMENT_FRAGMENT)
                                && notice.getText().contains("可以修改后重新提交"),
                        "被驳回后管理员的意见必须一直可见，实际 " + notice.getText());
                require(!button("#gradeBookSaveButton", "保存草稿").isDisabled(),
                        "被驳回的草稿必须可以继续修改");
                snapshot("gradebook-rejected.png");
            });

            // ------------------------------------------------ T4：更正表单的「原分数 / 拟修改」对照
            // 更正表单从成绩表上「已通过」的批次打开，而 Mock 里没有任何一个教学班处于已通过
            // （CS203-01 草稿、CS352-01 待审核、CS204-01 已驳回），所以离屏冒烟点不到那条入口。
            // 这里直接加载同一份 FXML、把「要更正谁、他的四项原分数是什么」注入控制器，之后走的是
            // 与真实弹窗完全相同的 render 路径；注入用的是反射，只为了越过那个包私有的入参。
            steps.add(this::openCorrectionComparisonDialog);
            steps.add(this::requireCorrectionComparison);
            steps.add(this::closeCorrectionComparisonDialog);

            // ------------------------------------------------ T5：导入异常明细弹窗的加载守卫
            // 这个弹窗只会在预览回来时由 TeacherGradeImportController 打开，而那条路径把加载失败
            // 整个吞掉（`catch (IOException | RuntimeException | LinkageError)`，预览照常合并进表格），
            // 所以 FXML 坏掉时界面上只是少一个弹窗、没有任何测试会红。这里用真实工具包直接加载它：
            // fx:controller 解析不到、fx:id 写错、onAction 指向不存在的方法都会在这一步失败。
            steps.add(() -> {
                Parent feedbackRoot;
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource(IMPORT_FEEDBACK_VIEW));
                    feedbackRoot = loader.load();
                    Object controller = loader.getController();
                    require(controller != null && "controller.TeacherGradeImportController$Feedback"
                                    .equals(controller.getClass().getName()),
                            "弹窗的 fx:controller 必须解析到导入控制器的 Feedback，实际 "
                                    + (controller == null ? "null" : controller.getClass().getName()));
                } catch (IOException failure) {
                    throw new UncheckedIOException("导入异常明细弹窗加载失败", failure);
                }
                Stage stage = new Stage();
                stage.initOwner(primaryStage);
                stage.setTitle(IMPORT_FEEDBACK_TITLE);
                stage.setScene(new Scene(feedbackRoot));
                stage.show();
                // 异常列表在 ScrollPane 里，而滚动面板的内容要等皮肤建出来才挂进场景图：
                // 先把皮肤与布局跑一遍，之后按 id 查节点才能真的查到（否则只会查到空儿童的控件）。
                feedbackRoot.applyCss();
                feedbackRoot.layout();
                requireIn(feedbackRoot, "#feedbackIssueList", VBox.class, "异常列表");
                requireIn(feedbackRoot, "#feedbackSummaryLabel", Label.class, "异常摘要标签");
                requireIn(feedbackRoot, "#feedbackHintLabel", Label.class, "异常提示标签");
                // 这两个标签的文案证明 initialize() → render() 真的跑过（空态而不是没渲染）：
                // 摘要为空（还没有预览），提示是未绑定宿主时那句。
                require(labelIn(feedbackRoot, "#feedbackSummaryLabel").isEmpty(),
                        "没有预览时摘要必须为空，实际 "
                                + labelIn(feedbackRoot, "#feedbackSummaryLabel"));
                require(IMPORT_FEEDBACK_HINT_TEXT.equals(labelIn(feedbackRoot,
                                "#feedbackHintLabel")),
                        "弹窗必须已经渲染过，实际提示 " + labelIn(feedbackRoot,
                                "#feedbackHintLabel"));
                snapshotNode(feedbackRoot, "gradebook-import-feedback.png");
                requireIn(feedbackRoot, "#feedbackCloseButton", Button.class, "关闭按钮").fire();
                require(!stage.isShowing(), "关闭按钮必须真的关掉弹窗");
                require(Window.getWindows().size() == 1,
                        "关闭弹窗后不能留下多余窗口，实际 " + Window.getWindows().size());
            });

            // 收尾：课次详情弹窗是 WINDOW_MODAL + show()（不阻塞），结束时不能有遗留窗口。
            steps.add(() -> {
                requireWindowSize("结束时");
                require(Window.getWindows().size() == 1,
                        "除主窗口外不应留下任何窗口，实际 " + Window.getWindows().size() + " 个");
                require(Window.getWindows().get(0) == primaryStage
                                || Window.getWindows().contains(primaryStage),
                        "唯一剩下的窗口必须是主窗口");
            });
        }

        private void advance() {
            if (stepIndex >= steps.size()) {
                System.out.println("TeacherCourseUiSmokeTest: PASS (" + steps.size() + " steps, "
                        + FILES.length + " snapshots)");
                Platform.exit();
                return;
            }
            Runnable step = steps.get(stepIndex++);
            PauseTransition settle = new PauseTransition(Duration.millis(SETTLE_MILLIS));
            // 步骤本身经 Platform.runLater 进入事件队列：否则在动画处理期间触发模态的
            // showAndWait() 会被 JavaFX 拒绝（“未保存提示”的确认框正是这条路径）。
            settle.setOnFinished(event -> Platform.runLater(() -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    Platform.exit();
                    System.exit(1);
                    return;
                }
                advance();
            }));
            settle.play();
        }

        // ---------------------------------------------------------------- 节点查找

        /**
         * 右上入口按钮。文案在「我的申请」上会随未读条数变长（{@code 我的申请（本页未读 N）}），
         * 所以这里按「逐字相等，或后接一个左括号的角标」匹配，而不是只认逐字相等——否则一旦某个
         * 调用点落在申请页加载之后，查找就会失败。<b>前缀匹配必须带括号</b>：单纯的
         * {@code startsWith} 会让「教学」之类的短串同时命中两个入口，把一个更脆的查找换成一个
         * 会静默选错的查找。
         */
        private Button entryButton(String text) {
            List<Button> matches = new ArrayList<>();
            for (Node node : root.lookupAll(".teacher-course-entry")) {
                if (node instanceof Button button && button.getText() != null
                        && (button.getText().equals(text)
                        || button.getText().startsWith(text + "（"))) {
                    matches.add(button);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            throw new IllegalStateException(matches.isEmpty()
                    ? "找不到工作台入口按钮：" + text
                    : "入口 " + text + " 匹配到多个按钮："
                            + matches.stream().map(Button::getText).toList());
        }

        /** 课表里某个课程的卡片按钮；卡片正文是 graphic，因此按标题标签反查它的按钮祖先。 */
        private Button cardForCourse(String courseName) {
            for (Node node : root.lookupAll(".teacher-schedule-card-title")) {
                if (node instanceof Label label && courseName.equals(label.getText())) {
                    for (Node current = node; current != null; current = current.getParent()) {
                        if (current instanceof Button button
                                && button.getStyleClass().contains("teacher-schedule-card")) {
                            return button;
                        }
                    }
                }
            }
            throw new IllegalStateException("找不到课程 " + courseName + " 的课表卡片");
        }

        private List<Button> cards() {
            List<Button> buttons = new ArrayList<>();
            for (Node node : root.lookupAll(".teacher-schedule-card")) {
                if (node instanceof Button button) {
                    buttons.add(button);
                }
            }
            return buttons;
        }

        /** 主窗口里除主 Stage 外恰好一个标题为“课程详情”的窗口；多于一个或没有都算失败。 */
        private Stage requireDialogStage() {
            Stage dialog = dialogStageOrNull();
            if (dialog == null) {
                throw new IllegalStateException("找不到标题为 " + DIALOG_TITLE + " 的课次详情窗口");
            }
            require(Window.getWindows().size() == 2,
                    "打开详情弹窗后应恰好有两个窗口（主窗口 + 弹窗），实际 "
                            + Window.getWindows().size());
            return dialog;
        }

        private Stage dialogStageOrNull() {
            Stage found = null;
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                    if (found != null) {
                        throw new IllegalStateException("出现了多个 " + DIALOG_TITLE + " 窗口");
                    }
                    found = stage;
                }
            }
            return found;
        }

        private Parent dialogStageRoot() {
            Stage dialog = requireDialogStage();
            return dialog.getScene().getRoot();
        }

        /** 调课表单窗口（标题“申请调课”）；打开时主窗口 + 课次详情 + 表单恰好三个窗口。 */
        private Stage adjustmentStageOrNull() {
            Stage found = null;
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage && ADJUSTMENT_TITLE.equals(stage.getTitle())) {
                    if (found != null) {
                        throw new IllegalStateException("出现了多个 " + ADJUSTMENT_TITLE + " 窗口");
                    }
                    found = stage;
                }
            }
            return found;
        }

        private Parent adjustmentStageRoot() {
            Stage stage = adjustmentStageOrNull();
            if (stage == null) {
                throw new IllegalStateException("找不到标题为 " + ADJUSTMENT_TITLE + " 的调课表单窗口");
            }
            require(Window.getWindows().size() == 3,
                    "打开调课表单后应恰好有三个窗口（主窗口 + 课次详情 + 调课表单），实际 "
                            + Window.getWindows().size());
            return stage.getScene().getRoot();
        }

        /** 在调课表单里按 ISO 日期选择目标日期（下拉项以日期结尾），值变化即触发预检查。 */
        private void selectAdjustmentDate(String isoDate) {
            Parent scope = adjustmentStageRoot();
            Node node = requireIn(scope, "#dateCombo", Node.class, "新日期下拉");
            if (!(node instanceof ComboBox<?> combo)) {
                throw new IllegalStateException("#dateCombo 不是下拉框");
            }
            for (Object item : combo.getItems()) {
                if (item instanceof String label && label.endsWith(isoDate)) {
                    selectComboValue(scope, "#dateCombo", label);
                    return;
                }
            }
            throw new IllegalStateException("日期下拉里没有 " + isoDate);
        }

        /** 在调课表单里选择开始/结束节次；两个值都选中后表单才完整并自动预检查。 */
        private void selectAdjustmentPeriods(int start, int end) {
            Parent scope = adjustmentStageRoot();
            selectComboValue(scope, "#startPeriodCombo", start);
            selectComboValue(scope, "#endPeriodCombo", end);
        }

        /** 给下拉框赋一个值；值监听器按用户选择处理（冒烟不模拟鼠标，但走的是同一条监听路径）。 */
        @SuppressWarnings("unchecked")
        private void selectComboValue(Parent scope, String selector, Object value) {
            Node node = requireIn(scope, selector, Node.class, "下拉 " + selector);
            if (!(node instanceof ComboBox<?>)) {
                throw new IllegalStateException(selector + " 不是下拉框");
            }
            ((ComboBox<Object>) node).setValue(value);
        }

        /** 我的申请子页的根节点：里面的 fx:id 带 application 前缀，但仍按子页作用域查找。 */
        private Parent applicationsScope() {
            return requireNode("#applicationsPage", Parent.class, "我的申请子页");
        }

        /**
         * 「我的申请」入口上的未读角标必须逐字等于当前页的未读条数：
         * {@code 我的申请（本页未读 N）}。这是「未读状态同步」在界面上的那一半——计数来自服务端
         * 算出的 {@code unread}，页面只负责显示，客户端不自己推。
         */
        private void requireUnreadBadge(int expectedUnread) {
            String entry = entryButton(APPLICATIONS_ENTRY).getText();
            require((APPLICATIONS_ENTRY + "（本页未读 " + expectedUnread + "）").equals(entry),
                    "我的申请入口必须显示本页未读 " + expectedUnread + " 条，实际 " + entry
                            + "；本页各行为 " + applicationRowTexts());
        }

        /** 本页每一行的可读文本（未读的行会多一颗「未读」角标），用在角标断言的失败信息里。 */
        private List<String> applicationRowTexts() {
            Node node = applicationsScope().lookup("#applicationList");
            if (!(node instanceof VBox list)) return List.of("找不到申请列表");
            List<String> texts = new ArrayList<>();
            for (Node child : list.getChildren()) {
                texts.add(rowText(child).replace('\n', ' '));
            }
            return texts;
        }

        // ---------------------------------------------------------------- 成绩表查找

        /** 成绩列表里状态为 {@code stateText} 的那一行的“录入成绩”按钮（真实点击）。 */
        private Button gradeEntryButtonForState(String stateText) {
            TableView<?> list = table("#gradeOfferingTable", "成绩列表");
            for (Node node : list.lookupAll(".teacher-course-row-detail-button")) {
                if (node instanceof Button button) {
                    TableRow<?> row = enclosingRow(node);
                    if (row != null && stateText.equals(cellText(list, row.getIndex(), 3))) {
                        return button;
                    }
                }
            }
            throw new IllegalStateException("找不到成绩状态为 " + stateText + " 的教学班");
        }

        /** 表格列里第 {@code rowIndex} 行的数据文本；不依赖单元格是否已经布局在视口里。 */
        private static String cellText(TableView<?> table, int rowIndex, int columnIndex) {
            Object value = table.getColumns().get(columnIndex).getCellData(rowIndex);
            return value == null ? "" : value.toString();
        }

        /** 一整行的数据文本，用于失败信息里说明“实际看到的是什么”。 */
        private static List<String> rowCells(TableView<?> table, int rowIndex) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < table.getColumns().size(); column++) {
                values.add(cellText(table, rowIndex, column));
            }
            return values;
        }

        /** 某列界面上真实存在的单元格：表格按行虚拟化，视口之外的行没有单元格可断言。 */
        private static List<TableCell<?, ?>> renderedCells(TableView<?> table, int columnIndex) {
            Object column = table.getColumns().get(columnIndex);
            List<TableCell<?, ?>> cells = new ArrayList<>();
            for (Node node : table.lookupAll(".table-cell")) {
                if (node instanceof TableCell<?, ?> cell && !cell.isEmpty()
                        && column.equals(cell.getTableColumn())) {
                    cells.add(cell);
                }
            }
            return cells;
        }

        /** 某列里留空（未录入）的已渲染单元格数。 */
        private static int emptyCellsIn(TableView<?> table, int columnIndex) {
            int count = 0;
            for (TableCell<?, ?> cell : renderedCells(table, columnIndex)) {
                if (cell.getText() == null || cell.getText().isEmpty()) count++;
            }
            return count;
        }

        /** 某列里带某个样式类的已渲染单元格数（标红/置灰要真的画在单元格上，不只是算出一个布尔）。 */
        private static int styledCellsIn(TableView<?> table, int columnIndex, String styleClass) {
            int count = 0;
            for (TableCell<?, ?> cell : renderedCells(table, columnIndex)) {
                if (cell.getStyleClass().contains(styleClass)) count++;
            }
            return count;
        }

        /**
         * 已渲染的某一格。表格按行虚拟化，所以只有进了视口的行才有单元格可断言；
         * 找不到就说明这一格根本不在界面上（例如导航跳出了视口）。
         */
        private static TableCell<?, ?> cellAt(TableView<?> table, int rowIndex, int columnIndex) {
            Object column = table.getColumns().get(columnIndex);
            for (Node node : table.lookupAll(".table-cell")) {
                if (node instanceof TableCell<?, ?> cell && !cell.isEmpty()
                        && column.equals(cell.getTableColumn()) && cell.getIndex() == rowIndex) {
                    return cell;
                }
            }
            return null;
        }

        /** 某一列里当前真正渲染出来的行下标，用在“找不到这一格”的失败信息里。 */
        private static List<Integer> renderedRows(TableView<?> table, int columnIndex) {
            Object column = table.getColumns().get(columnIndex);
            List<Integer> rows = new ArrayList<>();
            for (Node node : table.lookupAll(".table-cell")) {
                if (node instanceof TableCell<?, ?> cell && column.equals(cell.getTableColumn())) {
                    rows.add(cell.getIndex());
                }
            }
            Collections.sort(rows);
            return rows;
        }

        /**
         * 真实的鼠标单击：Excel 式录入要求“单击即选中并进入编辑”，不再需要双击，所以这里
         * 直接给单元格派发一个 MOUSE_CLICKED，走的就是控件自己挂的那条处理路径。
         */
        private static void clickCell(TableView<?> table, int rowIndex, int columnIndex) {
            TableCell<?, ?> cell = cellAt(table, rowIndex, columnIndex);
            if (cell == null) {
                throw new IllegalStateException(
                        "第 " + rowIndex + " 行第 " + columnIndex + " 列没有渲染出单元格；"
                                + "当前这一列渲染出来的行是 " + renderedRows(table, columnIndex));
            }
            Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5,
                    MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
                    false, false, null));
        }

        /**
         * 某一格界面上<b>真正画出来</b>的文本。与 {@link #cellText} 不同：后者读的是列数据
         * （模型当前值），这里读的是单元格节点自己的文本，因此能抓住“值写进去了但界面没刷新”
         * 这类只刷新一半的缺陷。
         */
        private static String renderedCellText(TableView<?> table, int rowIndex, int columnIndex) {
            TableCell<?, ?> cell = cellAt(table, rowIndex, columnIndex);
            return cell == null || cell.getText() == null ? "" : cell.getText();
        }

        /** 某一格里打开的编辑器；没有打开编辑器时返回 null。 */
        private static TextField editorIn(TableView<?> table, int rowIndex, int columnIndex) {
            TableCell<?, ?> cell = cellAt(table, rowIndex, columnIndex);
            if (cell == null) return null;
            Node node = cell.lookup(".text-field");
            return node instanceof TextField field && field.isVisible() ? field : null;
        }

        /** 某一格上的气泡提示（非法成绩的原因就写在这里，而不是弹窗）。 */
        private static Tooltip tooltipOf(TableCell<?, ?> cell) {
            return cell == null ? null : cell.getTooltip();
        }

        /** 给某个节点派发按键；模拟真实键盘时用的是控件的过滤器/处理器链路。 */
        private static void pressKey(Node target, KeyCode code, boolean shift, boolean control) {
            if (target == null) {
                throw new IllegalStateException("键盘事件没有目标节点（按键 " + code + "）");
            }
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control,
                    false, false));
        }

        /** 给某个节点派发一个可打印字符（“直接敲数字”这条路径）。 */
        private static void typeChar(Node target, String character) {
            if (target == null) {
                throw new IllegalStateException("键入没有目标节点");
            }
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED,
                    false, false, false, false));
        }

        /** 把一段文本放进系统剪贴板（模拟从 Excel 复制一片成绩）。 */
        private static void setClipboard(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        }

        // ---------------------------------------------------------------- 窗口与确认框

        /** 控制器经 AlertUtil 打开的模态框：Alert 的场景根就是它的 DialogPane。 */
        private static DialogPane renderedDialogPane() {
            for (Window window : Window.getWindows()) {
                if (!window.isShowing() || window.getScene() == null) continue;
                if (window.getScene().getRoot() instanceof DialogPane pane) return pane;
            }
            return null;
        }

        /**
         * 在确认框自己的嵌套事件循环里运行：断言它说的就是未保存的修改、截图，然后取消。
         * showAndWait 先建窗再进循环，所以找不到对话框时重新排队，而不是直接判失败。
         */
        private void captureAndRefuseLeavePrompt() {
            DialogPane pane = renderedDialogPane();
            if (pane == null) {
                if (++leavePromptAttempts > 60) {
                    fail(new IllegalStateException("有未保存修改时离开必须弹出确认框"));
                    return;
                }
                Platform.runLater(this::captureAndRefuseLeavePrompt);
                return;
            }
            try {
                Window window = pane.getScene().getWindow();
                require(window instanceof Stage stage && LEAVE_PROMPT_TITLE.equals(stage.getTitle()),
                        "确认框必须是" + LEAVE_PROMPT_TITLE);
                require(pane.getContentText() != null
                                && pane.getContentText().contains(LEAVE_PROMPT_FRAGMENT),
                        "确认框必须说明未保存的修改会丢失，实际 " + pane.getContentText());
                snapshotNode(pane, "gradebook-unsaved-prompt.png");
                Node cancel = pane.lookupButton(ButtonType.CANCEL);
                require(cancel instanceof Button, "确认框必须提供取消按钮");
                ((Button) cancel).fire();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        // ---------------------------------------------------------------- 更正表单（T4）

        /**
         * 真实工具包加载 TeacherGradeCorrectionDialog.fxml，并把一名学生的四项原分数喂给控制器。
         * 反映射的是包私有的 {@code prepare(CorrectionTarget)}：它只有一个入参（选中了谁），其余
         * 渲染、校验与提交都在控制器自己的公开路径上。
         */
        private void openCorrectionComparisonDialog() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(CORRECTION_VIEW));
                Parent correctionRoot = loader.load();
                Object controller = loader.getController();
                require(controller != null, "更正表单的 fx:controller 必须解析到真实控制器");
                String offeringId = gradeBookOfferingId();
                TeacherGradeBookDTO book = TeacherCourseServices.current()
                        .getGradeBook(offeringId).join();
                TeacherGradeRowDTO row = null;
                for (TeacherGradeRowDTO candidate : book.getRows()) {
                    // 挑一位「有组成没录」的学生：这样原分数行必须画占位符，而不是 0 或 null。
                    if (candidate.getScores() != null
                            && candidate.getScores().getExperimentScore() == null) {
                        row = candidate;
                        break;
                    }
                }
                require(row != null, "草稿夹具里必须有一位缺实验分的学生");
                correctionTarget = new CorrectionFixture(controller, correctionRoot, row,
                        book.getOfferingId(), book.getLastSubmissionId(), book.getRevision());
                correctionTarget.present();

                Stage stage = new Stage();
                stage.initOwner(primaryStage);
                stage.setTitle(CORRECTION_TITLE);
                stage.setScene(new Scene(correctionRoot));
                stage.show();
                correctionTarget.stage = stage;
                correctionRoot.applyCss();
                correctionRoot.layout();
            } catch (IOException failure) {
                throw new UncheckedIOException("更正表单加载失败", failure);
            }
        }

        /**
         * 草稿夹具教学班的<b>数值</b> offeringId。界面上与 {@link #GRADE_DRAFT_OFFERING} 用的是教学班
         * 代码（CS203-01），而成绩接口收的是十进制 ID（Mock 的教学班 ID 是 9007199254740993 这种
         * 刻意超出 double 精度的值），所以这里从成绩教学班列表里按代码反查。
         */
        private String gradeBookOfferingId() {
            var page = TeacherCourseServices.current()
                    .listGradeOfferings(GRADE_TERM_YEAR, GRADE_TERM_SEMESTER, 1, 20).join();
            for (var item : page.getItems()) {
                if (GRADE_DRAFT_OFFERING.equals(item.getOffering().getOfferingCode())) {
                    return item.getOffering().getOfferingId();
                }
            }
            throw new IllegalStateException("成绩列表里找不到教学班 " + GRADE_DRAFT_OFFERING);
        }

        /** 四项原分数逐行对上学生自己的分数；未录入的那一项必须是占位符而不是 0。 */
        private void requireCorrectionComparison() {
            require(correctionTarget != null, "更正表单必须先被打开");
            Parent scope = correctionTarget.root;
            GradeScoresDTO scores = correctionTarget.row.getScores();
            requireCorrectionOriginal(scope, "#dailyOriginalLabel", "平时", scores.getDailyScore());
            requireCorrectionOriginal(scope, "#midtermOriginalLabel", "期中",
                    scores.getMidtermScore());
            requireCorrectionOriginal(scope, "#experimentOriginalLabel", "实验",
                    scores.getExperimentScore());
            requireCorrectionOriginal(scope, "#finaltermOriginalLabel", "期末",
                    scores.getFinaltermScore());
            require(labelIn(scope, "#studentLine").contains(correctionTarget.row.getStudentName())
                            && labelIn(scope, "#studentLine")
                            .contains(correctionTarget.row.getStudentUid()),
                    "更正表单必须写明这名学生的姓名与学号，实际 " + labelIn(scope, "#studentLine"));

            // 拟修改一栏是原文：原分数行保持不动，右边的输入框改成新值，这就是界面上的「更正对比」。
            TextField experiment = requireIn(scope, "#experimentField", TextField.class, "实验拟修改值");
            require("".equals(experiment.getText()),
                    "未录入的组成在拟修改栏里必须是空的，实际 '" + experiment.getText() + "'");
            experiment.setText("95");
            require(requireIn(scope, "#experimentOriginalLabel", Label.class, "实验原分数")
                            .getText().endsWith(CORRECTION_PLACEHOLDER),
                    "改动拟修改值不得改写原分数行，实际 "
                            + requireIn(scope, "#experimentOriginalLabel", Label.class, "实验原分数")
                            .getText());

            Button submit = requireIn(scope, "#submitButton", Button.class, "确认更正按钮");
            require(submit.isDisabled(), "原因还是空的时候确认必须禁用（空文本在本地就被挡下）");
            TextArea reason = requireIn(scope, "#reasonArea", TextArea.class, "更正原因");
            reason.setText("期末成绩登分错误，需要更正");
            require(!submit.isDisabled(), "填了原因之后确认必须可用");
            // 长文本按上界截断（与服务端 500 字符的列宽一致），不靠服务端 400 来发现。
            String tooLong = "长".repeat(CORRECTION_MAX_REASON_LENGTH + 80);
            reason.setText(tooLong);
            require(reason.getText().length() == CORRECTION_MAX_REASON_LENGTH
                            && tooLong.startsWith(reason.getText()),
                    "超长原因必须按 " + CORRECTION_MAX_REASON_LENGTH + " 字符截断，实际 "
                            + reason.getText().length());
            reason.setText(LONG_REASON);
            requireWindowSize("更正表单截图前");
            snapshotNode(scope, "gradebook-correction-dialog.png");
        }

        private void requireCorrectionOriginal(Parent scope, String selector, String label,
                java.math.BigDecimal value) {
            String text = labelIn(scope, selector);
            String expected = value == null
                    ? CORRECTION_PLACEHOLDER
                    : value.stripTrailingZeros().toPlainString();
            require(text.equals(label + CORRECTION_ORIGINAL_PREFIX + expected),
                    "原分数行必须是「" + label + CORRECTION_ORIGINAL_PREFIX + expected + "」，实际 "
                            + text);
        }

        private void closeCorrectionComparisonDialog() {
            require(correctionTarget != null, "更正表单必须先被打开");
            requireIn(correctionTarget.root, "#cancelButton", Button.class, "取消按钮").fire();
            require(!correctionTarget.stage.isShowing(), "取消必须真的关掉更正表单");
            require(Window.getWindows().size() == 1,
                    "关闭更正表单后不能留下多余窗口，实际 " + Window.getWindows().size());
            correctionTarget = null;
        }

        /** 一次更正表单的装配结果：控制器（反射调用它的 prepare）、Scene root 与夹具学生。 */
        private final class CorrectionFixture {
            private final Object controller;
            private final Parent root;
            private final TeacherGradeRowDTO row;
            private final String offeringId;
            private final String submissionId;
            private final long revision;
            private Stage stage;

            private CorrectionFixture(Object controller, Parent root, TeacherGradeRowDTO row,
                    String offeringId, String submissionId, long revision) {
                this.controller = controller;
                this.root = root;
                this.row = row;
                this.offeringId = offeringId;
                this.submissionId = submissionId;
                this.revision = revision;
            }

            /** 把「选中了谁、他的原分数是什么」注入控制器（包私有方法，只能反射调用）。 */
            private void present() throws IOException {
                try {
                    Class<?> targetType = Class.forName(
                            "controller.TeacherGradeCorrectionDialogController$CorrectionTarget");
                    java.util.Map<GradeComponentCodeDTO, String> originals =
                            new java.util.LinkedHashMap<>();
                    for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                        originals.put(code, cellText(code));
                    }
                    java.lang.reflect.Constructor<?> constructor = targetType.getDeclaredConstructor(
                            String.class, String.class, long.class, String.class, String.class,
                            String.class, java.util.Map.class);
                    constructor.setAccessible(true);
                    Object target = constructor.newInstance(offeringId, submissionId,
                            revision, row.getEnrollmentId(), row.getStudentUid(),
                            row.getStudentName(), originals);
                    java.lang.reflect.Method prepare = controller.getClass()
                            .getDeclaredMethod("prepare", targetType);
                    prepare.setAccessible(true);
                    prepare.invoke(controller, target);
                } catch (ReflectiveOperationException failure) {
                    throw new IOException("无法把更正目标注入控制器", failure);
                }
            }

            private String cellText(GradeComponentCodeDTO code) {
                GradeScoresDTO scores = row.getScores();
                java.math.BigDecimal value = switch (code) {
                    case DAILY -> scores.getDailyScore();
                    case MIDTERM -> scores.getMidtermScore();
                    case EXPERIMENT -> scores.getExperimentScore();
                    case FINALTERM -> scores.getFinaltermScore();
                };
                return value == null ? "" : value.stripTrailingZeros().toPlainString();
            }
        }

        /** 排队任务里的失败不靠异常回传（它在嵌套事件循环里），直接以退出码 1 结束。 */
        private void fail(Throwable failure) {
            failure.printStackTrace();
            Platform.exit();
            System.exit(1);
        }

        /** 我的申请列表里按申请编号反查那一行的“查看”按钮（真实点击）。 */
        private Button applicationRow(String requestId) {
            Button row = applicationRowOrNull(requestId);
            if (row == null) {
                throw new IllegalStateException("找不到申请 " + requestId + " 的列表行");
            }
            return row;
        }

        private Button applicationRowOrNull(String requestId) {
            Node node = applicationsScope().lookup("#applicationList");
            if (!(node instanceof VBox list)) {
                throw new IllegalStateException("找不到申请列表");
            }
            for (Node child : list.getChildren()) {
                if (rowText(child).contains(requestId)) {
                    Node open = child.lookup(".teacher-course-row-detail-button");
                    if (open instanceof Button button) return button;
                }
            }
            return null;
        }

        /** 列表行的可读文本：行内所有标签拼在一起（标题、副标题）。 */
        private static String rowText(Node row) {
            StringBuilder text = new StringBuilder();
            collectLabelText(row, text);
            return text.toString();
        }

        private static void collectLabelText(Node node, StringBuilder text) {
            if (node == null) return;
            if (node instanceof Label label) {
                text.append(label.getText() == null ? "" : label.getText()).append('\n');
            }
            if (node instanceof Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    collectLabelText(child, text);
                }
            }
        }

        /** 某个作用域内某种样式类的全部标签文本。 */
        private static List<String> nodeTextsIn(Parent scope, String selector) {
            List<String> texts = new ArrayList<>();
            for (Node node : scope.lookupAll(selector)) {
                if (node instanceof Label label) {
                    texts.add(label.getText() == null ? "" : label.getText());
                }
            }
            return texts;
        }

        /**
         * 找到某一行“详情”按钮。列表按教学班代码排序，因此行号即 MockTeacherCourseService 的
         * 学期内顺序；这里通过按钮所属 {@link TableRow} 的索引定位，不依赖 lookupAll 的返回顺序。
         */
        private Button detailButtonForRow(int rowIndex) {
            TableView<?> table = table("#offeringTable", "教学班列表");
            for (Node node : table.lookupAll(".teacher-course-row-detail-button")) {
                TableRow<?> row = enclosingRow(node);
                if (row != null && row.getIndex() == rowIndex && node instanceof Button button) {
                    return button;
                }
            }
            throw new IllegalStateException("找不到第 " + rowIndex + " 行的详情按钮");
        }

        private static TableRow<?> enclosingRow(Node node) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (current instanceof TableRow<?> row) {
                    return row;
                }
            }
            return null;
        }

        private TableView<?> table(String selector, String description) {
            return requireNode(selector, TableView.class, description);
        }

        private TabPane tabs() {
            return requireNode("#detailTabs", TabPane.class, "详情 Tab 容器");
        }

        /**
         * 把主窗口调到指定大小（冒烟里代替用户拖动窗口），并立刻把布局跑一遍，让下一个步骤读到的
         * 就是新几何。传 {@link #WIDTH}x{@link #HEIGHT} 表示「还原」：那一路按装配时记下的外框尺寸
         * 还原，场景因此回到正好 860x580，所有主题截图都是同一个尺寸。
         */
        private void resizeWindow(double width, double height) {
            boolean restore = width == WIDTH && height == HEIGHT;
            primaryStage.setWidth(restore ? frameWidth : width);
            primaryStage.setHeight(restore ? frameHeight : height);
            root.applyCss();
            root.layout();
        }

        /**
         * 场景必须正好是 860x580。这是「截图检查就是在 860x580 下做的」这句话的凭据：窗口外框尺寸
         * 不是场景尺寸，只断言外框等于某个数会把标题栏与边框算进去，反而证明不了截图尺寸。
         */
        private void requireWindowSize(String where) {
            Scene scene = root.getScene();
            require(Math.abs(scene.getWidth() - WIDTH) < 1.0
                            && Math.abs(scene.getHeight() - HEIGHT) < 1.0,
                    where + "：场景必须是 " + WIDTH + "x" + HEIGHT + "，实际 "
                            + scene.getWidth() + "x" + scene.getHeight());
        }

        /**
         * 课表子页的根节点。工作台里教学班页也有一个 {@code #emptyLabel}，所以课表页里按 id
         * 查节点必须限定在这个子页内，否则会命中先被遍历到的教学班空态文案。
         */
        private Parent scheduleScope() {
            return requireNode("#schedulePage", Parent.class, "课表子页");
        }

        /**
         * 视口必须停在左上角：直接读 {@code #scheduleScroll} 自己的滚动值。
         *
         * <p>刻意不用 {@link #effectivelyVisible(Node)} 之类的可见性判断来代替——它只看
         * {@code isVisible()} 与场景挂载，看不见“节点被视口裁掉”这种情况，正是它让滚动位置
         * 出错时冒烟依然全绿。滚到别处的网格必须能被这里直接抓到。
         */
        private void requireViewportReset(String where) {
            ScrollPane scroll = requireNode("#scheduleScroll", ScrollPane.class, "课表滚动容器");
            require(scroll.getVvalue() == 0.0,
                    where + "后课表必须回到顶部（列头与第 1 节可见），实际 vvalue="
                            + scroll.getVvalue());
            require(scroll.getHvalue() == 0.0,
                    where + "后课表必须回到最左，实际 hvalue=" + scroll.getHvalue());
        }

        /**
         * 模拟用户把课表滚到右下角，并确认视口真的动了：没有这一步，套件自己的流程从不让视口偏移，
         * 滚动位置缺陷就抓不出来。
         *
         * <p>纵向一定滚得动（13 节在 860x580 里装不下）。横向只在表格比视口宽时才滚得动——
         * 铺满之后默认窗口下横向没有余量，只有把窗口拉窄才有（那条路由
         * {@link #requireGridScrollsInASmallWindow()} 走）。
         */
        private void scrollGridToBottom(String where) {
            ScrollPane scroll = requireNode("#scheduleScroll", ScrollPane.class, "课表滚动容器");
            GridPane grid = requireNode("#scheduleGrid", GridPane.class, "课表网格");
            scroll.setVvalue(1.0);
            scroll.setHvalue(1.0);
            require(scroll.getVvalue() > 0.5,
                    where + "：课表必须真的能纵向滚动，实际 vvalue=" + scroll.getVvalue());
            require(grid.getWidth() <= scroll.getViewportBounds().getWidth() + 1.0
                            || scroll.getHvalue() > 0.5,
                    where + "：表格比视口宽时横向必须滚得动，实际 hvalue=" + scroll.getHvalue()
                            + "，网格 " + grid.getWidth() + " / 视口 "
                            + scroll.getViewportBounds().getWidth());
        }

        /**
         * 铺满：网格被拉到视口大小，而不是停在自身 pref 尺寸。宽度必须正好等于视口宽度
         * （{@code fitToWidth} + 日列 {@code hgrow}），高度至少是视口高度——13 节撑不下时按最小高度
         * 渲染并交给 ScrollPane 滚动，撑得下时正好填满（{@code fitToHeight}）。
         * 视口同时必须仍在左上角，否则“铺满”会把列头推出屏幕。
         */
        private void requireGridFillsViewport(String where) {
            ScrollPane scroll = requireNode("#scheduleScroll", ScrollPane.class, "课表滚动容器");
            GridPane grid = requireNode("#scheduleGrid", GridPane.class, "课表网格");
            Bounds viewport = scroll.getViewportBounds();
            require(viewport.getWidth() > 0 && viewport.getHeight() > 0,
                    where + "：视口必须有尺寸，实际 " + viewport);
            require(Math.abs(grid.getWidth() - viewport.getWidth()) < 1.0,
                    where + "：网格宽度必须等于视口宽度（铺满），实际网格 " + grid.getWidth()
                            + " / 视口 " + viewport.getWidth());
            double expectedHeight = Math.max(viewport.getHeight(), grid.minHeight(-1));
            require(Math.abs(grid.getHeight() - expectedHeight) < 2.0,
                    where + "：网格高度必须是 max(视口, 最小高度)，实际网格 " + grid.getHeight()
                            + " / 视口 " + viewport.getHeight() + " / 最小高度 "
                            + grid.minHeight(-1));
            require(scroll.getVvalue() == 0.0 && scroll.getHvalue() == 0.0,
                    where + "：铺满之后视口仍必须停在左上角，实际 vvalue=" + scroll.getVvalue()
                            + "，hvalue=" + scroll.getHvalue());
        }

        /**
         * 调课角标必须是课次块右侧的竖排一列：宽 < 高（三个字纵向排下来，而不是整体旋转 90°，
         * 也不是原来那个横着占满一行的角标），并且不高于一个节次行（44px），因此不会撑高课次块。
         * 角标文本仍是完整的 {@code 原安排}／{@code 调课后}（上面按 CSS 类取文本的断言）。
         */
        private void requireVerticalBadges() {
            List<Node> badges = new ArrayList<>(root.lookupAll(".teacher-schedule-badge"));
            require(!badges.isEmpty(), "第 8 周必须画出租角标");
            for (Node badge : badges) {
                Bounds bounds = badge.getLayoutBounds();
                require(bounds.getWidth() < bounds.getHeight(),
                        "角标必须竖排（宽 < 高），实际 " + bounds.getWidth() + "x"
                                + bounds.getHeight());
                require(bounds.getHeight() <= 44.0,
                        "竖排角标不得撑高课次块（节次行 44px），实际高度 " + bounds.getHeight());
            }
        }

        /**
         * 跨周移动后的<b>原位置灰块</b>：第 8 周那一块只带 {@code teacher-schedule-adjusted-original}
         * 样式（灰底 + 灰边，CSS 里与「调课后」的黄色块成对），正文仍是原来的课程名与原教室。
         * 角标文本与几何由 {@link #requireVerticalBadges()} 钉住，这里钉的是它「长得像灰块」。
         */
        private void requireCrossWeekOriginalBlock() {
            Button card = cardForCourse(CROSS_WEEK_COURSE_NAME);
            require(card.getStyleClass().contains(ORIGINAL_BLOCK_CLASS),
                    "第 8 周的跨周原位置必须是灰显的提示块（" + ORIGINAL_BLOCK_CLASS + "），实际样式 "
                            + card.getStyleClass());
            require(!card.getStyleClass().contains(TARGET_BLOCK_CLASS),
                    "同一个块不得同时是原位置与新位置，实际样式 " + card.getStyleClass());
            require(cardTexts(card).contains(CROSS_WEEK_LOCATION),
                    "灰块上必须仍写着原教室 " + CROSS_WEEK_LOCATION + "，实际 " + cardTexts(card));
            require(!cardTexts(card).contains(CROSS_WEEK_NEW_LOCATION),
                    "灰块不得提前显示新教室，实际 " + cardTexts(card));
        }

        /** 跨周移动后的<b>新位置</b>：第 9 周唯一那块带 {@code teacher-schedule-adjusted-target}，地点是新的。 */
        private void requireCrossWeekTargetBlock() {
            Button card = cardForCourse(CROSS_WEEK_COURSE_NAME);
            require(card.getStyleClass().contains(TARGET_BLOCK_CLASS),
                    "第 9 周的跨周新位置必须是真实占用块（" + TARGET_BLOCK_CLASS + "），实际样式 "
                            + card.getStyleClass());
            require(!card.getStyleClass().contains(ORIGINAL_BLOCK_CLASS),
                    "新位置不得带原位置的灰显样式，实际样式 " + card.getStyleClass());
            require(cardTexts(card).contains(CROSS_WEEK_NEW_LOCATION),
                    "新位置必须写新教室 " + CROSS_WEEK_NEW_LOCATION + "，实际 " + cardTexts(card));
            require(!cardTexts(card).contains(CROSS_WEEK_LOCATION),
                    "新位置不得还写着原教室，实际 " + cardTexts(card));
        }

        /** 课表卡片正文里的全部标签文本（标题 + 元信息 + 角标），按出现顺序。 */
        private static List<String> cardTexts(Node card) {
            StringBuilder text = new StringBuilder();
            collectLabelText(card, text);
            return text.toString().lines().toList();
        }

        /** 窗口拉窄到装不下整表时：表格按最小宽度渲染（不压字），横向滚动接管，纵向照样能滚。 */
        private void requireGridScrollsInASmallWindow() {
            ScrollPane scroll = requireNode("#scheduleScroll", ScrollPane.class, "课表滚动容器");
            GridPane grid = requireNode("#scheduleGrid", GridPane.class, "课表网格");
            double viewportWidth = scroll.getViewportBounds().getWidth();
            require(grid.getWidth() >= grid.minWidth(-1) - 1.0,
                    "窄窗口下表格不得被压到最小宽度以下，实际网格 " + grid.getWidth()
                            + " / 最小宽度 " + grid.minWidth(-1));
            require(grid.getWidth() > viewportWidth + 1.0,
                    "窄窗口下表格应比视口宽并交给横向滚动，实际网格 " + grid.getWidth()
                            + " / 视口 " + viewportWidth);
            scrollGridToBottom("窄窗口滚到底");
            scroll.setVvalue(0.0);
            scroll.setHvalue(0.0);
        }

        private Button button(String selector, String description) {
            return requireNode(selector, Button.class, description);
        }

        // ---------------------------------------------------------------- 周次控件

        @SuppressWarnings("unchecked")
        private Spinner<Integer> weekSpinner() {
            return (Spinner<Integer>) requireNode("#weekSpinner", Spinner.class, "周次控件");
        }

        private SpinnerValueFactory.IntegerSpinnerValueFactory weekRange() {
            SpinnerValueFactory<Integer> factory = weekSpinner().getValueFactory();
            if (!(factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory range)) {
                throw new IllegalStateException(
                        "周次控件必须有一个整数范围的值工厂，实际 " + factory);
            }
            return range;
        }

        /** 周次控件当前显示的周：控件值与输入框文本必须一致，用户看到的就是它。 */
        private int shownWeek() {
            Spinner<Integer> spinner = weekSpinner();
            Integer value = spinner.getValue();
            if (value == null) {
                throw new IllegalStateException(
                        "周次控件没有值：范围应来自服务端的 minWeek/maxWeek");
            }
            require(String.valueOf(value).equals(spinner.getEditor().getText()),
                    "周次输入框必须显示当前这一周，实际输入框 "
                            + spinner.getEditor().getText() + " / 值 " + value);
            return value;
        }

        /** 按一次向下箭头（键盘 ↓ 走同一条 value factory 路径）：R4 之后它是往后一周。 */
        private void pressWeekDown() {
            weekSpinner().decrement(1);
        }

        /** 按一次向上箭头（键盘 ↑ 走同一条 value factory 路径）：R4 之后它是往前一周。 */
        private void pressWeekUp() {
            weekSpinner().increment(1);
        }

        private String labelText(String selector) {
            Label label = requireNode(selector, Label.class, "标签 " + selector);
            return label.getText() == null ? "" : label.getText();
        }

        /**
         * 在 FX 线程上等待一段真实时间：被阻塞的这段时间里没有脉冲，但动画时钟按脉冲时间戳推进，
         * 因此下一次脉冲会把已经过期的动画一次性推到结束（提示消退正是这样一条 Timeline）。
         * 只在必须等真实时间的地方用（提示的 3 秒消退），别拿它代替逐步的交互。
         */
        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待提示消退时被中断", interrupted);
            }
        }

        /** Tab 正文里的文本行数：正文由控制器用 Label 逐行渲染，行数即数据完整度。 */
        private int bodyLines(String selector) {
            VBox body = requireNode(selector, VBox.class, "Tab 正文 " + selector);
            return body.getChildren().size();
        }

        private <T extends Node> T requireNode(String selector, Class<T> type, String description) {
            Node node = root.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("找不到" + description + "（选择器 " + selector + "）");
            }
            if (!type.isInstance(node)) {
                throw new IllegalStateException(description + "（选择器 " + selector + "）类型应为 "
                        + type.getSimpleName() + "，实际 " + node.getClass().getName());
            }
            return type.cast(node);
        }

        private <T extends Node> T requireIn(Parent scope, String selector, Class<T> type,
                String description) {
            Node node = scope.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("找不到" + description + "（选择器 " + selector + "）");
            }
            if (!type.isInstance(node)) {
                throw new IllegalStateException(description + "类型应为 " + type.getSimpleName()
                        + "，实际 " + node.getClass().getName());
            }
            return type.cast(node);
        }

        private String labelIn(Parent scope, String selector) {
            Label label = requireIn(scope, selector, Label.class, "弹窗标签 " + selector);
            return label.getText() == null ? "" : label.getText();
        }

        /** 某个样式类下所有节点的文本（顺序不保证，调用方自行排序）。 */
        private List<String> nodeTexts(String selector) {
            List<String> texts = new ArrayList<>();
            for (Node node : root.lookupAll(selector)) {
                if (node instanceof Label label) {
                    texts.add(label.getText() == null ? "" : label.getText());
                }
            }
            return texts;
        }

        private static String labelTextOf(Node node) {
            return node instanceof Label label && label.getText() != null ? label.getText() : "";
        }

        /** 节点自身与所有祖先都可见、且已经挂进场景时才算真的看得见。 */
        private static boolean effectivelyVisible(Node node) {
            if (node.getScene() == null) {
                return false;
            }
            for (Node current = node; current != null; current = current.getParent()) {
                if (!current.isVisible()) {
                    return false;
                }
            }
            return true;
        }

        // ---------------------------------------------------------------- 断言与截图

        /** 步骤是 Runnable，所以写图失败以未检查异常抛出；{@link #advance()} 会捕获并以退出码 1 结束。 */
        private void snapshot(String fileName) {
            snapshotNode(root, fileName);
        }

        /** 弹窗内容不在主 root 里，必须用弹窗自己的 Scene root 截图，否则只拍得到主窗口。 */
        private void snapshotNode(Node node, String fileName) {
            Path target = OUTPUT.resolve(fileName);
            WritableImage image = node.snapshot(null, null);
            try {
                boolean written = ImageIO.write(
                        SwingFXUtils.fromFXImage(image, null), "png", target.toFile());
                if (!written) {
                    throw new IOException("没有可用的 PNG 编码器：" + target);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("写入截图失败：" + target, failure);
            }
            System.out.println("[ui] " + fileName + " -> " + target.toAbsolutePath());
        }

        private static TeacherOfferingDTO offeringAt(TableView<?> table, int index) {
            Object item = table.getItems().get(index);
            if (!(item instanceof TeacherOfferingDTO offering)) {
                throw new IllegalStateException("第 " + index + " 行不是教学班 DTO：" + item);
            }
            return offering;
        }

        private static String rosterName(Object item) {
            if (!(item instanceof TeacherRosterRowDTO row)) {
                throw new IllegalStateException("名单行不是名单 DTO：" + item);
            }
            String name = row.getStudentName();
            return name == null ? "" : name;
        }

        private static List<String> tabTexts(TabPane tabs) {
            List<String> texts = new ArrayList<>();
            for (Tab tab : tabs.getTabs()) {
                texts.add(tab.getText());
            }
            return List.copyOf(texts);
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }
}
