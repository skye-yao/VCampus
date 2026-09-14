package handler;

import com.google.gson.Gson;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminEnrollmentService;
import service.AdminOfferingService;
import service.ScheduleAdjustmentApprovalService;
import service.ScheduleManagementService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AdminCourseHandler {
    private static final String MODULE = "courseAdmin";

    /** 动作登记表里已定义、但由后续计划开放的管理员操作。 */
    private static final Set<String> UNAVAILABLE_ACTIONS = Set.of(
            AdminCourseActions.LIST_GRADE_SUBMISSIONS,
            AdminCourseActions.GET_GRADE_SUBMISSION,
            AdminCourseActions.REVIEW_GRADE_SUBMISSION);

    private final AdminCourseCatalogService catalog;
    private final AdminOfferingService offerings;
    private final ScheduleManagementService scheduling;
    private final AdminEnrollmentService enrollment;
    private final ScheduleAdjustmentApprovalService adjustments;
    private final Gson gson = new Gson();

    public AdminCourseHandler() {
        this(new AdminCourseCatalogService(), new AdminOfferingService(),
                new ScheduleManagementService(), new AdminEnrollmentService(),
                new ScheduleAdjustmentApprovalService());
    }

    /**
     * Catalog-only handler. Scheduling stays unavailable here so the Task 1 regression that
     * pins "该管理员操作尚未开放" for a scheduling action remains valid; production uses the
     * no-argument constructor, which wires the real scheduling service.
     */
    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings) {
        this(catalog, offerings, null);
    }

    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling) {
        this(catalog, offerings, scheduling, null);
    }

    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling,
                              AdminEnrollmentService enrollment) {
        this(catalog, offerings, scheduling, enrollment, null);
    }

    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling,
                              AdminEnrollmentService enrollment,
                              ScheduleAdjustmentApprovalService adjustments) {
        this.catalog = catalog;
        this.offerings = offerings;
        this.scheduling = scheduling;
        this.enrollment = enrollment;
        this.adjustments = adjustments;
    }

    public Message handle(Message request) {
        Message response = response(request);
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            return failure(response, MessageCode.UNAUTHORIZED, "登录会话已失效，请重新登录");
        }
        if (!"管理员".equals(session.getRole())) {
            return failure(response, MessageCode.FORBIDDEN, "仅管理员可以访问课程管理服务");
        }
        String action = request.getAction();
        if (action == null || action.isBlank()) {
            return failure(response, MessageCode.BAD_REQUEST, "Action 不能为空");
        }

        try {
            String uid = session.getUsername();
            switch (action) {
                case AdminCourseActions.LIST_COURSES -> response.putData("courses", catalog.list(
                        optionalText(request, "query"), optionalText(request, "status")));
                case AdminCourseActions.CREATE_COURSE -> mutation(response,
                        catalog.create(uid, payload(request, CourseEditorRequestDTO.class)));
                case AdminCourseActions.UPDATE_COURSE -> mutation(response,
                        catalog.update(uid, payload(request, CourseEditorRequestDTO.class)));
                case AdminCourseActions.ARCHIVE_COURSE -> mutation(response, catalog.archive(uid,
                        decimalId(request, "courseId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.RESTORE_COURSE -> mutation(response, catalog.restore(uid,
                        decimalId(request, "courseId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.LIST_OFFERINGS -> response.putData("offerings",
                        offerings.list(decimalId(request, "courseId")));
                case AdminCourseActions.CREATE_OFFERING -> mutation(response,
                        offerings.create(uid, payload(request, OfferingEditorRequestDTO.class)));
                case AdminCourseActions.UPDATE_OFFERING -> mutation(response,
                        offerings.update(uid, payload(request, OfferingEditorRequestDTO.class)));
                case AdminCourseActions.CANCEL_OFFERING -> mutation(response, offerings.cancel(uid,
                        decimalId(request, "offeringId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.DELETE_DRAFT_OFFERING -> mutation(response,
                        offerings.deleteDraft(uid, decimalId(request, "offeringId"),
                                integer(request, "expectedVersion"), text(request, "operationId")));
                case AdminCourseActions.SEARCH_STUDENTS -> enrollmentPage(response, "students",
                        enrollment().searchStudents(enrollmentQuery(request, true),
                                pageNumber(request), pageSize(request)));
                case AdminCourseActions.LIST_OFFERING_STUDENTS -> enrollmentPage(response,
                        "offeringStudents", enrollment().listOfferingStudents(
                                enrollmentOfferingId(data(request, "offeringId")),
                                enrollmentQuery(request, false), pageNumber(request),
                                pageSize(request)));
                case AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT -> response.putData("preview",
                        enrollment().previewAdminEnrollment(
                                enrollmentOfferingId(data(request, "offeringId")),
                                enrollmentStudentUid(data(request, "studentUid"))));
                case AdminCourseActions.ADD_STUDENT_TO_OFFERING -> mutation(response,
                        enrollment().addStudentToOffering(uid, enrollmentRequest(request)));
                case AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING -> mutation(response,
                        enrollment().removeStudentFromOffering(uid, enrollmentRequest(request)));
                case AdminCourseActions.LIST_SCHEDULE_RESOURCES -> response.putData("resources",
                        scheduling().listResources(optionalText(request, "type"),
                                optionalText(request, "query")));
                case AdminCourseActions.LOAD_SCHEDULE_PLAN -> response.putData("plan",
                        scheduling().loadPlan(integer(request, "academicYear"),
                                integer(request, "semester")));
                case AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS -> response.putData(
                        "arrangements", scheduling().listArrangements(
                                decimalId(request, "planId"),
                                optionalText(request, "offeringId")));
                case AdminCourseActions.CHECK_ARRANGEMENT -> response.putData("conflicts",
                        scheduling().checkArrangement(arrangementRequest(request)));
                case AdminCourseActions.SAVE_ARRANGEMENT -> mutation(response,
                        scheduling().save(uid, arrangementRequest(request)));
                case AdminCourseActions.DELETE_ARRANGEMENT -> mutation(response,
                        scheduling().delete(uid, decimalId(request, "arrangementId"),
                                integer(request, "expectedVersion"), text(request, "operationId")));
                case AdminCourseActions.PUBLISH_SCHEDULE_PLAN -> mutation(response,
                        scheduling().publish(uid, decimalId(request, "planId"),
                                integer(request, "expectedRevision"), text(request, "operationId"),
                                flag(request, "force"), optionalText(request, "overrideReason")));
                case AdminCourseActions.LIST_ADJUSTMENT_REQUESTS -> adjustmentPage(response,
                        adjustments().listRequests(adjustmentStatus(request), pageNumber(request),
                                pageSize(request)));
                case AdminCourseActions.GET_ADJUSTMENT_REQUEST -> response.putData(
                        "adjustmentRequest",
                        adjustments().getRequest(decimalId(request, "requestId")));
                case AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST -> mutation(response,
                        adjustments().review(uid, adjustmentDecision(request)));
                default -> {
                    return failure(response, MessageCode.BAD_REQUEST,
                            UNAVAILABLE_ACTIONS.contains(action)
                                    ? "该管理员操作尚未开放" : "不支持的课程管理操作");
                }
            }
            response.setCode(MessageCode.SUCCESS);
            return response;
        } catch (IllegalArgumentException failure) {
            return failure(response, MessageCode.BAD_REQUEST, failure.getMessage());
        } catch (AdminCourseCatalogService.NotFoundException
                 | AdminOfferingService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, "课程资源不存在");
        } catch (AdminCourseCatalogService.ConflictException failure) {
            return conflict(response, failure.getMessage(), failure.getLatest());
        } catch (AdminOfferingService.ConflictException failure) {
            return conflict(response, failure.getMessage(), failure.getLatest());
        } catch (ScheduleManagementService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, failure.getMessage());
        } catch (ScheduleManagementService.ConflictException failure) {
            return scheduleConflict(response, failure);
        } catch (ScheduleAdjustmentApprovalService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, failure.getMessage());
        } catch (ScheduleAdjustmentApprovalService.ConflictException failure) {
            response.putData("conflicts", failure.getConflicts());
            return conflict(response, failure.getMessage(), failure.getEntity());
        } catch (AdminEnrollmentService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, failure.getMessage());
        } catch (AdminEnrollmentService.ConflictException failure) {
            response.putData("conflicts", failure.getConflicts());
            return conflict(response, failure.getMessage(), failure.getEntity());
        } catch (DatabaseException failure) {
            return failure(response, MessageCode.ERROR, "课程管理服务暂不可用");
        } catch (RuntimeException failure) {
            return failure(response, MessageCode.ERROR, "服务端内部错误");
        }
    }

    private static Message response(Message request) {
        Message response = new Message(MessageType.RESPONSE, MODULE, request.getAction());
        response.setUID(request.getUID());
        return response;
    }

    private static Message failure(Message response, MessageCode code, String message) {
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    private static Message mutation(Message response, AdminOperationResultDTO<?> result) {
        response.putData("result", result);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage(result.getMessage());
        return response;
    }

    private static Message conflict(Message response, String message, Object latest) {
        response.setCode(MessageCode.CONFLICT);
        response.setMessage(message);
        if (latest != null) response.putData("latest", latest);
        return response;
    }

    private static Message scheduleConflict(Message response,
                                            ScheduleManagementService.ConflictException failure) {
        response.setCode(MessageCode.CONFLICT);
        response.setMessage(failure.getMessage());
        response.putData("conflicts", failure.getConflicts());
        if (failure.getEntity() != null) response.putData("latest", failure.getEntity());
        return response;
    }

    private ScheduleManagementService scheduling() {
        if (scheduling == null) {
            throw new IllegalArgumentException("该管理员操作尚未开放");
        }
        return scheduling;
    }

    private AdminEnrollmentService enrollment() {
        if (enrollment == null) {
            throw new IllegalArgumentException("该管理员操作尚未开放");
        }
        return enrollment;
    }

    private ScheduleAdjustmentApprovalService adjustments() {
        if (adjustments == null) {
            throw new IllegalArgumentException("该管理员操作尚未开放");
        }
        return adjustments;
    }

    private static void adjustmentPage(Message response, AdjustmentRequestPageDTO page) {
        response.putData("adjustmentRequests", page.getItems());
        response.putData("totalCount", page.getTotalCount());
        response.putData("pageNumber", page.getPageNumber());
        response.putData("pageSize", page.getPageSize());
    }

    /** An absent status keeps the server-side PENDING default; an unknown one is a bad request. */
    private static ApprovalStatusDTO adjustmentStatus(Message request) {
        String status = optionalText(request, "status");
        if (status == null) return null;
        try {
            return ApprovalStatusDTO.valueOf(status.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("status 必须为 PENDING、APPROVED 或 REJECTED");
        }
    }

    private ApprovalDecisionRequestDTO adjustmentDecision(Message request) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        // Validate raw JSON types before Gson can coerce them into the decision DTO.
        Object operation = values.get("operationId");
        try {
            if (!(operation instanceof String id)
                    || !UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("operationId 必须是 UUID 字符串");
        }
        Object requestId = values.get("requestId");
        if (!(requestId instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException("requestId 必须为十进制字符串");
        }
        Object version = values.get("expectedVersion");
        if (version instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("expectedVersion 必须为整数");
            }
        } else if (!(version instanceof String versionText)
                || !versionText.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("expectedVersion 必须为整数");
        }
        for (String key : new String[] {"approved", "force"}) {
            Object raw = values.get(key);
            if (values.containsKey(key) && !(raw instanceof Boolean)) {
                throw new IllegalArgumentException(key + " 必须为布尔值");
            }
        }
        for (String key : new String[] {"overrideReason", "reviewComment"}) {
            Object raw = values.get(key);
            if (raw != null && !(raw instanceof String)) {
                throw new IllegalArgumentException(key + " 必须为字符串");
            }
        }
        return payload(request, ApprovalDecisionRequestDTO.class);
    }

    private static void enrollmentPage(Message response, String key,
            AdminEnrollmentPageDTO<?> page) {
        response.putData(key, page.getItems());
        response.putData("totalCount", page.getTotalCount());
        response.putData("pageNumber", page.getPageNumber());
        response.putData("pageSize", page.getPageSize());
    }

    private static String enrollmentQuery(Message request, boolean required) {
        String value = optionalText(request, "query");
        String query = value == null ? "" : value.trim();
        if (required && query.isEmpty()) throw new IllegalArgumentException("搜索条件不能为空");
        return query;
    }

    private static int pageNumber(Message request) {
        int page = integer(request, "pageNumber");
        if (page < 1) throw new IllegalArgumentException("pageNumber 必须大于 0");
        return page;
    }

    private static int pageSize(Message request) {
        int size = integer(request, "pageSize");
        if (size < 1 || size > 100) throw new IllegalArgumentException("pageSize 必须为 1 至 100");
        return size;
    }

    private static String enrollmentOfferingId(Object value) {
        String id = decimalId(value, "offeringId");
        if (id.charAt(0) == '0') throw new IllegalArgumentException("offeringId 必须为正整数");
        return id;
    }

    private static String enrollmentStudentUid(Object value) {
        if (!(value instanceof String uid) || uid.trim().isEmpty() || uid.trim().length() > 32) {
            throw new IllegalArgumentException("studentUid 必须为 1 至 32 个字符的字符串");
        }
        return uid.trim();
    }

    private AdminEnrollmentRequestDTO enrollmentRequest(Message request) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        // Validate raw JSON types before Gson can coerce numbers or strings into DTO fields.
        enrollmentOfferingId(values.get("offeringId"));
        enrollmentStudentUid(values.get("studentUid"));
        Object operation = values.get("operationId");
        try {
            if (!(operation instanceof String id)
                    || !UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("operationId 必须是 UUID 字符串");
        }
        Object force = values.get("force");
        if (values.containsKey("force") && !(force instanceof Boolean)) {
            throw new IllegalArgumentException("force 必须为布尔值");
        }
        Object rawReason = values.get("overrideReason");
        if (rawReason != null && !(rawReason instanceof String)) {
            throw new IllegalArgumentException("overrideReason 必须为字符串");
        }
        String reason = rawReason == null ? null : ((String) rawReason).trim();
        if (Boolean.TRUE.equals(force) && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("强制操作必须填写原因");
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("强制原因不能超过 500 字符");
        }
        return payload(request, AdminEnrollmentRequestDTO.class);
    }

    private SaveArrangementRequestDTO arrangementRequest(Message request) {
        return payload(request, SaveArrangementRequestDTO.class);
    }

    private static boolean flag(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        throw new IllegalArgumentException(key + " 必须为布尔值");
    }

    private <T> T payload(Message request, Class<T> type) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        return gson.fromJson(gson.toJson(value), type);
    }

    private static String optionalText(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return text.isBlank() ? null : text;
    }

    private static int integer(Message request, String key) {
        Object value = data(request, key);
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(key + " 必须为整数");
            }
            return (int) decimal;
        }
        if (value instanceof String text && text.matches("-?[0-9]+")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(key + " 超出整数范围");
            }
        }
        throw new IllegalArgumentException(key + " 必须为整数");
    }

    private static String decimalId(Message request, String key) {
        return decimalId(data(request, key), key);
    }

    private static String decimalId(Object value, String key) {
        if (!(value instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " 必须为十进制字符串");
        }
        try {
            if (Long.parseLong(text) <= 0) {
                throw new IllegalArgumentException(key + " 必须为正整数");
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 超出 BIGINT 范围");
        }
        return text;
    }

    private static String text(Message request, String key) {
        Object value = data(request, key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须为非空字符串");
        }
        return text;
    }

    private static Object data(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }
}
