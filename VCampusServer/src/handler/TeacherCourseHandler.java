package handler;

import dto.course.teacher.TeacherCourseActions;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherCourseQueryService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;

/**
 * 教师端课程查询（module {@code courseTeacher}）的 TCP 入口。
 *
 * <p>处理顺序固定为：认证 → 角色 → 参数 → 调用查询服务。教师 UID 只取自服务端校验过的
 * Session（{@link UserSession#getUsername()}），请求体里的 {@code uid}/{@code teacherId}/
 * {@code sender} 不参与任何判定，防止客户端用请求体替换真实身份。
 *
 * <p>本阶段只读：响应键为 terms、offerings、offering、students、schedules、schedule。列表类的
 * items 由 {@link dto.course.teacher.TeacherPageDTO} 承载（含 totalCount/page/size），客户端用
 * TypeToken 解析泛型页。数据库异常只写服务端日志，响应里不出现 SQL、表名或堆栈。
 */
public class TeacherCourseHandler {
    private static final String MODULE = "courseTeacher";
    /** 与设计第 3 节一致：size 为 1..100。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final String TEACHER_ROLE = "教师";

    private final TeacherCourseQueryService queries;

    public TeacherCourseHandler() {
        this(new TeacherCourseQueryService());
    }

    public TeacherCourseHandler(TeacherCourseQueryService queries) {
        this.queries = queries == null ? new TeacherCourseQueryService() : queries;
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
        } catch (DatabaseException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "教师课程服务暂不可用");
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
