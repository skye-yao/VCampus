package service;

import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

import java.util.concurrent.CompletionException;

/**
 * Deterministic preview coverage for grade approval: seeded statuses, the paged read surface, the
 * approve/reject transition, the mock-owned published-grade projection and idempotent replays.
 */
public final class MockGradeApprovalServiceTest {
    private static final String PENDING = "9001";
    private static final String SEEDED_APPROVED = "9002";
    private static final String SEEDED_REJECTED = "9003";
    private static final String V2_CORRECTION = "9004";
    private static final String ADMIN = "admin-alpha";

    private MockGradeApprovalServiceTest() {
    }

    public static void main(String[] args) {
        verifySeededWorkflow();
        verifyPaging();
        verifyRejection();
        verifyApprovalPublishesProjection();
        verifyV2CorrectionLeavesV1Snapshot();
        verifyStaleVersionAndReplay();
        System.out.println("Mock grade approval service test passed.");
    }

    private static void verifySeededWorkflow() {
        MockAdminCourseService service = new MockAdminCourseService();
        require(service.getGradeSubmission(PENDING).join().getSummary().getStatus()
                        == ApprovalStatusDTO.PENDING,
                "the seeded pending submission must be PENDING");
        require(service.getGradeSubmission(SEEDED_APPROVED).join().getSummary().getStatus()
                        == ApprovalStatusDTO.APPROVED,
                "the seeded approved submission must be APPROVED");
        require(service.getGradeSubmission(SEEDED_REJECTED).join().getSummary().getStatus()
                        == ApprovalStatusDTO.REJECTED,
                "the seeded rejected submission must be REJECTED");
        require(service.publishedGrades().isEmpty(),
                "seeding must not publish a grade projection");

        GradeSubmissionDetailDTO detail = service.getGradeSubmission(PENDING).join();
        require(detail.getItems().size() == 2
                        && "8001".equals(detail.getItems().get(0).getEnrollmentId())
                        && detail.getItems().get(0).getScore() == 85.0
                        && detail.getItems().get(0).getExperimentScore() == null,
                "the detail must keep exact enrollment IDs and a nullable component score");
        require(detail.getDistribution().size() == 5
                        && "90-100".equals(detail.getDistribution().get(0).getLabel())
                        && "0-59".equals(detail.getDistribution().get(4).getLabel())
                        && detail.getDistribution().get(1).getCount() == 1
                        && detail.getDistribution().get(0).getCount() == 1,
                "the distribution must carry the five ordered bands with their counts");
        require("材料不足".equals(service.getGradeSubmission(SEEDED_REJECTED).join()
                        .getReviewComment()),
                "the seeded rejected submission must keep its recorded comment");

        expect(MessageCode.NOT_FOUND, () -> service.getGradeSubmission("9999").join(),
                "an unknown submission must be not found");
        expect(MessageCode.BAD_REQUEST, () -> service.getGradeSubmission("abc").join(),
                "a non-decimal submission ID must be a bad request");
    }

    private static void verifyPaging() {
        MockAdminCourseService service = new MockAdminCourseService();
        GradeSubmissionPageDTO pending = service.listGradeSubmissionsPage(null, 1, 20).join();
        require(pending.getTotalCount() == 2 && pending.getItems().size() == 2
                        && V2_CORRECTION.equals(pending.getItems().get(0).getSubmissionId())
                        && PENDING.equals(pending.getItems().get(1).getSubmissionId()),
                "a null status must default to PENDING ordered by submitted_at then submission_id");
        require(pending.getPageNumber() == 1 && pending.getPageSize() == 20,
                "the page must report the requested metadata");

        GradeSubmissionSummaryDTO summary = pending.getItems().get(1);
        require("数据结构".equals(summary.getCourseName())
                        && "OFF-1001".equals(summary.getOfferingCode())
                        && "T1001".equals(summary.getTeacherUid())
                        && "张老师".equals(summary.getTeacherName())
                        && summary.getStudentCount() == 2
                        && summary.getStatus() == ApprovalStatusDTO.PENDING,
                "the summary must join the offering, course and submitting teacher data");

        require(service.listGradeSubmissionsPage(ApprovalStatusDTO.APPROVED, 1, 20).join()
                        .getTotalCount() == 1,
                "an explicit status filter must isolate that status");
        require(service.listGradeSubmissionsPage(ApprovalStatusDTO.PENDING, 2, 1).join()
                        .getItems().get(0).getSubmissionId().equals(PENDING),
                "the second page must slice the ordered pending set");
        require(service.listGradeSubmissions(null, 1, 20).join().size() == 2,
                "the inherited list accessor must reuse the paged read");

        expect(MessageCode.BAD_REQUEST, () -> service.listGradeSubmissionsPage(null, 0, 20).join(),
                "page 0 must be a bad request");
        expect(MessageCode.BAD_REQUEST, () -> service.listGradeSubmissionsPage(null, 1, 101).join(),
                "size above 100 must be a bad request");
    }

    private static void verifyRejection() {
        MockAdminCourseService service = new MockAdminCourseService();
        expect(MessageCode.BAD_REQUEST, () -> service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000001", PENDING, 1, false, "   ")).join(),
                "a rejection without a comment must be a bad request");
        expect(MessageCode.BAD_REQUEST, () -> service.reviewGradeSubmission(forceDecision(
                        "60000000-0000-0000-0000-000000000002", PENDING, 1, true, null)).join(),
                "force = true must be a bad request for grade approval");

        AdminOperationResultView<GradeSubmissionDetailDTO> rejected =
                service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000003", PENDING, 1, false,
                        "  材料不足  ")).join();
        require(ApprovalStatusDTO.REJECTED == rejected.getEntity().getSummary().getStatus()
                        && rejected.getEntity().getSummary().getVersion() == 1
                        && "材料不足".equals(rejected.getEntity().getReviewComment())
                        && ADMIN.equals(rejected.getEntity().getReviewedBy()),
                "a rejection must record the trimmed comment and reviewer without bumping the version");
        require(service.publishedGrades().isEmpty(),
                "a rejection must not publish a grade projection");
        require(service.getGradeSubmission(PENDING).join().getSummary().getStatus()
                        == ApprovalStatusDTO.REJECTED,
                "the decision must be visible through the detail read");
    }

    private static void verifyApprovalPublishesProjection() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminOperationResultView<GradeSubmissionDetailDTO> approved =
                service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000004", PENDING, 1, true, "  同意  ")).join();
        require(ApprovalStatusDTO.APPROVED == approved.getEntity().getSummary().getStatus()
                        && approved.getEntity().getSummary().getVersion() == 1
                        && ADMIN.equals(approved.getEntity().getReviewedBy())
                        && "同意".equals(approved.getEntity().getReviewComment()),
                "an approval must record the reviewer and comment without bumping the version");
        require(service.publishedGrades().get("8001") == 85.0
                        && service.publishedGrades().get("8002") == 90.0,
                "an approval must publish the batch's scores into the per-enrollment projection");
    }

    private static void verifyV2CorrectionLeavesV1Snapshot() {
        MockAdminCourseService service = new MockAdminCourseService();
        GradeSubmissionDetailDTO v1 = service.getGradeSubmission(SEEDED_REJECTED).join();
        AdminOperationResultView<GradeSubmissionDetailDTO> corrected =
                service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000005", V2_CORRECTION, 2, true, "修正")).join();
        require(ApprovalStatusDTO.APPROVED == corrected.getEntity().getSummary().getStatus()
                        && corrected.getEntity().getSummary().getVersion() == 2,
                "the v2 correction must be approvable at its own version");

        require(service.publishedGrades().get("8001") == 92.0
                        && service.publishedGrades().get("8002") == 94.0,
                "the v2 correction must supersede the projection with its own scores");

        GradeSubmissionDetailDTO after = service.getGradeSubmission(SEEDED_REJECTED).join();
        require(after.getSummary().getStatus() == ApprovalStatusDTO.REJECTED
                        && after.getSummary().getVersion() == v1.getSummary().getVersion()
                        && "材料不足".equals(after.getReviewComment())
                        && after.getItems().size() == v1.getItems().size()
                        && after.getItems().get(0).getScore() == v1.getItems().get(0).getScore(),
                "deciding the v2 correction must leave the v1 snapshot byte-identical");
    }

    private static void verifyStaleVersionAndReplay() {
        MockAdminCourseService service = new MockAdminCourseService();
        String operationId = "60000000-0000-0000-0000-000000000006";
        expect(MessageCode.CONFLICT, () -> service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000007", PENDING, 9, true, null)).join(),
                "a stale expectedVersion must conflict");

        AdminOperationResultView<GradeSubmissionDetailDTO> first =
                service.reviewGradeSubmission(decision(operationId, PENDING, 1, true, null)).join();
        int projected = service.publishedGrades().size();
        AdminOperationResultView<GradeSubmissionDetailDTO> replay =
                service.reviewGradeSubmission(decision(operationId, PENDING, 1, true, null)).join();
        require(first.getEntity().getSummary().getStatus()
                        == replay.getEntity().getSummary().getStatus()
                        && service.publishedGrades().size() == projected,
                "a same-operation replay must return the stored decision without new projection");
        expect(MessageCode.CONFLICT, () -> service.reviewGradeSubmission(decision(operationId,
                        V2_CORRECTION, 2, true, null)).join(),
                "one operationId reused for another submission must conflict");

        expect(MessageCode.CONFLICT, () -> service.reviewGradeSubmission(decision(
                        "60000000-0000-0000-0000-000000000008", PENDING, 1, false, "重复审批")).join(),
                "an already decided submission must refuse a second decision");
    }

    private static ApprovalDecisionRequestDTO decision(String operationId, String submissionId,
                                                       int expectedVersion, boolean approved,
                                                       String reviewComment) {
        return new ApprovalDecisionRequestDTO(operationId, submissionId, expectedVersion, approved,
                false, null, reviewComment);
    }

    private static ApprovalDecisionRequestDTO forceDecision(String operationId, String submissionId,
                                                            int expectedVersion, boolean approved,
                                                            String reviewComment) {
        return new ApprovalDecisionRequestDTO(operationId, submissionId, expectedVersion, approved,
                true, null, reviewComment);
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
