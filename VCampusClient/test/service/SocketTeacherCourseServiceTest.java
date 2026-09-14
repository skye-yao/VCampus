package service;

import com.google.gson.Gson;
import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;

/**
 * 教师课程 Socket 服务的信封、泛型页解析、BIGINT 保真与错误映射。
 *
 * <p>用假 Transport 运行，不需要真实服务器：响应数据统一经过 Gson 洗成线格式，避免测试用
 * 对象引用冒充“服务器已解析成 DTO”的假象。
 */
public final class SocketTeacherCourseServiceTest {
    private static final String TOKEN = "teacher-token-123";
    private static final String OFFERING_ID = "9007199254740993";
    private static final String COURSE_ID = "9007199254740995";
    private static final String ENROLLMENT_ID = "9007199254740997";
    private static final String TEACHER_UID = "00001234";

    public static void main(String[] args) {
        ClientSession.getInstance().login("teacher-alpha", "教师", TOKEN, null);
        try {
            listTermsSendsEnvelopeAndMapsTerms();
            listOfferingsSendsFiltersAndParsesGenericPage();
            listOfferingsOmitsNullQuery();
            getOfferingParsesDetailAndKeepsBigIntegerId();
            listOfferingStudentsSendsFiltersAndParsesRosterPage();
            listOfferingStudentsOmitsOptionalFilters();
            listOfferingSchedulesUsesSchedulesKey();
            requestsNeverCarryClientSuppliedIdentity();
            nonSuccessBecomesStableException();
            nullResponseBecomesError();
            missingResponseKeyBecomesError();
        } finally {
            ClientSession.getInstance().logout();
        }
        System.out.println("SocketTeacherCourseServiceTest: PASS");
    }

    private static void listTermsSendsEnvelopeAndMapsTerms() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("terms", List.of(termDto(2025, 3))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        List<CourseTermDTO> terms = service.listTerms().join();
        requireEnvelope(transport, "listTerms");
        require(terms.size() == 1, "one term expected");
        require(terms.get(0).getAcademicYear() == 2025 && terms.get(0).getSemester() == 3,
                "the term must map its year and semester");
        require("2025-2026 春学期".equals(terms.get(0).getDisplayName()),
                "the term display name must map");
        require(transport.lastRequest.getData().size() == 0,
                "a read request must not invent extra parameters");
    }

    private static void listOfferingsSendsFiltersAndParsesGenericPage() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offerings",
                wireShaped(new TeacherPageDTO<>(List.of(offeringDto()), 121L, 2, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherPageDTO<TeacherOfferingDTO> page =
                service.listOfferings(2025, 3, "数据", 2, 20).join();
        requireEnvelope(transport, "listOfferings");
        require(Integer.valueOf(2025).equals(transport.lastRequest.getData("academicYear")),
                "academicYear must travel as an Integer");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("semester")),
                "semester must travel as an Integer");
        require("数据".equals(transport.lastRequest.getData("query")),
                "the search text must travel unchanged");
        require(Integer.valueOf(2).equals(transport.lastRequest.getData("page")),
                "page must travel under the page key");
        require(Integer.valueOf(20).equals(transport.lastRequest.getData("size")),
                "size must travel under the size key");

        require(page.getTotalCount() == 121L,
                "totalCount must survive the generic page parse, not the row count");
        require(page.getPage() == 2 && page.getSize() == 20,
                "the page metadata must survive the generic page parse");
        require(page.getItems().size() == 1, "one offering expected");
        TeacherOfferingDTO offering = page.getItems().get(0);
        require(OFFERING_ID.equals(offering.getOfferingId()),
                "the offering ID must stay the exact decimal string");
        require(COURSE_ID.equals(offering.getCourseId()), "the course ID must stay exact");
        require(offering.isCanEditGrades() && offering.isCanRequestAdjustment(),
                "the capability fields returned by the server must survive the parse");
    }

    private static void listOfferingsOmitsNullQuery() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offerings",
                wireShaped(new TeacherPageDTO<>(List.of(), 0L, 1, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        service.listOfferings(2025, 2, null, 1, 20).join();
        requireEnvelope(transport, "listOfferings");
        require(transport.lastRequest.getData("query") == null,
                "a null query must be omitted from the request");
        require(null == transport.lastRequest.getData("enrollmentStatus"),
                "a read must not invent an enrollmentStatus filter");
    }

    private static void getOfferingParsesDetailAndKeepsBigIntegerId() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offering", wireShaped(detailDto())));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherOfferingDetailDTO detail = service.getOffering(OFFERING_ID).join();
        requireEnvelope(transport, "getOffering");
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "the offeringId must travel as the exact decimal string");
        require(detail.getOffering() != null
                        && OFFERING_ID.equals(detail.getOffering().getOfferingId()),
                "the detail must keep its offering");
        require(detail.getTeachers().size() == 2, "the detail must keep every teacher");
        require(TEACHER_UID.equals(detail.getTeachers().get(0).getBusinessId()),
                "the teacher UID must keep its leading zeroes");
        require("计算机科学与工程学院".equals(detail.getOfferingCollege()),
                "the offering college must map, null stays null");
        require("专业核心课，含实验".equals(detail.getDescription()),
                "the description must map");
    }

    private static void listOfferingStudentsSendsFiltersAndParsesRosterPage() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("students", wireShaped(
                new TeacherPageDTO<>(List.of(enrolledRow(), droppedRow()), 2L, 1, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherPageDTO<TeacherRosterRowDTO> page =
                service.listOfferingStudents(OFFERING_ID, "张三", 3, 1, 20).join();
        requireEnvelope(transport, "listOfferingStudents");
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "the offeringId must travel as the exact decimal string");
        require("张三".equals(transport.lastRequest.getData("query")),
                "the search text must travel unchanged");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("enrollmentStatus")),
                "a dropped-status filter must travel as an Integer");

        require(page.getTotalCount() == 2L, "the roster total must survive the parse");
        require(page.getItems().size() == 2, "both roster rows expected");
        TeacherRosterRowDTO enrolled = page.getItems().get(0);
        require(ENROLLMENT_ID.equals(enrolled.getEnrollmentId()),
                "the enrollment ID must stay the exact decimal string");
        require("ENROLLED".equals(enrolled.getEnrollmentStatus())
                        && enrolled.getDroppedAt() == null,
                "an active row must keep a null droppedAt");
        TeacherRosterRowDTO dropped = page.getItems().get(1);
        require("DROPPED".equals(dropped.getEnrollmentStatus())
                        && "2026-09-10T02:30:00Z".equals(dropped.getDroppedAt()),
                "a dropped row must keep its UTC drop time");
    }

    private static void listOfferingStudentsOmitsOptionalFilters() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("students",
                wireShaped(new TeacherPageDTO<>(List.of(), 0L, 1, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        service.listOfferingStudents(OFFERING_ID, null, null, 1, 20).join();
        requireEnvelope(transport, "listOfferingStudents");
        require(transport.lastRequest.getData("query") == null,
                "a null query must be omitted from the roster request");
        require(transport.lastRequest.getData("enrollmentStatus") == null,
                "a null enrollmentStatus must be omitted, keeping the server-side default");
    }

    private static void listOfferingSchedulesUsesSchedulesKey() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("schedules",
                List.of(wireShaped(arrangementDto()))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        List<ScheduleArrangementDTO> arrangements =
                service.listOfferingSchedules(OFFERING_ID).join();
        requireEnvelope(transport, "listOfferingSchedules");
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "the offeringId must travel as the exact decimal string");
        require(arrangements.size() == 1, "one arrangement expected");
        ScheduleArrangementDTO arrangement = arrangements.get(0);
        require("9503".equals(arrangement.getArrangementId()),
                "the arrangement ID must stay the exact decimal string");
        require(OFFERING_ID.equals(arrangement.getOfferingId()),
                "the arrangement must keep its offering ID");
        require("ACTIVE".equals(arrangement.getStatus()),
                "only the published-plan arrangement status must survive");
        require(arrangement.getSlots().size() == 1
                        && arrangement.getSlots().get(0).getStartPeriod() == 1,
                "the arrangement slots must map");
    }

    /** The learner identity must never be smuggled through the request body. */
    private static void requestsNeverCarryClientSuppliedIdentity() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("terms", List.of()));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        service.listTerms().join();
        Message request = transport.lastRequest;
        require(request.getData("uid") == null && request.getData("teacherId") == null
                        && request.getData("sender") == null,
                "the request body must not carry a client-supplied identity");
        require(request.getSender() == null,
                "the service must not populate a sender; the transport attaches the token");
        require(TOKEN.equals(request.getToken()),
                "the token must be attached from ClientSession");
    }

    private static void nonSuccessBecomesStableException() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.FORBIDDEN);
            message.setMessage("没有查看该教学班的权限");
        });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        boolean thrown = false;
        try {
            service.getOffering(OFFERING_ID).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.FORBIDDEN,
                        "the denial code must survive the mapping");
                require("没有查看该教学班的权限".equals(error.getMessage()),
                        "the server message must survive the mapping");
            }
        }
        require(thrown, "a non-success response must fail with TeacherCourseServiceException");
    }

    private static void nullResponseBecomesError() {
        FakeTransport transport = new FakeTransport();
        transport.respondNull();
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        boolean thrown = false;
        try {
            service.listTerms().join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.ERROR,
                        "a null response must be an error");
                require("教师课程服务无响应".equals(error.getMessage()),
                        "a null response must use a stable message");
            }
        }
        require(thrown, "a null response must fail with TeacherCourseServiceException");
    }

    private static void missingResponseKeyBecomesError() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> { });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        boolean thrown = false;
        try {
            service.listTerms().join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error) {
                thrown = true;
                require(error.getMessage() != null && error.getMessage().contains("terms"),
                        "a missing response key must name the missing key");
            }
        }
        require(thrown, "a missing response key must fail with TeacherCourseServiceException");
    }

    private static void requireEnvelope(FakeTransport transport, String action) {
        Message request = transport.lastRequest;
        require(request.getType() == MessageType.REQUEST, "a teacher request must be REQUEST");
        require("courseTeacher".equals(request.getModule()), "module must be courseTeacher");
        require(action.equals(request.getAction()), "action must be " + action);
        require(TOKEN.equals(request.getToken()),
                "the token must be attached from ClientSession");
        require(request.getCode() == MessageCode.SUCCESS, "request code must be SUCCESS");
    }

    /** Mirrors the real transport: a socket response carries data as Gson maps, not typed DTOs. */
    private static Object wireShaped(Object dto) {
        Gson gson = new Gson();
        return gson.fromJson(gson.toJson(dto), Object.class);
    }

    private static CourseTermDTO termDto(int academicYear, int semester) {
        return new CourseTermDTO(academicYear, semester, "2025-2026 春学期");
    }

    private static TeacherOfferingDTO offeringDto() {
        return new TeacherOfferingDTO(OFFERING_ID, "CS203-01", "数据结构 CS203-01", COURSE_ID,
                "CS203", "数据结构", 4.0, 2025, 3, 58, 60, "ACTIVE", true, true);
    }

    private static TeacherOfferingDetailDTO detailDto() {
        return new TeacherOfferingDetailDTO(offeringDto(),
                List.of(new ScheduleResourceDTO(TEACHER_UID, TEACHER_UID, "陈老师", "teacher", 0),
                        new ScheduleResourceDTO("8002", "00009012", "王助教", "teacher", 0)),
                "计算机科学与工程学院", "专业核心课，含实验");
    }

    private static TeacherRosterRowDTO enrolledRow() {
        return new TeacherRosterRowDTO(ENROLLMENT_ID, "00005678", "张三", "计算机科学与技术",
                "ENROLLED", "2026-09-01T01:00:00Z", null);
    }

    private static TeacherRosterRowDTO droppedRow() {
        return new TeacherRosterRowDTO("9007199254740998", "00005679", "李四", "软件工程",
                "DROPPED", "2026-09-01T01:05:00Z", "2026-09-10T02:30:00Z");
    }

    private static ScheduleArrangementDTO arrangementDto() {
        return new ScheduleArrangementDTO("9503", "7001", OFFERING_ID,
                new ScheduleResourceDTO("8001", TEACHER_UID, "陈老师", "teacher", 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                List.of(new ScheduleSlotDTO(1, 1, 2)), 1, 16, "ACTIVE", 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeTransport implements TeacherCourseTransport {
        private final Deque<Consumer<Message>> responseBuilders = new ArrayDeque<>();
        private Message lastRequest;
        private boolean respondNull;

        private void respond(Consumer<Message> builder) {
            responseBuilders.addLast(builder);
        }

        private void respondNull() {
            respondNull = true;
        }

        @Override
        public CompletableFuture<Message> send(Message request) {
            lastRequest = request;
            if (respondNull) return CompletableFuture.completedFuture(null);
            Message response = new Message(MessageType.RESPONSE, "courseTeacher",
                    request.getAction());
            response.setUID(request.getUID());
            if (responseBuilders.isEmpty()) {
                response.setCode(MessageCode.SUCCESS);
            } else {
                responseBuilders.removeFirst().accept(response);
            }
            return CompletableFuture.completedFuture(response);
        }
    }
}
