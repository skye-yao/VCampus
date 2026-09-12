package service;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
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

    private static void requireSuccess(Message response, Function<Object, Object> latestMapper) {
        if (response == null) {
            throw new AdminCourseServiceException(MessageCode.ERROR, "课程管理服务无响应");
        }
        if (response.getCode() == MessageCode.SUCCESS) return;
        String message = response.getMessage() == null
                ? response.getCode().getMessage() : response.getMessage();
        Object rawLatest = response.getData() == null ? null : response.getData().get("latest");
        Object latest = rawLatest == null || latestMapper == null ? null
                : latestMapper.apply(rawLatest);
        throw new AdminCourseServiceException(response.getCode(), message, latest);
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

        public AdminCourseServiceException(MessageCode code, String message) {
            this(code, message, null);
        }

        public AdminCourseServiceException(MessageCode code, String message, Object latest) {
            super(message);
            this.code = code;
            this.latest = latest;
        }

        public MessageCode getCode() {
            return code;
        }

        public Object getLatest() {
            return latest;
        }
    }
}
