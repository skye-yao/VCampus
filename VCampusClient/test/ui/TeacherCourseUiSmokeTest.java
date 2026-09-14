package ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import service.MockTeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台 GUI 冒烟：用真实 JavaFX 工具包装入真实教师外壳，由 {@link MockTeacherCourseService}
 * 驱动，走一遍“首页 → 教学课程表（周视图 / 卡片详情 / 周导航）→ 教学班列表 → 四 Tab 详情 →
 * 退回列表 → 空名单教学班”的真实交互并逐步截图。
 *
 * <p>这里刻意不解析 XML 代替加载：断言全部打在真实节点上（表格行数、Tab 文本、占位符可见性、
 * 入口按钮的启用/禁用、课表网格的列数/行数/卡片数），既能抓住只在加载期才暴露的 FXML 属性错误，
 * 也能证明 DOM 解析看不见的布局与绑定确实生效。任一步失败都以退出码 1 结束，不会被脚本当成通过。
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
    /** Mock 周 8 的跨周原位置卡片对应的课程；点它打开详情再“查看教学班”。 */
    private static final String CROSS_WEEK_COURSE_NAME = "数据结构与算法基础";
    private static final String CROSS_WEEK_LOCATION = "A-101";
    private static final String WEEK_EIGHT_LABEL = "第 8 周（1-16）";
    private static final String WEEK_NINE_LABEL = "第 9 周（1-16）";
    private static final String WEEK_FIVE_LABEL = "第 5 周（1-16）";
    private static final String EMPTY_WEEK_TEXT = "本周没有课程";
    private static final int WEEK_EIGHT_CARDS = 4;
    private static final int WEEK_NINE_CARDS = 1;
    private static final String[] FILES = {
            "offering-list.png", "detail-basic-info.png", "detail-roster-page1.png",
            "roster-page2.png", "detail-schedule.png", "detail-grades.png", "roster-empty.png",
            "schedule-week8.png", "schedule-card-detail.png", "schedule-week9.png",
            "schedule-empty-week.png"
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
            planSteps();
            advance();
        }

        // ---------------------------------------------------------------- 步骤编排

        /**
         * 顺序即语义：每一步都在前一步的界面落定之后运行，触发加载的动作与断言其结果的步骤分开，
         * 因此每个断言看到的都是加载完成后的界面。
         */
        private void planSteps() {
            steps.add(() -> {
                require(!entryButton("教学班").isDisabled(),
                        "教学班入口在 T5 接通后必须可用");
                require(!entryButton("教学课程表").isDisabled(),
                        "教学课程表入口在 T4 接通后必须可用");
                for (String staging : List.of("成绩录入", "我的申请")) {
                    require(entryButton(staging).isDisabled(),
                            staging + "仍属于后续阶段，入口必须保持禁用");
                }
            });

            // 教学课程表：真实点击进入，先看 Mock 第 8 周（含周末课、第 13 节、原位置提示与调课后卡片）。
            steps.add(() -> entryButton("教学课程表").fire());
            steps.add(() -> {
                GridPane grid = requireNode("#scheduleGrid", GridPane.class, "课表网格");
                require(effectivelyVisible(grid), "点击教学课程表后课表网格必须可见");
                require(!requireNode("#homePanel", VBox.class, "首页提示区").isVisible(),
                        "进入课表页后首页提示区必须被替换掉");
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

                require(labelText("#weekLabel").equals(WEEK_EIGHT_LABEL),
                        "周标签应为 " + WEEK_EIGHT_LABEL + "，实际 " + labelText("#weekLabel"));
                require(cards().size() == WEEK_EIGHT_CARDS,
                        "第 8 周应有 " + WEEK_EIGHT_CARDS + " 张卡片，实际 " + cards().size());
                List<String> badges = nodeTexts(".teacher-schedule-badge");
                require(badges.contains("原安排") && badges.contains("调课后"),
                        "第 8 周必须同时出现 原安排 与 调课后 角标，实际 " + badges);
                require(!button("#previousWeekButton", "上一周按钮").isDisabled(),
                        "第 8 周不是最小周，上一周必须可用");
                require(!button("#nextWeekButton", "下一周按钮").isDisabled(),
                        "第 8 周不是最大周，下一周必须可用");
                snapshot("schedule-week8.png");
            });

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
                        "申请调课属于 T3，本阶段必须保持禁用");
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

            // 回到课表：周导航（跨周调入的第 9 周与无课的第 5 周）。
            steps.add(() -> entryButton("教学课程表").fire());
            steps.add(() -> require(labelText("#weekLabel").equals(WEEK_EIGHT_LABEL),
                    "回到课表应恢复第 8 周，实际 " + labelText("#weekLabel")));
            steps.add(() -> button("#nextWeekButton", "下一周按钮").fire());
            steps.add(() -> {
                require(labelText("#weekLabel").equals(WEEK_NINE_LABEL),
                        "下一周应为 " + WEEK_NINE_LABEL + "，实际 " + labelText("#weekLabel"));
                require(cards().size() == WEEK_NINE_CARDS,
                        "第 9 周只应剩跨周调入的 " + WEEK_NINE_CARDS + " 张卡片，实际 "
                                + cards().size());
                snapshot("schedule-week9.png");
            });
            steps.add(() -> button("#previousWeekButton", "上一周按钮").fire());
            steps.add(() -> {
                require(labelText("#weekLabel").equals(WEEK_EIGHT_LABEL),
                        "上一周应回到第 8 周，实际 " + labelText("#weekLabel"));
                require(cards().size() == WEEK_EIGHT_CARDS,
                        "第 8 周应恢复 " + WEEK_EIGHT_CARDS + " 张卡片，实际 " + cards().size());
            });

            // 无课周：卡片为 0，但 7 列日期、13 行节次与空态文案仍在。
            steps.add(() -> button("#previousWeekButton", "上一周按钮").fire());
            steps.add(() -> button("#previousWeekButton", "上一周按钮").fire());
            steps.add(() -> button("#previousWeekButton", "上一周按钮").fire());
            steps.add(() -> {
                require(labelText("#weekLabel").equals(WEEK_FIVE_LABEL),
                        "三次上一周后应为 " + WEEK_FIVE_LABEL + "，实际 " + labelText("#weekLabel"));
                require(cards().isEmpty(), "Mock 第 5 周没有课程，实际 " + cards().size() + " 张卡片");
                require(nodeTexts(".teacher-schedule-header").size() == 8,
                        "无课周仍必须画出节次列 + 7 个日期列");
                require(nodeTexts(".teacher-schedule-period-label").size() == 13,
                        "无课周仍必须画出 13 行节次");
                Label empty = requireIn(scheduleScope(), "#emptyLabel", Label.class, "空态文案");
                require(empty.isVisible() && EMPTY_WEEK_TEXT.equals(empty.getText()),
                        "无课周必须显示 " + EMPTY_WEEK_TEXT + "，实际 " + empty.getText());
                snapshot("schedule-empty-week.png");
            });

            // 回到本周：Mock 的 currentWeek 是第 8 周。
            steps.add(() -> button("#currentWeekButton", "回到本周按钮").fire());
            steps.add(() -> require(labelText("#weekLabel").equals(WEEK_EIGHT_LABEL),
                    "回到本周应恢复 " + WEEK_EIGHT_LABEL + "，实际 " + labelText("#weekLabel")));

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

            // 收尾：课次详情弹窗是 WINDOW_MODAL + show()（不阻塞），结束时不能有遗留窗口。
            steps.add(() -> {
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
            settle.setOnFinished(event -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    Platform.exit();
                    System.exit(1);
                    return;
                }
                advance();
            });
            settle.play();
        }

        // ---------------------------------------------------------------- 节点查找

        private Button entryButton(String text) {
            for (Node node : root.lookupAll(".teacher-course-entry")) {
                if (node instanceof Button button && text.equals(button.getText())) {
                    return button;
                }
            }
            throw new IllegalStateException("找不到工作台入口按钮：" + text);
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
         * 课表子页的根节点。工作台里教学班页也有一个 {@code #emptyLabel}，所以课表页里按 id
         * 查节点必须限定在这个子页内，否则会命中先被遍历到的教学班空态文案。
         */
        private Parent scheduleScope() {
            return requireNode("#schedulePage", Parent.class, "课表子页");
        }

        private Button button(String selector, String description) {
            return requireNode(selector, Button.class, description);
        }

        private String labelText(String selector) {
            Label label = requireNode(selector, Label.class, "标签 " + selector);
            return label.getText() == null ? "" : label.getText();
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
