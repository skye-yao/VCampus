package service;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

public final class MockAdminCourseServiceTest {

    public static void main(String[] args) {
        listCoursesFiltersSeedsByStatusAndQuery();
        offeringCountMatchesServerDefinitionAndCancellation();
        createThenUpdateBumpsVersionAndStaleUpdateConflicts();
        duplicateCourseCodeConflictsAndCourseCodeIsImmutable();
        archiveNeedsEveryOfferingCancelledThenRestores();
        offeringCreateAndUpdateBumpVersions();
        emptyDraftIsDeletableWhileOtherOfferingIsNot();
        operationIdReplayDoesNotApplyTwice();
        adjustmentListProvidesEveryFourStateValue();
        reviewDecisionChangesTheAdjustmentQueries();
        System.out.println("MockAdminCourseServiceTest: PASS");
    }

    /**
     * 调课列表的 mock 必须覆盖四态，尤其是教师撤销的 WITHDRAWN（审批页与 T5 的已撤销显示路径）。
     * {@code listAdjustmentRequestsByStatus} 是 T4 起的主查询名，旧名必须返回同一份快照。
     */
    private static void adjustmentListProvidesEveryFourStateValue() {
        MockAdminCourseService service = new MockAdminCourseService();

        require(AdjustmentRequestStatusDTO.values().length == 4,
                "the adjustment status domain must stay four-state");
        for (AdjustmentRequestStatusDTO status : AdjustmentRequestStatusDTO.values()) {
            AdjustmentRequestPageDTO page =
                    service.listAdjustmentRequestsByStatus(status, 1, 20).join();
            require(!page.getItems().isEmpty(),
                    "the mock must seed at least one " + status + " adjustment request");
            require(page.getItems().stream().allMatch(item -> item.getStatus() == status),
                    "the " + status + " filter must return only " + status + " rows");
        }

        AdjustmentRequestPageDTO withdrawn =
                service.listAdjustmentRequestsByStatus(AdjustmentRequestStatusDTO.WITHDRAWN, 1, 20)
                        .join();
        require(withdrawn.getItems().size() == 1
                        && withdrawn.getItems().get(0).getStatus()
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "a WITHDRAWN row must be visible to the approval list");
        String withdrawnId = withdrawn.getItems().get(0).getRequestId();
        require(service.getAdjustmentRequest(withdrawnId).join().getStatus()
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the WITHDRAWN detail must stay readable");

        require(service.listAdjustmentRequestsPage(AdjustmentRequestStatusDTO.WITHDRAWN, 1, 20)
                        .join().getItems().size() == 1,
                "the legacy page alias must return the same snapshot");
        List<AdjustmentRequestSummaryDTO> legacyList =
                service.listAdjustmentRequests(AdjustmentRequestStatusDTO.WITHDRAWN, 1, 20).join();
        require(legacyList.size() == 1 && withdrawnId.equals(legacyList.get(0).getRequestId()),
                "the legacy list adapter must return the same rows");
    }

    /**
     * 审批必须改变查询快照而不仅是界面：版本递增、PENDING 列表缩一、APPROVED 列表增一。
     */
    private static void reviewDecisionChangesTheAdjustmentQueries() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdjustmentRequestPageDTO pendingBefore =
                service.listAdjustmentRequestsByStatus(AdjustmentRequestStatusDTO.PENDING, 1, 20)
                        .join();
        AdjustmentRequestSummaryDTO target = pendingBefore.getItems().stream()
                .filter(item -> "9001".equals(item.getRequestId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture 9001 must be PENDING"));
        AdjustmentRequestDetailDTO before = service.getAdjustmentRequest("9001").join();

        AdminOperationResultView<AdjustmentRequestDetailDTO> decided =
                service.reviewAdjustmentRequest(new ApprovalDecisionRequestDTO(
                        "60000000-0000-0000-0000-000000000001", "9001", before.getVersion(), true,
                        false, null, "同意")).join();
        require(decided.getEntity() != null
                        && decided.getEntity().getStatus()
                        == AdjustmentRequestStatusDTO.APPROVED
                        && decided.getEntity().getVersion() == before.getVersion() + 1,
                "approving must publish a new immutable snapshot with the incremented version");
        require(service.getAdjustmentRequest("9001").join().getVersion()
                        == before.getVersion() + 1,
                "the detail query snapshot must change after the decision");
        require(service.listAdjustmentRequestsByStatus(AdjustmentRequestStatusDTO.APPROVED, 1, 20)
                        .join().getItems().stream()
                        .anyMatch(item -> "9001".equals(item.getRequestId())),
                "the APPROVED list must show the decided request");
        require(service.listAdjustmentRequestsByStatus(AdjustmentRequestStatusDTO.PENDING, 1, 20)
                        .join().getItems().stream()
                        .noneMatch(item -> "9001".equals(item.getRequestId())),
                "the PENDING list must no longer show the decided request");
        require(target.getStatus() == AdjustmentRequestStatusDTO.PENDING,
                "the fixture must have started PENDING");
    }

    private static void offeringCountMatchesServerDefinitionAndCancellation() {
        MockAdminCourseService service = new MockAdminCourseService();

        require(courseById(service, "101").getOfferingCount() == 1,
                "course 101 must count its open offering");
        require(courseById(service, "201").getOfferingCount() == 1,
                "course 201 must exclude its cancelled offering");
        require(courseById(service, "301").getOfferingCount() == 0,
                "course 301 must exclude its cancelled offering");

        service.cancelOffering("1001", 1, "op-cancel-count").join();
        require(courseById(service, "101").getOfferingCount() == 0,
                "cancelling an offering must remove it from the active count");
    }

    private static void listCoursesFiltersSeedsByStatusAndQuery() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<AdminCourseView> all = service.listCourses(null, null).join();
        require(all.size() == 3, "the mock must seed three courses");

        List<AdminCourseView> archived = service.listCourses(null, "ARCHIVED").join();
        require(archived.size() == 1 && "ARCHIVED".equals(archived.get(0).getStatus()),
                "status filter must keep only archived courses");

        List<AdminCourseView> active = service.listCourses(null, "ACTIVE").join();
        require(active.size() == 2, "status filter must keep the two active courses");

        List<AdminCourseView> byCode = service.listCourses("CS301", null).join();
        require(byCode.size() == 1 && "CS301".equals(byCode.get(0).getCourseCode()),
                "query must match the course code");

        List<AdminCourseView> byName = service.listCourses("操作系统", null).join();
        require(byName.size() == 1 && "操作系统".equals(byName.get(0).getCourseName()),
                "query must match the course name");
    }

    private static void createThenUpdateBumpsVersionAndStaleUpdateConflicts() {
        MockAdminCourseService service = new MockAdminCourseService();

        AdminOperationResultView<AdminCourseView> created = service.createCourse(
                courseRequest("op-create", null, 0, "NEW101", "新课程", 3.0, 48)).join();
        AdminCourseView course = created.getEntity();
        require(course != null && course.getVersion() == 1,
                "a created course must start at version 1");
        require("ACTIVE".equals(course.getStatus()), "a created course must be ACTIVE");
        require("NEW101".equals(course.getCourseCode()), "the course code must round-trip");
        require("op-create".equals(created.getOperationId())
                        && "OK".equals(created.getOutcomeCode()),
                "a create result must carry the operation id and outcome code");

        AdminOperationResultView<AdminCourseView> updated = service.updateCourse(
                courseRequest("op-update", course.getCourseId(), 1, "NEW101", "新课程改", 3.5, 48))
                .join();
        require(updated.getEntity().getVersion() == 2,
                "an update must bump the course version to 2");
        require("新课程改".equals(updated.getEntity().getCourseName()),
                "an update must apply the new name");

        requireConflict(service.updateCourse(
                courseRequest("op-stale", course.getCourseId(), 1, "NEW101", "过期写", 3.5, 48)));
    }

    private static void duplicateCourseCodeConflictsAndCourseCodeIsImmutable() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireConflict(service.createCourse(
                courseRequest("op-dup", null, 0, "CS203", "重复课程", 3.0, 48)));

        AdminCourseServiceException immutable = failureOf(service.updateCourse(
                courseRequest("op-code", "101", 1, "CS999", "数据结构", 4.0, 64)));
        require(immutable.getCode() == MessageCode.BAD_REQUEST,
                "changing the course code on update must be BAD_REQUEST");
    }

    private static void archiveNeedsEveryOfferingCancelledThenRestores() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireConflict(service.archiveCourse("101", 1, "op-archive-blocked"));

        AdminOperationResultView<AdminOfferingView> cancelled =
                service.cancelOffering("1001", 1, "op-cancel").join();
        require("CANCELLED".equals(cancelled.getEntity().getStatus()),
                "cancelling an offering must set CANCELLED");
        require(cancelled.getEntity().getVersion() == 2,
                "cancelling an offering must bump its version");

        AdminOperationResultView<AdminCourseView> archived =
                service.archiveCourse("101", 1, "op-archive").join();
        require("ARCHIVED".equals(archived.getEntity().getStatus()),
                "archiving must set ARCHIVED once every offering is cancelled");
        require(archived.getEntity().getVersion() == 2,
                "archiving must bump the course version");

        AdminOperationResultView<AdminCourseView> restored =
                service.restoreCourse("101", 2, "op-restore").join();
        require("ACTIVE".equals(restored.getEntity().getStatus()),
                "restoring must set ACTIVE");
        require(restored.getEntity().getVersion() == 3,
                "restoring must bump the course version");
    }

    private static void offeringCreateAndUpdateBumpVersions() {
        MockAdminCourseService service = new MockAdminCourseService();

        AdminOperationResultView<AdminOfferingView> created = service.createOffering(
                offeringRequest("op-offer-create", null, 0, "101", 1, 100)).join();
        AdminOfferingView offering = created.getEntity();
        require(offering.getVersion() == 1, "a created offering must start at version 1");
        require("NOT_OPEN".equals(offering.getStatus()), "status 1 must map to NOT_OPEN");
        require("UNSCHEDULED".equals(offering.getScheduleStatus()),
                "a new offering must be UNSCHEDULED");

        AdminOperationResultView<AdminOfferingView> updated = service.updateOffering(
                offeringRequest("op-offer-update", offering.getOfferingId(), 1, "101", 2, 120))
                .join();
        require(updated.getEntity().getVersion() == 2,
                "an offering update must bump the version");
        require("OPEN".equals(updated.getEntity().getStatus()), "status 2 must map to OPEN");
        require(updated.getEntity().getCapacity() == 120,
                "an offering update must apply the new capacity");
    }

    private static void emptyDraftIsDeletableWhileOtherOfferingIsNot() {
        MockAdminCourseService service = new MockAdminCourseService();

        require(containsOffering(service.listOfferings("201").join(), "2002"),
                "the seeded empty draft must be listed");

        AdminOperationResultView<Void> deleted =
                service.deleteDraftOffering("2002", 1, "op-delete").join();
        require(deleted.getEntity() == null, "deleting a draft must map a null entity");
        require("OK".equals(deleted.getOutcomeCode()),
                "deleting a draft must report the OK outcome");
        require(!containsOffering(service.listOfferings("201").join(), "2002"),
                "the deleted draft must vanish from listOfferings");

        requireConflict(service.deleteDraftOffering("2001", 1, "op-delete-cancelled"));
    }

    private static void operationIdReplayDoesNotApplyTwice() {
        MockAdminCourseService service = new MockAdminCourseService();
        int before = service.listCourses(null, null).join().size();
        CourseEditorRequestDTO request =
                courseRequest("op-replay", null, 0, "REP101", "回放课程", 2.0, 32);

        AdminOperationResultView<AdminCourseView> first = service.createCourse(request).join();
        AdminOperationResultView<AdminCourseView> second = service.createCourse(request).join();
        require(first.getEntity().getCourseId().equals(second.getEntity().getCourseId()),
                "replaying an operationId must return the same entity");
        require(service.listCourses(null, null).join().size() == before + 1,
                "replaying an operationId must not create a second course");
    }

    private static CourseEditorRequestDTO courseRequest(String operationId, String courseId,
            int expectedVersion, String code, String name, double credit, int creditHours) {
        return new CourseEditorRequestDTO(operationId, courseId, expectedVersion, code, name,
                "必修", credit, creditHours, "简介", "无", true, true);
    }

    private static OfferingEditorRequestDTO offeringRequest(String operationId, String offeringId,
            int expectedVersion, String courseId, int status, int capacity) {
        return new OfferingEditorRequestDTO(operationId, offeringId, expectedVersion, courseId,
                "OFF-" + status, 2026, 1, capacity, "T1", null, status);
    }

    private static boolean containsOffering(List<AdminOfferingView> offerings, String offeringId) {
        for (AdminOfferingView offering : offerings) {
            if (offeringId.equals(offering.getOfferingId())) return true;
        }
        return false;
    }

    private static AdminCourseView courseById(MockAdminCourseService service, String courseId) {
        for (AdminCourseView course : service.listCourses(null, null).join()) {
            if (courseId.equals(course.getCourseId())) return course;
        }
        throw new AssertionError("missing course " + courseId);
    }

    private static void requireConflict(CompletableFuture<?> future) {
        AdminCourseServiceException failure = failureOf(future);
        require(failure.getCode() == MessageCode.CONFLICT,
                "expected CONFLICT but was " + failure.getCode() + ": " + failure.getMessage());
    }

    private static AdminCourseServiceException failureOf(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                return error;
            }
            throw new AssertionError("unexpected failure cause: " + failure.getCause(),
                    failure.getCause());
        }
        throw new AssertionError("expected the future to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
