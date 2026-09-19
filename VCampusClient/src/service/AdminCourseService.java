package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.CheckArrangementResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import model.course.CourseTermView;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import model.course.admin.StudentSearchResultView;

/** 管理员课程目录、教学班、排课及审批操作的异步服务契约。 */
public interface AdminCourseService {
    CompletableFuture<List<AdminCourseView>> listCourses(String query, String status);

    /**
     * 带学期限定的课程列表：{@code academicYear}/{@code semester} 同时为 null 时等价于
     * {@link #listCourses(String, String)}。
     *
     * <p>默认实现丢弃学期并委托给旧方法，这样十个与本批无关的测试假实现不必跟着改。
     * 真正实现学期语义的是 {@link SocketAdminCourseService} 与 {@link MockAdminCourseService}。</p>
     */
    default CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
            Integer academicYear, Integer semester) {
        return listCourses(query, status);
    }

    CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId);

    /** 带学期限定的教学班列表：两个学期参数同时为 null 时等价于 {@link #listOfferings(String)}。 */
    default CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId,
            Integer academicYear, Integer semester) {
        return listOfferings(courseId);
    }

    /**
     * 学期下拉的取值来源：全局所有教学班出现过的学期，最近优先。默认实现返回一个**已完成但异常**
     * 的 future，而不是直接抛——直接抛会绕开调用方的 {@code whenComplete} 降级路径，把整页打红
     * （{@code ui.AdminCourseUiSmokeTest} 在 Task 7 就是这样被弄红的）；异步失败则会被
     * {@code AdminCourseCatalogController.loadTerms} 接住并退化成"不限定学期"，课程列表照常显示。
     */
    default CompletableFuture<List<CourseTermView>> listOfferingTerms() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("listOfferingTerms"));
    }

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

    default CompletableFuture<CheckArrangementResultDTO> checkArrangement(
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

    /**
     * 开一份该学期的草稿方案（服务端唯一能让 DRAFT 方案出现的入口）。与其它写操作同形：
     * 返回信封，而不是 {@link #loadSchedulePlan} 那样的裸 DTO。
     */
    default CompletableFuture<AdminOperationResultView<SchedulePlanView>> createSchedulePlan(
            int academicYear, int semester, boolean copyPublished, String operationId) {
        throw new UnsupportedOperationException("createSchedulePlan");
    }

    default CompletableFuture<List<AdjustmentRequestSummaryDTO>> listAdjustmentRequests(
            AdjustmentRequestStatusDTO status, int page, int size) {
        return listAdjustmentRequestsPage(status, page, size)
                .thenApply(AdjustmentRequestPageDTO::getItems);
    }

    /**
     * 四态调课列表（Task 4 起的主查询名）：WITHDRAWN 是教师撤销的终态，必须能被筛出来。
     * 旧的 {@link #listAdjustmentRequestsPage} 与 {@link #listAdjustmentRequests} 保留为委托别名，
     * 既有调用方与测试不需要改名。成绩筛选走独立的 {@code ApprovalStatusDTO} 方法，不受影响。
     */
    default CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsByStatus(
            AdjustmentRequestStatusDTO status, int page, int size) {
        throw new UnsupportedOperationException("listAdjustmentRequestsByStatus");
    }

    // Defaults keep existing implementations compatible until approval transport is provided.
    /** 旧名兼容别名：委托给四态主查询，行为与参数完全一致。 */
    default CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsPage(
            AdjustmentRequestStatusDTO status, int page, int size) {
        return listAdjustmentRequestsByStatus(status, page, size);
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
