package handler;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.CheckArrangementResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminEnrollmentService;
import service.AdminOfferingService;
import service.GradeApprovalService;
import service.ScheduleAdjustmentApprovalService;
import service.ScheduleManagementService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
* Internal course-management type AdminCourseHandler.
*/
public class AdminCourseHandler {
    private static final String MODULE = "courseAdmin";

    /**
    * 动作登记表里已定义、但由后续计划开放的管理员操作。当前所有登记动作均已开放，故为空；
    * 保留该集合以便后续计划登记新动作时仍能统一返回"尚未开放"。
    */
    private static final Set<String> UNAVAILABLE_ACTIONS = Set.of();

    private final AdminCourseCatalogService catalog;
    private final AdminOfferingService offerings;
    private final ScheduleManagementService scheduling;
    private final AdminEnrollmentService enrollment;
    private final ScheduleAdjustmentApprovalService adjustments;
    private final GradeApprovalService grades;
    private final Gson gson = new Gson();

    /**
    * Handles the course-management responsibility of AdminCourseHandler.
    */
    public AdminCourseHandler() {
        this(new AdminCourseCatalogService(), new AdminOfferingService(),
                new ScheduleManagementService(), new AdminEnrollmentService(),
                new ScheduleAdjustmentApprovalService(), new GradeApprovalService());
    }

    /**
    * Catalog-only handler. Scheduling stays unavailable here so the Task 1 regression that
    * pins "该管理员操作尚未开放" for a scheduling action remains valid; production uses the
    * no-argument constructor, which wires the real scheduling service.
    */
    /**
    * Handles the course-management responsibility of AdminCourseHandler.
    */
    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings) {
        this(catalog, offerings, null);
    }

    /**
    * Handles the course-management responsibility of AdminCourseHandler.
    */
    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling) {
        this(catalog, offerings, scheduling, null);
    }

    /**
    * Handles the course-management responsibility of AdminCourseHandler.
    */
    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling,
                              AdminEnrollmentService enrollment) {
        this(catalog, offerings, scheduling, enrollment, null);
    }

    /**
    * Handles the course-management responsibility of AdminCourseHandler.
    */
    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling,
                              AdminEnrollmentService enrollment,
                              ScheduleAdjustmentApprovalService adjustments) {
        this(catalog, offerings, scheduling, enrollment, adjustments, null);
    }

    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings,
                              ScheduleManagementService scheduling,
                              AdminEnrollmentService enrollment,
                              ScheduleAdjustmentApprovalService adjustments,
                              GradeApprovalService grades) {
        this.catalog = catalog;
        this.offerings = offerings;
        this.scheduling = scheduling;
        this.enrollment = enrollment;
        this.adjustments = adjustments;
        this.grades = grades;
    }

    /**
    * Dispatches the course-management protocol request by action.
    */
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
                        optionalText(request, "query"), optionalText(request, "status"),
                        optionalInteger(request, "academicYear"),
                        optionalInteger(request, "semester")));
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
                        offerings.list(decimalId(request, "courseId"),
                                optionalInteger(request, "academicYear"),
                                optionalInteger(request, "semester")));
                case AdminCourseActions.LIST_OFFERING_TERMS -> response.putData("terms",
                        offerings.listTerms());
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
                case AdminCourseActions.CHECK_ARRANGEMENT -> {
                    // 一次往返同时刷新表单级与方案级冲突：两个键各带一份权威列表。
                    CheckArrangementResultDTO checked =
                            scheduling().checkArrangement(arrangementRequest(request));
                    response.putData("conflicts", checked.getArrangementConflicts());
                    response.putData("planConflicts", checked.getPlanConflicts());
                }
                case AdminCourseActions.SAVE_ARRANGEMENT -> mutation(response,
                        scheduling().save(uid, arrangementRequest(request)));
                case AdminCourseActions.DELETE_ARRANGEMENT -> mutation(response,
                        scheduling().delete(uid, decimalId(request, "arrangementId"),
                                integer(request, "expectedVersion"), text(request, "operationId")));
                case AdminCourseActions.PUBLISH_SCHEDULE_PLAN -> mutation(response,
                        scheduling().publish(uid, decimalId(request, "planId"),
                                integer(request, "expectedRevision"), text(request, "operationId"),
                                flag(request, "force"), optionalText(request, "overrideReason")));
                case AdminCourseActions.CREATE_SCHEDULE_PLAN -> mutation(response,
                        scheduling().createDraftPlan(uid, integer(request, "academicYear"),
                                integer(request, "semester"), flag(request, "copyPublished"),
                                text(request, "operationId")));
                case AdminCourseActions.LIST_ADJUSTMENT_REQUESTS -> adjustmentPage(response,
                        adjustments().listRequests(adjustmentStatus(request), pageNumber(request),
                                pageSize(request)));
                case AdminCourseActions.GET_ADJUSTMENT_REQUEST -> response.putData(
                        "adjustmentRequest",
                        adjustments().getRequest(decimalId(request, "requestId")));
                case AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST -> mutation(response,
                        adjustments().review(uid, adjustmentDecision(request)));
                case AdminCourseActions.LIST_GRADE_SUBMISSIONS -> gradePage(response,
                        grades().listGradeSubmissionsPage(gradeStatus(request),
                                pageNumber(request), pageSize(request)));
                case AdminCourseActions.GET_GRADE_SUBMISSION -> response.putData("gradeSubmission",
                        grades().getGradeSubmission(decimalId(request, "submissionId")));
                case AdminCourseActions.REVIEW_GRADE_SUBMISSION -> mutation(response,
                        grades().review(uid, gradeDecision(request)));
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
        } catch (GradeApprovalService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, failure.getMessage());
        } catch (GradeApprovalService.ConflictException failure) {
            return conflict(response, failure.getMessage(), failure.getEntity());
        } catch (DatabaseException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "课程管理服务暂不可用");
        } catch (RuntimeException failure) {
            logFailure(action, failure);
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

    private GradeApprovalService grades() {
        if (grades == null) {
            throw new IllegalArgumentException("该管理员操作尚未开放");
        }
        return grades;
    }

    private static void adjustmentPage(Message response, AdjustmentRequestPageDTO page) {
        response.putData("adjustmentRequests", page.getItems());
        response.putData("totalCount", page.getTotalCount());
        response.putData("pageNumber", page.getPageNumber());
        response.putData("pageSize", page.getPageSize());
    }

    private static void gradePage(Message response, GradeSubmissionPageDTO page) {
        response.putData("gradeSubmissions", page.getItems());
        response.putData("totalCount", page.getTotalCount());
        response.putData("pageNumber", page.getPageNumber());
        response.putData("pageSize", page.getPageSize());
    }

    /**
    * 调课状态解析：缺省交给服务端默认 PENDING，未知值一律 400。
    * 调课是四态，教师撤销的 WITHDRAWN 必须能被筛选出来。
    */
    private static AdjustmentRequestStatusDTO adjustmentStatus(Message request) {
        String status = optionalText(request, "status");
        if (status == null) return null;
        try {
            return AdjustmentRequestStatusDTO.valueOf(status.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "status 必须为 PENDING、APPROVED、REJECTED 或 WITHDRAWN");
        }
    }

    /**
    * 成绩状态解析保持三态：成绩提交没有“撤销”，不能因为调课新增了 WITHDRAWN
    * 就让成绩列表承认一个不存在的状态。
    */
    private static ApprovalStatusDTO gradeStatus(Message request) {
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

    /**
    * Parses a grade decision. Grade approval never forwards {@code force} (R12): a true flag is
    * refused here, so every accepted decision reaches the service with {@code force = false} and no
    * override reason, and the service's own defensive refusal stays a safety net rather than the
    * only guard.
    */
    private ApprovalDecisionRequestDTO gradeDecision(Message request) {
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
        Object submission = values.get("requestId");
        if (!(submission instanceof String text) || !text.matches("[0-9]+")) {
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
        Object approved = values.get("approved");
        if (values.containsKey("approved") && !(approved instanceof Boolean)) {
            throw new IllegalArgumentException("approved 必须为布尔值");
        }
        Object force = values.get("force");
        if (values.containsKey("force") && !(force instanceof Boolean)) {
            throw new IllegalArgumentException("force 必须为布尔值");
        }
        if (Boolean.TRUE.equals(force)) {
            throw new IllegalArgumentException("成绩审批不支持强制覆盖");
        }
        Object comment = values.get("reviewComment");
        if (comment != null && !(comment instanceof String)) {
            throw new IllegalArgumentException("reviewComment 必须为字符串");
        }
        ApprovalDecisionRequestDTO parsed = payload(request, ApprovalDecisionRequestDTO.class);
        return new ApprovalDecisionRequestDTO(parsed.getOperationId(), parsed.getRequestId(),
                parsed.getExpectedVersion(), parsed.isApproved(), false, null,
                parsed.getReviewComment());
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

    private static void logFailure(String action, RuntimeException failure) {
        System.err.println("课程管理请求处理失败: action=" + action);
        failure.printStackTrace(System.err);
    }

    private <T> T payload(Message request, Class<T> type) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        try {
            return gson.fromJson(gson.toJson(value), type);
        } catch (JsonParseException | IllegalStateException | NumberFormatException failure) {
            throw new IllegalArgumentException("request 字段格式无效", failure);
        }
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

    /**
    * 可选整数：字段缺席或为 null 时返回 {@code null}，让调用方按"不限定"处理。
    * 字段在但格式不对仍然抛——那是客户端 bug，不能静默降级成"不限定"。
    */
    private static Integer optionalInteger(Message request, String key) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get(key) == null) return null;
        return integer(request, key);
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
