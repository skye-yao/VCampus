package handler;

import dto.course.CourseActions;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.CourseQueryService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;

public class CourseHandler {
    private final CourseQueryService service;

    public CourseHandler() {
        this(new CourseQueryService());
    }

    CourseHandler(CourseQueryService service) {
        this.service = service;
    }

    public Message handle(Message request) {
        Message response = response(request);
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            return failure(response, MessageCode.UNAUTHORIZED, "登录会话已失效，请重新登录");
        }
        if (!"学生".equals(session.getRole())) {
            return failure(response, MessageCode.FORBIDDEN, "仅学生可以访问选课服务");
        }
        String action = request.getAction();
        if (action == null || action.isBlank()) {
            return failure(response, MessageCode.BAD_REQUEST, "Action 不能为空");
        }

        try {
            String uid = session.getUsername();
            switch (action) {
                case CourseActions.LIST_TERMS -> response.putData("terms", service.listTerms(uid));
                case CourseActions.LIST_COURSES -> {
                    Term term = term(request);
                    response.putData("courses",
                            service.listCourses(uid, term.academicYear, term.semester));
                }
                case CourseActions.LIST_COURSE_OFFERINGS -> {
                    Term term = term(request);
                    long courseId = decimalId(request, "courseId");
                    response.putData("offerings", service.listCourseOfferings(
                            uid, term.academicYear, term.semester, courseId));
                }
                case CourseActions.LOAD_SELECTION_SNAPSHOT -> {
                    Term term = term(request);
                    response.putData("snapshot", service.loadSelectionSnapshot(
                            uid, term.academicYear, term.semester));
                }
                case CourseActions.LOAD_SCHEDULE -> {
                    Term term = term(request);
                    int week = positiveInt(request, "week");
                    response.putData("schedule", service.loadSchedule(
                            uid, term.academicYear, term.semester, week));
                }
                case CourseActions.LOAD_NOTICES -> {
                    Term term = term(request);
                    int week = positiveInt(request, "week");
                    response.putData("notices", service.loadNotices(
                            uid, term.academicYear, term.semester, week));
                }
                case CourseActions.LOAD_GRADES -> {
                    Term term = term(request);
                    response.putData("grades", service.loadGrades(
                            uid, term.academicYear, term.semester));
                }
                case CourseActions.LOAD_TRAINING_PLAN ->
                        response.putData("trainingPlan", service.loadTrainingPlan(uid));
                default -> {
                    return failure(response, MessageCode.BAD_REQUEST, "不支持的课程操作");
                }
            }
            response.setCode(MessageCode.SUCCESS);
            return response;
        } catch (IllegalArgumentException failure) {
            return failure(response, MessageCode.BAD_REQUEST, failure.getMessage());
        } catch (CourseQueryService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, "课程资源不存在");
        } catch (DatabaseException failure) {
            return failure(response, MessageCode.ERROR, "课程服务暂不可用");
        } catch (RuntimeException failure) {
            return failure(response, MessageCode.ERROR, "服务端内部错误");
        }
    }

    private static Message response(Message request) {
        Message response = new Message(MessageType.RESPONSE, "course", request.getAction());
        response.setUID(request.getUID());
        return response;
    }

    private static Message failure(Message response, MessageCode code, String message) {
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    private static Term term(Message request) {
        int academicYear = positiveInt(request, "academicYear");
        int semester = integer(request, "semester");
        if (semester < 1 || semester > 3) {
            throw new IllegalArgumentException("semester 必须是 1、2 或 3");
        }
        return new Term(academicYear, semester);
    }

    private static int positiveInt(Message request, String key) {
        int value = integer(request, key);
        if (value <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
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

    private static long decimalId(Message request, String key) {
        Object value = data(request, key);
        if (!(value instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " 必须为十进制字符串");
        }
        try {
            long parsed = Long.parseLong(text);
            if (parsed <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 超出 BIGINT 范围");
        }
    }

    private static Object data(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }

    private record Term(int academicYear, int semester) {
    }
}
