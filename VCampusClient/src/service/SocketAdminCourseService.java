package service;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
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
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;

public final class SocketAdminCourseService implements AdminCourseService {
    private static final String MODULE = "courseAdmin";

    private static final Type COURSE_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, AdminCourseDTO.class).getType();
    private static final Type OFFERING_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, AdminOfferingDTO.class).getType();
    private static final Type VOID_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, Void.class).getType();
    private static final Type ARRANGEMENT_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, ScheduleArrangementDTO.class).getType();
    private static final Type PLAN_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, SchedulePlanDTO.class).getType();
    private static final Type ENROLLMENT_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, OfferingStudentDTO.class).getType();
    private static final Type ADJUSTMENT_RESULT_TYPE = TypeToken.getParameterized(
            AdminOperationResultDTO.class, AdjustmentRequestDetailDTO.class).getType();

    private final AdminCourseTransport transport;
    private final Gson gson = new Gson();

    public SocketAdminCourseService() {
        this(new SocketAdminCourseTransport());
    }

    SocketAdminCourseService(AdminCourseTransport transport) {
        this.transport = transport;
    }

    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
        Message request = request(AdminCourseActions.LIST_COURSES);
        if (query != null) request.putData("query", query);
        if (status != null) request.putData("status", status);
        return map(request, response -> {
            List<AdminCourseView> courses = new ArrayList<>();
            for (AdminCourseDTO dto : list(response, "courses", AdminCourseDTO.class)) {
                courses.add(course(dto));
            }
            return List.copyOf(courses);
        });
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
            CourseEditorRequestDTO request) {
        return courseMutation(AdminCourseActions.CREATE_COURSE, request);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
            CourseEditorRequestDTO request) {
        return courseMutation(AdminCourseActions.UPDATE_COURSE, request);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId) {
        return courseTargetMutation(AdminCourseActions.ARCHIVE_COURSE,
                courseId, expectedVersion, operationId);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId) {
        return courseTargetMutation(AdminCourseActions.RESTORE_COURSE,
                courseId, expectedVersion, operationId);
    }

    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
        Message request = request(AdminCourseActions.LIST_OFFERINGS);
        request.putData("courseId", courseId);
        return map(request, response -> {
            List<AdminOfferingView> offerings = new ArrayList<>();
            for (AdminOfferingDTO dto : list(response, "offerings", AdminOfferingDTO.class)) {
                offerings.add(offering(dto));
            }
            return List.copyOf(offerings);
        });
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
            OfferingEditorRequestDTO request) {
        return offeringMutation(AdminCourseActions.CREATE_OFFERING, request);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
            OfferingEditorRequestDTO request) {
        return offeringMutation(AdminCourseActions.UPDATE_OFFERING, request);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
            String offeringId, int expectedVersion, String operationId) {
        Message request = request(AdminCourseActions.CANCEL_OFFERING);
        putTarget(request, "offeringId", offeringId, expectedVersion, operationId);
        return map(request,
                response -> offeringResult(read(response, "result", OFFERING_RESULT_TYPE)),
                this::latestOffering);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
            String offeringId, int expectedVersion, String operationId) {
        Message request = request(AdminCourseActions.DELETE_DRAFT_OFFERING);
        putTarget(request, "offeringId", offeringId, expectedVersion, operationId);
        return map(request, response -> {
            AdminOperationResultDTO<Void> dto = read(response, "result", VOID_RESULT_TYPE);
            return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                    dto.getMessage(), null);
        }, this::latestOffering);
    }

    private CompletableFuture<AdminOperationResultView<AdminCourseView>> courseMutation(
            String action, CourseEditorRequestDTO payload) {
        Message request = request(action);
        request.putData("request", payload);
        return map(request,
                response -> courseResult(read(response, "result", COURSE_RESULT_TYPE)),
                this::latestCourse);
    }

    @Override
    public CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> searchStudentsPage(
            String query, int page, int size) {
        Message request = request(AdminCourseActions.SEARCH_STUDENTS);
        request.putData("query", query);
        putPage(request, page, size);
        return map(request, response -> {
            List<StudentSearchResultView> students = new ArrayList<>();
            for (StudentSearchResultDTO dto : list(response, "students", StudentSearchResultDTO.class)) {
                students.add(new StudentSearchResultView(dto.getUid(), dto.getName(), dto.getMajor(),
                        dto.getCohortYear(), dto.getAcademicStatus()));
            }
            return enrollmentPage(response, students);
        });
    }

    @Override
    public CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> listOfferingStudentsPage(
            String offeringId, String query, int page, int size) {
        Message request = request(AdminCourseActions.LIST_OFFERING_STUDENTS);
        request.putData("offeringId", offeringId);
        if (query != null) request.putData("query", query);
        putPage(request, page, size);
        return map(request, response -> {
            List<OfferingStudentView> students = new ArrayList<>();
            for (OfferingStudentDTO dto : list(response, "offeringStudents", OfferingStudentDTO.class)) {
                students.add(offeringStudent(dto));
            }
            return enrollmentPage(response, students);
        });
    }

    @Override
    public CompletableFuture<AdminEnrollmentPreviewDTO> previewAdminEnrollment(
            String offeringId, String studentUid) {
        Message request = request(AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT);
        request.putData("offeringId", offeringId);
        request.putData("studentUid", studentUid);
        return map(request, response -> read(response, "preview", AdminEnrollmentPreviewDTO.class));
    }

    @Override
    public CompletableFuture<AdminOperationResultView<OfferingStudentView>> addStudentToOffering(
            AdminEnrollmentRequestDTO request) {
        return enrollmentMutation(AdminCourseActions.ADD_STUDENT_TO_OFFERING, request);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<OfferingStudentView>> removeStudentFromOffering(
            AdminEnrollmentRequestDTO request) {
        return enrollmentMutation(AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING, request);
    }

    private CompletableFuture<AdminOperationResultView<OfferingStudentView>> enrollmentMutation(
            String action, AdminEnrollmentRequestDTO payload) {
        Message request = request(action);
        request.putData("request", payload);
        return map(request, response -> {
            AdminOperationResultDTO<OfferingStudentDTO> dto = read(response, "result", ENROLLMENT_RESULT_TYPE);
            return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                    dto.getMessage(), dto.getEntity() == null ? null : offeringStudent(dto.getEntity()));
        }, value -> offeringStudent(gson.fromJson(gson.toJson(value), OfferingStudentDTO.class)));
    }

    private static void putPage(Message request, int page, int size) {
        request.putData("pageNumber", page);
        request.putData("pageSize", size);
    }

    private <T> AdminEnrollmentPageView<T> enrollmentPage(Message response, List<T> items) {
        return new AdminEnrollmentPageView<>(items, read(response, "totalCount", Long.class),
                read(response, "pageNumber", Integer.class), read(response, "pageSize", Integer.class));
    }

    private static OfferingStudentView offeringStudent(OfferingStudentDTO dto) {
        return new OfferingStudentView(dto.getEnrollmentId(), dto.getUid(), dto.getName(),
                dto.getMajor(), dto.getCohortYear(), dto.getEnrollmentStatus(), dto.isRemovable(),
                dto.getBlockedReason());
    }

    @Override
    public CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
            String type, String query) {
        Message request = request(AdminCourseActions.LIST_SCHEDULE_RESOURCES);
        if (type != null) request.putData("type", type);
        if (query != null) request.putData("query", query);
        return map(request, response -> List.copyOf(
                list(response, "resources", ScheduleResourceDTO.class)));
    }

    @Override
    public CompletableFuture<SchedulePlanDTO> loadSchedulePlan(int academicYear, int semester) {
        Message request = request(AdminCourseActions.LOAD_SCHEDULE_PLAN);
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        return map(request, response -> read(response, "plan", SchedulePlanDTO.class));
    }

    @Override
    public CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
            String planId, String offeringId) {
        Message request = request(AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS);
        request.putData("planId", planId);
        if (offeringId != null) request.putData("offeringId", offeringId);
        return map(request, response -> {
            List<ScheduleArrangementView> arrangements = new ArrayList<>();
            for (ScheduleArrangementDTO dto : list(response, "arrangements",
                    ScheduleArrangementDTO.class)) {
                arrangements.add(arrangement(dto));
            }
            return List.copyOf(arrangements);
        });
    }

    @Override
    public CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
            SaveArrangementRequestDTO request) {
        Message message = request(AdminCourseActions.CHECK_ARRANGEMENT);
        message.putData("request", request);
        return map(message, response -> List.copyOf(
                list(response, "conflicts", ScheduleConflictDTO.class)));
    }

    @Override
    public CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
            SaveArrangementRequestDTO request) {
        Message message = request(AdminCourseActions.SAVE_ARRANGEMENT);
        message.putData("request", request);
        return map(message,
                response -> arrangementResult(read(response, "result", ARRANGEMENT_RESULT_TYPE)),
                this::latestArrangement);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
            String arrangementId, int expectedVersion, String operationId) {
        Message request = request(AdminCourseActions.DELETE_ARRANGEMENT);
        request.putData("arrangementId", arrangementId);
        request.putData("expectedVersion", expectedVersion);
        request.putData("operationId", operationId);
        return map(request, response -> {
            AdminOperationResultDTO<Void> dto = read(response, "result", VOID_RESULT_TYPE);
            return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                    dto.getMessage(), null);
        }, this::latestArrangement);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
            String planId, int expectedRevision, String operationId,
            boolean force, String overrideReason) {
        Message request = request(AdminCourseActions.PUBLISH_SCHEDULE_PLAN);
        request.putData("planId", planId);
        request.putData("expectedRevision", expectedRevision);
        request.putData("operationId", operationId);
        request.putData("force", force);
        request.putData("overrideReason", overrideReason);
        return map(request,
                response -> planResult(read(response, "result", PLAN_RESULT_TYPE)),
                this::latestPlan);
    }

    @Override
    public CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsPage(
            ApprovalStatusDTO status, int page, int size) {
        Message request = request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS);
        if (status != null) request.putData("status", status.name());
        putPage(request, page, size);
        return map(request, response -> new AdjustmentRequestPageDTO(
                list(response, "adjustmentRequests", AdjustmentRequestSummaryDTO.class),
                read(response, "totalCount", Long.class),
                read(response, "pageNumber", Integer.class),
                read(response, "pageSize", Integer.class)));
    }

    @Override
    public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
        Message request = request(AdminCourseActions.GET_ADJUSTMENT_REQUEST);
        request.putData("requestId", requestId);
        return map(request, response -> read(response, "adjustmentRequest",
                AdjustmentRequestDetailDTO.class));
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>> reviewAdjustmentRequest(
            ApprovalDecisionRequestDTO decision) {
        Message request = request(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST);
        request.putData("request", decision);
        return map(request, response -> {
            AdminOperationResultDTO<AdjustmentRequestDetailDTO> dto =
                    read(response, "result", ADJUSTMENT_RESULT_TYPE);
            return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                    dto.getMessage(), dto.getEntity());
        }, this::latestAdjustmentRequest);
    }

    private AdjustmentRequestDetailDTO latestAdjustmentRequest(Object value) {
        return gson.fromJson(gson.toJson(value), AdjustmentRequestDetailDTO.class);
    }

    private ScheduleArrangementView latestArrangement(Object value) {
        return arrangement(gson.fromJson(gson.toJson(value), ScheduleArrangementDTO.class));
    }

    private SchedulePlanView latestPlan(Object value) {
        return plan(gson.fromJson(gson.toJson(value), SchedulePlanDTO.class));
    }

    private static ScheduleArrangementView arrangement(ScheduleArrangementDTO dto) {
        return new ScheduleArrangementView(dto.getArrangementId(), dto.getPlanId(),
                dto.getOfferingId(), dto.getTeacher(), dto.getAssistant(), dto.getClassroom(),
                dto.getSlots(), dto.getStartWeek(), dto.getEndWeek(), dto.getStatus(),
                dto.getVersion());
    }

    private static SchedulePlanView plan(SchedulePlanDTO dto) {
        return new SchedulePlanView(dto.getPlanId(), dto.getName(), dto.getRevision(),
                dto.getStatus(), dto.isCurrent(), dto.getConflicts());
    }

    private static AdminOperationResultView<ScheduleArrangementView> arrangementResult(
            AdminOperationResultDTO<ScheduleArrangementDTO> dto) {
        return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                dto.getMessage(), dto.getEntity() == null ? null : arrangement(dto.getEntity()));
    }

    private static AdminOperationResultView<SchedulePlanView> planResult(
            AdminOperationResultDTO<SchedulePlanDTO> dto) {
        return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                dto.getMessage(), dto.getEntity() == null ? null : plan(dto.getEntity()));
    }

    private CompletableFuture<AdminOperationResultView<AdminCourseView>> courseTargetMutation(
            String action, String courseId, int expectedVersion, String operationId) {
        Message request = request(action);
        putTarget(request, "courseId", courseId, expectedVersion, operationId);
        return map(request,
                response -> courseResult(read(response, "result", COURSE_RESULT_TYPE)),
                this::latestCourse);
    }

    private CompletableFuture<AdminOperationResultView<AdminOfferingView>> offeringMutation(
            String action, OfferingEditorRequestDTO payload) {
        Message request = request(action);
        request.putData("request", payload);
        return map(request,
                response -> offeringResult(read(response, "result", OFFERING_RESULT_TYPE)),
                this::latestOffering);
    }

    private static void putTarget(Message request, String idKey, String id,
            int expectedVersion, String operationId) {
        request.putData(idKey, id);
        request.putData("expectedVersion", expectedVersion);
        request.putData("operationId", operationId);
    }

    private static Message request(String action) {
        Message message = new Message(MessageType.REQUEST, MODULE, action);
        message.setCode(MessageCode.SUCCESS);
        message.setToken(ClientSession.getInstance().getToken());
        return message;
    }

    private <T> CompletableFuture<T> map(Message request, Function<Message, T> mapper) {
        return map(request, mapper, null);
    }

    private <T> CompletableFuture<T> map(Message request, Function<Message, T> mapper,
            Function<Object, Object> latestMapper) {
        return transport.send(request).thenApply(response -> {
            requireSuccess(response, latestMapper);
            return mapper.apply(response);
        });
    }

    private void requireSuccess(Message response, Function<Object, Object> latestMapper) {
        if (response == null) {
            throw new AdminCourseServiceException(MessageCode.ERROR, "课程管理服务无响应");
        }
        if (response.getCode() == MessageCode.SUCCESS) return;
        String message = response.getMessage() == null
                ? response.getCode().getMessage() : response.getMessage();
        Object rawLatest = response.getData() == null ? null : response.getData().get("latest");
        Object latest = rawLatest == null || latestMapper == null ? null
                : latestMapper.apply(rawLatest);
        List<ScheduleConflictDTO> conflicts = response.getData() == null
                || response.getData().get("conflicts") == null ? List.of()
                : list(response, "conflicts", ScheduleConflictDTO.class);
        throw new AdminCourseServiceException(response.getCode(), message, latest, conflicts);
    }

    private AdminCourseView latestCourse(Object value) {
        return course(gson.fromJson(gson.toJson(value), AdminCourseDTO.class));
    }

    private AdminOfferingView latestOffering(Object value) {
        return offering(gson.fromJson(gson.toJson(value), AdminOfferingDTO.class));
    }

    private <T> List<T> list(Message response, String key, Class<T> type) {
        Type listType = TypeToken.getParameterized(List.class, type).getType();
        return read(response, key, listType);
    }

    private <T> T read(Message response, String key, Type type) {
        Object value = response.getData() == null ? null : response.getData().get(key);
        if (value == null) {
            throw new AdminCourseServiceException(MessageCode.ERROR, "缺少响应字段: " + key);
        }
        return gson.fromJson(gson.toJson(value), type);
    }

    private static AdminCourseView course(AdminCourseDTO dto) {
        return new AdminCourseView(dto.getCourseId(), dto.getCourseCode(), dto.getCourseName(),
                dto.getCourseType(), dto.getCredit(), dto.getCreditHours(), dto.getDescription(),
                dto.getPrerequisites(), dto.isAllowCrossMajor(), dto.isFinalExam(),
                dto.getStatus(), dto.getOfferingCount(), dto.getVersion());
    }

    private static AdminOfferingView offering(AdminOfferingDTO dto) {
        return new AdminOfferingView(dto.getOfferingId(), dto.getOfferingCode(),
                dto.getCourseId(), dto.getAcademicYear(), dto.getSemester(), dto.getCapacity(),
                dto.getEnrolledCount(), dto.getStatus(), dto.getTeacherUid(), dto.getTeacherName(),
                dto.getAssistantUid(), dto.getAssistantName(), dto.getScheduleStatus(),
                dto.getVersion());
    }

    private static AdminOperationResultView<AdminCourseView> courseResult(
            AdminOperationResultDTO<AdminCourseDTO> dto) {
        return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                dto.getMessage(), dto.getEntity() == null ? null : course(dto.getEntity()));
    }

    private static AdminOperationResultView<AdminOfferingView> offeringResult(
            AdminOperationResultDTO<AdminOfferingDTO> dto) {
        return new AdminOperationResultView<>(dto.getOperationId(), dto.getOutcomeCode(),
                dto.getMessage(), dto.getEntity() == null ? null : offering(dto.getEntity()));
    }

    public static final class AdminCourseServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final MessageCode code;
        private final Object latest;
        private final List<ScheduleConflictDTO> conflicts;

        public AdminCourseServiceException(MessageCode code, String message) {
            this(code, message, null);
        }

        public AdminCourseServiceException(MessageCode code, String message, Object latest) {
            this(code, message, latest, List.of());
        }

        public AdminCourseServiceException(MessageCode code, String message, Object latest,
                List<ScheduleConflictDTO> conflicts) {
            super(message);
            this.code = code;
            this.latest = latest;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public MessageCode getCode() {
            return code;
        }

        public Object getLatest() {
            return latest;
        }

        public List<ScheduleConflictDTO> getConflicts() {
            return conflicts;
        }
    }
}
