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
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import controller.CourseEditorDialogController;
import controller.OfferingEditorDialogController;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
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
    private static final String SCHEDULE_PATH =
            "/resources/fxml/ScheduleArrangementDialog.fxml";
    private static final String[] FILES = {
            "catalog-populated.png", "catalog-expanded.png", "catalog-empty.png",
            "catalog-error.png", "course-editor.png", "offering-editor.png",
            "destroy-confirm.png", "schedule-normal.png", "schedule-blocking.png",
            "schedule-overridable.png", "schedule-two-slot.png", "schedule-force-publish.png",
            "schedule-many-conflicts.png"
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
        /**
         * 可见入口：教学班行上的“排课”必须打开控制器渲染的排课对话框。
         */
        private void openScheduleDialogFromOfferingRow() {
            Node node = root.lookup(".course-admin-schedule-button");
            if (!(node instanceof Button scheduleButton)) {
                throw new IllegalStateException(
                        "an eligible offering row must expose a visible 排课 action");
            }
            if (scheduleButton.isDisabled() || !scheduleButton.isVisible()) {
                throw new IllegalStateException("the 排课 action on an OPEN offering must be usable");
            }
            service.installPreviewConflicts(List.of());
            service.installArrangements(null);
            scheduleButton.fire();

            scheduleDialog = findAppDialogWindow("course-admin-schedule-dialog");
            if (scheduleDialog == null) {
                throw new IllegalStateException("排课 must open the scheduling dialog");
            }
            scheduleScene = scheduleDialog.getScene();
            requireScheduleNode("#dialogRoot", "scheduling dialog root");
            requireScheduleNode("#teacherField", "teacher selector");
            requireScheduleNode("#assistantField", "assistant selector");
            requireScheduleNode("#classroomField", "classroom selector");
            requireScheduleNode("#slotEditorList", "dynamic slot list");
            requireScheduleNode("#addSlotButton", "add slot button");
            requireScheduleNode("#arrangementList", "authoritative arrangement list");
            requireScheduleNode("#conflictArea", "conflict area");
            requireScheduleNode("#overrideReasonField", "override reason field");
            requireScheduleNode("#previewButton", "preview button");
            requireScheduleNode("#publishButton", "publish button");
            requireScheduleNode("#cancelButton", "cancel button");
        }

        /**
         * 普通状态：加载完成后应显示教学班上下文、预选教师与既有的多时间段安排卡片。
         */
        private void prepareScheduleNormalPreview() {
            ComboBox<ScheduleResourceDTO> teacher = scheduleCombo("#teacherField");
            if (teacher.getValue() == null) {
                throw new IllegalStateException(
                        "the dialog must preselect the offering's teacher, saw " + teacher.getValue());
            }
            if (scheduleSlotRowCount() != 1) {
                throw new IllegalStateException(
                        "a fresh dialog must show exactly one slot row, saw " + scheduleSlotRowCount());
            }
            if (scheduleCardCount() != 1) {
                throw new IllegalStateException(
                        "the authoritative arrangement must render one card, saw "
                                + scheduleCardCount());
            }
            Label context = (Label) requireScheduleNode("#offeringContextLabel", "context label");
            if (!context.getText().contains("OFF-1001")) {
                throw new IllegalStateException(
                        "the dialog must show the offering context, saw " + context.getText());
            }
            selectClassroom("3001");
            fireSchedule("#previewButton");
        }

        private void captureScheduleNormal() throws Exception {
            if (scheduleConflictCount(".course-admin-conflict-blocking") != 0
                    || scheduleConflictCount(".course-admin-conflict-overridable") != 0) {
                throw new IllegalStateException("a conflict-free preview must show no conflicts");
            }
            layoutScheduleDialog();
            requireImage(captureWindow(scheduleDialog, "schedule-normal.png"), "schedule-normal.png");
        }

        /**
         * 阻断性冲突：保留返回修改的路径，移除强制保存。
         */
        private void prepareScheduleBlockingPreview() {
            service.installPreviewConflicts(List.of(blockingConflict()));
            fireSchedule("#previewButton");
        }

        private void captureScheduleBlockingConflict() throws Exception {
            if (scheduleConflictCount(".course-admin-conflict-blocking") != 1) {
                throw new IllegalStateException(
                        "a BLOCKING conflict must render in the blocking group, saw "
                                + scheduleConflictCount(".course-admin-conflict-blocking"));
            }
            Node force = requireScheduleNode("#forceSaveButton", "force save button");
            if (force.isVisible() || force.isManaged()) {
                throw new IllegalStateException(
                        "a BLOCKING conflict must remove the force save path");
            }
            Button save = (Button) requireScheduleNode("#saveButton", "save button");
            if (!save.isDisabled()) {
                throw new IllegalStateException(
                        "a BLOCKING conflict must not leave saving enabled");
            }
            layoutScheduleDialog();
            requireImage(captureWindow(scheduleDialog, "schedule-blocking.png"),
                    "schedule-blocking.png");
        }

        /**
         * 可绕过冲突：暴露“填写原因并保存”，并在填写原因后仍保持可保存。
         */
        private void prepareScheduleOverridablePreview() {
            service.installPreviewConflicts(List.of(overridableConflict()));
            fireSchedule("#previewButton");
        }

        private void captureScheduleOverridableConflict() throws Exception {
            if (scheduleConflictCount(".course-admin-conflict-overridable") != 1) {
                throw new IllegalStateException(
                        "an OVERRIDABLE conflict must render in the overridable group, saw "
                                + scheduleConflictCount(".course-admin-conflict-overridable"));
            }
            Node force = requireScheduleNode("#forceSaveButton", "force save button");
            if (!force.isVisible() || !force.isManaged()) {
                throw new IllegalStateException(
                        "an OVERRIDABLE conflict must expose 填写原因并保存");
            }
            if (!"填写原因并保存".equals(((Button) force).getText())) {
                throw new IllegalStateException(
                        "the force action must read 填写原因并保存, saw " + ((Button) force).getText());
            }
            ((TextField) requireScheduleNode("#overrideReasonField", "override reason field"))
                    .setText("教师冲突已确认");
            layoutScheduleDialog();
            requireImage(captureWindow(scheduleDialog, "schedule-overridable.png"),
                    "schedule-overridable.png");
        }

        private void prepareManyScheduleConflicts() {
            List<ScheduleConflictDTO> conflicts = new ArrayList<>();
            for (int week = 1; week <= 16; week++) {
                for (int day : List.of(1, 3)) {
                    conflicts.add(new ScheduleConflictDTO("TEACHER",
                            ScheduleConflictSeverityDTO.OVERRIDABLE, "8001", "1001",
                            week, day, 1, 2, "任课教师在该时间段已有教学安排"));
                }
            }
            service.installPreviewConflicts(conflicts);
            fireSchedule("#previewButton");
        }

        private void captureManyScheduleConflicts() throws IOException {
            layoutScheduleDialog();
            if (scheduleConflictCount(".course-admin-conflict-overridable") != 32) {
                throw new IllegalStateException("all 32 week/slot conflicts must be available");
            }
            requireScheduleActionVisible("#cancelButton");
            requireScheduleActionVisible("#previewButton");
            requireScheduleActionVisible("#publishButton");
            requireScheduleActionVisible("#forceSaveButton");
            ScrollPane scroll = (ScrollPane) requireScheduleNode(
                    "#scheduleContentScroll", "scrollable schedule content");
            if (scroll.getContent().getBoundsInLocal().getHeight()
                    <= scroll.getViewportBounds().getHeight()) {
                throw new IllegalStateException("many conflicts must scroll within the dialog");
            }
            scroll.setVvalue(scroll.getVmax());
            layoutScheduleDialog();
            Node reason = requireScheduleNode("#overrideReasonField", "override reason");
            Node viewport = scroll.lookup(".viewport");
            Bounds visible = viewport.localToScene(viewport.getBoundsInLocal());
            Bounds field = reason.localToScene(reason.getBoundsInLocal());
            if (field.getMinY() < visible.getMinY() - 1
                    || field.getMaxY() > visible.getMaxY() + 1) {
                throw new IllegalStateException("scrolling must make the force reason field reachable");
            }
            requireScheduleActionVisible("#forceSaveButton");
            requireImage(captureWindow(scheduleDialog, "schedule-many-conflicts.png"),
                    "schedule-many-conflicts.png");
            scroll.setVvalue(scroll.getVmin());
        }

        private void verifyAssistantCanBeCleared() {
            ComboBox<ScheduleResourceDTO> assistant = scheduleCombo("#assistantField");
            ScheduleResourceDTO selected = assistant.getItems().stream()
                    .filter(resource -> "T2001".equals(resource.getBusinessId()))
                    .findFirst().orElseThrow();
            assistant.setValue(selected);
            fireSchedule("#previewButton");
            if (!"T2001".equals(service.lastArrangementPreview.getAssistantUid())) {
                throw new IllegalStateException("the selected assistant must reach the preview request");
            }
            fireSchedule("#clearAssistantButton");
            if (assistant.getValue() != null) {
                throw new IllegalStateException("clearing an assistant must clear the visible selection");
            }
            fireSchedule("#previewButton");
            if (service.lastArrangementPreview.getAssistantUid() != null) {
                throw new IllegalStateException("clearing an assistant must send assistantUid=null");
            }
        }

        /**
         * 两个时间段都避开既有安排，使随后的保存能真正落到服务端。
         */
        private void prepareScheduleTwoSlotPreview() {
            service.installPreviewConflicts(List.of());
            fireSchedule("#addSlotButton");
            if (scheduleSlotRowCount() != 2) {
                throw new IllegalStateException(
                        "adding a slot row must keep both rows, saw " + scheduleSlotRowCount());
            }
            fillSlotRow(0, 2, 5, 6);
            fillSlotRow(1, 4, 7, 8);
            fireSchedule("#previewButton");
        }

        private void captureScheduleTwoSlotArrangement() throws Exception {
            if (scheduleSlotRowCount() != 2) {
                throw new IllegalStateException(
                        "both slot rows must survive the preview, saw " + scheduleSlotRowCount());
            }
            if (scheduleCardCount() != 1) {
                throw new IllegalStateException(
                        "the loaded arrangement card must stay rendered, saw " + scheduleCardCount());
            }
            layoutScheduleDialog();
            requireImage(captureWindow(scheduleDialog, "schedule-two-slot.png"),
                    "schedule-two-slot.png");
        }

        /**
         * 保存只在预检查结果匹配当前表单时生效，并权威重载安排列表。
         */
        private void saveScheduleFromDialog() {
            Button save = (Button) requireScheduleNode("#saveButton", "save button");
            if (save.isDisabled()) {
                throw new IllegalStateException("a conflict-free current preview must enable saving");
            }
            scheduleMutationsBefore = service.mutationCalls();
            service.holdNextArrangementSave();
            service.injectedPlanConflicts = List.of(overridableConflict());
            // 保存失败会经 AlertUtil 打开模态提示并阻塞事件循环，先排队关闭它。
            resetAlertProbe();
            Platform.runLater(this::dismissRenderedAlert);
            save.fire();
            for (String selector : List.of("#saveButton", "#previewButton", "#publishButton",
                    "#teacherField", "#classroomField", "#slotEditorList")) {
                if (!requireScheduleNode(selector, "pending write control").isDisabled()) {
                    throw new IllegalStateException(selector + " must be disabled during a save");
                }
            }
        }

        private void verifyScheduleSaved() {
            if (alertSeen) {
                throw new IllegalStateException(
                        "saving a valid arrangement must not raise an alert, saw " + alertContentText);
            }
            if (service.mutationCalls() <= scheduleMutationsBefore) {
                throw new IllegalStateException("保存安排 must reach the Service");
            }
            SaveArrangementRequestDTO request = service.lastArrangementSave();
            if (request == null) {
                throw new IllegalStateException("the saved request must be collected");
            }
            if (request.getSlots().size() != 2) {
                throw new IllegalStateException(
                        "the saved request must carry both slot rows, saw " + request.getSlots());
            }
            if (!"1001".equals(request.getOfferingId()) || request.getStartWeek() != 1
                    || request.getEndWeek() != 16) {
                throw new IllegalStateException(
                        "the saved request must carry the offering and shared weeks, saw "
                                + request.getOfferingId() + " " + request.getStartWeek() + "-"
                                + request.getEndWeek());
            }
            if (scheduleCardCount() != 2) {
                throw new IllegalStateException(
                        "a successful save must reload the authoritative arrangements, saw "
                                + scheduleCardCount() + " cards");
            }
            if (!requireScheduleNode("#saveButton", "save button").isDisabled()) {
                throw new IllegalStateException("a successful save must consume its preview");
            }
        }

        private void verifyNewArrangementAction() {
            Button edit = (Button) requireScheduleNode(".course-admin-arrangement-edit", "edit action");
            edit.fire();
            fireSchedule("#newArrangementButton");
            if (scheduleCombo("#classroomField").getValue() != null || scheduleSlotRowCount() != 1) {
                throw new IllegalStateException("新增安排 must reset the editor after editing an existing card");
            }
        }

        private void publishScheduleWithForce() throws IOException {
            Button publish = (Button) requireScheduleNode("#publishButton", "publish action");
            if (publish.isDisabled() || !publish.getText().contains("原因")
                    || !requireScheduleNode("#planConflictArea", "whole-plan conflicts").isVisible()) {
                throw new IllegalStateException("whole-plan warnings must expose the force-publish action");
            }
            TextField reason = (TextField) requireScheduleNode("#overrideReasonField", "force reason");
            reason.setText("   ");
            publish.fire();
            if (service.publishCalls != 0) {
                throw new IllegalStateException("blank force reason must not reach publication");
            }
            reason.setText("  已核实教室使用  ");
            layoutScheduleDialog();
            requireImage(captureWindow(scheduleDialog, "schedule-force-publish.png"),
                    "schedule-force-publish.png");
            resetAlertProbe();
            Platform.runLater(this::dismissRenderedAlert);
            publish.fire();
        }

        private void verifyForcedPublication() {
            if (!alertSeen || service.publishCalls != 1 || !service.lastPublishForce
                    || !"已核实教室使用".equals(service.lastPublishReason)) {
                throw new IllegalStateException(
                        "confirmed publication must send force=true with a trimmed reason");
            }
            if (!requireScheduleNode("#publishButton", "publish action").isDisabled()
                    || !requireScheduleNode("#teacherField", "published editor").isDisabled()) {
                throw new IllegalStateException("a published plan must leave the dialog read-only");
            }
        }

        /**
         * 原生窗口关闭发生在写入之后：不做额外写入，且必须刷新目录。
         */
        private void closeScheduleDialogAfterSave() {
            int mutationsBefore = service.mutationCalls();
            int listCallsBefore = service.listCoursesCalls();
            Event.fireEvent(scheduleDialog,
                    new WindowEvent(scheduleDialog, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (scheduleDialog != null && scheduleDialog.isShowing()) {
                throw new IllegalStateException("the native close request must close the scheduling dialog");
            }
            if (service.mutationCalls() != mutationsBefore) {
                throw new IllegalStateException("cancelling the dialog must not write");
            }
            if (service.listCoursesCalls() <= listCallsBefore) {
                throw new IllegalStateException(
                        "closing after a mutation must refresh the catalog");
            }
            scheduleScene = null;
        }

        private void layoutScheduleDialog() {
            scheduleScene.getRoot().applyCss();
            scheduleScene.getRoot().layout();
        }

        private void requireScheduleActionVisible(String selector) {
            Node action = requireScheduleNode(selector, "accessible schedule action");
            Bounds bounds = action.localToScene(action.getBoundsInLocal());
            if (!action.isVisible() || bounds.getMinX() < 0 || bounds.getMinY() < 0
                    || bounds.getMaxX() > scheduleScene.getWidth() + 1
                    || bounds.getMaxY() > scheduleScene.getHeight() + 1) {
                throw new IllegalStateException(selector + " must stay inside the dialog without resizing: "
                        + bounds + ", scene=" + scheduleScene.getWidth() + "x" + scheduleScene.getHeight());
            }
        }

        private void fireSchedule(String selector) {
            Node node = requireScheduleNode(selector, "schedule control " + selector);
            if (!(node instanceof Button button)) {
                throw new IllegalStateException(selector + " must be a button");
            }
            button.fire();
        }

        private Node requireScheduleNode(String selector, String description) {
            if (scheduleScene == null) {
                throw new IllegalStateException(
                        "No scheduling dialog open while looking for " + description);
            }
            Node node = scheduleScene.lookup(selector);
            if (node == null) {
                if ("#dialogRoot".equals(selector)) {
                    node = scheduleScene.getRoot();
                }
            }
            if (node == null) {
                throw new IllegalStateException(
                        "Missing " + description + " for schedule selector " + selector);
            }
            return node;
        }

        private int scheduleSlotRowCount() {
            Node node = requireScheduleNode("#slotEditorList", "dynamic slot list");
            return node instanceof VBox box ? box.getChildren().size() : -1;
        }

        private int scheduleCardCount() {
            Node node = requireScheduleNode("#arrangementList", "arrangement list");
            return node instanceof VBox box
                    ? box.lookupAll(".course-admin-arrangement-card").size() : -1;
        }

        private int scheduleConflictCount(String styleClass) {
            Node node = requireScheduleNode("#conflictArea", "conflict area");
            int count = 0;
            for (Node child : node.lookupAll(styleClass)) {
                if (child.isVisible() && child.isManaged()) count++;
            }
            return count;
        }

        @SuppressWarnings("unchecked")
        private ComboBox<ScheduleResourceDTO> scheduleCombo(String selector) {
            Node node = requireScheduleNode(selector, "schedule selector " + selector);
            if (!(node instanceof ComboBox<?> combo)) {
                throw new IllegalStateException(selector + " must be a selector");
            }
            return (ComboBox<ScheduleResourceDTO>) combo;
        }

        private void selectClassroom(String businessId) {
            ComboBox<ScheduleResourceDTO> classroom = scheduleCombo("#classroomField");
            for (ScheduleResourceDTO resource : classroom.getItems()) {
                if (businessId.equals(resource.getBusinessId())) {
                    classroom.setValue(resource);
                    return;
                }
            }
            throw new IllegalStateException("classroom " + businessId + " must be selectable");
        }

        private void fillSlotRow(int index, int weekday, int startPeriod, int endPeriod) {
            Node node = requireScheduleNode("#slotEditorList", "dynamic slot list");
            if (!(node instanceof VBox list) || list.getChildren().size() <= index) {
                throw new IllegalStateException("slot row " + index + " must exist");
            }
            Node row = list.getChildren().get(index);
            if (!(row instanceof HBox cells) || cells.getChildren().size() < 3) {
                throw new IllegalStateException("slot row " + index + " must expose three selectors");
            }
            setPeriodCombo(cells.getChildren().get(0), weekday);
            setPeriodCombo(cells.getChildren().get(1), startPeriod);
            setPeriodCombo(cells.getChildren().get(2), endPeriod);
        }

        @SuppressWarnings("unchecked")
        private void setPeriodCombo(Node node, int value) {
            if (node instanceof ComboBox<?> combo) {
                ((ComboBox<Integer>) combo).setValue(value);
            }
        }

        private static ScheduleConflictDTO blockingConflict() {
            return new ScheduleConflictDTO("OFFERING_SELF_OVERLAP",
                    ScheduleConflictSeverityDTO.BLOCKING, "1001", "1001", 1, 1, 1, 2,
                    "同一教学班的时间段与现有安排重叠");
        }

        private static ScheduleConflictDTO overridableConflict() {
            return new ScheduleConflictDTO("TEACHER", ScheduleConflictSeverityDTO.OVERRIDABLE,
                    "8001", "1001", 1, 3, 3, 4, "任课教师在该时间段已有教学安排");
        }

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
        private Window scheduleDialog;
        private Scene scheduleScene;
        private int scheduleMutationsBefore;
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
            steps.add(this::openScheduleDialogFromOfferingRow);
            steps.add(this::verifyNewArrangementAction);
            steps.add(this::prepareScheduleNormalPreview);
            steps.add(this::captureScheduleNormal);
            steps.add(this::prepareScheduleBlockingPreview);
            steps.add(this::captureScheduleBlockingConflict);
            steps.add(this::prepareScheduleOverridablePreview);
            steps.add(this::captureScheduleOverridableConflict);
            steps.add(this::prepareManyScheduleConflicts);
            steps.add(this::captureManyScheduleConflicts);
            steps.add(this::verifyAssistantCanBeCleared);
            steps.add(this::prepareScheduleTwoSlotPreview);
            steps.add(this::captureScheduleTwoSlotArrangement);
            steps.add(this::saveScheduleFromDialog);
            steps.add(service::completeHeldArrangementSave);
            steps.add(this::verifyScheduleSaved);
            steps.add(this::publishScheduleWithForce);
            steps.add(this::verifyForcedPublication);
            steps.add(this::closeScheduleDialogAfterSave);
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
            FXMLLoader.load(getClass().getResource(SCHEDULE_PATH));
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
                throw new IllegalStateException(file + " content is clipped by its window: content "
                        + content.getBoundsInLocal().getWidth() + "x"
                        + content.getBoundsInLocal().getHeight() + " window "
                        + availableWidth + "x" + availableHeight);
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
        private List<ScheduleConflictDTO> injectedConflicts;
        private List<ScheduleArrangementView> injectedArrangements;
        private SaveArrangementRequestDTO lastArrangementSave;
        private SaveArrangementRequestDTO lastArrangementPreview;
        private List<ScheduleConflictDTO> injectedPlanConflicts;
        private CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> heldArrangementSave;
        private int publishCalls;
        private boolean lastPublishForce;
        private String lastPublishReason;

        void holdNextArrangementSave() {
            heldArrangementSave = new CompletableFuture<>();
        }

        void completeHeldArrangementSave() {
            CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> pending = heldArrangementSave;
            heldArrangementSave = null;
            delegate.saveArrangement(lastArrangementSave).whenComplete((result, failure) -> {
                if (failure == null) pending.complete(result);
                else pending.completeExceptionally(failure);
            });
        }

        void setFailListing(boolean value) {
            failListing = value;
        }

        /** 注入预检查冲突；传 {@code null} 恢复为委托给 MockAdminCourseService。 */
        void installPreviewConflicts(List<ScheduleConflictDTO> conflicts) {
            injectedConflicts = conflicts == null ? null : List.copyOf(conflicts);
        }

        /** 注入权威安排列表；传 {@code null} 恢复为委托给 MockAdminCourseService。 */
        void installArrangements(List<ScheduleArrangementView> arrangements) {
            injectedArrangements = arrangements == null ? null : List.copyOf(arrangements);
        }

        SaveArrangementRequestDTO lastArrangementSave() {
            return lastArrangementSave;
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

        @Override
        public CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
                String type, String query) {
            return delegate.listScheduleResources(type, query);
        }

        @Override
        public CompletableFuture<SchedulePlanDTO> loadSchedulePlan(int academicYear, int semester) {
            return delegate.loadSchedulePlan(academicYear, semester).thenApply(plan ->
                    injectedPlanConflicts == null ? plan
                            : new SchedulePlanDTO(plan.getPlanId(), plan.getName(), plan.getRevision(),
                                    plan.getStatus(), plan.isCurrent(), injectedPlanConflicts));
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
                String planId, String offeringId) {
            if (injectedArrangements != null) {
                return CompletableFuture.completedFuture(injectedArrangements);
            }
            return delegate.loadOfferingArrangements(planId, offeringId);
        }

        @Override
        public CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
                SaveArrangementRequestDTO request) {
            lastArrangementPreview = request;
            if (injectedConflicts != null) {
                return CompletableFuture.completedFuture(injectedConflicts);
            }
            return delegate.checkArrangement(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
                SaveArrangementRequestDTO request) {
            lastArrangementSave = request;
            mutationCalls++;
            if (heldArrangementSave != null) return heldArrangementSave;
            return delegate.saveArrangement(request);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
                String arrangementId, int expectedVersion, String operationId) {
            mutationCalls++;
            return delegate.deleteArrangement(arrangementId, expectedVersion, operationId);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
                String planId, int expectedRevision, String operationId, boolean force,
                String overrideReason) {
            mutationCalls++;
            publishCalls++;
            lastPublishForce = force;
            lastPublishReason = overrideReason;
            return delegate.publishSchedulePlan(planId, expectedRevision, operationId, force,
                    overrideReason);
        }
    }
}
