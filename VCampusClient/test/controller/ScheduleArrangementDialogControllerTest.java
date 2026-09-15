package controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import javafx.scene.control.ButtonType;
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

        CompletableFuture<List<ScheduleConflictDTO>> pending = new CompletableFuture<>();
        service.enqueuePreview(pending);
        controller.previewArrangement();
        controller.setWeekRange(2, 6);
        require(!controller.isPreviewPending() && !controller.isPreviewCurrent(),
                "editing must invalidate both completed and pending preview state");
        pending.complete(List.of());
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

        CompletableFuture<List<ScheduleConflictDTO>> stale = new CompletableFuture<>();
        CompletableFuture<List<ScheduleConflictDTO>> newest = new CompletableFuture<>();
        service.enqueuePreview(stale);
        service.enqueuePreview(newest);
        controller.previewArrangement();
        controller.previewArrangement();
        require(controller.isPreviewPending(), "two pending previews must report pending");

        newest.complete(List.of());
        require(!controller.isPreviewPending(), "the newest preview must clear the pending state");
        require(controller.isPreviewCurrent(), "the newest preview must authorize saving");
        require(controller.conflicts().isEmpty(), "the newest preview reported no conflicts");

        stale.complete(List.of(blockingConflict()));
        require(controller.conflicts().isEmpty(),
                "a stale preview must not overwrite the newest conflict state, saw "
                        + controller.conflicts());
        require(!controller.hasBlockingConflicts(),
                "a stale preview must not introduce blocking conflicts");
        require(controller.canSave(), "a stale preview must not revoke the newest authorization");

        CompletableFuture<List<ScheduleConflictDTO>> orphaned = new CompletableFuture<>();
        service.enqueuePreview(orphaned);
        controller.previewArrangement();
        controller.setWeekRange(5, 10);
        orphaned.complete(List.of());
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
        private final List<SaveArrangementRequestDTO> previewRequests = new ArrayList<>();
        private final List<SaveArrangementRequestDTO> saveRequests = new ArrayList<>();
        private final Deque<CompletableFuture<SchedulePlanDTO>> planResults = new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleResourceDTO>>> resourceResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleArrangementView>>> arrangementResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleConflictDTO>>> previewResults =
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
        private RuntimeException saveFailure;
        private RuntimeException publishFailure;

        private void enqueuePlan(CompletableFuture<SchedulePlanDTO> result) {
            planResults.addLast(result);
        }

        private void enqueueArrangements(
                CompletableFuture<List<ScheduleArrangementView>> result) {
            arrangementResults.addLast(result);
        }

        private void enqueuePreview(CompletableFuture<List<ScheduleConflictDTO>> result) {
            previewResults.addLast(result);
        }

        private void enqueueSave(
                CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> result) {
            saveResults.addLast(result);
        }

        private void setNextPreview(List<ScheduleConflictDTO> conflicts) {
            nextPreviewConflicts = List.copyOf(conflicts);
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
        public CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
                SaveArrangementRequestDTO request) {
            previewRequests.add(request);
            CompletableFuture<List<ScheduleConflictDTO>> held = previewResults.poll();
            return held != null
                    ? held
                    : CompletableFuture.completedFuture(nextPreviewConflicts);
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
