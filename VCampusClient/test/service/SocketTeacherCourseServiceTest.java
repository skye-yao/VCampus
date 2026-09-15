package service;

import com.google.gson.Gson;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
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
    private static final String CALENDAR_ID = "9007199254740001";
    private static final String OCCURRENCE_ID = "9201";
    private static final String ADJUSTMENT_ID = "9301";
    private static final String SCHEDULE_DATE = "2026-10-26";
    private static final String REQUEST_ID = "9007199254740994";
    private static final String OPERATION_ID = "30000000-0000-0000-0000-000000000001";
    private static final String CLASSROOM_ID = "8103";
    private static final String TARGET_DATE = "2026-11-04";
    private static final String ROSTER_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
            loadTeachingScheduleSendsScalarArguments();
            loadTeachingScheduleMapsEveryField();
            loadTeachingScheduleMissingScheduleKeyBecomesError();
            loadTeachingScheduleKeepsServerCodeAndMessage();
            unsupportedDisplayKindIsRejectedNotDefaulted();
            adjustmentOptionsUseTheOptionsKeyAndKeepExactIds();
            adjustmentPreviewMapsConflictsAndCanSubmit();
            adjustmentWriteMethodsMapTheOperationResult();
            adjustmentWithdrawMapsTheOperationResult();
            adjustmentDetailMapsFourStateStatusAndTargetDate();
            adjustmentApplicationsParseTheGenericPageAndOmitNullStatus();
            adjustmentConflictKeepsTypedConflictsAndLatestDetail();
            gradeOfferingsParseTheGenericPage();
            gradeBookMapsScoresSchemeAndNullableTotals();
            gradeWritesUseTheirOwnActionAndMapTheResult();
            gradeConflictKeepsTheLatestGradeBook();
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

    /**
     * 课表请求只带标量参数：{@code week} 为 null 时不出现该键（由服务端按教学日历决定），
     * 非 null 时以 Integer 出行。
     */
    private static void loadTeachingScheduleSendsScalarArguments() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("schedule",
                wireShaped(scheduleWeek(8, 8))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        service.loadTeachingSchedule(2025, 3, null).join();
        requireEnvelope(transport, "loadTeachingSchedule");
        require(Integer.valueOf(2025).equals(transport.lastRequest.getData("academicYear")),
                "academicYear must travel as an Integer");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("semester")),
                "semester must travel as an Integer");
        require(!transport.lastRequest.getData().containsKey("week"),
                "a null week must be omitted so the server picks the calendar week");
        require(transport.lastRequest.getData().size() == 2,
                "a timetable request must not invent extra parameters, saw "
                        + transport.lastRequest.getData().keySet());

        transport.respond(message -> message.putData("schedule",
                wireShaped(scheduleWeek(9, 8))));
        service.loadTeachingSchedule(2025, 3, 9).join();
        require(Integer.valueOf(9).equals(transport.lastRequest.getData("week")),
                "an explicit week must travel as an Integer");
        require(!transport.lastRequest.getData().containsKey("uid"),
                "the timetable request must never carry a client-supplied identity");
    }

    /** 三层字段（dates/periods/entries）与可空的 currentWeek/adjustmentId 都要逐字段映射。 */
    private static void loadTeachingScheduleMapsEveryField() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("schedule",
                wireShaped(scheduleWeek(8, 8))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherScheduleWeekDTO week = service.loadTeachingSchedule(2025, 3, 8).join();
        require(CALENDAR_ID.equals(week.getCalendarId()),
                "calendarId must stay the exact decimal string");
        require("Asia/Shanghai".equals(week.getTimezone()), "timezone must stay the IANA name");
        require(week.getWeek() == 8 && week.getMinWeek() == 1 && week.getMaxWeek() == 16,
                "the week bounds must map");
        require(Integer.valueOf(8).equals(week.getCurrentWeek()), "currentWeek must map");

        require(week.getDates().size() == 1, "the dates layer must map");
        TeacherCalendarDateDTO date = week.getDates().get(0);
        require(SCHEDULE_DATE.equals(date.getDate()) && date.getWeek() == 8
                        && date.getTeachingWeekday() == 1 && date.isTeachingDay(),
                "every calendar-date field must map");

        require(week.getPeriods().size() == 1, "the periods layer must map");
        TeacherPeriodDTO period = week.getPeriods().get(0);
        require(SCHEDULE_DATE.equals(period.getDate()) && period.getPeriod() == 1
                        && "08:00:00".equals(period.getStartTime())
                        && "08:45:00".equals(period.getEndTime()),
                "every period field must map, times included");

        require(week.getEntries().size() == 1, "the entries layer must map");
        TeacherScheduleEntryDTO entry = week.getEntries().get(0);
        require(OCCURRENCE_ID.equals(entry.getOccurrenceId()),
                "the occurrence ID must stay exact");
        require(OFFERING_ID.equals(entry.getOfferingId()), "the offering ID must stay exact");
        require("CS203".equals(entry.getCourseCode()) && "数据结构".equals(entry.getCourseName()),
                "the course fields must map");
        require("陈老师, 王助教".equals(entry.getTeacher()),
                "the teacher and assistant must render as the server joins them");
        require("A-101".equals(entry.getLocation()), "the location must map");
        require(SCHEDULE_DATE.equals(entry.getLocalDate()),
                "localDate must stay an ISO local date, never a UTC instant");
        require(entry.getWeek() == 8 && entry.getDayOfWeek() == 1,
                "the week and calendar weekday must map");
        require(entry.getStartPeriod() == 1 && entry.getEndPeriod() == 2,
                "the period span must map");
        require(entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL,
                "a known displayKind must map to the existing enum");
        require(ADJUSTMENT_ID.equals(entry.getAdjustmentId()),
                "the adjustment ID must stay exact");
        require("周一 第1-2节 A-101".equals(entry.getOriginalScheduleText()),
                "the original schedule text must survive untouched");
        require("周三 第3-4节 B-203".equals(entry.getAdjustedScheduleText()),
                "the adjusted schedule text must survive untouched");
        require("教师出差".equals(entry.getAdjustmentReason()),
                "the adjustment reason must survive untouched");
        require(!entry.isCanRequestAdjustment(), "the capability flag must map");

        transport.respond(message -> message.putData("schedule",
                wireShaped(scheduleWeek(8, null))));
        TeacherScheduleWeekDTO outsideTerm = service.loadTeachingSchedule(2025, 2, 8).join();
        require(outsideTerm.getCurrentWeek() == null,
                "a null currentWeek must stay null, never a default");
        require(outsideTerm.getWeek() == 8,
                "the displayed week must still map when currentWeek is null");
    }

    private static void loadTeachingScheduleMissingScheduleKeyBecomesError() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> { });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        boolean thrown = false;
        try {
            service.loadTeachingSchedule(2025, 3, 8).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error) {
                thrown = true;
                require("缺少响应字段: schedule".equals(error.getMessage()),
                        "a missing schedule key must name the missing key, saw "
                                + error.getMessage());
            }
        }
        require(thrown, "a missing schedule key must fail with TeacherCourseServiceException");
    }

    private static void loadTeachingScheduleKeepsServerCodeAndMessage() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.NOT_FOUND);
            message.setMessage("该学期暂无已发布的教学日历");
        });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        boolean thrown = false;
        try {
            service.loadTeachingSchedule(2025, 1, null).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.NOT_FOUND,
                        "the server code must survive the timetable mapping");
                require("该学期暂无已发布的教学日历".equals(error.getMessage()),
                        "the server message must survive the timetable mapping");
            }
        }
        require(thrown, "a non-success timetable response must fail with the stable exception");
    }

    /**
     * 未知 displayKind 绝不能退化成 NORMAL：Gson 会把无法识别的枚举常量解析成 null，所以实现必须
     * 先看原始 JSON。缺失与显式 null 走同一条错误路径，消息里要带原始非法值。
     */
    private static void unsupportedDisplayKindIsRejectedNotDefaulted() {
        requireUnsupportedKind("\"displayKind\":\"RESCHEDULED\",", "RESCHEDULED");
        requireUnsupportedKind("\"displayKind\":\"rescheduled\",", "rescheduled");
        requireUnsupportedKind("", "空值");
        requireUnsupportedKind("\"displayKind\":null,", "空值");
    }

    private static void requireUnsupportedKind(String kindField, String expectedRaw) {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("schedule",
                new Gson().fromJson(scheduleJson(kindField), Object.class)));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        try {
            TeacherScheduleWeekDTO mapped = service.loadTeachingSchedule(2025, 3, 8).join();
            throw new AssertionError("an unsupported displayKind must not be accepted, mapped "
                    + mapped.getEntries().get(0).getDisplayKind());
        } catch (CompletionException failure) {
            if (!(failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error)) {
                throw new AssertionError("unexpected cause " + failure.getCause(),
                        failure.getCause());
            }
            String message = error.getMessage();
            require(message != null && message.contains(expectedRaw),
                    "the mapping error must name the raw value " + expectedRaw + ", saw "
                            + message);
            require(message.contains("课表展示类型"),
                    "the mapping error must be a clear display-kind error, saw " + message);
        }
    }

    private static String scheduleJson(String displayKindField) {
        return "{\"calendarId\":\"" + CALENDAR_ID + "\",\"timezone\":\"Asia/Shanghai\","
                + "\"week\":8,\"minWeek\":1,\"maxWeek\":16,\"currentWeek\":8,"
                + "\"dates\":[],\"periods\":[],\"entries\":[{" + displayKindField
                + "\"occurrenceId\":\"" + OCCURRENCE_ID + "\",\"offeringId\":\"" + OFFERING_ID
                + "\",\"courseCode\":\"CS203\",\"courseName\":\"数据结构\","
                + "\"teacher\":\"陈老师\",\"location\":\"A-101\",\"localDate\":\""
                + SCHEDULE_DATE + "\",\"week\":8,\"dayOfWeek\":1,\"startPeriod\":1,"
                + "\"endPeriod\":2,\"adjustmentId\":null,\"originalScheduleText\":null,"
                + "\"adjustedScheduleText\":null,\"adjustmentReason\":null,"
                + "\"canRequestAdjustment\":true}]}";
    }

    private static TeacherScheduleWeekDTO scheduleWeek(int week, Integer currentWeek) {
        return new TeacherScheduleWeekDTO(CALENDAR_ID, "Asia/Shanghai", week, 1, 16, currentWeek,
                List.of(new TeacherCalendarDateDTO(SCHEDULE_DATE, week, 1, true)),
                List.of(new TeacherPeriodDTO(SCHEDULE_DATE, 1, "08:00:00", "08:45:00")),
                List.of(new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203", "数据结构",
                        "陈老师, 王助教", "A-101", SCHEDULE_DATE, week, 1, 1, 2,
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, ADJUSTMENT_ID,
                        "周一 第1-2节 A-101", "周三 第3-4节 B-203", "教师出差", false)));
    }

    private static void adjustmentOptionsUseTheOptionsKeyAndKeepExactIds() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("options",
                wireShaped(new TeacherAdjustmentOptionsDTO(CALENDAR_ID, "Asia/Shanghai",
                        List.of(new TeacherCalendarDateDTO("2026-11-04", 9, 3, true)),
                        List.of(new TeacherPeriodDTO("2026-11-04", 3, "10:00:00", "10:45:00")),
                        List.of(new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60))))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherAdjustmentOptionsDTO options =
                service.getAdjustmentOptions(OFFERING_ID, OCCURRENCE_ID).join();
        requireEnvelope(transport, "getAdjustmentOptions");
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "the offeringId must travel as the exact decimal string");
        require(OCCURRENCE_ID.equals(transport.lastRequest.getData("originalOccurrenceId")),
                "the originalOccurrenceId must travel as the exact decimal string");
        require(transport.lastRequest.getData("uid") == null,
                "the options read must never carry a client-supplied identity");
        require(CALENDAR_ID.equals(options.getCalendarId())
                        && "Asia/Shanghai".equals(options.getTimezone()),
                "the calendar identity and timezone must map");
        require(options.getDates().size() == 1 && "2026-11-04".equals(
                        options.getDates().get(0).getDate()),
                "the teaching dates must map");
        require(options.getPeriods().size() == 1 && options.getPeriods().get(0).getPeriod() == 3,
                "the period template must map");
        require(options.getClassrooms().size() == 1
                        && "8103".equals(options.getClassrooms().get(0).getResourceId()),
                "the classroom resources must map with exact IDs");
    }

    private static void adjustmentPreviewMapsConflictsAndCanSubmit() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("conflicts", wireShaped(
                new TeacherAdjustmentPreviewDTO(List.of(adjustmentConflict()), false))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherAdjustmentWriteDTO write = writeDto();
        TeacherAdjustmentPreviewDTO preview = service.previewAdjustment(write).join();
        requireEnvelope(transport, "previewAdjustment");
        require(transport.lastRequest.getData("request") == write,
                "the typed write DTO must travel under request unchanged");
        require(!preview.isCanSubmit(), "the canSubmit flag must survive the mapping");
        require(preview.getConflicts().size() == 1
                        && preview.getConflicts().get(0).getSeverity()
                        == ScheduleConflictSeverityDTO.BLOCKING,
                "the typed conflicts must survive the mapping");

        transport.respond(message -> message.putData("conflicts",
                wireShaped(new TeacherAdjustmentPreviewDTO(List.of(), true))));
        require(service.previewAdjustment(write).join().isCanSubmit(),
                "a conflict-free preview must report canSubmit");
    }

    private static void adjustmentWriteMethodsMapTheOperationResult() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", wireShaped(
                new TeacherOperationResultDTO<>(OPERATION_ID, "调课申请已提交",
                        adjustmentDetail(), false))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherAdjustmentWriteDTO write = writeDto();
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                service.submitAdjustment(write).join();
        requireEnvelope(transport, "submitAdjustment");
        require(transport.lastRequest.getData("request") == write,
                "the submit must travel as the typed write DTO under request");
        require(transport.lastRequest.getData("uid") == null,
                "the submit must not carry a client-supplied identity");
        require(OPERATION_ID.equals(result.getOperationId())
                        && "调课申请已提交".equals(result.getMessage())
                        && !result.isReplayed(),
                "the operation result envelope must map");
        require(result.getValue() != null && REQUEST_ID.equals(result.getValue().getRequestId())
                        && result.getValue().getVersion() == 1,
                "the write result value must carry the created detail");
    }

    private static void adjustmentWithdrawMapsTheOperationResult() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", wireShaped(
                new TeacherOperationResultDTO<>(OPERATION_ID, "调课申请已撤销",
                        withdrawnDetail(), false))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        WithdrawTeacherAdjustmentRequestDTO withdrawal =
                new WithdrawTeacherAdjustmentRequestDTO(OPERATION_ID, REQUEST_ID, 1);
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                service.withdrawAdjustment(withdrawal).join();
        requireEnvelope(transport, "withdrawAdjustment");
        require(transport.lastRequest.getData("request") == withdrawal,
                "the withdraw must travel as the typed DTO under request");
        require(result.getValue() != null
                        && result.getValue().getStatus() == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the withdrawn status must map from the write result");
    }

    private static void adjustmentDetailMapsFourStateStatusAndTargetDate() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("adjustmentRequest",
                wireShaped(withdrawnDetail())));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        AdjustmentRequestDetailDTO detail = service.getAdjustmentRequest(REQUEST_ID).join();
        requireEnvelope(transport, "getAdjustmentRequest");
        require(REQUEST_ID.equals(transport.lastRequest.getData("requestId")),
                "the detail read must send the exact decimal request ID");
        require(detail.getStatus() == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the four-state status must map, WITHDRAWN included");
        require(detail.getVersion() == 2, "the version needed for withdraw must map");
        require(detail.getTargets().size() == 1
                        && TARGET_DATE.equals(detail.getTargets().get(0).getTargetDate()),
                "each target must keep its ISO local target date");
        require(detail.getNewTeacher() == null && detail.getNewAssistant() == null,
                "teacher-submitted requests never carry a substituted person");
    }

    private static void adjustmentApplicationsParseTheGenericPageAndOmitNullStatus() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("applications",
                wireShaped(new TeacherPageDTO<>(List.of(adjustmentSummary()), 7L, 2, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherPageDTO<AdjustmentRequestSummaryDTO> page = service.listMyAdjustmentRequests(
                AdjustmentRequestStatusDTO.WITHDRAWN, 2, 20).join();
        requireEnvelope(transport, "listMyAdjustmentRequests");
        require("WITHDRAWN".equals(transport.lastRequest.getData("status")),
                "the four-state filter must travel by name");
        require(Integer.valueOf(2).equals(transport.lastRequest.getData("page"))
                        && Integer.valueOf(20).equals(transport.lastRequest.getData("size")),
                "the paging must travel under page/size");
        require(page.getTotalCount() == 7L && page.getPage() == 2 && page.getSize() == 20,
                "the generic page metadata must survive the parse");
        require(page.getItems().size() == 1
                        && page.getItems().get(0).getStatus()
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the four-state summary status must survive the generic parse");
        require(REQUEST_ID.equals(page.getItems().get(0).getRequestId()),
                "the summary request ID must stay exact");

        transport.respond(message -> message.putData("applications",
                wireShaped(new TeacherPageDTO<>(List.of(), 0L, 1, 20))));
        service.listMyAdjustmentRequests(null, 1, 20).join();
        require(transport.lastRequest.getData("status") == null,
                "a null status must be omitted so the server keeps its PENDING default");
    }

    private static void adjustmentConflictKeepsTypedConflictsAndLatestDetail() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("存在冲突，无法提交调课申请");
            message.putData("conflicts", wireShaped(List.of(adjustmentConflict())));
            message.putData("latest", wireShaped(adjustmentDetail()));
        });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        try {
            service.submitAdjustment(writeDto()).join();
            throw new AssertionError("a conflict must fail the future");
        } catch (CompletionException failure) {
            if (!(failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error)) {
                throw new AssertionError("unexpected cause " + failure.getCause(),
                        failure.getCause());
            }
            require(error.getCode() == MessageCode.CONFLICT,
                    "a conflict must keep the CONFLICT code");
            require(error.getConflicts().size() == 1
                            && error.getConflicts().get(0).getSeverity()
                            == ScheduleConflictSeverityDTO.BLOCKING,
                    "the typed conflicts must be available to the dialog");
            require(error.getLatest() != null && REQUEST_ID.equals(error.getLatest().getRequestId())
                            && error.getLatest().getVersion() == 1,
                    "the latest detail must be available to refresh the dialog");
        }

        FakeTransport bare = new FakeTransport();
        bare.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("operationId 已用于不同的业务请求");
            message.putData("conflicts", wireShaped(List.of()));
        });
        try {
            new SocketTeacherCourseService(bare).submitAdjustment(writeDto()).join();
            throw new AssertionError("a bare conflict must fail the future");
        } catch (CompletionException failure) {
            SocketTeacherCourseService.TeacherCourseServiceException error =
                    (SocketTeacherCourseService.TeacherCourseServiceException) failure.getCause();
            require(error.getConflicts().isEmpty() && error.getLatest() == null,
                    "a conflict without an entity must stay empty, never fabricated");
        }
    }

    /** 成绩列表：动作、分页、泛型页解析与嵌套教学班摘要都要映射，且不带别班筛选参数。 */
    private static void gradeOfferingsParseTheGenericPage() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offerings",
                wireShaped(new TeacherPageDTO<>(List.of(gradeOfferingDto()), 5L, 2, 20))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherPageDTO<TeacherGradeOfferingDTO> page =
                service.listGradeOfferings(2025, 3, 2, 20).join();
        requireEnvelope(transport, "listGradeOfferings");
        require(Integer.valueOf(2025).equals(transport.lastRequest.getData("academicYear"))
                        && Integer.valueOf(3).equals(transport.lastRequest.getData("semester")),
                "the term must travel as Integers");
        require(Integer.valueOf(2).equals(transport.lastRequest.getData("page"))
                        && Integer.valueOf(20).equals(transport.lastRequest.getData("size")),
                "the paging must travel under page/size");
        require(transport.lastRequest.getData("uid") == null,
                "the grade list must never carry a client-supplied identity");
        require(page.getTotalCount() == 5L && page.getItems().size() == 1,
                "the grade list metadata and rows must survive the generic parse");
        TeacherGradeOfferingDTO item = page.getItems().get(0);
        require("DRAFT".equals(item.getState()) && item.getEnteredCount() == 20
                        && item.getMissingCount() == 4,
                "the grade state and progress counters must map");
        require(item.getOffering() != null && OFFERING_ID.equals(
                        item.getOffering().getOfferingId()),
                "the nested offering summary must map with its exact id");
    }

    /** 成绩表：四个组成的权重、每行分数（BigDecimal/可空）、服务端总评与只读位逐字段映射。 */
    private static void gradeBookMapsScoresSchemeAndNullableTotals() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("gradeBook", wireShaped(gradeBookDto())));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        TeacherGradeBookDTO book = service.getGradeBook(OFFERING_ID).join();
        requireEnvelope(transport, "getGradeBook");
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "the offeringId must travel as the exact decimal string");
        require(OFFERING_ID.equals(book.getOfferingId()) && book.getRevision() == 4
                        && book.isCanEdit(),
                "the grade book header fields must map");
        require("PENDING".equals(book.getState()) && book.getLastSubmissionId() == null,
                "the batch state fields must map, null included");
        require(book.getScheme() != null && book.getScheme().getComponents().size() == 4
                        && book.getScheme().getComponents().get(0).getWeightBasisPoints() == 3000,
                "every scheme component and weight must map as basis points");
        require(book.getRows().size() == 1, "the grade rows must map");
        TeacherGradeRowDTO row = book.getRows().get(0);
        require(ENROLLMENT_ID.equals(row.getEnrollmentId()),
                "the enrollment ID must stay the exact decimal string");
        require(new java.math.BigDecimal("88.50").compareTo(row.getScores().getDailyScore()) == 0,
                "an entered score must keep its decimal value");
        require(row.getScores().getMidtermScore() == null,
                "a missing score must stay null, never 0");
        require(row.getTotalScore() == null && row.getGradePoint() == null && !row.isComplete(),
                "a row that cannot be computed must keep null totals");
        require(row.getErrors().isEmpty(), "a clean row must have no error text");
        require(!book.isRosterChangedSinceSubmission(),
                "the roster-change flag must map");
    }

    /** 保存与提交是两个动作常量：请求体走 request，结果是 result 键上的泛型信封。 */
    private static void gradeWritesUseTheirOwnActionAndMapTheResult() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", wireShaped(
                new TeacherOperationResultDTO<>(OPERATION_ID, "成绩草稿已保存", gradeBookDto(),
                        false))));
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        WriteGradeBookRequestDTO write = writeGradeDto();
        TeacherOperationResultDTO<TeacherGradeBookDTO> saved = service.saveGradeDraft(write).join();
        requireEnvelope(transport, "saveGradeDraft");
        require(transport.lastRequest.getData("request") == write,
                "the typed write DTO must travel under request unchanged");
        require(transport.lastRequest.getData("uid") == null,
                "the draft save must not carry a client-supplied identity");
        require(OPERATION_ID.equals(saved.getOperationId())
                        && "成绩草稿已保存".equals(saved.getMessage()) && !saved.isReplayed(),
                "the operation result envelope must map");
        require(saved.getValue() != null && saved.getValue().getRevision() == 4,
                "the write result must carry the refreshed grade book");

        transport.respond(message -> message.putData("result", wireShaped(
                new TeacherOperationResultDTO<>(OPERATION_ID, "成绩批次已提交", gradeBookDto(),
                        true))));
        TeacherOperationResultDTO<TeacherGradeBookDTO> submitted =
                service.submitGradeBook(write).join();
        requireEnvelope(transport, "submitGradeBook");
        require(transport.lastRequest.getData("request") == write,
                "the submit must travel as the typed write DTO under request");
        require(submitted.isReplayed(),
                "a replayed submit must be reported as a replay, not a second batch");
    }

    /** 成绩冲突：CONFLICT 里带的是最新成绩表，客户端不能把它解析成调课申请详情。 */
    private static void gradeConflictKeepsTheLatestGradeBook() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("成绩草稿版本已变化，请重新加载后重试");
            message.putData("gradeBook", wireShaped(gradeBookDto()));
        });
        SocketTeacherCourseService service = new SocketTeacherCourseService(transport);

        try {
            service.saveGradeDraft(writeGradeDto()).join();
            throw new AssertionError("a stale draft write must fail the future");
        } catch (CompletionException failure) {
            if (!(failure.getCause()
                    instanceof SocketTeacherCourseService.TeacherCourseServiceException error)) {
                throw new AssertionError("unexpected cause " + failure.getCause(),
                        failure.getCause());
            }
            require(error.getCode() == MessageCode.CONFLICT,
                    "a stale write must keep the CONFLICT code");
            require(error.getLatestGradeBook() != null
                            && error.getLatestGradeBook().getRevision() == 4,
                    "the latest grade book must be available for the refresh hint");
            require(error.getLatest() == null && error.getConflicts().isEmpty(),
                    "a grade conflict must not fabricate adjustment conflicts or details");
            require(error.getMessage().contains("重新加载"),
                    "the server message must survive the mapping");
        }

        FakeTransport bare = new FakeTransport();
        bare.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("operationId 已用于不同的成绩写入请求");
        });
        try {
            new SocketTeacherCourseService(bare).submitGradeBook(writeGradeDto()).join();
            throw new AssertionError("a bare conflict must fail the future");
        } catch (CompletionException failure) {
            SocketTeacherCourseService.TeacherCourseServiceException error =
                    (SocketTeacherCourseService.TeacherCourseServiceException) failure.getCause();
            require(error.getLatestGradeBook() == null,
                    "a conflict without a grade book must stay null, never fabricated");
        }
    }

    private static WriteGradeBookRequestDTO writeGradeDto() {
        return new WriteGradeBookRequestDTO(OPERATION_ID, new GradeBookContentDTO(OFFERING_ID, 4,
                ROSTER_DIGEST, new GradeSchemeDTO(List.of(
                        new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                        new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                        new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, 2000),
                        new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 3000))),
                List.of(new GradeRowInputDTO(ENROLLMENT_ID,
                        new GradeScoresDTO(new java.math.BigDecimal("88.5"), null, null, null)))));
    }

    private static TeacherGradeOfferingDTO gradeOfferingDto() {
        return new TeacherGradeOfferingDTO(offeringDto(), "DRAFT", 20, 4, null);
    }

    private static TeacherGradeBookDTO gradeBookDto() {
        return new TeacherGradeBookDTO(OFFERING_ID, 4, ROSTER_DIGEST, "PENDING",
                new GradeSchemeDTO(List.of(
                        new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                        new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                        new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, 2000),
                        new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 3000))),
                List.of(new TeacherGradeRowDTO(ENROLLMENT_ID, "00005678", "张三",
                        new GradeScoresDTO(new java.math.BigDecimal("88.50"), null,
                                new java.math.BigDecimal("91"), new java.math.BigDecimal("77.5")),
                        null, null, false, List.of())),
                null, null, true, null, false);
    }

    private static TeacherAdjustmentWriteDTO writeDto() {
        return new TeacherAdjustmentWriteDTO("not-a-uuid", OFFERING_ID,
                List.of(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, TARGET_DATE)),
                3, 4, CLASSROOM_ID, "教师出差");
    }

    private static AdjustmentRequestDetailDTO adjustmentDetail() {
        return new AdjustmentRequestDetailDTO(REQUEST_ID, OFFERING_ID, TEACHER_UID, "教师出差",
                AdjustmentRequestStatusDTO.PENDING, 1, 3, 3, 4,
                null, null, new ScheduleResourceDTO(CLASSROOM_ID, "3003", "B-203", "classroom", 60),
                List.of(new AdjustmentTargetDTO(OCCURRENCE_ID, 8, "2026-10-26T00:00:00Z",
                        "2026-10-26T01:35:00Z", "陈老师", null, "A-101", TARGET_DATE)),
                List.of(), "2026-09-14T08:00:00Z", null, null, null);
    }

    private static AdjustmentRequestDetailDTO withdrawnDetail() {
        return new AdjustmentRequestDetailDTO(REQUEST_ID, OFFERING_ID, TEACHER_UID, "教师出差",
                AdjustmentRequestStatusDTO.WITHDRAWN, 2, 3, 3, 4,
                null, null, new ScheduleResourceDTO(CLASSROOM_ID, "3003", "B-203", "classroom", 60),
                List.of(new AdjustmentTargetDTO(OCCURRENCE_ID, 8, "2026-10-26T00:00:00Z",
                        "2026-10-26T01:35:00Z", "陈老师", null, "A-101", TARGET_DATE)),
                List.of(), "2026-09-14T08:00:00Z", null, null, null);
    }

    private static AdjustmentRequestSummaryDTO adjustmentSummary() {
        return new AdjustmentRequestSummaryDTO(REQUEST_ID, "数据结构与算法基础", "CS203-01",
                TEACHER_UID, "陈老师", 1, AdjustmentRequestStatusDTO.WITHDRAWN,
                "2026-09-14T08:00:00Z");
    }

    private static ScheduleConflictDTO adjustmentConflict() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                OCCURRENCE_ID, OFFERING_ID, 8, 3, 3, 4, "任课教师在该时间已有其他课程");
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
