package ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.MockAdminCourseService;

/**
 * 管理员审批页的 GUI 冒烟：用真实 JavaFX 工具包装入真实审批外壳，由 {@link MockAdminCourseService}
 * 驱动，进入“成绩审批”标签页，逐条点击成绩提交并断言右侧详情面板。
 *
 * <p>存在的理由只有一个：成绩提交详情面板上的<b>组成与权重、提交人数、基础批次、未纳入批次的新成员</b>
 * 都是 T1-T4 新增的显示，而它在 T5 之前只有纯函数单元测试、没有任何渲染证据（当时工具包起不来），
 * 也没有任何 GUI 冒烟加载过 {@code AdminApprovalView.fxml}/{@code GradeApprovalView.fxml}——FXML 里
 * 一个未转义的 {@code %} 就会让整页加载失败，这正是只有真实工具包能抓住的一类缺陷。
 *
 * <p>断言打在真实节点上：列表行、详情行文本、指标卡与成绩表行数都从界面读；提交详情由 Mock 的
 * 夹具提供，因此这里同时钉住“Mock 必须给出方案快照等新字段”，否则界面会退回
 * “历史批次未记录方案快照”的兜底文案（本冒烟把它当作失败）。
 */
public final class AdminApprovalUiSmokeTest {
    private static final String VIEW_PATH =
            "/resources/fxml/AdminApprovalView.fxml";
    private static final double WIDTH = 860.0;
    private static final double HEIGHT = 580.0;
    /** 每步之间等待界面落定的时间；Mock 的 Future 已完成，只需等待一个脉冲。 */
    private static final double SETTLE_MILLIS = 200.0;
    private static final String[] FILES = {"admin-grade-approval.png", "admin-grade-resubmission.png"};
    private static final Path OUTPUT = Path.of(".codex-tmp", "admin-approval");

    /** Mock 夹具 9001（PENDING，2 名学生，平均 87.5）。 */
    private static final String PLAIN_ROW_MARKER = "平均：87.5";
    /** Mock 夹具 9004（9001 的重提，平均 93.0）。 */
    private static final String RESUBMISSION_ROW_MARKER = "平均：93.0";
    /** 详情面板上的方案行：30/20/20/30 必须由 Mock 的真实快照渲染出来。 */
    private static final String SCHEME_LINE =
            "成绩组成：平时 30.00%　期中 20.00%　实验 20.00%　期末 30.00%";
    private static final String LEGACY_FALLBACK = "历史批次未记录方案快照";

    static void prepareOutputDirectory() throws IOException {
        Files.createDirectories(OUTPUT);
        for (String file : FILES) {
            Files.deleteIfExists(OUTPUT.resolve(file));
        }
    }

    public static void main(String[] args) throws Exception {
        AdminCourseServices.install(new MockAdminCourseService());
        AdminCourseUiPreview.requireMockService(AdminCourseServices.current());
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
            var resource = getClass().getResource(VIEW_PATH);
            if (resource == null) {
                throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
            }
            this.primaryStage = stage;
            root = FXMLLoader.load(resource);
            stage.setTitle("教务管理系统 - 审批冒烟");
            stage.setScene(new Scene(root, WIDTH, HEIGHT));
            stage.setResizable(false);
            stage.show();
            planSteps();
            advance();
        }

        private void planSteps() {
            steps.add(() -> {
                require(gradePage() != null && !gradePage().isVisible(),
                        "审批外壳默认停在调课标签页");
                requireNode("#adjustmentPanel", VBox.class, "调课面板").isVisible();
            });
            steps.add(() -> requireNode("#gradeTabButton", ToggleButton.class, "成绩审批标签").fire());
            steps.add(() -> {
                require(effectivelyVisible(gradePage()), "点成绩审批后成绩子页必须可见");
                require(!requireNode("#adjustmentPanel", VBox.class, "调课面板").isVisible(),
                        "切到成绩审批后调课面板必须被替换掉");
                VBox list = gradeNode("#submissionList", VBox.class, "成绩提交列表");
                require(list.getChildren().size() == 2,
                        "默认 PENDING 筛选应有 2 条提交，实际 " + list.getChildren().size());
                require(rowText(list.getChildren().get(0)).contains(RESUBMISSION_ROW_MARKER)
                                && rowText(list.getChildren().get(1)).contains(PLAIN_ROW_MARKER),
                        "列表按提交时间倒序：先 9004 后 9001，实际 "
                                + rowText(list.getChildren().get(0)) + " / "
                                + rowText(list.getChildren().get(1)));
            });

            // 9001：单据、统计、方案与明细都必须来自真实的提交详情，而不是历史批次兜底文案。
            steps.add(() -> clickRow(PLAIN_ROW_MARKER));
            steps.add(() -> {
                require(labelText("#detailTitleLabel").contains("9001")
                                && labelText("#detailTitleLabel").contains("待审批"),
                        "详情标题必须写明提交编号与状态，实际 " + labelText("#detailTitleLabel"));
                List<String> lines = detailLines();
                require(lines.contains("提交人数：2 人　不及格：0 人"),
                        "详情必须给出提交人数，实际 " + lines);
                require(lines.contains(SCHEME_LINE),
                        "详情必须渲染真实的成绩组成与权重，实际 " + lines);
                require(lines.stream().noneMatch(line -> line.contains(LEGACY_FALLBACK)),
                        "Mock 夹具带着方案快照，不得退回历史批次兜底文案，实际 " + lines);
                require(requireNode("#metricCardBox", HBox.class, "统计指标卡")
                                .getChildren().size() == 4
                                && requireNode("#distributionRows", VBox.class, "成绩分布")
                                .getChildren().size() == 5,
                        "四张指标卡与五个分布分段必须都在");
                require(table("#itemTable", "成绩明细").getItems().size() == 2,
                        "成绩明细应有两行");
                require(!gradeNode("#approveButton", Button.class, "通过按钮").isDisabled(),
                        "PENDING 批次必须可以审批");
                // 组成/权重与提交人数在明细表下方的滚动区里：滚到底再截图，画面与断言必须一致。
                scrollDetailToBottom();
            });
            steps.add(() -> snapshot("admin-grade-approval.png"));

            // 9004：基础批次与“提交后新增、尚未纳入批次”的学生也要显示在详情上。
            steps.add(() -> clickRow(RESUBMISSION_ROW_MARKER));
            steps.add(() -> {
                require(labelText("#detailTitleLabel").contains("9004"),
                        "第二次点击必须打开 9004 的详情，实际 " + labelText("#detailTitleLabel"));
                List<String> lines = detailLines();
                require(lines.contains("基础批次：9001"),
                        "重提批次必须显示它基于哪一批，实际 " + lines);
                require(lines.stream().anyMatch(line -> line.contains("未纳入批次的新成员：1 人")),
                        "提交后新增的学生必须被报告出来，实际 " + lines);
                require(lines.contains(SCHEME_LINE)
                                && lines.stream().noneMatch(line -> line.contains(LEGACY_FALLBACK)),
                        "重提批次的组成与权重同样必须来自真实快照，实际 " + lines);
                scrollDetailToBottom();
            });
            steps.add(() -> snapshot("admin-grade-resubmission.png"));

            // 审批之后快照不能被丢掉：Mock 的决策路径必须原样带过方案等新字段。
            steps.add(() -> {
                AdminCourseService service = AdminCourseServices.current();
                service.reviewGradeSubmission(new ApprovalDecisionRequestDTO(
                        "00000000-0000-0000-0000-0000000009a1", "9001", 1, true, false, null, null))
                        .orTimeout(5, TimeUnit.SECONDS).join();
                GradeSubmissionDetailDTO decided = service.getGradeSubmission("9001")
                        .orTimeout(5, TimeUnit.SECONDS).join();
                require(decided.getSummary().getStatus() == ApprovalStatusDTO.APPROVED
                                && decided.getSchemeSnapshot() != null
                                && decided.getSchemeSnapshot().getComponents().size() == 4,
                        "审批后的详情必须保留方案快照（否则界面会退回兜底文案）");
            });
            steps.add(() -> requireNode("#refreshButton", Button.class, "刷新按钮").fire());
            steps.add(() -> {
                VBox list = gradeNode("#submissionList", VBox.class, "成绩提交列表");
                require(list.getChildren().size() == 1
                                && rowText(list.getChildren().get(0)).contains(RESUBMISSION_ROW_MARKER),
                        "审批 9001 之后 PENDING 列表必须只剩 9004，实际 "
                                + list.getChildren().size());
            });

            // 收尾：整个冒烟只应留下主窗口。
            steps.add(() -> {
                require(Window.getWindows().size() == 1,
                        "除主窗口外不应留下任何窗口，实际 " + Window.getWindows().size() + " 个");
                require(Window.getWindows().contains(primaryStage), "唯一剩下的窗口必须是主窗口");
            });
        }

        private void advance() {
            if (stepIndex >= steps.size()) {
                System.out.println("AdminApprovalUiSmokeTest: PASS (" + steps.size() + " steps, "
                        + FILES.length + " snapshots)");
                Platform.exit();
                return;
            }
            Runnable step = steps.get(stepIndex++);
            PauseTransition settle = new PauseTransition(Duration.millis(SETTLE_MILLIS));
            // 步骤经 Platform.runLater 进入事件队列：动画处理期间 JavaFX 会拒绝模态 showAndWait()。
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

        /** 成绩子页的根节点；外壳与成绩页有重名 fx:id（detailBody/detailTitleLabel 等），必须限定作用域。 */
        private Parent gradePage() {
            Node node = root.lookup("#gradePage");
            return node instanceof Parent parent ? parent : null;
        }

        private <T extends Node> T gradeNode(String selector, Class<T> type, String description) {
            Parent page = gradePage();
            Node node = page == null ? null : page.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("找不到" + description + "（选择器 " + selector + "）");
            }
            if (!type.isInstance(node)) {
                throw new IllegalStateException(description + " 类型应为 " + type.getSimpleName()
                        + "，实际 " + node.getClass().getName());
            }
            return type.cast(node);
        }

        private <T extends Node> T requireNode(String selector, Class<T> type, String description) {
            Node node = root.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("找不到" + description + "（选择器 " + selector + "）");
            }
            if (!type.isInstance(node)) {
                throw new IllegalStateException(description + " 类型应为 " + type.getSimpleName()
                        + "，实际 " + node.getClass().getName());
            }
            return type.cast(node);
        }

        private TableView<?> table(String selector, String description) {
            return gradeNode(selector, TableView.class, description);
        }

        private String labelText(String selector) {
            return gradeNode(selector, Label.class, "标签 " + selector).getText();
        }

        /** 把详情正文滚到底：组成/权重、提交人数、基础批次都在明细表下方的滚动区里。 */
        private void scrollDetailToBottom() {
            for (Node current = gradeNode("#detailBody", VBox.class, "详情正文");
                    current != null; current = current.getParent()) {
                if (current instanceof ScrollPane pane) {
                    pane.setVvalue(1.0);
                    return;
                }
            }
            throw new IllegalStateException("详情正文必须在可滚动容器里");
        }

        /** 成绩详情的固定文本行。 */
        private List<String> detailLines() {
            List<String> lines = new ArrayList<>();
            for (Node child : gradeNode("#detailBody", VBox.class, "详情正文").getChildren()) {
                if (child instanceof Label label) lines.add(label.getText());
            }
            return lines;
        }

        /** 按行文本里的标记点击一条成绩提交（列表行是带 onMouseClicked 的 VBox）。 */
        private void clickRow(String marker) {
            VBox list = gradeNode("#submissionList", VBox.class, "成绩提交列表");
            for (Node child : list.getChildren()) {
                if (rowText(child).contains(marker)) {
                    child.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                            MouseButton.PRIMARY, 1, false, false, false, false, true, false, false,
                            true, false, false, null));
                    return;
                }
            }
            throw new IllegalStateException("找不到包含 " + marker + " 的成绩提交行");
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

        /** 节点自身与所有祖先都可见、且已经挂进场景时才算真的看得见。 */
        private static boolean effectivelyVisible(Node node) {
            if (node == null || node.getScene() == null) return false;
            for (Node current = node; current != null; current = current.getParent()) {
                if (!current.isVisible()) return false;
            }
            return true;
        }

        // ---------------------------------------------------------------- 断言与截图

        private void snapshot(String fileName) {
            Path target = OUTPUT.resolve(fileName);
            WritableImage image = root.snapshot(null, null);
            try {
                if (!ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", target.toFile())) {
                    throw new IOException("没有可用的 PNG 编码器：" + target);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("写入截图失败：" + target, failure);
            }
            System.out.println("[ui] " + fileName + " -> " + target.toAbsolutePath());
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }

    private AdminApprovalUiSmokeTest() {
    }
}
