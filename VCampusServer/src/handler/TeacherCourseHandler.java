package handler;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import session.SessionManager;
import session.UserSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 教师端课程查询与调课（module {@code courseTeacher}）的 TCP 入口。
 *
 * <p>处理顺序固定为：认证 → 角色 → 参数 → 调用服务。教师 UID 只取自服务端校验过的
 * Session（{@link UserSession#getUsername()}），请求体里的 {@code uid}/{@code teacherId}/
 * {@code sender} 不参与任何判定，防止客户端用请求体替换真实身份。
 *
 * <p>只读查询的响应键为 terms、offerings、offering、students、schedules、schedule；调课动作的
 * 响应键为 options、conflicts（预览）、adjustmentRequest、applications、result（写操作）。列表类的
 * items 由 {@link dto.course.teacher.TeacherPageDTO} 承载（含 totalCount/page/size），客户端用
 * TypeToken 解析泛型页。数据库异常只写服务端日志，响应里不出现 SQL、表名或堆栈。
 *
 * <p>调课写请求体位于 {@code data.request}；其中身份字段（uid/教师/助教）与 {@code force} 是
 * 协议外字段，出现即 BAD_REQUEST，绝不传入服务——教师没有强制权限，新安排的教师/助教由服务端按
 * 原课次快照派生。提交与撤销是写操作，Handler 不做“先查后写”的归属判断，权限一律由服务端在事务
 * 内重新计算。
 */
public class TeacherCourseHandler {
    private static final String MODULE = "courseTeacher";
    /** 与设计第 3 节一致：size 为 1..100。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final String TEACHER_ROLE = "教师";
    /** 写请求体只做一次 JSON → 类型转换，转换失败统一按 BAD_REQUEST 返回。 */
    private static final Gson GSON = new Gson();
    /**
     * 教师调课写请求体里绝不允许出现的字段：uid/教师/助教身份与强制标志。教师协议没有可替换人员
     * 的字段（服务端从原课次快照派生），也没有 force；出现任何一个是客户端伪造，直接拒绝。
     */
    private static final Set<String> FORGED_WRITE_FIELDS = Set.of(
            "uid", "teacherId", "teacherUid", "newTeacherUid",
            "assistantId", "assistantUid", "newAssistantUid", "force");

    private final TeacherCourseQueryService queries;
    private final TeacherAdjustmentApplicationService adjustments;

    public TeacherCourseHandler() {
        this(new TeacherCourseQueryService(), new TeacherAdjustmentApplicationService());
    }

    /**
     * 只读查询的构造：调课动作在该形态下报告“尚未开放”，与
     * {@link AdminCourseHandler} 对未接线服务的处理一致；生产入口使用无参构造。
     */
    public TeacherCourseHandler(TeacherCourseQueryService queries) {
        this(queries, null);
    }

    public TeacherCourseHandler(TeacherCourseQueryService queries,
                                TeacherAdjustmentApplicationService adjustments) {
        this.queries = queries == null ? new TeacherCourseQueryService() : queries;
        this.adjustments = adjustments;
    }

    public Message handle(Message request) {
        Message response = response(request);
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            return failure(response, MessageCode.UNAUTHORIZED, "登录会话已失效，请重新登录");
        }
        if (!TEACHER_ROLE.equals(session.getRole())) {
            return failure(response, MessageCode.FORBIDDEN, "仅教师可以访问教学班服务");
        }
        String action = request.getAction();
        if (action == null || action.isBlank()) {
            return failure(response, MessageCode.BAD_REQUEST, "Action 不能为空");
        }

        try {
            // 身份只来自会话；请求体中的任何归属字段都被忽略。
            String uid = session.getUsername();
            switch (action) {
                case TeacherCourseActions.LIST_TERMS ->
                        response.putData("terms", queries.listTerms(uid));
                case TeacherCourseActions.LIST_OFFERINGS -> {
                    Paging paging = paging(request);
                    response.putData("offerings", queries.listOfferings(uid,
                            integer(request, "academicYear"), integer(request, "semester"),
                            optionalText(request, "query"), paging.number(), paging.size()));
                }
                case TeacherCourseActions.GET_OFFERING -> response.putData("offering",
                        queries.getOffering(uid, decimalId(request, "offeringId")));
                case TeacherCourseActions.LIST_OFFERING_STUDENTS -> {
                    Paging paging = paging(request);
                    response.putData("students", queries.listOfferingStudents(uid,
                            decimalId(request, "offeringId"), optionalText(request, "query"),
                            enrollmentStatus(request), paging.number(), paging.size()));
                }
                case TeacherCourseActions.LIST_OFFERING_SCHEDULES -> response.putData("schedules",
                        queries.listOfferingSchedules(uid, decimalId(request, "offeringId")));
                case TeacherCourseActions.LOAD_TEACHING_SCHEDULE -> response.putData("schedule",
                        queries.loadTeachingSchedule(uid,
                                integer(request, "academicYear"), integer(request, "semester"),
                                optionalInteger(request, "week")));
                case TeacherCourseActions.GET_ADJUSTMENT_OPTIONS -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("options", service.options(uid, decimalId(request, "offeringId"),
                            decimalId(request, "originalOccurrenceId")));
                }
                case TeacherCourseActions.PREVIEW_ADJUSTMENT -> {
                    // 纯预检查：忽略 operationId，也不要求原因非空；权限由服务端重算。
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("conflicts", service.preview(uid,
                            adjustmentWrite(request)));
                }
                case TeacherCourseActions.SUBMIT_ADJUSTMENT -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    return mutation(response, service.submit(uid, adjustmentWrite(request)));
                }
                case TeacherCourseActions.WITHDRAW_ADJUSTMENT -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    return mutation(response, service.withdraw(uid, withdrawal(request)));
                }
                case TeacherCourseActions.GET_ADJUSTMENT_REQUEST -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("adjustmentRequest",
                            service.get(uid, decimalId(request, "requestId")));
                }
                case TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    Paging paging = paging(request);
                    response.putData("applications", service.listMine(uid,
                            adjustmentStatus(request), paging.number(), paging.size()));
                }
                default -> {
                    return failure(response, MessageCode.BAD_REQUEST, "不支持的教师课程操作");
                }
            }
            response.setCode(MessageCode.SUCCESS);
            return response;
        } catch (TeacherAccessPolicy.AccessDeniedException denied) {
            // 授权失败只表达“无权限”，与“对象不存在 / 空名单”区分，且不泄露内部关系。
            return failure(response, MessageCode.FORBIDDEN, denied.getMessage());
        } catch (IllegalArgumentException invalid) {
            return failure(response, MessageCode.BAD_REQUEST, invalid.getMessage());
        } catch (TeacherAdjustmentApplicationService.NotFoundException missing) {
            return failure(response, MessageCode.NOT_FOUND, missing.getMessage());
        } catch (TeacherAdjustmentApplicationService.ConflictException conflict) {
            // 与管理员调课审批同形：冲突类型化列表 + 最新可见实体（可能没有）。
            response.putData("conflicts", conflict.getConflicts());
            if (conflict.getEntity() != null) response.putData("latest", conflict.getEntity());
            return failure(response, MessageCode.CONFLICT, conflict.getMessage());
        } catch (DatabaseException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "教师课程服务暂不可用");
        } catch (RuntimeException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "服务端内部错误");
        }
    }

    private TeacherAdjustmentApplicationService adjustments() {
        if (adjustments == null) {
            throw new IllegalArgumentException("该教师操作尚未开放");
        }
        return adjustments;
    }

    /** 写操作响应：结果信封进 result，响应消息取自操作结果，与管理员课程写操作一致。 */
    private static Message mutation(Message response,
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result) {
        response.putData("result", result);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage(result.getMessage());
        return response;
    }

    /**
     * 解析调课写请求体。先拒绝身份/人员/force 伪造字段与非法原始类型，再交给 Gson；operationId
     * 对提交/撤销由服务校验 UUID（BAD_REQUEST 原样返回），预览忽略它。
     */
    private TeacherAdjustmentWriteDTO adjustmentWrite(Message request) {
        Map<String, Object> values = requestValues(request);
        requireDecimalText(values.get("offeringId"), "offeringId");
        Object rawTargets = values.get("targets");
        if (!(rawTargets instanceof List<?> targets)) {
            throw new IllegalArgumentException("targets 必须为数组");
        }
        for (Object raw : targets) {
            if (!(raw instanceof Map<?, ?> target)) {
                throw new IllegalArgumentException("targets 的元素必须为 JSON 对象");
            }
            requireDecimalText(target.get("originalOccurrenceId"), "originalOccurrenceId");
            Object targetDate = target.get("targetDate");
            if (targetDate != null && !(targetDate instanceof String)) {
                throw new IllegalArgumentException("targetDate 必须为 ISO 本地日期字符串");
            }
        }
        requireOptionalString(values, "operationId");
        requireOptionalString(values, "newClassroomId");
        requireOptionalString(values, "reason");
        return payload(request, TeacherAdjustmentWriteDTO.class);
    }

    private WithdrawTeacherAdjustmentRequestDTO withdrawal(Message request) {
        Map<String, Object> values = requestValues(request);
        requireOptionalString(values, "operationId");
        requireDecimalText(values.get("requestId"), "requestId");
        return payload(request, WithdrawTeacherAdjustmentRequestDTO.class);
    }

    /**
     * data.request 必须是 JSON 对象，且不得携带伪造身份/人员/强制字段：教师写协议没有这些字段，
     * 出现即拒绝，避免客户端以为可以替换人员或强制通过。
     */
    private static Map<String, Object> requestValues(Message request) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        for (String forged : FORGED_WRITE_FIELDS) {
            if (raw.containsKey(forged)) {
                throw new IllegalArgumentException(
                        forged + " 不是教师调课字段，教师不能指定人员或强制通过");
            }
        }
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) values.put(key, entry.getValue());
        }
        return values;
    }

    /** JSON → 写 DTO；类型不匹配（数字放进字符串字段、小数放进整数等）统一按 BAD_REQUEST 表达。 */
    private <T> T payload(Message request, Class<T> type) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        try {
            return GSON.fromJson(GSON.toJson(value), type);
        } catch (JsonParseException | IllegalStateException | NumberFormatException failure) {
            throw new IllegalArgumentException("request 字段格式无效", failure);
        }
    }

    /** BIGINT 标识只接受十进制字符串；数字会被 Gson 经 double 静默改写，必须在解析前拒绝。 */
    private static void requireDecimalText(Object value, String key) {
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
    }

    private static void requireOptionalString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
    }

    /** 我的调课申请筛选：缺省交给服务端默认 PENDING，未知值一律 400（四态，含教师撤销）。 */
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

    /** 请求里成对出现的 page/size；两者一起校验，避免页码与页大小各自越界。 */
    private record Paging(int number, int size) {
    }

    private static Paging paging(Message request) {
        int number = integer(request, "page");
        int size = integer(request, "size");
        if (number < 1) {
            throw new IllegalArgumentException("page 必须大于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须为 1 至 100");
        }
        // DAO 的 OFFSET 是 int；(page-1)*size 溢出的页码在这里就拒绝，绝不落到 SQL 层。
        if ((long) (number - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page 超出有效范围");
        }
        return new Paging(number, size);
    }

    /** enrollmentStatus 只接受缺省（NULL）、2（正常）与 3（退课）；其余一律视为非法输入。 */
    private static Integer enrollmentStatus(Message request) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get("enrollmentStatus") == null) {
            return null;
        }
        int status = integer(request, "enrollmentStatus");
        if (status != 2 && status != 3) {
            throw new IllegalArgumentException("enrollmentStatus 只接受 2（正常）或 3（退课）");
        }
        return status;
    }

    /**
     * week 可缺省：缺省表示“由服务端按教学日历决定当前周”。出现时必须是合法整数（越界由服务层判定，
     * 因为它们依赖教学日历的 minWeek/maxWeek）。
     */
    private static Integer optionalInteger(Message request, String key) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get(key) == null) return null;
        return integer(request, key);
    }

    private static void logFailure(String action, RuntimeException failure) {
        System.err.println("教师课程请求处理失败: action=" + action);
        failure.printStackTrace(System.err);
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

    /** BIGINT 标识在网络上是十进制字符串；非数字、非正数、超出范围都在这里拒绝。 */
    private static String decimalId(Message request, String key) {
        Object value = data(request, key);
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

    private static Object data(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }
}
