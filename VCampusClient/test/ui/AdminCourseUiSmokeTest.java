package ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import controller.CourseEditorDialogController;
import controller.OfferingEditorDialogController;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.MockAdminCourseService;
import session.ClientSession;

/**
 * 管理员课程 UI 冒烟测试：加载全部新增 FXML，逐个断言控件与行，再输出 860x580 截图。
 */
public final class AdminCourseUiSmokeTest {
    private static final String SHELL_PATH =
            "/resources/fxml/AdminCourseManagementView.fxml";
    private static final String CATALOG_PATH =
            "/resources/fxml/AdminCourseCatalogView.fxml";
    private static final String COURSE_EDITOR_PATH =
            "/resources/fxml/CourseEditorDialog.fxml";
    private static final String OFFERING_EDITOR_PATH =
            "/resources/fxml/OfferingEditorDialog.fxml";
    private static final String MAIN_VIEW_PATH = "/resources/fxml/MainView.fxml";
    private static final String[] FILES = {
            "catalog-populated.png", "catalog-expanded.png", "catalog-empty.png",
            "catalog-error.png", "course-editor.png", "offering-editor.png",
            "destroy-confirm.png"
    };
    private static final Path OUTPUT =
            Path.of(".codex-tmp", "admin-course-ui-snapshots");
    private static final double SHELL_WIDTH = 860.0;
    private static final double SHELL_HEIGHT = 580.0;
    private static final String EDITED_COURSE_NAME = "数据结构（修订）";
    private static final String ARCHIVE_MESSAGE =
            "确认归档“" + EDITED_COURSE_NAME + "”？归档后学生将无法选课。";

    static void prepareOutputDirectory() throws IOException {
        Files.createDirectories(OUTPUT);
        for (String file : FILES) {
            Files.deleteIfExists(OUTPUT.resolve(file));
        }
    }

    static void requireImageWritten(boolean written, Path target) throws IOException {
        if (!written) {
            throw new IOException("No PNG writer available for " + target);
        }
    }

    public static void main(String[] args) throws Exception {
        TogglableAdminCourseService service = new TogglableAdminCourseService();
        AdminCourseServices.install(service);
        prepareOutputDirectory();
        Application.launch(SnapshotApplication.class, args);
    }

    interface SmokeStep {
        void run() throws Exception;
    }

    public static final class SnapshotApplication extends Application {
        private final Deque<SmokeStep> steps = new ArrayDeque<>();
        private Parent root;
        private TogglableAdminCourseService service;
        private Stage shellStage;
        private Stage standaloneDialog;
        private Parent standaloneDialogRoot;
        private Window appEditorWindow;
        private Scene appEditorScene;
        private int confirmationAttempts;
        private int mutationCallsBefore;
        private int listCallsBeforeEdit;
        private int offeringCallsBefore;
        private int alertAttempts;
        private boolean alertSeen;
        private String alertContentText;
        private VBox detachedOfferingPanel;
        private int expectedOfferingRows = -1;
        private final List<String> variantFailures = new ArrayList<>();
        private int captures;

        @Override
        public void start(Stage stage) throws Exception {
            service = (TogglableAdminCourseService) AdminCourseServices.current();
            root = FXMLLoader.load(getClass().getResource(SHELL_PATH));
            stage.setScene(new Scene(root, SHELL_WIDTH, SHELL_HEIGHT));
            stage.setResizable(false);
            stage.show();
            shellStage = stage;

            steps.add(this::capturePopulatedCatalog);
            steps.add(this::expandOfferings);
            steps.add(this::captureExpandedOfferings);
            steps.add(() -> setSearchText("ZZZZ"));
            steps.add(this::captureEmptyState);
            steps.add(() -> setSearchText(""));
            steps.add(this::verifyPopulatedAgain);
            steps.add(this::requestFailedListing);
            steps.add(this::captureErrorState);
            steps.add(this::retryListing);
            steps.add(this::verifyRecoveredListing);
            steps.add(this::openAppDrivenCourseEditor);
            steps.add(this::captureAppDrivenCourseEditor);
            steps.add(this::saveAppDrivenCourseEditor);
            steps.add(this::verifyAuthoritativeReloadAfterEdit);
            steps.add(this::openArchiveConfirmation);
            steps.add(this::verifyCancelledArchiveMutation);
            steps.add(this::refreshCatalog);
            steps.add(this::startCollapseRaceOfferingLoad);
            steps.add(this::collapseOfferingPanel);
            steps.add(this::completeHiddenOfferingLoad);
            steps.add(this::requireOfferingResultAfterReExpand);
            steps.add(this::refreshCatalog);
            steps.add(this::startFailureRaceOfferingLoad);
            steps.add(this::collapseOfferingPanel);
            steps.add(this::failHiddenOfferingLoad);
            steps.add(this::requireNewOfferingLoadAfterReExpand);
            steps.add(this::requireOfferingRowsAfterRetry);
            steps.add(this::refreshCatalog);
            steps.add(this::startDetachedRaceOfferingLoad);
            steps.add(this::refreshCatalog);
            steps.add(this::requireDetachedOfferingPanel);
            steps.add(this::failDetachedOfferingLoad);
            steps.add(this::requireDetachedResponseIgnored);
            steps.add(this::requireCurrentRowStillLoads);
            steps.add(this::requireCurrentRowRendered);
            steps.add(this::showStandaloneOfferingEditor);
            steps.add(this::captureStandaloneOfferingEditor);
            steps.add(this::verifyMainViewRoleTitles);
            runNextStep();
        }

        /**
         * 每步之间留出渲染时间；步骤本身经 {@code Platform.runLater} 进入事件队列，
         * 否则在动画处理期间触发模态 {@code showAndWait()} 会被 JavaFX 拒绝。
         */
        private void runNextStep() {
            if (steps.isEmpty()) {
                Platform.exit();
                return;
            }
            SmokeStep step = steps.removeFirst();
            PauseTransition pause = new PauseTransition(Duration.millis(220));
            pause.setOnFinished(event -> Platform.runLater(() -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    fail(failure);
                    return;
                }
                runNextStep();
            }));
            pause.play();
        }

        private void capturePopulatedCatalog() throws Exception {
            requireRows(3, "populated catalog");
            requireNode("#createCourseButton", "create course button");
            requireImage(capture(root, "catalog-populated.png"), SHELL_WIDTH, SHELL_HEIGHT,
                    "catalog-populated.png");
        }

        private void expandOfferings() {
            ((Button) requireNode(".course-admin-details-button", "course details button")).fire();
        }

        private void captureExpandedOfferings() throws Exception {
            Node row = requireNode(".course-admin-offering-row", "expanded offering row");
            if (!row.isVisible()) throw new IllegalStateException("offering row must be visible");
            requireNoRowClipped();
            requireImage(capture(root, "catalog-expanded.png"), SHELL_WIDTH, SHELL_HEIGHT,
                    "catalog-expanded.png");
        }

        /**
         * 每行要么完整落在可视区域内，要么内容溢出时必须出现可用的滚动条。
         */
        private void requireNoRowClipped() {
            ScrollPane scroll = (ScrollPane) requireNode(
                    ".course-admin-list-scroll", "catalog scroll pane");
            double contentHeight = scroll.getContent().getBoundsInLocal().getHeight();
            double viewportHeight = scroll.getViewportBounds().getHeight();
            if (contentHeight > viewportHeight) {
                Node bar = scroll.lookup(".scroll-bar");
                if (bar == null || !bar.isVisible()) {
                    throw new IllegalStateException("overflowing catalog must show a scroll bar");
                }
                return;
            }
            double visibleBottom = scroll.localToScene(scroll.getBoundsInLocal()).getMaxY();
            for (Node row : scroll.getContent().lookupAll(".course-admin-row")) {
                double rowBottom = row.localToScene(row.getBoundsInLocal()).getMaxY();
                if (rowBottom > visibleBottom + 0.5) {
                    throw new IllegalStateException("course row is clipped by the catalog area:"
                            + " row bottom " + rowBottom + " > visible bottom " + visibleBottom);
                }
            }
        }

        private void captureEmptyState() throws Exception {
            requireVisible("#emptyLabel", "empty state label");
            requireRows(0, "empty result");
            requireImage(capture(root, "catalog-empty.png"), SHELL_WIDTH, SHELL_HEIGHT,
                    "catalog-empty.png");
        }

        /**
         * 清空搜索框后目录必须重新显示全部课程，且不残留错误状态。
         */
        private void verifyPopulatedAgain() {
            requireRows(3, "catalog after clearing the search box");
            if (requireNode("#errorLabel", "error state label").isVisible()) {
                throw new IllegalStateException(
                        "clearing the search box must clear the error state");
            }
        }

        private void requestFailedListing() {
            service.setFailListing(true);
            ((Button) requireNode("#refreshButton", "refresh button")).fire();
        }

        /**
         * 加载失败必须保留已经显示的课程行，同时展示可重试的错误状态。
         */
        private void captureErrorState() throws Exception {
            requireVisible("#errorLabel", "error state label");
            requireVisible("#errorRetryButton", "error retry button");
            requireRows(3, "error state with a previously loaded catalog");
            requireImage(capture(root, "catalog-error.png"), SHELL_WIDTH, SHELL_HEIGHT,
                    "catalog-error.png");
        }

        private void retryListing() {
            service.setFailListing(false);
            ((Button) requireNode("#errorRetryButton", "error retry button")).fire();
        }

        private void verifyRecoveredListing() {
            requireRows(3, "recovered catalog");
            if (requireNode("#errorLabel", "error state label").isVisible()) {
                throw new IllegalStateException("retry must clear the error state");
            }
        }

        /**
         * 真实行内“编辑”控件必须打开控制器渲染的对话框，而不是测试自建的对话框。
         */
        private void openAppDrivenCourseEditor() {
            ((Button) requireNode(".course-admin-edit-button", "course row edit button")).fire();
            appEditorWindow = findAppDialogWindow("course-admin-dialog");
            if (appEditorWindow == null) {
                throw new IllegalStateException(
                        "the row edit control must open the rendered course editor dialog");
            }
            appEditorScene = appEditorWindow.getScene();
            if (!(appEditorScene.lookup("#courseCodeField") instanceof TextField codeField)
                    || !codeField.isDisable()) {
                throw new IllegalStateException(
                        "the rendered course editor must keep the course code disabled");
            }
            if (!"CS203".equals(codeField.getText())) {
                throw new IllegalStateException(
                        "the row edit control must open course CS203, saw " + codeField.getText());
            }
            if (!(appEditorScene.lookup("#courseNameField") instanceof TextField nameField)
                    || !"数据结构".equals(nameField.getText())) {
                throw new IllegalStateException(
                        "the row edit control must prefill the course name");
            }
            if (appEditorWindow instanceof Stage stage
                    && !"编辑课程".equals(stage.getTitle())) {
                throw new IllegalStateException(
                        "the course editor window must be titled 编辑课程, saw " + stage.getTitle());
            }
        }

        private void captureAppDrivenCourseEditor() throws Exception {
            requireImage(captureWindow(appEditorWindow, "course-editor.png"), "course-editor.png");
        }

        /**
         * 在控制器打开的对话框里改字段并保存，断言 Service 收到收集到的请求与权威重载。
         */
        private void saveAppDrivenCourseEditor() {
            if (appEditorScene == null) {
                throw new IllegalStateException("no rendered course editor to save");
            }
            if (!(appEditorScene.lookup("#courseNameField") instanceof TextField nameField)) {
                throw new IllegalStateException(
                        "the rendered course editor must expose #courseNameField");
            }
            nameField.setText(EDITED_COURSE_NAME);
            listCallsBeforeEdit = service.listCoursesCalls();
            if (!(appEditorScene.lookup("#saveButton") instanceof Button saveButton)) {
                throw new IllegalStateException(
                        "the rendered course editor must expose #saveButton");
            }
            saveButton.fire();

            CourseEditorRequestDTO request = service.lastCourseUpdate();
            if (request == null) {
                throw new IllegalStateException(
                        "saving the rendered course editor must reach the Service");
            }
            if (!"101".equals(request.getCourseId())) {
                throw new IllegalStateException(
                        "the collected request must target course 101, saw " + request.getCourseId());
            }
            if (request.getExpectedVersion() != 1) {
                throw new IllegalStateException(
                        "the collected request must carry expectedVersion 1, saw "
                                + request.getExpectedVersion());
            }
            if (!"CS203".equals(request.getCourseCode())) {
                throw new IllegalStateException(
                        "the collected request must keep course code CS203, saw "
                                + request.getCourseCode());
            }
            if (!EDITED_COURSE_NAME.equals(request.getCourseName())) {
                throw new IllegalStateException(
                        "the collected request must carry the edited name, saw "
                                + request.getCourseName());
            }
            UUID.fromString(request.getOperationId());
            if (appEditorWindow != null && appEditorWindow.isShowing()) {
                throw new IllegalStateException("saving the course editor must close it");
            }
        }

        private void verifyAuthoritativeReloadAfterEdit() {
            if (service.listCoursesCalls() <= listCallsBeforeEdit) {
                throw new IllegalStateException(
                        "a successful editor save must reload the authoritative catalog");
            }
            boolean renamed = false;
            for (Node title : root.lookupAll(".course-admin-row-title")) {
                if (title instanceof Label label && EDITED_COURSE_NAME.equals(label.getText())) {
                    renamed = true;
                }
            }
            if (!renamed) {
                throw new IllegalStateException(
                        "the reloaded catalog must render " + EDITED_COURSE_NAME);
            }
        }

        /**
         * 触发真实行内“归档课程”菜单项；确认框由控制器经 AlertUtil 弹出并在自己的嵌套事件循环里
         * 渲染，所以必须先排队取消任务，再调用会阻塞的 {@code fire()}。
         */
        private void openArchiveConfirmation() {
            MenuButton menu = (MenuButton) requireNode(".course-admin-row-menu", "course row menu");
            if (menu.getItems().size() != 1
                    || !"归档课程".equals(menu.getItems().get(0).getText())) {
                throw new IllegalStateException(
                        "an ACTIVE course row must offer 归档课程, saw " + menu.getItems());
            }
            mutationCallsBefore = service.mutationCalls();
            confirmationAttempts = 0;
            Platform.runLater(this::captureAndCancelConfirmation);
            menu.getItems().get(0).fire();
        }

        /**
         * 在确认框的嵌套事件循环里运行：断言文案与按钮、截图，然后取消。
         */
        private void captureAndCancelConfirmation() {
            DialogPane pane = renderedConfirmationPane();
            if (pane == null) {
                if (++confirmationAttempts > 60) {
                    fail(new IllegalStateException(
                            "the 归档课程 row action must render a confirmation window"));
                    return;
                }
                Platform.runLater(this::captureAndCancelConfirmation);
                return;
            }
            try {
                requireArchiveConfirmationText(pane);
                requireImage(capture(pane, "destroy-confirm.png"), "destroy-confirm.png");
                fireCancel(pane);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        /**
         * 确认框必须由真实行内公文控件触发，文案随目标课程变化。
         */
        private void requireArchiveConfirmationText(DialogPane pane) {
            Window window = pane.getScene().getWindow();
            String title = window instanceof Stage stage ? stage.getTitle() : null;
            if (!"归档课程".equals(title)) {
                throw new IllegalStateException(
                        "the destructive confirmation must be produced by the 归档课程 row action,"
                                + " saw title " + title);
            }
            if (pane.getHeaderText() != null) {
                throw new IllegalStateException(
                        "the archive confirmation must not set a header, saw "
                                + pane.getHeaderText());
            }
            if (!ARCHIVE_MESSAGE.equals(pane.getContentText())) {
                throw new IllegalStateException(
                        "the archive confirmation must say " + ARCHIVE_MESSAGE + ", saw "
                                + pane.getContentText());
            }
            if (!pane.getButtonTypes().contains(ButtonType.OK)
                    || !pane.getButtonTypes().contains(ButtonType.CANCEL)) {
                throw new IllegalStateException(
                        "the archive confirmation must offer confirm and cancel buttons, saw "
                                + pane.getButtonTypes());
            }
        }

        private void verifyCancelledArchiveMutation() {
            if (service.mutationCalls() != mutationCallsBefore) {
                throw new IllegalStateException(
                        "a cancelled confirmation must not mutate, saw "
                                + (service.mutationCalls() - mutationCallsBefore) + " calls");
            }
            if (renderedConfirmationPane() != null) {
                throw new IllegalStateException("the cancelled confirmation must be closed");
            }
            requireRows(3, "catalog after a cancelled confirmation");
        }

        /**
         * 从顶层窗口里找控制器打开的对话框：排除外壳与测试自建窗口。
         */
        private Window findAppDialogWindow(String styleClass) {
            for (Window window : Window.getWindows()) {
                if (window == shellStage || window == standaloneDialog
                        || !window.isShowing() || window.getScene() == null) {
                    continue;
                }
                if (window.getScene().getRoot().getStyleClass().contains(styleClass)) {
                    return window;
                }
            }
            return null;
        }

        private DialogPane renderedConfirmationPane() {
            for (Window window : Window.getWindows()) {
                if (window == shellStage || window == standaloneDialog
                        || !window.isShowing() || window.getScene() == null) {
                    continue;
                }
                if (window.getScene().getRoot() instanceof DialogPane pane) return pane;
            }
            return null;
        }

        private void fireCancel(DialogPane pane) {
            Node cancel = pane.lookupButton(ButtonType.CANCEL);
            if (!(cancel instanceof Button button)) {
                throw new IllegalStateException("the confirmation must offer a cancel button");
            }
            button.fire();
        }

        /**
         * 记录失败但不立即中断，便于一次 RED 运行收集全部三个场景的证据。
         */
        private void requireVariant(boolean condition, String message) {
            if (!condition) variantFailures.add(message);
        }

        private Button offeringToggle() {
            Node node = root.lookup(".course-admin-details-button");
            return node instanceof Button button ? button : null;
        }

        private VBox offeringPanel() {
            Node node = root.lookup(".course-admin-offering-list");
            return node instanceof VBox box ? box : null;
        }

        private boolean panelStuckLoading(VBox panel) {
            return panel != null && panel.lookup(".course-admin-loading-text") != null;
        }

        private int panelOfferingRows(VBox panel) {
            return panel == null ? -1 : panel.lookupAll(".course-admin-offering-row").size();
        }

        private void refreshCatalog() {
            ((Button) requireNode("#refreshButton", "refresh button")).fire();
        }

        /** 展开第一门课程的详情，并让它的教学班加载停在进行中。 */
        private void startOfferingLoad() {
            service.holdNextOfferingLoad();
            offeringCallsBefore = service.offeringCalls();
            Button toggle = offeringToggle();
            requireVariant(toggle != null, "the catalog must render a course details toggle");
            if (toggle != null) toggle.fire();
            VBox panel = offeringPanel();
            requireVariant(panel != null && panel.isVisible(),
                    "expanding a course must show its offering panel");
            requireVariant(panelStuckLoading(panel),
                    "expanding a course must show 正在加载教学班... while the load is in flight");
        }

        private void collapseOfferingPanel() {
            Button toggle = offeringToggle();
            if (toggle != null) toggle.fire();
            VBox panel = offeringPanel();
            requireVariant(panel != null && !panel.isVisible(),
                    "collapsing a course must hide its offering panel");
        }

        private void startCollapseRaceOfferingLoad() {
            startOfferingLoad();
        }

        private void startFailureRaceOfferingLoad() {
            startOfferingLoad();
        }

        private void completeHiddenOfferingLoad() {
            if (service.pendingOfferingLoad == null) {
                requireVariant(false, "an offering load must be pending before it can complete");
                return;
            }
            List<AdminOfferingView> loaded =
                    service.authoritativeOfferings(service.pendingOfferingCourseId);
            expectedOfferingRows = loaded.size();
            service.pendingOfferingLoad.complete(loaded);
        }

        /**
         * 收起状态下完成的响应必须被采纳：再展开应直接显示权威结果，而不是卡在加载占位。
         */
        private void requireOfferingResultAfterReExpand() {
            Button toggle = offeringToggle();
            if (toggle != null) toggle.fire();
            VBox panel = offeringPanel();
            requireVariant(panel != null && panel.isVisible(),
                    "re-expanding a course must show its offering panel");
            requireVariant(!panelStuckLoading(panel),
                    "a load that completed while collapsed must not leave 正在加载教学班... on"
                            + " re-expansion");
            requireVariant(panelOfferingRows(panel) == expectedOfferingRows,
                    "re-expansion must show the completed authoritative offering result, expected "
                            + expectedOfferingRows + " offering rows but saw "
                            + panelOfferingRows(panel));
            requireVariant(service.offeringCalls() == offeringCallsBefore + 1,
                    "re-expansion must not re-request a load that already completed, saw "
                            + (service.offeringCalls() - offeringCallsBefore - 1) + " extra calls");
        }

        private void failHiddenOfferingLoad() {
            if (service.pendingOfferingLoad == null) {
                requireVariant(false, "an offering load must be pending before it can fail");
                return;
            }
            resetAlertProbe();
            Platform.runLater(this::dismissRenderedAlert);
            service.pendingOfferingLoad.completeExceptionally(
                    new IllegalStateException("模拟教学班加载失败"));
        }

        /**
         * 收起状态下失败的响应也必须留下可用的重试路径：再展开应发出新的加载请求。
         */
        private void requireNewOfferingLoadAfterReExpand() {
            Button toggle = offeringToggle();
            if (toggle != null) toggle.fire();
            requireVariant(service.offeringCalls() == offeringCallsBefore + 2,
                    "re-expansion after a failed in-flight load must issue a new offering load, saw "
                            + (service.offeringCalls() - offeringCallsBefore - 1) + " new calls");
        }

        private void requireOfferingRowsAfterRetry() {
            VBox panel = offeringPanel();
            requireVariant(!panelStuckLoading(panel),
                    "the retried offering load must not stay on 正在加载教学班...");
            requireVariant(panelOfferingRows(panel) > 0,
                    "the retried offering load must render the authoritative offerings, saw "
                            + panelOfferingRows(panel) + " offering rows");
        }

        /** 展开详情、记住该面板，随后整体重渲染把它变成脱离场景的旧行。 */
        private void startDetachedRaceOfferingLoad() {
            service.holdNextOfferingLoad();
            Button toggle = offeringToggle();
            if (toggle != null) toggle.fire();
            detachedOfferingPanel = offeringPanel();
            requireVariant(detachedOfferingPanel != null && detachedOfferingPanel.isVisible(),
                    "expanding a course must show its offering panel");
        }

        private void requireDetachedOfferingPanel() {
            requireVariant(detachedOfferingPanel != null
                            && detachedOfferingPanel.getScene() == null,
                    "a full catalog re-render must detach the previous offering panel");
            requireVariant(detachedOfferingPanel != null && detachedOfferingPanel.isVisible(),
                    "the detached panel is the regression case: it is still flagged visible");
        }

        private void failDetachedOfferingLoad() {
            if (service.pendingOfferingLoad == null) {
                requireVariant(false, "an offering load must be pending before it can fail");
                return;
            }
            resetAlertProbe();
            Platform.runLater(this::dismissRenderedAlert);
            service.pendingOfferingLoad.completeExceptionally(
                    new IllegalStateException("模拟已脱离场景面板的教学班加载失败"));
        }

        private void requireDetachedResponseIgnored() {
            requireVariant(!alertSeen,
                    "a detached row's failed offering load must not reach the user, saw alert "
                            + alertContentText);
        }

        /** 旧行的响应被丢弃后，当前行仍必须能正常加载教学班。 */
        private void requireCurrentRowStillLoads() {
            Button toggle = offeringToggle();
            if (toggle != null) toggle.fire();
            VBox panel = offeringPanel();
            requireVariant(panel != null && panel.isVisible(),
                    "the current row must still expand after a detached row's response");
        }

        private void requireCurrentRowRendered() {
            VBox panel = offeringPanel();
            requireVariant(!panelStuckLoading(panel),
                    "the current row must not stay on 正在加载教学班... after a detached"
                            + " row's response");
            requireVariant(panelOfferingRows(panel) == expectedOfferingRows,
                    "the current row must still render its offerings, expected "
                            + expectedOfferingRows + " rows but saw "
                            + panelOfferingRows(panel));
        }

        private void resetAlertProbe() {
            alertAttempts = 0;
            alertSeen = false;
            alertContentText = null;
        }

        /**
         * 反复排队，直到捕获到真实弹出的模态提示框并关闭它；最多重试 60 次。
         */
        private void dismissRenderedAlert() {
            DialogPane pane = renderedConfirmationPane();
            if (pane == null) {
                if (++alertAttempts > 60) return;
                Platform.runLater(this::dismissRenderedAlert);
                return;
            }
            alertSeen = true;
            alertContentText = pane.getContentText();
            Node ok = pane.lookupButton(ButtonType.OK);
            if (ok instanceof Button button) button.fire();
        }

        private void showStandaloneOfferingEditor() throws Exception {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(OFFERING_EDITOR_PATH));
            Parent dialogRoot = loader.load();
            OfferingEditorDialogController controller = loader.getController();
            controller.prepareForEdit(sampleOffering());
            OfferingEditorRequestDTO request = controller.collectRequest("smoke-operation");
            if (request == null || !"1001".equals(request.getOfferingId())
                    || request.getExpectedVersion() != 1 || !"101".equals(request.getCourseId())) {
                throw new IllegalStateException(
                        "offering edit must preserve id, version and course");
            }
            if (OfferingEditorDialogController.isEditable(cancelledOffering())) {
                throw new IllegalStateException("cancelled offerings must not be editable");
            }
            standaloneDialog = showStandaloneDialog(dialogRoot, "编辑教学班");
        }

        private void captureStandaloneOfferingEditor() throws Exception {
            requireDialogNode("#offeringCodeField", "offering code field");
            requireDialogNode("#statusField", "offering status field");
            requireDialogNode("#saveButton", "offering editor save button");
            requireImage(captureWindow(standaloneDialog, "offering-editor.png"),
                    "offering-editor.png");
        }

        private void verifyMainViewRoleTitles() throws Exception {
            if (!variantFailures.isEmpty()) {
                throw new IllegalStateException("offering collapse-race variants failed: "
                        + String.join(" | ", variantFailures));
            }
            ClientSession.getInstance().logout();
            requireLabelText(loadMainView(), "#courseCardTitle", "选课");
            ClientSession.getInstance().login("admin", "管理员", "token", null);
            requireLabelText(loadMainView(), "#courseCardTitle", "教务管理");
            ClientSession.getInstance().logout();
            FXMLLoader.load(getClass().getResource(CATALOG_PATH));
            System.out.println("AdminCourseUiSmokeTest: PASS (" + captures + " captures)");
        }

        /**
         * 挂入 Scene 并应用 CSS，否则 ScrollPane 尚未生成皮肤，{@code lookup} 无法穿透其内容。
         */
        private Parent loadMainView() throws Exception {
            Parent view = FXMLLoader.load(getClass().getResource(MAIN_VIEW_PATH));
            new Scene(view);
            view.applyCss();
            return view;
        }

        private void requireLabelText(Parent view, String selector, String expected) {
            Node node = view.lookup(selector);
            if (!(node instanceof Label label) || !expected.equals(label.getText())) {
                throw new IllegalStateException(
                        selector + " must render " + expected + ", saw " + node);
            }
        }

        private void setSearchText(String text) {
            ((TextField) requireNode("#searchField", "search field")).setText(text);
        }

        private void requireRows(int expected, String description) {
            int actual = root.lookupAll(".course-admin-row").size();
            if (actual != expected) {
                throw new IllegalStateException(
                        description + " must show " + expected + " rows but showed " + actual);
            }
        }

        private void requireVisible(String selector, String description) {
            Node node = requireNode(selector, description);
            if (!node.isVisible()) {
                throw new IllegalStateException(description + " must be visible");
            }
        }

        private Node requireNode(String selector, String description) {
            Node node = root.lookup(selector);
            if (node == null) {
                throw new IllegalStateException(
                        "Missing " + description + " for selector " + selector);
            }
            return node;
        }

        private Stage showStandaloneDialog(Parent dialogRoot, String title) {
            Stage stage = new Stage();
            stage.setTitle(title);
            stage.setScene(new Scene(dialogRoot));
            stage.sizeToScene();
            stage.show();
            standaloneDialogRoot = dialogRoot;
            return stage;
        }

        /**
         * 截取真实窗口的内容，并确认内容没有被窗口裁掉。
         */
        private WritableImage captureWindow(Window window, String file) {
            if (window == null || window.getScene() == null) {
                throw new IllegalStateException("No window for " + file);
            }
            Node content = window.getScene().getRoot();
            double availableHeight = window.getScene().getHeight();
            double availableWidth = window.getScene().getWidth();
            if (content.getBoundsInLocal().getHeight() > availableHeight + 0.5
                    || content.getBoundsInLocal().getWidth() > availableWidth + 0.5) {
                throw new IllegalStateException(file + " content is clipped by its window");
            }
            return content.snapshot(null, null);
        }

        private Node requireDialogNode(String selector, String description) {
            if (standaloneDialogRoot == null) {
                throw new IllegalStateException(
                        "No dialog open while looking for " + description);
            }
            Node node = standaloneDialogRoot.lookup(selector);
            if (node == null) {
                throw new IllegalStateException(
                        "Missing " + description + " for dialog selector " + selector);
            }
            return node;
        }

        private WritableImage capture(Node node, String file) {
            if (node == null) {
                throw new IllegalStateException("Missing capture node for " + file);
            }
            return node.snapshot(null, null);
        }

        private void requireImage(WritableImage image, double width, double height,
                String file) throws IOException {
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalStateException("Empty snapshot for " + file);
            }
            if (Math.abs(image.getWidth() - width) > 0.5
                    || Math.abs(image.getHeight() - height) > 0.5) {
                throw new IllegalStateException(file + " must be "
                        + (int) width + "x" + (int) height + " but was "
                        + (int) image.getWidth() + "x" + (int) image.getHeight());
            }
            write(image, file);
        }

        private void requireImage(WritableImage image, String file) throws IOException {
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalStateException("Empty snapshot for " + file);
            }
            write(image, file);
        }

        private void write(WritableImage image, String file) throws IOException {
            Path target = OUTPUT.resolve(file);
            boolean written = ImageIO.write(
                    SwingFXUtils.fromFXImage(image, null), "png", target.toFile());
            requireImageWritten(written, target);
            captures++;
            System.out.println("captured " + target + " "
                    + (int) image.getWidth() + "x" + (int) image.getHeight());
        }

        private void fail(Throwable failure) {
            failure.printStackTrace();
            Platform.exit();
            System.exit(1);
        }

        private static AdminCourseView sampleCourse() {
            return new AdminCourseView("101", "CS203", "数据结构", "必修", 4.0, 64,
                    "线性表、树和图", "程序设计基础", true, true, "ACTIVE", 1, 1);
        }

        private static AdminOfferingView sampleOffering() {
            return new AdminOfferingView("1001", "OFF-1001", "101", 2026, 1, 120, 30,
                    "OPEN", "T1001", "张老师", null, null, "SCHEDULED", 1);
        }

        private static AdminOfferingView cancelledOffering() {
            return new AdminOfferingView("2001", "OFF-2001", "201", 2026, 1, 100, 0,
                    "CANCELLED", "T2001", "李老师", null, null, "SCHEDULED", 1);
        }
    }

    /**
     * 预览/冒烟用的可切换失败服务：默认委托给确定性的 {@link MockAdminCourseService}，
     * 并记录 UI 是否真的通过控制器走到了 Service。
     */
    static final class TogglableAdminCourseService implements AdminCourseService {
        private final MockAdminCourseService delegate = new MockAdminCourseService();
        private final Deque<CompletableFuture<List<AdminOfferingView>>> heldOfferingLoads =
                new ArrayDeque<>();
        private boolean failListing;
        private int listCoursesCalls;
        private int mutationCalls;
        private int offeringCalls;
        private CourseEditorRequestDTO lastCourseUpdate;
        private CompletableFuture<List<AdminOfferingView>> pendingOfferingLoad;
        private String pendingOfferingCourseId;

        void setFailListing(boolean value) {
            failListing = value;
        }

        int listCoursesCalls() {
            return listCoursesCalls;
        }

        int mutationCalls() {
            return mutationCalls;
        }

        int offeringCalls() {
            return offeringCalls;
        }

        /** 让下一次教学班加载停在进行中，由测试显式完成或失败。 */
        void holdNextOfferingLoad() {
            heldOfferingLoads.add(new CompletableFuture<>());
        }

        List<AdminOfferingView> authoritativeOfferings(String courseId) {
            return delegate.listOfferings(courseId).join();
        }

        CourseEditorRequestDTO lastCourseUpdate() {
            return lastCourseUpdate;
        }

        private <T> CompletableFuture<T> listing(Supplier<CompletableFuture<T>> supplier) {
            if (failListing) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("模拟课程服务不可用"));
            }
            return supplier.get();
        }

        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
            listCoursesCalls++;
            return listing(() -> delegate.listCourses(query, status));
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
            offeringCalls++;
            CompletableFuture<List<AdminOfferingView>> held = heldOfferingLoads.poll();
            if (held != null) {
                pendingOfferingLoad = held;
                pendingOfferingCourseId = courseId;
                return held;
            }
            return listing(() -> delegate.listOfferings(courseId));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
                CourseEditorRequestDTO request) {
            mutationCalls++;
            return delegate.createCourse(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
                CourseEditorRequestDTO request) {
            lastCourseUpdate = request;
            mutationCalls++;
            return delegate.updateCourse(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
                String courseId, int expectedVersion, String operationId) {
            mutationCalls++;
            return delegate.archiveCourse(courseId, expectedVersion, operationId);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
                String courseId, int expectedVersion, String operationId) {
            mutationCalls++;
            return delegate.restoreCourse(courseId, expectedVersion, operationId);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
                OfferingEditorRequestDTO request) {
            mutationCalls++;
            return delegate.createOffering(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
                OfferingEditorRequestDTO request) {
            mutationCalls++;
            return delegate.updateOffering(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
                String offeringId, int expectedVersion, String operationId) {
            mutationCalls++;
            return delegate.cancelOffering(offeringId, expectedVersion, operationId);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            mutationCalls++;
            return delegate.deleteDraftOffering(offeringId, expectedVersion, operationId);
        }
    }
}
