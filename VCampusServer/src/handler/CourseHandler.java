package handler;

import dto.course.CourseActions;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseTermDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.CourseEventDispatcher;
import service.CourseQueryService;
import service.CourseSelectionService;
import service.CourseWaitlistService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;

/**
* Internal course-management type CourseHandler.
*/
public class CourseHandler {
    private final CourseQueryService service;
    private final CourseSelectionService selectionService;
    private final CourseWaitlistService waitlistService;
    private final CourseEventDispatcher eventDispatcher;

    /**
    * Handles the course-management responsibility of CourseHandler.
    */
    public CourseHandler() {
        this(defaultServices(), null);
    }

    /**
    * Handles the course-management responsibility of CourseHandler.
    */
    public CourseHandler(CourseEventDispatcher eventDispatcher) {
        this(defaultServices(), eventDispatcher);
    }

    /**
    * Handles the course-management responsibility of CourseHandler.
    */
    public CourseHandler(CourseWaitlistService waitlistService,
                         CourseEventDispatcher eventDispatcher) {
        this(new CourseQueryService(), waitlistService.newSelectionService(),
                waitlistService, eventDispatcher);
    }

    private CourseHandler(DefaultServices services, CourseEventDispatcher eventDispatcher) {
        this(services.queryService, services.selectionService, services.waitlistService,
                eventDispatcher);
    }

    CourseHandler(CourseQueryService service) {
        this(service, new CourseSelectionService(), new CourseWaitlistService(), null);
    }

    CourseHandler(CourseQueryService service, CourseSelectionService selectionService) {
        this(service, selectionService, new CourseWaitlistService(), null);
    }

    CourseHandler(CourseQueryService service, CourseSelectionService selectionService,
                  CourseWaitlistService waitlistService) {
        this(service, selectionService, waitlistService, null);
    }

    CourseHandler(CourseQueryService service, CourseSelectionService selectionService,
                  CourseWaitlistService waitlistService, CourseEventDispatcher eventDispatcher) {
        this.service = service;
        this.selectionService = selectionService;
        this.waitlistService = waitlistService;
        this.eventDispatcher = eventDispatcher;
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
                    // week 可缺省：缺省即“由服务端按教学日历决定当前周”，与教师端
                    // courseTeacher.loadTeachingSchedule 同一约定；出现时行为完全不变。
                    response.putData("schedule", service.loadSchedule(
                            uid, term.academicYear, term.semester, optionalInteger(request, "week")));
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
                case CourseActions.ADD_TO_PLAN -> {
                    Term term = term(request);
                    return mutationResponse(response, selectionService.addToPlan(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.REMOVE_FROM_PLAN -> {
                    Term term = term(request);
                    return mutationResponse(response, selectionService.removeFromPlan(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.SELECT_OFFERING -> {
                    Term term = term(request);
                    return mutationResponse(response, selectionService.selectOffering(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.DROP_OFFERING -> {
                    Term term = term(request);
                    return mutationResponse(response, selectionService.dropOffering(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.JOIN_WAITLIST -> {
                    Term term = term(request);
                    return mutationResponse(response, waitlistService.joinWaitlist(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.CANCEL_WAITLIST -> {
                    Term term = term(request);
                    return mutationResponse(response, waitlistService.cancelWaitlist(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId")));
                }
                case CourseActions.RESOLVE_WAITLIST_OFFER -> {
                    Term term = term(request);
                    return mutationResponse(response, waitlistService.resolveWaitlistOffer(uid,
                            term.dto(), decimalId(request, "offeringId"),
                            text(request, "operationId"), text(request, "decision")));
                }
                case CourseActions.ACK_COURSE_EVENT -> {
                    if (eventDispatcher == null) {
                        return failure(response, MessageCode.ERROR, "课程事件确认服务不可用");
                    }
                    long eventId = decimalId(request, "eventId");
                    boolean acked = eventDispatcher.acknowledge(uid, eventId);
                    response.putData("acked", acked);
                    response.setCode(MessageCode.SUCCESS);
                    response.setMessage(acked ? "已确认课程事件" : "未找到可确认的课程事件");
                    return response;
                }
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
        } catch (CourseSelectionService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, "课程资源不存在");
        } catch (CourseSelectionService.ConflictException failure) {
            return failure(response, MessageCode.CONFLICT, failure.getMessage());
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

    private static Message mutationResponse(Message response, CourseMutationResultDTO result) {
        response.putData("result", result);
        response.setCode(isConflict(result.getOutcomeCode())
                ? MessageCode.CONFLICT : MessageCode.SUCCESS);
        response.setMessage(result.getMessage());
        return response;
    }

    private static boolean isConflict(String outcomeCode) {
        return "FULL".equals(outcomeCode)
                || "SAME_COURSE_CONFLICT".equals(outcomeCode)
                || "SCHEDULE_CONFLICT".equals(outcomeCode)
                || "WINDOW_CLOSED".equals(outcomeCode)
                || "SELECTION_CLOSED".equals(outcomeCode)
                || "DROP_CLOSED".equals(outcomeCode)
                || "WAITLIST_ACTIVE".equals(outcomeCode)
                || "WAITLIST_OFFER_EXPIRED".equals(outcomeCode);
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

    /**
    * week 可缺省：缺省表示“由服务端按教学日历决定当前周”。出现时必须是合法整数（越界由服务层
    * 判定，因为它依赖教学日历的 minWeek/maxWeek）。与教师端 {@code TeacherCourseHandler} 同形。
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

    /**
    * Internal course-management type Term.
    */
    private record Term(int academicYear, int semester) {
        private CourseTermDTO dto() {
            return new CourseTermDTO(academicYear, semester, "");
        }
    }

    private static DefaultServices defaultServices() {
        CourseWaitlistService waitlistService = new CourseWaitlistService();
        return new DefaultServices(new CourseQueryService(),
                waitlistService.newSelectionService(), waitlistService);
    }

    /**
    * Internal course-management type DefaultServices.
    */
    private record DefaultServices(CourseQueryService queryService,
                                   CourseSelectionService selectionService,
                                   CourseWaitlistService waitlistService) {
    }
}
