package handler;

import dto.course.CourseDTO;
import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseTermDTO;
import dto.course.GradeSummaryDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.TrainingPlanGroupDTO;
import network.MessageDispatcher;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.CourseQueryService;
import session.SessionManager;
import session.UserSession;

import java.util.List;

public final class CourseHandlerTest {
    private CourseHandlerTest() {
    }

    public static void main(String[] args) {
        FakeCourseQueryService service = new FakeCourseQueryService();
        CourseHandler handler = new CourseHandler(service);
        SessionManager sessions = SessionManager.getInstance();
        UserSession student = sessions.createSession("student-alpha", "学生");
        UserSession teacher = sessions.createSession("teacher-alpha", "教师");
        try {
            require(handler.handle(request("listTerms", null)).getCode() == MessageCode.UNAUTHORIZED,
                    "missing token must be unauthorized");
            require(handler.handle(request("listTerms", "invalid-token")).getCode()
                            == MessageCode.UNAUTHORIZED,
                    "invalid token must be unauthorized");
            require(handler.handle(request("listTerms", teacher.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "non-student role must be forbidden");

            Message malformedTerm = request("listCourses", student.getToken());
            malformedTerm.putData("academicYear", "bad-year");
            malformedTerm.putData("semester", 2);
            require(handler.handle(malformedTerm).getCode() == MessageCode.BAD_REQUEST,
                    "malformed term must be bad request");

            Message malformedId = termRequest("listCourseOfferings", student.getToken());
            malformedId.putData("courseId", "9.5");
            require(handler.handle(malformedId).getCode() == MessageCode.BAD_REQUEST,
                    "non-decimal course ID must be bad request");

            service.notFound = true;
            Message unknown = termRequest("listCourseOfferings", student.getToken());
            unknown.putData("courseId", "999999");
            require(handler.handle(unknown).getCode() == MessageCode.NOT_FOUND,
                    "unknown course must be not found");
            service.notFound = false;

            assertSuccessKey(handler, service, request("listTerms", student.getToken()), "terms");
            assertSuccessKey(handler, service, termRequest("listCourses", student.getToken()), "courses");
            Message offerings = termRequest("listCourseOfferings", student.getToken());
            offerings.putData("courseId", "1001");
            offerings.setSender("attacker");
            offerings.putData("uid", "student-beta");
            assertSuccessKey(handler, service, offerings, "offerings");
            require("student-alpha".equals(service.lastUid),
                    "service UID must come from the authenticated session");
            assertSuccessKey(handler, service,
                    termRequest("loadSelectionSnapshot", student.getToken()), "snapshot");
            Message schedule = termRequest("loadSchedule", student.getToken());
            schedule.putData("week", 1);
            assertSuccessKey(handler, service, schedule, "schedule");
            Message notices = termRequest("loadNotices", student.getToken());
            notices.putData("week", 1);
            assertSuccessKey(handler, service, notices, "notices");
            assertSuccessKey(handler, service, termRequest("loadGrades", student.getToken()), "grades");
            assertSuccessKey(handler, service,
                    request("loadTrainingPlan", student.getToken()), "trainingPlan");

            Message routed = new MessageDispatcher().dispatch(request("listTerms", "invalid-token"));
            require(routed.getCode() == MessageCode.UNAUTHORIZED,
                    "server dispatcher must route the course module");

            Message admin = new Message(MessageType.REQUEST, "courseAdmin", "listCourses");
            admin.setToken("invalid-token");
            require(new MessageDispatcher().dispatch(admin).getCode() == MessageCode.UNAUTHORIZED,
                    "server dispatcher must route the courseAdmin module");

            Message unsupported = handler.handle(request("unknownRead", student.getToken()));
            require(unsupported.getCode() == MessageCode.BAD_REQUEST,
                    "unknown action must be bad request");
        } finally {
            sessions.removeSession(student.getToken());
            sessions.removeSession(teacher.getToken());
        }
        System.out.println("Course handler test passed.");
    }

    private static void assertSuccessKey(CourseHandler handler, FakeCourseQueryService service,
                                         Message request, String key) {
        service.lastUid = null;
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                request.getAction() + " must succeed: " + response.getMessage());
        require(response.getData().size() == 1 && response.getData().containsKey(key),
                request.getAction() + " must use response key " + key);
        require(service.lastUid != null, request.getAction() + " must call the service");
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "course", action);
        request.setToken(token);
        return request;
    }

    private static Message termRequest(String action, String token) {
        Message request = request(action, token);
        request.putData("academicYear", 2026);
        request.putData("semester", 2);
        return request;
    }

    private static final class FakeCourseQueryService extends CourseQueryService {
        private String lastUid;
        private boolean notFound;

        @Override
        public List<CourseTermDTO> listTerms(String uid) {
            record(uid);
            return List.of(new CourseTermDTO(2026, 2, "2026-2027 秋学期"));
        }

        @Override
        public List<CourseDTO> listCourses(String uid, int year, int semester) {
            record(uid);
            return List.of();
        }

        @Override
        public List<CourseOfferingDTO> listCourseOfferings(
                String uid, int year, int semester, long courseId) {
            record(uid);
            if (notFound) throw new NotFoundException("course");
            return List.of();
        }

        @Override
        public CoursePlanSnapshotDTO loadSelectionSnapshot(String uid, int year, int semester) {
            record(uid);
            return new CoursePlanSnapshotDTO(new CourseTermDTO(year, semester, "term"),
                    List.of(), List.of(), List.of());
        }

        @Override
        public List<ScheduleEntryDTO> loadSchedule(String uid, int year, int semester, int week) {
            record(uid);
            return List.of();
        }

        @Override
        public List<CourseNoticeDTO> loadNotices(String uid, int year, int semester, int week) {
            record(uid);
            return List.of();
        }

        @Override
        public GradeSummaryDTO loadGrades(String uid, int year, int semester) {
            record(uid);
            return new GradeSummaryDTO("term", 0, 0, 0, 0, List.of());
        }

        @Override
        public List<TrainingPlanGroupDTO> loadTrainingPlan(String uid) {
            record(uid);
            return List.of();
        }

        private void record(String uid) {
            lastUid = uid;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
