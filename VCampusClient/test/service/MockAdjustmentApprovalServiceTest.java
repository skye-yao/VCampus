package service;

import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import model.course.admin.AdminOperationResultView;
import service.MockAdminCourseService.AdjustmentNotice;
import service.MockAdminCourseService.AdjustmentRecord;
import service.SocketAdminCourseService.AdminCourseServiceException;
import protocol.MessageCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Deterministic preview coverage for adjustment approval: seeded statuses, the paged read surface,
 * the approval/rejection state machine, idempotent replays and the single adjustment/notice state a
 * later UI test renders.
 */
public final class MockAdjustmentApprovalServiceTest {
    private static final String PENDING_CLEAN = "9001";
    private static final String PENDING_CONFLICTED = "9002";
    private static final String SEEDED_APPROVED = "9003";
    private static final String SEEDED_REJECTED = "9004";
    private static final String ADMIN = "admin-alpha";

    private MockAdjustmentApprovalServiceTest() {
    }

    public static void main(String[] args) {
        verifySeededWorkflow();
        verifyPaging();
        verifyRejection();
        verifyApprovalRequiresForce();
        verifyApprovalProducesOneState();
        verifyStaleVersionAndReplay();
        System.out.println("Mock adjustment approval service test passed.");
    }

    private static void verifySeededWorkflow() {
        MockAdminCourseService service = new MockAdminCourseService();
        require(service.getAdjustmentRequest(PENDING_CLEAN).join().getStatus()
                        == ApprovalStatusDTO.PENDING,
                "the seeded clean request must be PENDING");
        require(service.getAdjustmentRequest(SEEDED_APPROVED).join().getStatus()
                        == ApprovalStatusDTO.APPROVED,
                "the seeded approved request must be APPROVED");
        require(service.getAdjustmentRequest(SEEDED_REJECTED).join().getStatus()
                        == ApprovalStatusDTO.REJECTED,
                "the seeded rejected request must be REJECTED");
        require(service.adjustmentRecords().isEmpty() && service.adjustmentNotices().isEmpty(),
                "seeding must not fabricate an adjustment or a notice");

        AdjustmentRequestDetailDTO conflicted =
                service.getAdjustmentRequest(PENDING_CONFLICTED).join();
        require(conflicted.getConflicts().size() == 1
                        && conflicted.getConflicts().get(0).getSeverity()
                        == dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE,
                "the seeded conflicted request must expose its typed risk to the dialog");

        expect(MessageCode.NOT_FOUND, () -> service.getAdjustmentRequest("9999").join(),
                "an unknown request must be not found");
        expect(MessageCode.BAD_REQUEST, () -> service.getAdjustmentRequest("abc").join(),
                "a non-decimal request ID must be a bad request");
    }

    private static void verifyPaging() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdjustmentRequestPageDTO pending = service.listAdjustmentRequestsPage(null, 1, 20).join();
        require(pending.getTotalCount() == 2 && pending.getItems().size() == 2
                        && PENDING_CLEAN.equals(pending.getItems().get(0).getRequestId())
                        && PENDING_CONFLICTED.equals(pending.getItems().get(1).getRequestId()),
                "a null status must default to PENDING ordered by submitted_at then request_id");
        require(pending.getPageNumber() == 1 && pending.getPageSize() == 20,
                "the page must report the requested metadata");

        AdjustmentRequestSummaryDTO summary = pending.getItems().get(0);
        require("数据结构".equals(summary.getCourseName()) && "OFF-1001".equals(summary.getOfferingCode())
                        && "T1001".equals(summary.getApplicantUid())
                        && "张老师".equals(summary.getApplicantName())
                        && summary.getTargetWeekCount() == 2
                        && summary.getStatus() == ApprovalStatusDTO.PENDING,
                "the summary must join the offering, course and applicant display data");

        require(service.listAdjustmentRequestsPage(ApprovalStatusDTO.APPROVED, 1, 20).join()
                        .getTotalCount() == 1,
                "an explicit status filter must isolate that status");
        require(service.listAdjustmentRequestsPage(ApprovalStatusDTO.PENDING, 2, 1).join()
                        .getItems().get(0).getRequestId().equals(PENDING_CONFLICTED),
                "the second page must slice the ordered pending set");
        require(service.listAdjustmentRequests(null, 1, 20).join().size() == 2,
                "the inherited list accessor must reuse the paged read");

        expect(MessageCode.BAD_REQUEST, () -> service.listAdjustmentRequestsPage(null, 0, 20).join(),
                "page 0 must be a bad request");
        expect(MessageCode.BAD_REQUEST, () -> service.listAdjustmentRequestsPage(null, 1, 101).join(),
                "size above 100 must be a bad request");
    }

    private static void verifyRejection() {
        MockAdminCourseService service = new MockAdminCourseService();
        expect(MessageCode.BAD_REQUEST, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000001", PENDING_CLEAN, 1, false, false, null,
                        "   ")).join(),
                "a rejection without a comment must be a bad request");
        expect(MessageCode.BAD_REQUEST, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000002", PENDING_CLEAN, 1, false, true, "理由",
                        null)).join(),
                "a forced rejection must be a bad request");

        AdminOperationResultView<AdjustmentRequestDetailDTO> rejected =
                service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000003", PENDING_CLEAN, 1, false, false, null,
                        "  材料不足  ")).join();
        require(ApprovalStatusDTO.REJECTED == rejected.getEntity().getStatus()
                        && rejected.getEntity().getVersion() == 2
                        && "材料不足".equals(rejected.getEntity().getReviewComment())
                        && ADMIN.equals(rejected.getEntity().getReviewedBy()),
                "a rejection must record the trimmed comment, reviewer and next version");
        require(service.adjustmentRecords().isEmpty() && service.adjustmentNotices().isEmpty(),
                "a rejection must produce neither an adjustment nor a notice");
        require(service.getAdjustmentRequest(PENDING_CLEAN).join().getStatus()
                        == ApprovalStatusDTO.REJECTED,
                "the decision must be visible through the detail read");
    }

    private static void verifyApprovalRequiresForce() {
        MockAdminCourseService service = new MockAdminCourseService();
        expect(MessageCode.CONFLICT, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000004", PENDING_CONFLICTED, 1, true, false,
                        null, null)).join(),
                "an overridable conflict must block an unforced approval");
        expect(MessageCode.BAD_REQUEST, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000005", PENDING_CONFLICTED, 1, true, true,
                        "   ", null)).join(),
                "force without a reason must be a bad request");
        require(service.getAdjustmentRequest(PENDING_CONFLICTED).join().getConflicts().size() == 1,
                "a refused approval must leave the request untouched");
    }

    private static void verifyApprovalProducesOneState() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminOperationResultView<AdjustmentRequestDetailDTO> approved =
                service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000006", PENDING_CONFLICTED, 1, true, true,
                        "  已协调教师  ", "同意")).join();
        require(ApprovalStatusDTO.APPROVED == approved.getEntity().getStatus()
                        && approved.getEntity().getVersion() == 2
                        && ADMIN.equals(approved.getEntity().getReviewedBy())
                        && "同意".equals(approved.getEntity().getReviewComment()),
                "an approval must advance the version and record the reviewer and comment");

        List<AdjustmentRecord> records = service.adjustmentRecords();
        require(records.size() == 1 && "9101".equals(records.get(0).adjustmentId())
                        && PENDING_CONFLICTED.equals(records.get(0).requestId())
                        && "7003".equals(records.get(0).originalOccurrenceId())
                        && records.get(0).week() == 3
                        && "星期1 第3-4节".equals(records.get(0).scheduleText())
                        && "ACTIVE".equals(records.get(0).status()),
                "an approval must write one ACTIVE adjustment per target week");
        List<AdjustmentNotice> notices = service.adjustmentNotices();
        require(notices.size() == 1 && "8101".equals(notices.get(0).noticeId())
                        && PENDING_CONFLICTED.equals(notices.get(0).requestId())
                        && "2002".equals(notices.get(0).offeringId())
                        && "RESCHEDULED".equals(notices.get(0).noticeType())
                        && "PUBLISHED".equals(notices.get(0).status())
                        && ADMIN.equals(notices.get(0).createdBy())
                        && notices.get(0).content().contains("周次：3")
                        && notices.get(0).content().contains("新安排：星期1 第3-4节"),
                "an approval must publish exactly one linked rescheduled notice");
        AdjustmentRequestPageDTO pending = service.listAdjustmentRequestsPage(
                ApprovalStatusDTO.PENDING, 1, 20).join();
        require(pending.getTotalCount() == 1
                        && PENDING_CLEAN.equals(pending.getItems().get(0).getRequestId()),
                "the approved request must leave the pending queue and keep the other one in it");
    }

    private static void verifyStaleVersionAndReplay() {
        MockAdminCourseService service = new MockAdminCourseService();
        String operationId = "50000000-0000-0000-0000-000000000007";
        expect(MessageCode.CONFLICT, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000008", PENDING_CLEAN, 9, true, false, null,
                        null)).join(),
                "a stale expectedVersion must conflict");

        AdminOperationResultView<AdjustmentRequestDetailDTO> first =
                service.reviewAdjustmentRequest(decision(operationId, PENDING_CLEAN, 1, true, false,
                        null, null)).join();
        int records = service.adjustmentRecords().size();
        int notices = service.adjustmentNotices().size();
        AdminOperationResultView<AdjustmentRequestDetailDTO> replay =
                service.reviewAdjustmentRequest(decision(operationId, PENDING_CLEAN, 1, true, false,
                        null, null)).join();
        require(first.getEntity().getStatus() == replay.getEntity().getStatus()
                        && service.adjustmentRecords().size() == records
                        && service.adjustmentNotices().size() == notices,
                "a same-operation replay must return the stored decision without new state");
        expect(MessageCode.CONFLICT, () -> service.reviewAdjustmentRequest(decision(operationId,
                        PENDING_CONFLICTED, 1, true, true, "理由", null)).join(),
                "one operationId reused for another request must conflict");

        expect(MessageCode.CONFLICT, () -> service.reviewAdjustmentRequest(decision(
                        "50000000-0000-0000-0000-000000000009", PENDING_CLEAN, 2, false, false, null,
                        "重复审批")).join(),
                "an already decided request must refuse a second decision");
    }

    private static ApprovalDecisionRequestDTO decision(String operationId, String requestId,
                                                       int expectedVersion, boolean approved,
                                                       boolean force, String overrideReason,
                                                       String reviewComment) {
        return new ApprovalDecisionRequestDTO(operationId, requestId, expectedVersion, approved,
                force, overrideReason, reviewComment);
    }

    private static void expect(MessageCode code, Runnable action, String message) {
        try {
            action.run();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AdminCourseServiceException error
                    && error.getCode() == code) {
                return;
            }
            throw new AssertionError(message + " (unexpected " + failure.getCause() + ")", failure);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
