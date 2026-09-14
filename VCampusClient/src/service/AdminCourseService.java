package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import model.course.admin.StudentSearchResultView;

public interface AdminCourseService {
    CompletableFuture<List<AdminCourseView>> listCourses(String query, String status);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
            OfferingEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
            OfferingEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
            String offeringId, int expectedVersion, String operationId);

    CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
            String offeringId, int expectedVersion, String operationId);

    default CompletableFuture<List<StudentSearchResultView>> searchStudents(
            String query, int page, int size) {
        return searchStudentsPage(query, page, size).thenApply(AdminEnrollmentPageView::getItems);
    }

    default CompletableFuture<List<OfferingStudentView>> listOfferingStudents(
            String offeringId, String query, int page, int size) {
        return listOfferingStudentsPage(offeringId, query, page, size)
                .thenApply(AdminEnrollmentPageView::getItems);
    }

    // Defaults keep existing implementations compatible until enrollment transport is provided.
    default CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> searchStudentsPage(
            String query, int page, int size) {
        throw new UnsupportedOperationException("searchStudentsPage");
    }

    default CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> listOfferingStudentsPage(
            String offeringId, String query, int page, int size) {
        throw new UnsupportedOperationException("listOfferingStudentsPage");
    }

    default CompletableFuture<AdminEnrollmentPreviewDTO> previewAdminEnrollment(
            String offeringId, String studentUid) {
        throw new UnsupportedOperationException("previewAdminEnrollment");
    }

    default CompletableFuture<AdminOperationResultView<OfferingStudentView>> addStudentToOffering(
            AdminEnrollmentRequestDTO request) {
        throw new UnsupportedOperationException("addStudentToOffering");
    }

    default CompletableFuture<AdminOperationResultView<OfferingStudentView>> removeStudentFromOffering(
            AdminEnrollmentRequestDTO request) {
        throw new UnsupportedOperationException("removeStudentFromOffering");
    }

    // Declared as defaults so the existing final implementations keep compiling until the
    // scheduling transport task wires them to the server.
    default CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
            String type, String query) {
        throw new UnsupportedOperationException("listScheduleResources");
    }

    default CompletableFuture<SchedulePlanDTO> loadSchedulePlan(
            int academicYear, int semester) {
        throw new UnsupportedOperationException("loadSchedulePlan");
    }

    default CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
            String planId, String offeringId) {
        throw new UnsupportedOperationException("loadOfferingArrangements");
    }

    default CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
            SaveArrangementRequestDTO request) {
        throw new UnsupportedOperationException("checkArrangement");
    }

    default CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
            SaveArrangementRequestDTO request) {
        throw new UnsupportedOperationException("saveArrangement");
    }

    default CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
            String arrangementId, int expectedVersion, String operationId) {
        throw new UnsupportedOperationException("deleteArrangement");
    }

    default CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
            String planId, int expectedRevision, String operationId,
            boolean force, String overrideReason) {
        throw new UnsupportedOperationException("publishSchedulePlan");
    }

    default CompletableFuture<List<AdjustmentRequestSummaryDTO>> listAdjustmentRequests(
            AdjustmentRequestStatusDTO status, int page, int size) {
        return listAdjustmentRequestsPage(status, page, size)
                .thenApply(AdjustmentRequestPageDTO::getItems);
    }

    // Defaults keep existing implementations compatible until approval transport is provided.
    default CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsPage(
            AdjustmentRequestStatusDTO status, int page, int size) {
        throw new UnsupportedOperationException("listAdjustmentRequestsPage");
    }

    default CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(
            String requestId) {
        throw new UnsupportedOperationException("getAdjustmentRequest");
    }

    default CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>> reviewAdjustmentRequest(
            ApprovalDecisionRequestDTO request) {
        throw new UnsupportedOperationException("reviewAdjustmentRequest");
    }

    default CompletableFuture<List<GradeSubmissionSummaryDTO>> listGradeSubmissions(
            ApprovalStatusDTO status, int page, int size) {
        return listGradeSubmissionsPage(status, page, size)
                .thenApply(GradeSubmissionPageDTO::getItems);
    }

    // Defaults keep existing implementations compatible until grade approval transport is provided.
    default CompletableFuture<GradeSubmissionPageDTO> listGradeSubmissionsPage(
            ApprovalStatusDTO status, int page, int size) {
        throw new UnsupportedOperationException("listGradeSubmissionsPage");
    }

    default CompletableFuture<GradeSubmissionDetailDTO> getGradeSubmission(
            String submissionId) {
        throw new UnsupportedOperationException("getGradeSubmission");
    }

    default CompletableFuture<AdminOperationResultView<GradeSubmissionDetailDTO>> reviewGradeSubmission(
            ApprovalDecisionRequestDTO request) {
        throw new UnsupportedOperationException("reviewGradeSubmission");
    }
}
