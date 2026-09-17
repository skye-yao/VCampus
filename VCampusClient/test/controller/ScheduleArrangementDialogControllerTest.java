package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.schedule.CheckArrangementResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.SocketAdminCourseService.AdminCourseServiceException;

/**
 * 无 JavaFX 依赖的排课对话框控制器测试：注入假服务与 {@code Runnable::run} 的 FX 执行器。
 */
public final class ScheduleArrangementDialogControllerTest {
    private static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";
    private static final String SAVE_KEY = "schedule:save";
    private static final String DIALOG_VIEW = "/resources/fxml/ScheduleArrangementDialog.fxml";
    /** 服务端 createDraftPlan 的结果文案形状：复制条数必须落到界面上。 */
    private static final String CREATE_DRAFT_MESSAGE = "草稿方案已创建：已复制 3 条 / 跳过 1 条";

    public static void main(String[] args) {
        if (args.length > 0) {
            int failures = 0;
            for (String name : args) {
                try {
                    ScheduleArrangementDialogControllerTest.class.getDeclaredMethod(name).invoke(null);
                    System.out.println(name + ": PASS");
                } catch (ReflectiveOperationException failure) {
                    failures++;
                    System.err.println(name + ": " + failure.getCause());
                }
            }
            require(failures == 0, failures + " selected regressions failed");
            return;
        }
        testPendingCloseWaitsForTheFinalWrite();
        testPublishConflictReloadsThePlan();
        testSaveConflictReconcilesTheEditedVersion();
        testUnknownSaveResultRetainsOperationIdUntilIntentChanges();
        testUnknownPublishResultRetainsOperationId();
        testTeacherCannotAlsoBeAssistant();
        testNewArrangementEndsUnknownSaveAttempt();
        testReselectingArrangementEndsUnknownSaveAttempt();
        testSuccessfulDeleteEndsUnknownSaveAttempt();
        testSuccessfulSaveConsumesPreviewAndEditIdentity();
        testClosingTwiceRefreshesCatalogOnce();
        testSaveCompletingAfterCloseRefreshesWithoutReloadingDialog();
        testPublishUsesWholePlanConflictsAndTrimmedReason();
        testPlanConflictsArePartitionedByOffering();
        testPreviewSuccessRefreshesPlanConflicts();
        testPreviewFailureKeepsPlanConflicts();
        testConflictTextCarriesThePosition();
        testConflictTextRendersTheMergedWeekRange();
        testOtherConflictsSummaryTextCountsOthers();
        testEmptyArrangementSectionText();
        testFilledArrangementSectionText();
        testSuccessfulWriteReloadsPlanState();
        testReadFailuresKeepTheirRetryTarget();
        testEditingCanReturnToANewArrangement();
        testPublishedPlanRejectsFurtherMutations();
        testMultipleSlotRowsAndSharedWeeks();
        testArrangementCardsSummarizeSharedWeeksAndAllSlots();
        testLocalValidationRejectsIncompleteOverlappingAndOutOfRangeInput();
        testFormEditInvalidatesCompletedPreview();
        testPreviewGenerationProtectionIgnoresStaleResult();
        testStalePlanAndArrangementLoadsAreIgnored();
        testResponsesAfterCloseAreIgnored();
        testBlockingConflictsDisableTheForcePath();
        testOverridableConflictsRequireATrimmedReason();
        testPreviewAndWritesUseDistinctOperationIds();
        testSaveControlDisabledWhilePendingAndRestoredOnEveryPath();
        testDeleteRequiresConfirmationAndCurrentVersion();
        testPublishRequiresConfirmationAndPlanRevision();
        testSuccessfulWritesReloadAuthoritativeArrangements();
        testWriteConflictTriggersPreviewAndAuthoritativeReload();
        testCatalogRefreshCallbackFiresOnlyAfterAMutation();
        createDraftIsOfferedWhenNoPlanLoaded();
        createDraftReloadsThePlanOnSuccess();
        createDraftIsHiddenWhenADraftIsEditable();
        createDraftFailureKeepsTheServerReason();
        loadPlanFailureSurfacesTheServerMessage();
        scheduleArrangementDialogViewKeepsItsBindings();
        System.out.println("ScheduleArrangementDialogControllerTest: PASS");
    }

    private static void testPendingCloseWaitsForTheFinalWrite() {
        for (boolean fail : List.of(false, true)) {
            ControlledScheduleService service = new ControlledScheduleService();
            ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
            AtomicInteger refreshes = new AtomicInteger();
            controller.setOnChanged(refreshes::incrementAndGet);
            controller.previewArrangement();
            controller.saveArrangement();
            CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> pending =
                    new CompletableFuture<>();
            service.enqueueSave(pending);
            controller.previewArrangement();
            controller.saveArrangement();
            controller.handleCancel();
            require(refreshes.get() == 0,
                    "closing after an earlier success must wait for the outstanding write");
            if (fail) pending.completeExceptionally(new IllegalStateException("offline"));
            else pending.complete(new AdminOperationResultView<>("done", "OK", "saved", null));
            require(refreshes.get() == 1,
                    "the final write settling must refresh earlier committed changes exactly once");
        }
    }

    private static void testTeacherCannotAlsoBeAssistant() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        controller.setAssistantUid("T1001");
        controller.previewArrangement();
        require(service.previewRequests.isEmpty(),
                "the same teacher and assistant must be rejected before sending a preview");
        require(controller.validationMessage() != null
                        && controller.validationMessage().contains("助教"),
                "the local validation must identify the assistant selection");
        controller.setAssistantUid(null);
        controller.previewArrangement();
        require(service.previewRequests.size() == 1
                        && service.previewRequests.get(0).getAssistantUid() == null,
                "removing the assistant must restore a valid preview");
    }

    private static void testNewArrangementEndsUnknownSaveAttempt() {
        verifySaveAttemptBoundary("new");
    }

    private static void testReselectingArrangementEndsUnknownSaveAttempt() {
        verifySaveAttemptBoundary("edit");
    }

    private static void testSuccessfulDeleteEndsUnknownSaveAttempt() {
        verifySaveAttemptBoundary("delete");
    }

    private static void verifySaveAttemptBoundary(String boundary) {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        if ("edit".equals(boundary)) controller.editArrangement(arrangement("9001"));
        controller.previewArrangement();
        service.enqueueSave(CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException()));
        controller.saveArrangement();
        String uncertainId = service.saveRequests.get(0).getOperationId();
        switch (boundary) {
            case "new" -> invokeAction(controller, "handleNewArrangement");
            case "edit" -> controller.editArrangement(arrangement("9001"));
            case "delete" -> controller.requestDeleteArrangement(arrangement("9001"));
            default -> throw new AssertionError("unknown editor boundary");
        }
        validForm(controller);
        controller.previewArrangement();
        controller.saveArrangement();
        require(service.saveRequests.size() == 2
                        && !uncertainId.equals(service.saveRequests.get(1).getOperationId()),
                boundary + " must end the previous save attempt even when form values match");
    }

    private static void testPublishConflictReloadsThePlan() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        service.plan = new SchedulePlanDTO("7001", "new revision", 2, "DRAFT", false, List.of());
        service.publishFailure = new AdminCourseServiceException(MessageCode.CONFLICT, "stale revision");
        controller.requestPublishPlan();
        require(controller.plan().getRevision() == 2,
                "publication conflict must reload the authoritative plan revision");
        controller.requestPublishPlan();
        require(service.publishRequests.get(1).startsWith("7001@2@"),
                "publication retry must use the reloaded revision");
    }

    private static void testSaveConflictReconcilesTheEditedVersion() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        ScheduleArrangementView original = arrangement("9001");
        controller.editArrangement(original);
        controller.setWeekRange(5, 10);
        controller.previewArrangement();
        service.authoritativeArrangements = List.of(new ScheduleArrangementView(
                "9001", "7001", "1001", original.getTeacher(), original.getAssistant(),
                original.getClassroom(), original.getSlots(), 1, 16, "DRAFT", 2));
        service.failNextSave(new AdminCourseServiceException(MessageCode.CONFLICT, "stale version"));
        controller.saveArrangement();
        SaveArrangementRequestDTO preview = service.previewRequests.get(service.previewRequests.size() - 1);
        require(preview.getExpectedVersion() == 2 && preview.getStartWeek() == 5
                        && preview.getEndWeek() == 10,
                "conflict recovery must refresh the edit version while retaining the user's draft");
    }

    private static void testUnknownSaveResultRetainsOperationIdUntilIntentChanges() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        controller.previewArrangement();
        service.enqueueSave(CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException()));
        controller.saveArrangement();
        String firstId = service.saveRequests.get(0).getOperationId();
        controller.saveArrangement();
        require(firstId.equals(service.saveRequests.get(1).getOperationId()),
                "retrying an unchanged write after timeout must reuse its operationId");

        controller.previewArrangement();
        service.enqueueSave(CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException()));
        controller.saveArrangement();
        String nextId = service.saveRequests.get(2).getOperationId();
        require(!firstId.equals(nextId), "success must consume the previous write intention");
        controller.setWeekRange(5, 10);
        controller.previewArrangement();
        controller.saveArrangement();
        require(!nextId.equals(service.saveRequests.get(3).getOperationId()),
                "editing an unknown-result request must create a new write intention");

        service.setNextPreview(List.of(overridableConflict()));
        controller.previewArrangement();
        service.enqueueSave(CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException()));
        controller.saveArrangementWithForce(" 原因甲 ");
        String forceId = controller.lastSaveOperationId();
        controller.saveArrangementWithForce("原因甲");
        require(forceId.equals(controller.lastSaveOperationId()),
                "trimming-only reason changes must preserve the retry intention");
    }

    private static void testUnknownPublishResultRetainsOperationId() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        service.publishFailure = new java.util.concurrent.CompletionException(
                new java.util.concurrent.TimeoutException());
        controller.requestPublishPlan();
        controller.requestPublishPlan();
        require(service.publishRequests.get(0).equals(service.publishRequests.get(1)),
                "an unchanged publication retry must replay the same operationId and payload");
    }

    private static void testSuccessfulSaveConsumesPreviewAndEditIdentity() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        controller.editArrangement(arrangement("9001"));
        controller.previewArrangement();
        controller.saveArrangement();
        require(!controller.isPreviewCurrent() && !controller.canSave(),
                "a successful save must consume its preview and disable another save");
        int saves = service.saveRequests.size();
        controller.saveArrangement();
        require(service.saveRequests.size() == saves,
                "a second click after success must not repeat the consumed write");
        validForm(controller);
        controller.previewArrangement();
        SaveArrangementRequestDTO fresh = service.previewRequests.get(service.previewRequests.size() - 1);
        require(fresh.getArrangementId() == null && fresh.getExpectedVersion() == 0,
                "the reset editor must not retain a consumed arrangement version");
    }

    private static void testClosingTwiceRefreshesCatalogOnce() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        AtomicInteger refreshes = new AtomicInteger();
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.previewArrangement();
        controller.saveArrangement();
        controller.handleCancel();
        controller.handleCancel();
        require(refreshes.get() == 1,
                "button close and window-hidden cleanup must notify the catalog only once");
    }

    private static void testSaveCompletingAfterCloseRefreshesWithoutReloadingDialog() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        AtomicInteger refreshes = new AtomicInteger();
        controller.setOnChanged(refreshes::incrementAndGet);
        CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> pending =
                new CompletableFuture<>();
        service.enqueueSave(pending);
        controller.previewArrangement();
        controller.saveArrangement();
        controller.handleCancel();
        int loads = service.arrangementCalls.size();
        pending.complete(new AdminOperationResultView<>("done", "OK", "saved", null));
        require(refreshes.get() == 1,
                "a successful write that completes after closing must refresh the catalog");
        require(service.arrangementCalls.size() == loads && controller.isClosed(),
                "a late write must not reload or reopen the detached dialog");
        controller.handleCancel();
        require(refreshes.get() == 1, "late completion must not duplicate the close callback");
    }

    private static void testPublishUsesWholePlanConflictsAndTrimmedReason() {
        ControlledScheduleService warning = new ControlledScheduleService();
        warning.plan = new SchedulePlanDTO("7001", "draft", 4, "DRAFT", false,
                List.of(overridableConflict()));
        ScheduleArrangementDialogController controller = validForm(loaded(warning, new Recorder()));
        controller.setOverrideReason("   ");
        controller.requestPublishPlan();
        require(warning.publishRequests.isEmpty(),
                "whole-plan warnings must require a nonblank force reason before publication");
        controller.setOverrideReason("  已核实共享教室  ");
        controller.requestPublishPlan();
        require(warning.publishRequests.size() == 1
                        && warning.publishRequests.get(0).startsWith("7001@4@")
                        && warning.publishRequests.get(0).endsWith("@true@已核实共享教室"),
                "the visible publish action must force with the trimmed reason and plan revision");

        ControlledScheduleService blocking = new ControlledScheduleService();
        blocking.plan = new SchedulePlanDTO("7001", "draft", 2, "DRAFT", false,
                List.of(blockingConflict(), overridableConflict()));
        ScheduleArrangementDialogController blocked = validForm(loaded(blocking, new Recorder()));
        // 甲2 之后预检查也会刷新方案级冲突：服务端快照仍是这份方案的冲突，预检查改变不了它。
        blocking.setNextPreviewResult(List.of(), blocking.plan.getConflicts());
        blocked.previewArrangement();
        blocked.setOverrideReason("不能绕过阻断冲突");
        blocked.requestPublishPlan();
        require(blocking.publishRequests.isEmpty(),
                "a conflict-free editor preview must not bypass whole-plan blocking conflicts");

        ControlledScheduleService clean = new ControlledScheduleService();
        ScheduleArrangementDialogController cleanPlan = validForm(loaded(clean, new Recorder()));
        clean.setNextPreview(List.of(blockingConflict()));
        cleanPlan.previewArrangement();
        cleanPlan.requestPublishPlan();
        require(clean.publishRequests.size() == 1
                        && clean.publishRequests.get(0).endsWith("@false@null"),
                "an unsaved editor conflict must not block publication of a clean stored plan");
    }

    /**
     * 甲1(b)：方案级冲突按归属教学班分区；归属不明的旧数据一律算「其他教学班」。
     */
    private static void testPlanConflictsArePartitionedByOffering() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleConflictDTO ownBlocking = ownBlockingConflict();
        ScheduleConflictDTO ownOverridable = ownOverridableConflict();
        ScheduleConflictDTO otherBlocking = otherBlockingConflict();
        ScheduleConflictDTO otherOverridable = otherOverridableConflict();
        ScheduleConflictDTO unattributed = blockingConflict();
        service.plan = new SchedulePlanDTO("7001", "draft", 1, "DRAFT", false,
                List.of(ownBlocking, ownOverridable, otherBlocking, otherOverridable, unattributed));
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING)
                        .equals(List.of(ownBlocking)),
                "本教学班的阻断冲突必须只含本班那条，saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING));
        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE)
                        .equals(List.of(ownOverridable)),
                "本教学班的可绕过冲突必须只含本班那条，saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE));
        require(controller.otherPlanConflicts()
                        .equals(List.of(otherBlocking, otherOverridable, unattributed)),
                "其余冲突必须归入其他教学班，offeringId=null 的旧数据也不例外，saw "
                        + controller.otherPlanConflicts());
    }

    /**
     * 甲2：预检查的一次往返必须同时刷新表单级与方案级冲突——
     * 对话框开着时点「预检查冲突」，顶部的方案级冲突不能再等关闭重开。
     */
    private static void testPreviewSuccessRefreshesPlanConflicts() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleConflictDTO stale = ownBlockingConflict();
        service.plan = new SchedulePlanDTO("7001", "draft", 1, "DRAFT", false, List.of(stale));
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING)
                        .equals(List.of(stale)),
                "the loaded plan conflicts must be visible before the preview, saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING));

        ScheduleConflictDTO form = overridableConflict();
        ScheduleConflictDTO fresh = ownOverridableConflict();
        service.setNextPreviewResult(List.of(form), List.of(fresh));
        controller.previewArrangement();

        require(controller.conflicts().equals(List.of(form)),
                "a successful preview must refresh the form-level conflicts, saw "
                        + controller.conflicts());
        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE)
                        .equals(List.of(fresh)),
                "a successful preview must refresh the plan-level conflicts, saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE));
        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING).isEmpty(),
                "the stale plan-level conflict must be gone after the preview, saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING));
    }

    /**
     * 甲2：预检查失败时方案级冲突保持原样——旧数据仍然有效，
     * 不能因为一次失败的刷新就把顶部的冲突清空。
     */
    private static void testPreviewFailureKeepsPlanConflicts() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleConflictDTO stale = ownBlockingConflict();
        service.plan = new SchedulePlanDTO("7001", "draft", 1, "DRAFT", false, List.of(stale));
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        service.enqueuePreview(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        controller.previewArrangement();

        require(!controller.isPreviewCurrent() && controller.conflicts().isEmpty(),
                "a failed preview must clear the form-level conflicts, saw "
                        + controller.conflicts());
        require(controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING)
                        .equals(List.of(stale)),
                "a failed preview must keep the plan-level conflicts, saw "
                        + controller.ownPlanConflicts(ScheduleConflictSeverityDTO.BLOCKING));
    }

    /**
     * 甲1(a)：冲突文案必须渲染位置（周次/星期/节次），星期与节次复用 slotSummary 的格式化；
     * 位置数据缺失的段直接省略。
     */
    private static void testConflictTextCarriesThePosition() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        String text = controller.conflictText(ownBlockingConflict());
        require(("同一教学班在该时间已有排课（第 3 周 周三 第3-4节）").equals(text),
                "冲突文案必须带完整位置，saw " + text);
        require(text.contains(controller.slotSummary(new ScheduleSlotDTO(3, 3, 4))),
                "星期与节次必须复用 slotSummary 的格式化，saw " + text);

        ScheduleConflictDTO positionless = new ScheduleConflictDTO("TEACHER_OVERLAP",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "1001", "2004", "2004", "CS202-2026-2-A",
                0, 0, 0, 0, "任课教师在该时间已有其他课程");
        require("任课教师在该时间已有其他课程".equals(controller.conflictText(positionless)),
                "缺位置数据的冲突不得渲染空括号，saw " + controller.conflictText(positionless));
    }

    /**
     * 甲4：服务端已把连续周次合并成区间，文案必须渲染成「第 8-16 周」；单周（endWeek==week，或旧
     * journal JSON 缺失 endWeek 时的 0）仍是「第 8 周」，绝不出现「第 8-8 周」。
     */
    private static void testConflictTextRendersTheMergedWeekRange() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        ScheduleConflictDTO range = weekRangeConflict(8, 16);
        require("教室容量 40 小于教学班容量 45（第 8-16 周 周三 第3-4节）"
                        .equals(controller.conflictText(range)),
                "合并后的周次区间必须渲染成第 X-Y 周，saw " + controller.conflictText(range));

        ScheduleConflictDTO single = weekRangeConflict(8, 8);
        require("教室容量 40 小于教学班容量 45（第 8 周 周三 第3-4节）"
                        .equals(controller.conflictText(single)),
                "week==endWeek 的单周必须保持「第 8 周」，saw " + controller.conflictText(single));

        ScheduleConflictDTO missing = weekRangeConflict(8, 0);
        require("教室容量 40 小于教学班容量 45（第 8 周 周三 第3-4节）"
                        .equals(controller.conflictText(missing)),
                "缺失 endWeek（旧数据为 0）必须按单周归一，saw " + controller.conflictText(missing));
    }

    private static ScheduleConflictDTO weekRangeConflict(int week, int endWeek) {
        return new ScheduleConflictDTO("CLASSROOM_CAPACITY",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "3101", "2004", "2004",
                "CS202-2026-2-A", week, endWeek, 3, 3, 4, "教室容量 40 小于教学班容量 45");
    }

    /**
     * 甲1(b)：折叠成一行的文案必须报出其他教学班冲突的条数。
     */
    private static void testOtherConflictsSummaryTextCountsOthers() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.plan = new SchedulePlanDTO("7001", "draft", 1, "DRAFT", false,
                List.of(ownBlockingConflict(), otherBlockingConflict(), otherOverridableConflict()));
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        require(controller.otherPlanConflicts().size() == 2,
                "两条其他教学班的冲突必须都归入折叠区，saw " + controller.otherPlanConflicts());
        require("本方案还有 2 条其他教学班的冲突（点击展开）"
                        .equals(ScheduleArrangementDialogController.otherConflictsSummaryText(2)),
                "折叠行必须报出其他教学班的冲突条数，saw "
                        + ScheduleArrangementDialogController.otherConflictsSummaryText(2));
    }

    /**
     * 甲3：零条安排时分区标题接管空态文案，不再由永远可见的静态「已有安排」与「暂无」打架。
     */
    private static void testEmptyArrangementSectionText() {
        require("该教学班暂无排课安排"
                        .equals(ScheduleArrangementDialogController.arrangementSectionText(true)),
                "空态分区标题必须改说「暂无排课安排」，saw "
                        + ScheduleArrangementDialogController.arrangementSectionText(true));
    }

    /** 甲3：有安排（或加载中、出错）时分区标题保持默认文案。 */
    private static void testFilledArrangementSectionText() {
        require("该教学班已有安排"
                        .equals(ScheduleArrangementDialogController.arrangementSectionText(false)),
                "非空态分区标题必须保持默认文案，saw "
                        + ScheduleArrangementDialogController.arrangementSectionText(false));
    }

    private static void testSuccessfulWriteReloadsPlanState() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        service.plan = new SchedulePlanDTO("7001", "draft", 5, "DRAFT", false,
                List.of(overridableConflict()));
        controller.previewArrangement();
        controller.saveArrangement();
        require(controller.plan().getRevision() == 5 && controller.plan().getConflicts().size() == 1,
                "successful writes must reload the authoritative plan and its publication conflicts");
    }

    private static void testReadFailuresKeepTheirRetryTarget() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.resourceResults.add(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        require(controller.errorText() != null && !controller.arrangements().isEmpty(),
                "resource failure must remain visible when independent plan and arrangement loads succeed");
        invokeAction(controller, "handleRetry");
        require(service.resourceCalls.size() == 2 && controller.errorText() == null,
                "retry must reload failed resources and clear their error after success");

        service.enqueuePreview(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        controller.previewArrangement();
        int previews = service.previewRequests.size();
        require(controller.errorText() != null, "a failed preview must show its retry state");
        invokeAction(controller, "handleRetry");
        require(service.previewRequests.size() == previews + 1 && controller.isPreviewCurrent(),
                "retry after preview failure must actually rerun the preview");

        CompletableFuture<CheckArrangementResultDTO> pending = new CompletableFuture<>();
        service.enqueuePreview(pending);
        controller.previewArrangement();
        controller.setWeekRange(2, 6);
        require(!controller.isPreviewPending() && !controller.isPreviewCurrent(),
                "editing must invalidate both completed and pending preview state");
        pending.complete(new CheckArrangementResultDTO(List.of(), List.of()));
        require(!controller.canSave(), "a stale preview completion must not enable saving");
    }

    private static void testEditingCanReturnToANewArrangement() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        controller.editArrangement(arrangement("9001"));
        invokeAction(controller, "handleNewArrangement");
        validForm(controller);
        controller.previewArrangement();
        SaveArrangementRequestDTO request = service.previewRequests.get(0);
        require(request.getArrangementId() == null && request.getExpectedVersion() == 0,
                "the new-arrangement action must clear the previous edit identity");
    }

    private static void testPublishedPlanRejectsFurtherMutations() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.plan = new SchedulePlanDTO("7001", "published", 1, "PUBLISHED", true, List.of());
        ScheduleArrangementDialogController controller = validForm(loaded(service, new Recorder()));
        controller.previewArrangement();
        controller.saveArrangement();
        controller.requestDeleteArrangement(arrangement("9001"));
        controller.requestPublishPlan();
        require(service.saveRequests.isEmpty() && service.deleteRequests.isEmpty()
                        && service.publishRequests.isEmpty(),
                "the published plan must be read-only in the dialog");
    }

    private static void invokeAction(ScheduleArrangementDialogController controller, String name) {
        try {
            var method = ScheduleArrangementDialogController.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("dialog action failed: " + name, failure);
        }
    }

    /**
     * 多个时间段行共享同一周次，并且必须原样到达服务端。
     */
    private static void testMultipleSlotRowsAndSharedWeeks() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = loaded(service, new Recorder());
        require(controller.slotEditors().size() == 1, "a fresh dialog must start with one slot row");

        controller.addSlotRow();
        require(controller.slotEditors().size() == 2,
                "the dialog must support multiple slot rows, saw " + controller.slotEditors().size());
        controller.setClassroomId("3001");
        controller.setWeekRange(5, 10);
        setSlot(controller, 0, 1, 1, 2);
        setSlot(controller, 1, 3, 3, 4);
        require(controller.validationMessage() == null,
                "two disjoint slot rows are valid, saw " + controller.validationMessage());

        controller.previewArrangement();
        require(service.previewRequests.size() == 1,
                "a valid preview must reach the service exactly once");
        SaveArrangementRequestDTO request = service.previewRequests.get(0);
        require(request.getSlots().size() == 2, "both slot rows must be transported");
        require(request.getStartWeek() == 5 && request.getEndWeek() == 10,
                "the shared week range must be transported once, saw "
                        + request.getStartWeek() + "-" + request.getEndWeek());
        require(request.getSlots().get(0).getDayOfWeek() == 1
                        && request.getSlots().get(0).getStartPeriod() == 1
                        && request.getSlots().get(0).getEndPeriod() == 2,
                "the first slot row must keep its weekday and periods");
        require(request.getSlots().get(1).getDayOfWeek() == 3
                        && request.getSlots().get(1).getStartPeriod() == 3
                        && request.getSlots().get(1).getEndPeriod() == 4,
                "the second slot row must keep its weekday and periods");
        require("1001".equals(request.getOfferingId()),
                "the dialog context must supply the offering identity");
        require(request.getArrangementId() == null && request.getExpectedVersion() == 0,
                "a new arrangement must not carry an identifier or version");

        controller.removeSlotRow(1);
        require(controller.slotEditors().size() == 1, "removing a slot row must drop it");
        controller.removeSlotRow(0);
        require(controller.slotEditors().size() == 1,
                "the editor must retain at least one slot row");
    }

    /**
     * 一张教学安排卡片必须同时呈现全部时间段与共享周次。
     */
    private static void testArrangementCardsSummarizeSharedWeeksAndAllSlots() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = loaded(service, new Recorder());
        require(controller.arrangements().size() == 1,
                "one authoritative arrangement must render exactly one card, saw "
                        + controller.arrangements().size());

        ScheduleArrangementView arrangement = controller.arrangements().get(0);
        String summary = controller.arrangementSummary(arrangement);
        require(summary.contains("张老师"), "the card must show the teacher, saw " + summary);
        require(summary.contains("A-101"), "the card must show the classroom, saw " + summary);
        require(summary.contains("1-16 周"),
                "the card must show the shared week range, saw " + summary);
        require(summary.contains("周一 第1-2节"),
                "the card must list the first slot, saw " + summary);
        require(summary.contains("周三 第3-4节"),
                "the card must list the second slot, saw " + summary);
        require("周二 第5节".equals(controller.slotSummary(new ScheduleSlotDTO(2, 5, 5))),
                "a single-period slot must not print a range, saw "
                        + controller.slotSummary(new ScheduleSlotDTO(2, 5, 5)));
    }

    /**
     * 本地校验必须先于传输拒绝缺字段、重叠与越界时间段。
     */
    private static void testLocalValidationRejectsIncompleteOverlappingAndOutOfRangeInput() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = loaded(service, new Recorder());
        setSlot(controller, 0, 1, 1, 2);
        controller.setClassroomId("3001");
        controller.setWeekRange(1, 16);
        require(controller.validationMessage() == null,
                "a complete form must validate, saw " + controller.validationMessage());

        controller.addSlotRow();
        require(controller.validationMessage() != null,
                "an incomplete slot row must not validate");
        setSlot(controller, 1, 1, 1, 2);
        require(controller.validationMessage() != null
                        && controller.validationMessage().contains("重叠"),
                "duplicate or overlapping slot rows must be rejected locally, saw "
                        + controller.validationMessage());

        setSlot(controller, 1, 1, 8, 9);
        require(controller.validationMessage() == null,
                "disjoint periods on the same weekday must validate, saw "
                        + controller.validationMessage());

        controller.slotEditors().get(1).setStartPeriod(9);
        controller.slotEditors().get(1).setEndPeriod(8);
        require(controller.validationMessage() != null,
                "an inverted period range must be rejected locally");

        setSlot(controller, 1, 1, 8, 9);
        controller.setWeekRange(10, 5);
        require(controller.validationMessage() != null,
                "an inverted week range must be rejected locally");

        controller.setWeekRange(1, 16);
        controller.setTeacherUid("");
        require(controller.validationMessage() != null
                        && controller.validationMessage().contains("教师"),
                "a missing teacher must be rejected locally, saw "
                        + controller.validationMessage());

        controller.setTeacherUid("T1001");
        controller.setClassroomId(null);
        require(controller.validationMessage() != null,
                "a missing classroom must be rejected locally");

        int previews = service.previewRequests.size();
        controller.previewArrangement();
        require(service.previewRequests.size() == previews,
                "an invalid form must not be transported as a preview");
    }

    /**
     * 表单再次变化后，之前的预检查结果不再授权保存。
     */
    private static void testFormEditInvalidatesCompletedPreview() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        require(!controller.isPreviewCurrent() && !controller.canSave(),
                "saving must require a completed preview");

        controller.previewArrangement();
        require(controller.isPreviewCurrent(),
                "a completed preview must authorize saving");
        require(controller.canSave(), "a conflict-free preview must enable saving");

        controller.setWeekRange(5, 10);
        require(!controller.isPreviewCurrent(),
                "editing the form must invalidate the completed preview");
        require(!controller.canSave(), "an invalidated preview must not enable saving");
    }

    /**
     * 过期的预检查响应不能覆盖较新的冲突状态，也不能开启保存。
     */
    private static void testPreviewGenerationProtectionIgnoresStaleResult() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));

        CompletableFuture<CheckArrangementResultDTO> stale = new CompletableFuture<>();
        CompletableFuture<CheckArrangementResultDTO> newest = new CompletableFuture<>();
        service.enqueuePreview(stale);
        service.enqueuePreview(newest);
        controller.previewArrangement();
        controller.previewArrangement();
        require(controller.isPreviewPending(), "two pending previews must report pending");

        newest.complete(new CheckArrangementResultDTO(List.of(), List.of()));
        require(!controller.isPreviewPending(), "the newest preview must clear the pending state");
        require(controller.isPreviewCurrent(), "the newest preview must authorize saving");
        require(controller.conflicts().isEmpty(), "the newest preview reported no conflicts");

        stale.complete(new CheckArrangementResultDTO(List.of(blockingConflict()),
                List.of(otherBlockingConflict())));
        require(controller.conflicts().isEmpty(),
                "a stale preview must not overwrite the newest conflict state, saw "
                        + controller.conflicts());
        require(!controller.hasBlockingConflicts(),
                "a stale preview must not introduce blocking conflicts");
        require(controller.otherPlanConflicts().isEmpty(),
                "a stale preview must not overwrite the plan-level conflicts either, saw "
                        + controller.otherPlanConflicts());
        require(controller.canSave(), "a stale preview must not revoke the newest authorization");

        CompletableFuture<CheckArrangementResultDTO> orphaned = new CompletableFuture<>();
        service.enqueuePreview(orphaned);
        controller.previewArrangement();
        controller.setWeekRange(5, 10);
        orphaned.complete(new CheckArrangementResultDTO(List.of(), List.of()));
        require(!controller.isPreviewCurrent(),
                "a preview whose form changed mid-flight must not authorize saving");
        require(!controller.canSave(),
                "a stale preview must not enable saving for the current form");
    }

    /**
     * 过期的方案与教学安排加载不能覆盖较新的对话框状态。
     */
    private static void testStalePlanAndArrangementLoadsAreIgnored() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        CompletableFuture<SchedulePlanDTO> stalePlan = new CompletableFuture<>();
        CompletableFuture<SchedulePlanDTO> newestPlan = new CompletableFuture<>();
        service.enqueuePlan(stalePlan);
        service.enqueuePlan(newestPlan);
        controller.prepareForOffering(offering());
        controller.prepareForOffering(offering());
        int callsBefore = service.arrangementCalls.size();
        stalePlan.complete(plan("7001"));
        require(service.arrangementCalls.size() == callsBefore,
                "a stale plan must not drive an arrangement load, saw "
                        + service.arrangementCalls);
        newestPlan.complete(plan("7002"));
        require(controller.plan() != null && "7002".equals(controller.plan().getPlanId()),
                "the newest plan load must win, saw " + controller.plan());
        require(service.arrangementCalls.size() == callsBefore + 1
                        && "7002|1001".equals(
                                service.arrangementCalls.get(callsBefore)),
                "the newest plan must drive exactly one arrangement load, saw "
                        + service.arrangementCalls);

        CompletableFuture<List<ScheduleArrangementView>> staleArrangements =
                new CompletableFuture<>();
        CompletableFuture<List<ScheduleArrangementView>> newestArrangements =
                new CompletableFuture<>();
        service.enqueueArrangements(staleArrangements);
        service.enqueueArrangements(newestArrangements);
        controller.reloadArrangements();
        controller.reloadArrangements();
        require(controller.isLoadingArrangements(),
                "two pending arrangement loads must report loading");
        newestArrangements.complete(List.of(arrangement("9100")));
        require(!controller.isLoadingArrangements(),
                "the newest arrangement load must clear the loading state");
        staleArrangements.complete(List.of(arrangement("9001")));
        require(controller.arrangements().size() == 1
                        && "9100".equals(controller.arrangements().get(0).getArrangementId()),
                "a stale arrangement load must not replace the newest result, saw "
                        + controller.arrangements());
    }

    /**
     * 对话框关闭后到达的响应不再更新已卸载状态。
     */
    private static void testResponsesAfterCloseAreIgnored() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        controller.reloadArrangements();
        List<ScheduleArrangementView> displayed = controller.arrangements();

        CompletableFuture<List<ScheduleArrangementView>> detached = new CompletableFuture<>();
        service.enqueueArrangements(detached);
        controller.reloadArrangements();
        controller.handleCancel();
        require(controller.isClosed(), "cancelling must mark the dialog as closed");
        detached.complete(List.of(arrangement("9001"), arrangement("9002")));
        require(controller.arrangements() == displayed
                        || controller.arrangements().size() == displayed.size(),
                "a response arriving after close must not update the detached view, saw "
                        + controller.arrangements());
    }

    /**
     * 阻断冲突只提供返回修改，强制路径必须被移除。
     */
    private static void testBlockingConflictsDisableTheForcePath() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        service.setNextPreview(List.of(blockingConflict()));
        controller.previewArrangement();

        require(controller.hasBlockingConflicts(), "the blocking conflict must be reported");
        require(controller.blockingConflicts().size() == 1,
                "grouping must expose exactly one blocking conflict");
        require(controller.overridableConflicts().isEmpty(),
                "a blocking conflict must not be grouped as overridable");
        require(!controller.canForce(),
                "a blocking conflict must remove the force path");
        require(!controller.canSave(), "a blocking conflict must not enable saving");

        int writes = service.saveRequests.size();
        controller.saveArrangement();
        controller.saveArrangementWithForce("教室冲突已确认");
        require(service.saveRequests.size() == writes,
                "a blocking conflict must never reach the service, saw "
                        + service.saveRequests.size() + " writes");
        require(controller.validationMessage() != null,
                "a blocked save must explain itself locally");
    }

    /**
     * 可绕过冲突要求去空白后非空的强制原因。
     */
    private static void testOverridableConflictsRequireATrimmedReason() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        service.setNextPreview(List.of(overridableConflict()));
        controller.previewArrangement();

        require(controller.overridableConflicts().size() == 1,
                "the overridable conflict must be grouped as overridable");
        require(controller.canForce(), "an overridable conflict must expose the force path");
        require(!controller.canSave(),
                "an overridable conflict must not save without a force reason");

        int writes = service.saveRequests.size();
        controller.saveArrangementWithForce("   ");
        require(service.saveRequests.size() == writes,
                "a blank force reason must be rejected before transport");
        require(controller.validationMessage() != null,
                "a blank force reason must be explained locally");

        controller.saveArrangementWithForce("  教师冲突已确认  ");
        require(service.saveRequests.size() == writes + 1,
                "a trimmed non-blank reason must reach the service");
        SaveArrangementRequestDTO request = service.saveRequests.get(writes);
        require(request.isForce(), "an overridable save must set force");
        require("教师冲突已确认".equals(request.getOverrideReason()),
                "the force reason must be trimmed before transport, saw "
                        + request.getOverrideReason());
    }

    /**
     * 预检查与每次语义不同的写入都必须使用新的 operationId。
     */
    private static void testPreviewAndWritesUseDistinctOperationIds() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));

        controller.previewArrangement();
        String firstPreview = controller.lastPreviewOperationId();
        require(firstPreview != null, "a preview must mint an operation id");
        UUID.fromString(firstPreview);
        require(firstPreview.equals(service.previewRequests.get(0).getOperationId()),
                "the preview request must carry the reported operation id");

        controller.saveArrangement();
        String firstSave = controller.lastSaveOperationId();
        UUID.fromString(firstSave);
        require(!firstSave.equals(firstPreview),
                "a save must not reuse the preview operation id");

        service.setNextPreview(List.of(overridableConflict()));
        controller.previewArrangement();
        String secondPreview = controller.lastPreviewOperationId();
        UUID.fromString(secondPreview);
        require(!secondPreview.equals(firstPreview),
                "each preview must mint a distinct operation id");

        controller.previewArrangement();
        require(!controller.lastPreviewOperationId().equals(secondPreview),
                "a repeated preview must not reuse the previous operation id");

        controller.saveArrangementWithForce("教师冲突已确认");
        String secondSave = controller.lastSaveOperationId();
        require(!secondSave.equals(firstSave),
                "changing force intent after a preview must mint a new operation id");
        require(!secondSave.equals(secondPreview),
                "a write must not reuse a preview operation id");

        require(!controller.isPreviewCurrent(), "a successful force save must also consume its preview");
        controller.previewArrangement();
        controller.saveArrangementWithForce("教室容量已确认");
        String thirdSave = controller.lastSaveOperationId();
        require(!thirdSave.equals(secondSave),
                "changing the force reason must mint a new operation id");
    }

    /**
     * 写入期间相关控件禁用，成功与失败路径都必须恢复。
     */
    private static void testSaveControlDisabledWhilePendingAndRestoredOnEveryPath() {
        ControlledScheduleService service = new ControlledScheduleService();
        Recorder recorder = new Recorder();
        ScheduleArrangementDialogController controller = validForm(loaded(service, recorder));
        AtomicBoolean disabled = new AtomicBoolean(false);
        controller.registerWriteControl(SAVE_KEY, disabled::set);
        require(!disabled.get(), "a registered idle control must start enabled");

        CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> pending =
                new CompletableFuture<>();
        service.enqueueSave(pending);
        controller.previewArrangement();
        controller.saveArrangement();
        require(disabled.get(), "the save control must be disabled while the write is pending");
        require(controller.isWritePending(SAVE_KEY), "the write must be tracked as pending");

        pending.completeExceptionally(new IllegalStateException("network down"));
        require(!disabled.get(), "a failed write must restore the save control");
        require(!controller.isWritePending(SAVE_KEY), "a failed write must not stay pending");
        require(recorder.lastMessage().startsWith("network down"),
                "an ordinary failure must surface a stable error, saw " + recorder.lastMessage());

        controller.previewArrangement();
        controller.saveArrangement();
        require(!disabled.get() && !controller.isWritePending(SAVE_KEY),
                "a successful write must restore the save control");
    }

    /**
     * 删除必须二次确认并携带当前版本；取消不产生写入。
     */
    private static void testDeleteRequiresConfirmationAndCurrentVersion() {
        ControlledScheduleService service = new ControlledScheduleService();
        List<String> confirmations = new ArrayList<>();
        ScheduleArrangementDialogController controller = prepared(service, (title, message) -> {
            confirmations.add(title + "|" + message);
            return ButtonType.CANCEL;
        }, new Recorder());
        controller.reloadArrangements();
        ScheduleArrangementView target = controller.arrangements().get(0);

        controller.requestDeleteArrangement(target);
        require(confirmations.size() == 1, "deleting must ask for confirmation");
        require(service.deleteRequests.isEmpty(),
                "a cancelled delete must not reach the service");

        ControlledScheduleService agreeing = new ControlledScheduleService();
        ScheduleArrangementDialogController confirmed = prepared(agreeing,
                (title, message) -> ButtonType.OK, new Recorder());
        confirmed.reloadArrangements();
        int loadsBefore = agreeing.arrangementCalls.size();
        confirmed.requestDeleteArrangement(confirmed.arrangements().get(0));
        require(agreeing.deleteRequests.size() == 1, "a confirmed delete must reach the service");
        require(agreeing.deleteRequests.get(0).startsWith("9001@1@"),
                "the delete must carry the arrangement identity and current version, saw "
                        + agreeing.deleteRequests.get(0));
        UUID.fromString(agreeing.deleteRequests.get(0).substring(
                agreeing.deleteRequests.get(0).lastIndexOf('@') + 1));
        require(agreeing.arrangementCalls.size() == loadsBefore + 1,
                "a successful delete must reload the authoritative arrangements");
    }

    /**
     * 发布必须二次确认并携带当前方案修订号。
     */
    private static void testPublishRequiresConfirmationAndPlanRevision() {
        ControlledScheduleService service = new ControlledScheduleService();
        List<String> confirmations = new ArrayList<>();
        ScheduleArrangementDialogController controller = prepared(service, (title, message) -> {
            confirmations.add(title);
            return ButtonType.CANCEL;
        }, new Recorder());
        controller.requestPublishPlan();
        require(confirmations.size() == 1, "publishing must ask for confirmation");
        require(service.publishRequests.isEmpty(),
                "a cancelled publish must not reach the service");

        ControlledScheduleService agreeing = new ControlledScheduleService();
        ScheduleArrangementDialogController confirmed = prepared(agreeing,
                (title, message) -> ButtonType.OK, new Recorder());
        int loadsBefore = agreeing.arrangementCalls.size();
        confirmed.requestPublishPlan();
        require(agreeing.publishRequests.size() == 1, "a confirmed publish must reach the service");
        String publish = agreeing.publishRequests.get(0);
        require(publish.startsWith("7001@1@"),
                "the publish must carry the plan identity and current revision, saw " + publish);
        require(publish.endsWith("@false@null"),
                "a conflict-free publish must not force, saw " + publish);
        require(agreeing.arrangementCalls.size() == loadsBefore + 1,
                "a successful publish must reload the authoritative arrangements");
    }

    /**
     * 任何成功写入之后都必须重新加载权威教学安排。
     */
    private static void testSuccessfulWritesReloadAuthoritativeArrangements() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        require(controller.arrangements().size() == 1,
                "the dialog must load the authoritative arrangements");
        int loads = service.arrangementCalls.size();

        service.authoritativeArrangements = List.of(arrangement("9001"), arrangement("9002"));
        controller.previewArrangement();
        controller.saveArrangement();

        require(service.arrangementCalls.size() == loads + 1,
                "a successful save must reload the authoritative arrangements");
        require(controller.arrangements().size() == 2,
                "the reloaded arrangements must replace the displayed ones, saw "
                        + controller.arrangements());
        require(controller.hasMutated(),
                "a successful write must mark the dialog as mutated");
    }

    /**
     * 写入返回不带冲突列表的冲突时，必须按既定流程重新预检查并权威重载。
     */
    private static void testWriteConflictTriggersPreviewAndAuthoritativeReload() {
        ControlledScheduleService service = new ControlledScheduleService();
        Recorder recorder = new Recorder();
        ScheduleArrangementDialogController controller = validForm(loaded(service, recorder));
        controller.previewArrangement();
        int previews = service.previewRequests.size();
        int loads = service.arrangementCalls.size();

        service.failNextSave(new AdminCourseServiceException(MessageCode.CONFLICT, "版本冲突"));
        controller.saveArrangement();

        require(CONFLICT_MESSAGE.equals(recorder.lastMessage()),
                "a write conflict must report exactly " + CONFLICT_MESSAGE + ", saw "
                        + recorder.lastMessage());
        require(service.previewRequests.size() == previews + 1,
                "a write conflict must trigger a fresh preview instead of trusting the"
                        + " exception payload");
        require(service.arrangementCalls.size() == loads + 1,
                "a write conflict must reload the authoritative arrangements");
        require(!controller.isWritePending(SAVE_KEY),
                "a conflicting write must not stay pending");
    }

    /**
     * 目录回调只在关闭且已经发生写入时触发一次。
     */
    private static void testCatalogRefreshCallbackFiresOnlyAfterAMutation() {
        ControlledScheduleService service = new ControlledScheduleService();
        AtomicInteger refreshes = new AtomicInteger();
        ScheduleArrangementDialogController controller = validForm(
                loaded(service, new Recorder()));
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.handleCancel();
        require(refreshes.get() == 0,
                "closing without a mutation must not refresh the catalog, saw "
                        + refreshes.get());

        ControlledScheduleService mutating = new ControlledScheduleService();
        ScheduleArrangementDialogController writer = validForm(
                loaded(mutating, new Recorder()));
        writer.setOnChanged(refreshes::incrementAndGet);
        writer.previewArrangement();
        writer.saveArrangement();
        require(writer.hasMutated(), "the successful save must be recorded as a mutation");
        writer.handleCancel();
        require(refreshes.get() == 1,
                "closing after a mutation must refresh the catalog exactly once, saw "
                        + refreshes.get());
    }

    /**
     * loadPlan 失败后仍必须给出创建草稿的出路，否则用户无路可走：整屏写控件都依赖方案。
     * 同时钉住复制意图：加载失败时**信息不足**，必须按安全默认值传 true——该学期可能真有已发布
     * 方案，传 false 会静默造出一份空草稿。只有确知没有方案（加载成功且返回空）才允许传 false。
     */
    private static void createDraftIsOfferedWhenNoPlanLoaded() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.enqueuePlan(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.NOT_FOUND, "该学期尚未创建教学日历")));
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        require(controller.plan() == null,
                "a failed plan load must leave the dialog without a plan, saw " + controller.plan());
        require(controller.errorText() != null,
                "the failed plan load must stay visible with its retry action");
        require(createDraftOffered(controller),
                "a dialog without a plan must still offer the create-draft entry");

        invokeAction(controller, "handleCreateDraft");
        require(service.createDraftRequests.size() == 1,
                "the offered entry must create exactly one draft, saw "
                        + service.createDraftRequests);
        require("true".equals(service.createDraftRequests.get(0).split("\\|", -1)[2]),
                "a failed plan load must still ask the server to copy: that term may well have a "
                        + "published plan, saw " + service.createDraftRequests.get(0));

        ControlledScheduleService emptyTerm = new ControlledScheduleService();
        emptyTerm.plan = null;
        ScheduleArrangementDialogController noPlan = controller(emptyTerm, new Recorder());
        require(createDraftOffered(noPlan),
                "a term that really has no plan must still offer the create-draft entry");
        invokeAction(noPlan, "handleCreateDraft");
        require(emptyTerm.createDraftRequests.size() == 1
                        && "false".equals(emptyTerm.createDraftRequests.get(0).split("\\|", -1)[2]),
                "only a successfully loaded term without a plan may pass false, saw "
                        + emptyTerm.createDraftRequests);
    }

    /**
     * 成功后要重新拉一次方案，否则界面仍停在「无方案」；请求本身必须带学期与复制意图，
     * 服务端的结果文案（已复制/跳过条数）必须留在界面上，空草稿才不会是静默的。
     */
    private static void createDraftReloadsThePlanOnSuccess() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.plan = new SchedulePlanDTO("7001", "published", 3, "PUBLISHED", true, List.of());
        ScheduleArrangementDialogController controller = controller(service, new Recorder());
        int loads = service.planCalls.size();
        require(createDraftOffered(controller),
                "a published plan must still offer the create-draft entry");

        invokeAction(controller, "handleCreateDraft");
        require(service.createDraftRequests.size() == 1,
                "the offered entry must create exactly one draft, saw "
                        + service.createDraftRequests);
        String[] request = service.createDraftRequests.get(0).split("\\|", -1);
        require(request.length == 4 && "2026".equals(request[0]) && "1".equals(request[1])
                        && "true".equals(request[2]),
                "the request must carry the offering term and copy the published plan, saw "
                        + service.createDraftRequests.get(0));
        UUID.fromString(request[3]);
        require(service.planCalls.size() == loads + 1,
                "a created draft must be reloaded so the dialog shows the editable plan, saw "
                        + service.planCalls);
        require(CREATE_DRAFT_MESSAGE.equals(controller.validationMessage())
                        && validationShown(controller),
                "the server's copy counts must be visible on the dialog, saw "
                        + controller.validationMessage() + " (visible=" + validationShown(controller)
                        + ")");
    }

    /**
     * 失败分支（本计划新增的 validationVisible = true 之后才看得见）必须把服务端原因留在界面上，
     * 否则按钮弹回去而用户不知道原因。
     */
    private static void createDraftFailureKeepsTheServerReason() {
        ControlledScheduleService service = new ControlledScheduleService();
        service.plan = new SchedulePlanDTO("7001", "published", 3, "PUBLISHED", true, List.of());
        service.createDraftFailure = new AdminCourseServiceException(MessageCode.CONFLICT,
                "该学期已有草稿方案");
        ScheduleArrangementDialogController controller = controller(service, new Recorder());
        require(createDraftOffered(controller),
                "a published plan must still offer the create-draft entry");

        invokeAction(controller, "handleCreateDraft");
        require(service.createDraftRequests.size() == 1,
                "the offered entry must ask the server once, saw " + service.createDraftRequests);
        String message = controller.validationMessage();
        require(message != null && message.contains("创建草稿方案失败")
                        && message.contains("该学期已有草稿方案"),
                "a refused create must surface the server reason, saw " + message);
        require(validationShown(controller),
                "the refusal must be visible rather than only computed");
    }

    /**
     * 已有可编辑草稿时不该再给一个必然报「该学期已有草稿方案」的按钮。
     */
    private static void createDraftIsHiddenWhenADraftIsEditable() {
        ControlledScheduleService service = new ControlledScheduleService();
        ScheduleArrangementDialogController controller = controller(service, new Recorder());
        require(controller.plan() != null && "DRAFT".equals(controller.plan().getStatus()),
                "the fixture must load an editable draft, saw " + controller.plan());

        require(!createDraftOffered(controller),
                "an editable draft must hide the create-draft entry");
        invokeAction(controller, "handleCreateDraft");
        require(service.createDraftRequests.isEmpty(),
                "the hidden entry must not ask the server for a second draft of the same term");
    }

    /**
     * 丢掉服务端消息会让「缺失任课教师」这类真实原因永远看不见。
     */
    private static void loadPlanFailureSurfacesTheServerMessage() {
        ControlledScheduleService service = new ControlledScheduleService();
        String message = "教学安排缺少任课教师或时间段，无法发布";
        service.enqueuePlan(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.CONFLICT, message)));
        ScheduleArrangementDialogController controller = controller(service, new Recorder());

        require(controller.errorText() != null && controller.errorText().contains(message),
                "the server message must survive the failed plan load, saw " + controller.errorText());
        require(!"排课方案加载失败，请重试".equals(controller.errorText()),
                "the failed plan load must not replace the server message with a generic one");
    }

    /**
     * FXML 的 fx:id / onAction 必须与控制器对得上。这个文件全仓只有生产路径与一个跑不起来的冒烟
     * 测试会加载，打错一个字不会有任何能跑的测试变红，所以在这里用 DOM + 反射钉住新的入口按钮与静态文案。
     *
     * 没有做全量扫描（TeacherGradeBookControllerTest.verifyBindings）：该 FXML 里
     * fx:id="scheduleContentScroll" 本来就没有对应的控制器字段（既有债，见 Task 7 报告），
     * 全量扫描会因为这条既有不匹配直接报红，而修它不是本任务的范围。
     */
    private static void scheduleArrangementDialogViewKeepsItsBindings() {
        try {
            Document view = parseView(DIALOG_VIEW);
            Element createDraft = elementWithId(view, "createDraftButton");
            require(createDraft != null, "排课对话框必须提供创建草稿方案的入口按钮");
            require("Button".equals(createDraft.getTagName()),
                    "创建草稿入口必须是 Button，收到 <" + createDraft.getTagName() + ">");
            require("#handleCreateDraft".equals(createDraft.getAttribute("onAction")),
                    "创建草稿入口必须接到 handleCreateDraft，收到 onAction=\""
                            + createDraft.getAttribute("onAction") + "\"");
            Field button = findField(ScheduleArrangementDialogController.class, "createDraftButton");
            require(button != null, "fx:id=\"createDraftButton\" 在控制器里没有对应字段");
            require(Button.class.equals(button.getType()),
                    "createDraftButton 必须声明为 Button，收到 " + button.getType());
            require(hasActionMethod(ScheduleArrangementDialogController.class, "handleCreateDraft"),
                    "onAction=\"#handleCreateDraft\" 在控制器里没有对应处理函数");
            Element section = elementWithId(view, "arrangementSectionLabel");
            require(section != null, "「该教学班已有安排」分区标题必须带 fx:id，控制器才能改写空态文案");
            require("Label".equals(section.getTagName()),
                    "分区标题必须是 Label，收到 <" + section.getTagName() + ">");
            require("该教学班已有安排".equals(section.getAttribute("text")),
                    "分区标题的 FXML 默认文案必须是非空态文案，收到 text=\""
                            + section.getAttribute("text") + "\"");
            Field sectionField = findField(ScheduleArrangementDialogController.class,
                    "arrangementSectionLabel");
            require(sectionField != null, "fx:id=\"arrangementSectionLabel\" 在控制器里没有对应字段");
            require(Label.class.equals(sectionField.getType()),
                    "arrangementSectionLabel 必须声明为 Label，收到 " + sectionField.getType());
            require(elementWithId(view, "emptyArrangementLabel") == null,
                    "空态文案已由分区标题接管，emptyArrangementLabel 节点必须从 FXML 删除");
            Element legend = elementWithId(view, "conflictLegendLabel");
            require(legend != null, "方案冲突区必须提供红/黄严重度图例");
            require("Label".equals(legend.getTagName()),
                    "冲突图例必须是 Label，收到 <" + legend.getTagName() + ">");
            require(("红色为阻断性冲突：必须解决后才能保存或发布；黄色为可绕过冲突：填写原因后可保存或发布")
                            .equals(legend.getAttribute("text")),
                    "图例必须解释红/黄对保存与发布意味着什么，收到 text=\""
                            + legend.getAttribute("text") + "\"");
            Field legendField = findField(ScheduleArrangementDialogController.class,
                    "conflictLegendLabel");
            require(legendField != null, "fx:id=\"conflictLegendLabel\" 在控制器里没有对应字段");
            require(Label.class.equals(legendField.getType()),
                    "conflictLegendLabel 必须声明为 Label，收到 " + legendField.getType());
        } catch (Exception failure) {
            throw new AssertionError("排课对话框的 FXML 契约检查失败", failure);
        }
    }

    /**
     * 「创建草稿方案」是否提供，是控制器自己的判定：测试里没有真实窗口，读不到按钮节点。
     */
    private static boolean createDraftOffered(ScheduleArrangementDialogController controller) {
        try {
            Method method = ScheduleArrangementDialogController.class
                    .getDeclaredMethod("isCreateDraftOffered");
            method.setAccessible(true);
            return (Boolean) method.invoke(controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "the dialog must expose whether it offers the create-draft entry", failure);
        }
    }

    /**
     * 校验行是否真的显示：没有窗口时渲染结果读数不到，只能读控制器的开关。
     */
    private static boolean validationShown(ScheduleArrangementDialogController controller) {
        try {
            Field field = findField(ScheduleArrangementDialogController.class, "validationVisible");
            field.setAccessible(true);
            return field.getBoolean(controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the dialog must expose whether its validation line is shown",
                    failure);
        }
    }

    private static Document parseView(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream = ScheduleArrangementDialogControllerTest.class
                .getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
        }
        return null;
    }

    private static boolean hasActionMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return true;
                if (method.getParameterCount() == 1
                        && javafx.event.Event.class.isAssignableFrom(
                                method.getParameterTypes()[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void setSlot(ScheduleArrangementDialogController controller, int index,
            int dayOfWeek, int startPeriod, int endPeriod) {
        ScheduleSlotEditor editor = controller.slotEditors().get(index);
        editor.setDayOfWeek(dayOfWeek);
        editor.setStartPeriod(startPeriod);
        editor.setEndPeriod(endPeriod);
    }

    private static ScheduleArrangementDialogController validForm(
            ScheduleArrangementDialogController controller) {
        controller.setTeacherUid("T1001");
        controller.setClassroomId("3001");
        controller.setWeekRange(1, 16);
        setSlot(controller, 0, 1, 1, 2);
        return controller;
    }

    private static ScheduleArrangementDialogController loaded(
            ControlledScheduleService service, Recorder recorder) {
        return controller(service, recorder);
    }

    private static ScheduleArrangementDialogController prepared(
            ControlledScheduleService service,
            BiFunction<String, String, ButtonType> confirmation, Recorder recorder) {
        return validForm(controller(service, recorder, confirmation));
    }

    private static ScheduleArrangementDialogController controller(
            ControlledScheduleService service, Recorder recorder) {
        return controller(service, recorder, (title, message) -> ButtonType.OK);
    }

    private static ScheduleArrangementDialogController controller(
            ControlledScheduleService service, Recorder recorder,
            BiFunction<String, String, ButtonType> confirmation) {
        ScheduleArrangementDialogController controller = new ScheduleArrangementDialogController(
                service, confirmation, recorder::accept, Runnable::run);
        controller.prepareForOffering(offering());
        return controller;
    }

    private static AdminOfferingView offering() {
        return new AdminOfferingView("1001", "OFF-1001", "101", 2026, 1, 120, 30, "OPEN",
                "T1001", "张老师", null, null, "SCHEDULED", 1);
    }

    private static SchedulePlanDTO plan(String planId) {
        return new SchedulePlanDTO(planId, "2026-2027 学年第一学期排课方案", 1, "DRAFT", true,
                List.of());
    }

    private static ScheduleArrangementView arrangement(String arrangementId) {
        return new ScheduleArrangementView(arrangementId, "7001", "1001",
                new ScheduleResourceDTO("8001", "T1001", "张老师", "teacher", 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                1, 16, "DRAFT", 1);
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

    /** 本教学班（1001）的冲突：带归属标识与完整位置（第 3 周 周三 第3-4节）。 */
    private static ScheduleConflictDTO ownBlockingConflict() {
        return new ScheduleConflictDTO("OFFERING_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                "1001", "1001", "1001", "CS101-2026-2-A", 3, 3, 3, 4,
                "同一教学班在该时间已有排课");
    }

    private static ScheduleConflictDTO ownOverridableConflict() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "8001", "2004", "1001",
                "CS101-2026-2-A", 3, 3, 3, 4, "任课教师在该时间已有其他课程");
    }

    /** 其他教学班（2004）的冲突。 */
    private static ScheduleConflictDTO otherBlockingConflict() {
        return new ScheduleConflictDTO("OFFERING_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                "2004", "2004", "2004", "CS202-2026-2-A", 3, 3, 1, 2,
                "同一教学班在该时间已有排课");
    }

    private static ScheduleConflictDTO otherOverridableConflict() {
        return new ScheduleConflictDTO("CLASSROOM_CAPACITY",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "3101", "2004", "2004",
                "CS202-2026-2-A", 3, 3, 3, 4, "教室容量 40 小于教学班容量 45");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Recorder {
        private final List<String> messages = new ArrayList<>();

        private void accept(String title, String message) {
            messages.add(message);
        }

        private String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }
    }

    private static final class ControlledScheduleService implements AdminCourseService {
        private final List<String> resourceCalls = new ArrayList<>();
        private final List<String> planCalls = new ArrayList<>();
        private final List<String> arrangementCalls = new ArrayList<>();
        private final List<String> deleteRequests = new ArrayList<>();
        private final List<String> publishRequests = new ArrayList<>();
        private final List<String> createDraftRequests = new ArrayList<>();
        private final List<SaveArrangementRequestDTO> previewRequests = new ArrayList<>();
        private final List<SaveArrangementRequestDTO> saveRequests = new ArrayList<>();
        private final Deque<CompletableFuture<SchedulePlanDTO>> planResults = new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleResourceDTO>>> resourceResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleArrangementView>>> arrangementResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<CheckArrangementResultDTO>> previewResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminOperationResultView<ScheduleArrangementView>>>
                saveResults = new ArrayDeque<>();

        private List<ScheduleResourceDTO> resources = List.of(
                new ScheduleResourceDTO("8001", "T1001", "张老师", "teacher", 0),
                new ScheduleResourceDTO("8002", "T2001", "李老师", "teacher", 0),
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                new ScheduleResourceDTO("8102", "3002", "A-102", "classroom", 60));
        private SchedulePlanDTO plan = plan("7001");
        private List<ScheduleArrangementView> authoritativeArrangements =
                List.of(arrangement("9001"));
        private List<ScheduleConflictDTO> nextPreviewConflicts = List.of();
        private List<ScheduleConflictDTO> nextPreviewPlanConflicts = List.of();
        private RuntimeException saveFailure;
        private RuntimeException publishFailure;
        private RuntimeException createDraftFailure;

        private void enqueuePlan(CompletableFuture<SchedulePlanDTO> result) {
            planResults.addLast(result);
        }

        private void enqueueArrangements(
                CompletableFuture<List<ScheduleArrangementView>> result) {
            arrangementResults.addLast(result);
        }

        private void enqueuePreview(CompletableFuture<CheckArrangementResultDTO> result) {
            previewResults.addLast(result);
        }

        private void enqueueSave(
                CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> result) {
            saveResults.addLast(result);
        }

        /** 只关心表单级冲突的既有用例：方案级快照一律为空。 */
        private void setNextPreview(List<ScheduleConflictDTO> conflicts) {
            nextPreviewConflicts = List.copyOf(conflicts);
            nextPreviewPlanConflicts = List.of();
        }

        /** 甲2：两处列表都能设，验证一次预检查刷新两处。 */
        private void setNextPreviewResult(List<ScheduleConflictDTO> conflicts,
                List<ScheduleConflictDTO> planConflicts) {
            nextPreviewConflicts = List.copyOf(conflicts);
            nextPreviewPlanConflicts = List.copyOf(planConflicts);
        }

        private void failNextSave(RuntimeException failure) {
            saveFailure = failure;
        }

        @Override
        public CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
                String type, String query) {
            resourceCalls.add(type + "|" + query);
            CompletableFuture<List<ScheduleResourceDTO>> held = resourceResults.poll();
            return held != null ? held : CompletableFuture.completedFuture(resources);
        }

        @Override
        public CompletableFuture<SchedulePlanDTO> loadSchedulePlan(int academicYear, int semester) {
            planCalls.add(academicYear + "|" + semester);
            CompletableFuture<SchedulePlanDTO> held = planResults.poll();
            return held != null ? held : CompletableFuture.completedFuture(plan);
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
                String planId, String offeringId) {
            arrangementCalls.add(planId + "|" + offeringId);
            CompletableFuture<List<ScheduleArrangementView>> held = arrangementResults.poll();
            return held != null
                    ? held
                    : CompletableFuture.completedFuture(authoritativeArrangements);
        }

        @Override
        public CompletableFuture<CheckArrangementResultDTO> checkArrangement(
                SaveArrangementRequestDTO request) {
            previewRequests.add(request);
            CompletableFuture<CheckArrangementResultDTO> held = previewResults.poll();
            return held != null
                    ? held
                    : CompletableFuture.completedFuture(new CheckArrangementResultDTO(
                            nextPreviewConflicts, nextPreviewPlanConflicts));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
                SaveArrangementRequestDTO request) {
            saveRequests.add(request);
            CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> held =
                    saveResults.poll();
            if (held != null) return held;
            if (saveFailure != null) {
                RuntimeException failure = saveFailure;
                saveFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    request.getOperationId(), "OK", "教学安排已保存", null));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
                String arrangementId, int expectedVersion, String operationId) {
            deleteRequests.add(arrangementId + "@" + expectedVersion + "@" + operationId);
            return CompletableFuture.completedFuture(
                    new AdminOperationResultView<>(operationId, "OK", "教学安排已删除", null));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
                String planId, int expectedRevision, String operationId,
                boolean force, String overrideReason) {
            publishRequests.add(planId + "@" + expectedRevision + "@" + operationId + "@"
                    + force + "@" + overrideReason);
            if (publishFailure != null) {
                RuntimeException failure = publishFailure;
                publishFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    operationId, "OK", "排课方案已发布", null));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<SchedulePlanView>> createSchedulePlan(
                int academicYear, int semester, boolean copyPublished, String operationId) {
            createDraftRequests.add(academicYear + "|" + semester + "|" + copyPublished
                    + "|" + operationId);
            if (createDraftFailure != null) {
                RuntimeException failure = createDraftFailure;
                createDraftFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    operationId, "OK", CREATE_DRAFT_MESSAGE, null));
        }

        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
                CourseEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
                CourseEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
                String courseId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
                String courseId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
                OfferingEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
                OfferingEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
                String offeringId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
