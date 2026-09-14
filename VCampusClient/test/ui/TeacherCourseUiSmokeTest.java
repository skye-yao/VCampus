package ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import service.MockTeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台 GUI 冒烟：用真实 JavaFX 工具包装入真实教师外壳，由 {@link MockTeacherCourseService}
 * 驱动，走一遍“首页 → 教学班列表 → 四 Tab 详情 → 退回列表 → 空名单教学班”的真实交互并逐步截图。
 *
 * <p>这里刻意不解析 XML 代替加载：断言全部打在真实节点上（表格行数、Tab 文本、占位符可见性、
 * 入口按钮的启用/禁用），既能抓住只在加载期才暴露的 FXML 属性错误，也能证明 DOM 解析看不见的
 * 布局与绑定确实生效。任一步失败都以退出码 1 结束，不会被脚本当成通过。
 *
 * <p>用真实点击而不是直接调控制器：教学班入口按钮是分阶段交付的产物（T4 曾禁用、T5 接通），
 * 只有真的点下去才能证明它现在是可用的。
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
    private static final String[] FILES = {
            "offering-list.png", "detail-basic-info.png", "detail-roster-page1.png",
            "roster-page2.png", "detail-schedule.png", "detail-grades.png", "roster-empty.png"
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
        private int stepIndex;

        @Override
        public void start(Stage stage) throws Exception {
            TeacherCourseUiPreview.requireMockService(TeacherCourseServices.current());
            var resource = getClass().getResource(VIEW_PATH);
            if (resource == null) {
                throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
            }
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
                for (String staging : List.of("教学课程表", "成绩录入", "我的申请")) {
                    require(entryButton(staging).isDisabled(),
                            staging + "仍属于后续阶段，入口必须保持禁用");
                }
            });
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

        // ---------------------------------------------------------------- 断言与截图

        /** 步骤是 Runnable，所以写图失败以未检查异常抛出；{@link #advance()} 会捕获并以退出码 1 结束。 */
        private void snapshot(String fileName) {
            Path target = OUTPUT.resolve(fileName);
            WritableImage image = root.snapshot(null, null);
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
