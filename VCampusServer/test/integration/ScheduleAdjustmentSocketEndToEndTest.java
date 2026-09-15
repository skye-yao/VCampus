package integration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dto.course.CourseActions;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseNoticeDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.DBUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP/MySQL proof that an approved temporary adjustment reaches the enrolled student's
 * timetable and notices, and that the published base plan is untouched.
 *
 * <p>Everything lives in an isolated 2028/2 term with 98xxxx fixtures owned by this test and removed
 * even on failure, so neither the shared seed nor a pre-existing adjustment in the guarded schema
 * can influence the result. Only virtual_campus_course_test is allowed.
 */
public final class ScheduleAdjustmentSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String ADMIN_A = "adjust-e2e-admin-a";
    private static final String ADMIN_B = "adjust-e2e-admin-b";
    private static final String STUDENT = "adjust-e2e-student";
    private static final String PASSWORD = "course-test-only";
    private static final Gson GSON = new Gson();

    private static final int YEAR = 2028;
    private static final int SEMESTER = 2;

    private static final long CALENDAR = 980001;
    private static final long PLAN = 980002;
    private static final long COURSE = 980101;
    private static final long OFFERING = 980201;
    private static final long OFFERING_RACE = 980202;
    private static final long ENROLLMENT = 980301;
    private static final long ROOM = 980401;
    private static final long ROOM_RESOURCE = 980402;
    private static final long RULE_FIRST = 980501;
    private static final long RULE_SECOND = 980502;
    private static final long RULE_RACE = 980503;
    private static final long ARRANGEMENT = 980551;
    private static final long ARRANGEMENT_RACE = 980552;
    private static final long OCCURRENCE_W1 = 980601;
    private static final long OCCURRENCE_W2 = 980602;
    private static final long OCCURRENCE_W3 = 980603;
    private static final long OCCURRENCE_SECOND = 980604;
    private static final long OCCURRENCE_RACE = 980605;
    private static final long REQUEST_TWO_WEEKS = 980701;
    private static final long REQUEST_RACE = 980702;
    private static final long TARGET_W1 = 980801;
    private static final long TARGET_W3 = 980802;
    private static final long TARGET_RACE = 980803;

    private ScheduleAdjustmentSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        LoginSchemaTestBridge.ensureBankAccountTable();
        cleanup();
        try {
            insertFixtures();
            String before = basePlanChecksum();
            Server server = new Server(0, new OnlineConnectionRegistry(),
                    new MessageDispatcher(), null, null);
            Thread serverThread = new Thread(server::start, "adjustment-e2e-server");
            serverThread.setDaemon(true);
            try {
                serverThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                require(server.isRunning(), "real server must start");
                try (JsonLineClient admin = new JsonLineClient(server.getPort());
                     JsonLineClient student = new JsonLineClient(server.getPort())) {
                    String adminToken = admin.login(ADMIN_A, "管理员");
                    String studentToken = student.login(STUDENT, "学生");
                    verifyPendingRequestIsVisible(admin, adminToken);
                    String operationId = verifyApproval(admin, adminToken);
                    verifyStoredApproval();
                    verifyStudentTimetable(student, studentToken);
                    verifyReplay(admin, adminToken, operationId);
                    verifySecondAdministratorLoses(server.getPort(), adminToken);
                    verifyConcurrentApprovalRace(server.getPort());
                }
            } finally {
                server.stop();
                serverThread.join(5_000);
                require(!serverThread.isAlive(), "server must stop after the test");
            }
            require(before.equals(basePlanChecksum()),
                    "approval must never rewrite the published plan, its rules, weeks, occurrences or"
                            + " bookings");
        } finally {
            cleanup();
        }
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id"
                        + " BETWEEN 980000 AND 989999") == 0
                        && number("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE request_id BETWEEN 980000 AND 989999") == 0
                        && number("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=" + PLAN) == 0
                        && number("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN) == 0
                        && number("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'adjust-e2e-%'") == 0,
                "cleanup must leave no fixture row behind");
        System.out.println("Schedule adjustment socket end-to-end test passed.");
    }

    private static void verifyPendingRequestIsVisible(JsonLineClient admin, String token)
            throws Exception {
        Message listed = admin.admin(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS, token,
                Map.of("pageNumber", 1, "pageSize", 20));
        requireCode(listed, MessageCode.SUCCESS, "list pending adjustments");
        JsonArray rows = GSON.toJsonTree(listed.getData("adjustmentRequests")).getAsJsonArray();
        boolean visible = false;
        for (int index = 0; index < rows.size(); index++) {
            JsonObject row = rows.get(index).getAsJsonObject();
            if (Long.toString(REQUEST_TWO_WEEKS).equals(row.get("requestId").getAsString())
                    && row.get("targetWeekCount").getAsInt() == 2) {
                visible = true;
            }
        }
        require(visible,
                "the pending two-week request must be visible as PENDING with two target weeks");
        require(GSON.toJsonTree(listed.getData("totalCount")).getAsLong() >= 1,
                "the list must report a server total");

        Message detail = admin.admin(AdminCourseActions.GET_ADJUSTMENT_REQUEST, token,
                Map.of("requestId", Long.toString(REQUEST_TWO_WEEKS)));
        requireCode(detail, MessageCode.SUCCESS, "read the pending request");
        AdjustmentRequestDetailDTO request = GSON.fromJson(
                GSON.toJsonTree(detail.getData("adjustmentRequest")),
                AdjustmentRequestDetailDTO.class);
        require(request.getStatus() == AdjustmentRequestStatusDTO.PENDING && request.getVersion() == 1
                        && request.getTargets().size() == 2
                        && "980601".equals(request.getTargets().get(0).getOriginalOccurrenceId())
                        && "980603".equals(request.getTargets().get(1).getOriginalOccurrenceId()),
                "the detail must expose both target weeks over the wire");
        require(request.getConflicts().isEmpty(),
                "an isolated fixture must have no conflicts, saw "
                        + describe(request.getConflicts()));
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE action='"
                        + AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST + "'") == 0,
                "no review must be recorded before the approval");
    }

    private static String verifyApproval(JsonLineClient admin, String token) throws IOException {
        String operationId = "98000000-0000-0000-0000-000000000001";
        Message approved = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token,
                Map.of("request", Map.of("operationId", operationId,
                        "requestId", Long.toString(REQUEST_TWO_WEEKS),
                        "expectedVersion", 1, "approved", true, "force", false)));
        requireCode(approved, MessageCode.SUCCESS, "approve over TCP");
        AdjustmentRequestDetailDTO result = GSON.fromJson(
                GSON.toJsonTree(approved.getData("result")).getAsJsonObject().get("entity"),
                AdjustmentRequestDetailDTO.class);
        require(result.getStatus() == AdjustmentRequestStatusDTO.APPROVED && result.getVersion() == 2,
                "the approved entity must advance to APPROVED v2");
        require(ADMIN_A.equals(result.getReviewedBy()),
                "the reviewer must be the authenticated administrator, saw " + result.getReviewedBy());
        return operationId;
    }

    private static void verifyStoredApproval() throws Exception {
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_TWO_WEEKS + " AND status='ACTIVE'") == 2,
                "one ACTIVE adjustment per target week must be stored");
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + REQUEST_TWO_WEEKS + " AND status='APPROVED' AND version=2") == 1,
                "the request must be APPROVED at version 2");
        require(number("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_TWO_WEEKS + " AND notice_type='RESCHEDULED'"
                        + " AND status='PUBLISHED' AND week_no IS NULL AND created_by='" + ADMIN_A
                        + "'") == 1,
                "exactly one published rescheduled notice must be linked to the request");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='98000000-0000-0000-0000-000000000001'"
                        + " AND result_code='OK'") == 1,
                "the approval must be audited exactly once");
    }

    private static void verifyStudentTimetable(JsonLineClient student, String token)
            throws IOException {
        List<ScheduleEntryDTO> weekOne = schedule(student, token, 1);
        require(weekOne.size() == 3,
                "the adjusted week must pair the adjusted meeting beside the untouched one, saw "
                        + weekOne.size());
        List<ScheduleEntryDTO> pair = weekOne.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind()).toList();
        require(pair.size() == 2
                        && ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == pair.get(0).getDisplayKind()
                        && ScheduleDisplayKindDTO.ADJUSTED_TARGET == pair.get(1).getDisplayKind()
                        && pair.get(0).getAdjustmentId().equals(pair.get(1).getAdjustmentId()),
                "the student must receive one original and one target sharing an adjustment id");
        require(pair.get(0).getDayOfWeek() == 2 && pair.get(0).getStartPeriod() == 1
                        && pair.get(1).getDayOfWeek() == 5 && pair.get(1).getStartPeriod() == 1,
                "the pair must sit at the published and the approved coordinates, saw "
                        + pair.get(0).getDayOfWeek() + "/" + pair.get(0).getStartPeriod() + " and "
                        + pair.get(1).getDayOfWeek() + "/" + pair.get(1).getStartPeriod());
        require(weekOne.stream().anyMatch(entry ->
                        ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()
                                && entry.getDayOfWeek() == 4 && entry.getStartPeriod() == 3),
                "the offering's other meeting must stay a plain entry");

        List<ScheduleEntryDTO> weekTwo = schedule(student, token, 2);
        require(weekTwo.size() == 1
                        && ScheduleDisplayKindDTO.NORMAL == weekTwo.get(0).getDisplayKind()
                        && weekTwo.get(0).getAdjustmentId() == null
                        && weekTwo.get(0).getDayOfWeek() == 2,
                "the neighbouring unrequested week must stay NORMAL, saw " + weekTwo.size());

        List<ScheduleEntryDTO> weekThree = schedule(student, token, 3);
        require(weekThree.size() == 2 && weekThree.stream().noneMatch(entry ->
                        ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()),
                "the second requested week must also pair, saw " + weekThree.size());

        // T6 起通知按关联申请的原周/目标周查询并去重：两次同周调课分别只落在第 1 周与第 3 周，
        // 第 2 周（两侧都不是）必须为空，且每周至多一条。
        List<CourseNoticeDTO> weekOneNotices = notices(student, token, 1);
        List<CourseNoticeDTO> weekTwoNotices = notices(student, token, 2);
        List<CourseNoticeDTO> weekThreeNotices = notices(student, token, 3);
        require(weekOneNotices.size() == 1 && "RESCHEDULED".equals(weekOneNotices.get(0)
                        .getNoticeType())
                        && weekOneNotices.get(0).getWeek() == 0
                        && weekOneNotices.get(0).getContent().contains("第1周"),
                "the summary notice of the week-1 move must reach the enrolled student once, saw "
                        + describeNotices(weekOneNotices));
        require(weekThreeNotices.size() == 1 && "RESCHEDULED".equals(weekThreeNotices.get(0)
                        .getNoticeType())
                        && weekThreeNotices.get(0).getContent().contains("第3周"),
                "the summary notice of the week-3 move must reach the enrolled student once, saw "
                        + describeNotices(weekThreeNotices));
        require(weekTwoNotices.isEmpty(),
                "a week that is neither the origin nor the target of a move must stay free of the"
                        + " linked notice, saw " + describeNotices(weekTwoNotices));
    }

    private static List<CourseNoticeDTO> notices(JsonLineClient student, String token, int week)
            throws IOException {
        Message noticed = student.request("course", CourseActions.LOAD_NOTICES, token,
                Map.of("academicYear", YEAR, "semester", SEMESTER, "week", week));
        requireCode(noticed, MessageCode.SUCCESS, "read notices for week " + week);
        return GSON.fromJson(GSON.toJsonTree(noticed.getData("notices")),
                new TypeToken<List<CourseNoticeDTO>>() { }.getType());
    }

    private static String describeNotices(List<CourseNoticeDTO> notices) {
        List<String> described = new ArrayList<>();
        for (CourseNoticeDTO notice : notices) {
            described.add(notice.getNoticeId() + "/" + notice.getNoticeType() + "/"
                    + notice.getWeek());
        }
        return described.toString();
    }

    private static void verifyReplay(JsonLineClient admin, String token, String operationId)
            throws Exception {
        Message replayed = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token,
                Map.of("request", Map.of("operationId", operationId,
                        "requestId", Long.toString(REQUEST_TWO_WEEKS),
                        "expectedVersion", 1, "approved", true, "force", false)));
        requireCode(replayed, MessageCode.SUCCESS, "replay the same approval");
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_TWO_WEEKS) == 2
                        && number("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_TWO_WEEKS) == 1,
                "a replay must duplicate neither adjustment nor notice");
    }

    private static void verifySecondAdministratorLoses(int port, String firstToken)
            throws Exception {
        try (JsonLineClient second = new JsonLineClient(port)) {
            String secondToken = second.login(ADMIN_B, "管理员");
            Message refused = second.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, secondToken,
                    Map.of("request", Map.of(
                            "operationId", "98000000-0000-0000-0000-000000000002",
                            "requestId", Long.toString(REQUEST_TWO_WEEKS),
                            "expectedVersion", 1, "approved", true, "force", false)));
            requireCode(refused, MessageCode.CONFLICT,
                    "a second administrator must not re-decide a settled request");
            require(refused.getData("latest") != null,
                    "the refusal must carry the settled state for the dialog");
        }
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_TWO_WEEKS) == 2,
                "the losing decision must not add an adjustment");
    }

    /** Two administrators race one pending request from two connections; exactly one may commit. */
    private static void verifyConcurrentApprovalRace(int port) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> observed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        Runnable first = racer(port, ADMIN_A, "98000000-0000-0000-0000-000000000003", barrier,
                observed, failures);
        Runnable second = racer(port, ADMIN_B, "98000000-0000-0000-0000-000000000004", barrier,
                observed, failures);
        Thread threadA = new Thread(first, "adjustment-racer-a");
        Thread threadB = new Thread(second, "adjustment-racer-b");
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        require(failures.isEmpty(), "the race must not raise " + failures);
        require(observed.size() == 2
                        && observed.stream().filter(entry -> entry.startsWith("SUCCESS")).count() == 1
                        && observed.stream().filter(entry -> entry.startsWith("CONFLICT")).count() == 1,
                "exactly one racing administrator must commit and one must conflict, saw " + observed);
        require(number("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_RACE + " AND status='ACTIVE'") == 1
                        && number("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_RACE) == 1,
                "the race must leave exactly one adjustment and one notice");
    }

    private static Runnable racer(int port, String admin, String operationId, CyclicBarrier barrier,
            List<String> observed, List<Throwable> failures) {
        return () -> {
            try (JsonLineClient client = new JsonLineClient(port)) {
                String token = client.login(admin, "管理员");
                barrier.await(10, TimeUnit.SECONDS);
                Message response = client.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token,
                        Map.of("request", Map.of("operationId", operationId,
                                "requestId", Long.toString(REQUEST_RACE),
                                "expectedVersion", 1, "approved", true, "force", false)));
                synchronized (observed) {
                    observed.add(response.getCode() + "/" + response.getMessage() + "/"
                            + GSON.toJson(response.getData()));
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        };
    }

    private static List<ScheduleEntryDTO> schedule(JsonLineClient student, String token, int week)
            throws IOException {
        Message response = student.request("course", CourseActions.LOAD_SCHEDULE, token,
                Map.of("academicYear", YEAR, "semester", SEMESTER, "week", week));
        requireCode(response, MessageCode.SUCCESS, "read the student timetable for week " + week);
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule")),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
    }

    // ------------------------------------------------------------------ fixtures

    private static void insertFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + ADMIN_A + "','Adjust E2E Admin A',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),"
                + "('" + ADMIN_B + "','Adjust E2E Admin B',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),"
                + "('" + STUDENT + "','Adjust E2E Student',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE + ",'ADJ-E2E','Adjustment E2E Course',"
                + "3.00,48,1,'ACTIVE')");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR + ",'Adjustment e2e calendar',"
                + YEAR + "," + SEMESTER + ",'2028-02-28','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN + ",'Adjustment e2e plan'," + CALENDAR
                + ",1,'PUBLISHED','2028-01-01 00:00:00','2028-01-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN + " WHERE id="
                + CALENDAR);
        execute("INSERT INTO day_template(id,name,version) VALUES(980901,'Adjust E2E template',1)");
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(980911,980901,1,'08:00:00','08:45:00'),"
                + "(980912,980901,2,'08:50:00','09:35:00'),"
                + "(980913,980901,3,'10:00:00','10:45:00'),"
                + "(980914,980901,4,'10:50:00','11:35:00')");
        int dateId = 980920;
        for (int week = 1; week <= 3; week++) {
            for (int day = 1; day <= 5; day++) {
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                        + "day_template_id,is_teaching_day) VALUES(" + dateId++ + "," + CALENDAR
                        + ",'" + localDate(week, day) + "'," + week + "," + day + ",980901,1)");
            }
        }
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING + ",'ADJ-E2E-A'," + COURSE + ","
                + YEAR + "," + SEMESTER + ",30,2),(" + OFFERING_RACE + ",'ADJ-E2E-B'," + COURSE + ","
                + YEAR + "," + SEMESTER + ",30,2)");
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + ENROLLMENT + "," + OFFERING + "," + COURSE + ","
                + YEAR + "," + SEMESTER + ",'" + STUDENT + "',2,'2028-02-01 00:00:00')");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(" + ROOM
                + ",'Adjust E2E Room',60,1)");
        execute("INSERT INTO schedule_resource(id,resource_type,business_id,conflict_mode) VALUES("
                + ROOM_RESOURCE + ",'classroom','" + ROOM + "','EXCLUSIVE')");
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,classroom_id,status,version) VALUES(" + ARRANGEMENT + "," + PLAN + ","
                + OFFERING + ",'teacher-alpha'," + ROOM + ",'ACTIVE',1),(" + ARRANGEMENT_RACE + ","
                + PLAN + "," + OFFERING_RACE + ",'teacher-alpha'," + ROOM + ",'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + RULE_FIRST + "," + PLAN + ","
                + OFFERING + "," + ARRANGEMENT + ",2,1,2,'ACTIVE'),(" + RULE_SECOND + "," + PLAN + ","
                + OFFERING + "," + ARRANGEMENT + ",4,3,4,'ACTIVE'),(" + RULE_RACE + "," + PLAN + ","
                + OFFERING_RACE + "," + ARRANGEMENT_RACE + ",3,3,4,'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + RULE_FIRST
                + ",1),(" + RULE_FIRST + ",2),(" + RULE_FIRST + ",3),(" + RULE_SECOND + ",1),("
                + RULE_RACE + ",1)");
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + OCCURRENCE_W1 + "," + RULE_FIRST + "," + PLAN
                + ",'2028-02-29 00:00:00','2028-02-29 01:35:00',1,2),(" + OCCURRENCE_W2 + ","
                + RULE_FIRST + "," + PLAN + ",'2028-03-07 00:00:00','2028-03-07 01:35:00',2,2),("
                + OCCURRENCE_W3 + "," + RULE_FIRST + "," + PLAN
                + ",'2028-03-14 00:00:00','2028-03-14 01:35:00',3,2),(" + OCCURRENCE_SECOND + ","
                + RULE_SECOND + "," + PLAN + ",'2028-03-02 02:00:00','2028-03-02 03:35:00',1,4),("
                + OCCURRENCE_RACE + "," + RULE_RACE + "," + PLAN
                + ",'2028-03-01 02:00:00','2028-03-01 03:35:00',1,3)");
        execute("INSERT INTO resource_booking(plan_id,occurrence_id,resource_id,resource_role) VALUES"
                + "(" + PLAN + "," + OCCURRENCE_W1 + "," + ROOM_RESOURCE + ",'CLASSROOM'),(" + PLAN
                + "," + OCCURRENCE_W2 + "," + ROOM_RESOURCE + ",'CLASSROOM'),(" + PLAN + ","
                + OCCURRENCE_W3 + "," + ROOM_RESOURCE + ",'CLASSROOM'),(" + PLAN + ","
                + OCCURRENCE_SECOND + "," + ROOM_RESOURCE + ",'CLASSROOM'),(" + PLAN + ","
                + OCCURRENCE_RACE + "," + ROOM_RESOURCE + ",'CLASSROOM')");
        request(REQUEST_TWO_WEEKS, OFFERING, 5, 1, 2);
        target(TARGET_W1, REQUEST_TWO_WEEKS, OCCURRENCE_W1, 1, 2, 1, 2);
        target(TARGET_W3, REQUEST_TWO_WEEKS, OCCURRENCE_W3, 3, 2, 1, 2);
        request(REQUEST_RACE, OFFERING_RACE, 2, 3, 4);
        target(TARGET_RACE, REQUEST_RACE, OCCURRENCE_RACE, 1, 3, 3, 4);
    }

    private static void request(long requestId, long offeringId, int weekday, int startPeriod,
                                int endPeriod) throws Exception {
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,"
                + "reason,version,status,new_weekday,new_start_period,new_end_period,new_teacher_uid,"
                + "new_assistant_uid,new_classroom_id,submitted_at) VALUES(" + requestId + ","
                + offeringId + ",'teacher-alpha','教师出差',1,'PENDING'," + weekday + ","
                + startPeriod + "," + endPeriod + ",NULL,NULL,NULL,'2028-02-20 01:00:00')");
    }

    private static void target(long targetId, long requestId, long occurrenceId, int week,
                               int weekday, int startPeriod, int endPeriod) throws Exception {
        String date = localDate(week, weekday);
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES("
                + targetId + "," + requestId + "," + occurrenceId + "," + week + ",'"
                + utcText(date, periodTime(startPeriod, true)) + "','"
                + utcText(date, periodTime(endPeriod, false)) + "','teacher-alpha',NULL," + ROOM
                + ")");
    }

    private static String periodTime(int periodNo, boolean start) {
        return switch (periodNo) {
            case 1 -> start ? "08:00:00" : "08:45:00";
            case 2 -> start ? "08:50:00" : "09:35:00";
            case 3 -> start ? "10:00:00" : "10:45:00";
            case 4 -> start ? "10:50:00" : "11:35:00";
            default -> throw new IllegalArgumentException("no fixture period " + periodNo);
        };
    }

    private static String localDate(int week, int weekday) {
        return java.time.LocalDate.parse("2028-02-28")
                .plusDays((week - 1) * 7L + weekday - 1).toString();
    }

    /** The fixture windows are UTC wall clock, so local period times are re-anchored first. */
    private static String utcText(String date, String time) {
        java.time.Instant instant = java.time.ZonedDateTime.of(java.time.LocalDate.parse(date),
                java.time.LocalTime.parse(time), java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).toString()
                .replace('T', ' ');
    }

    private static void cleanup() throws Exception {
        execute("DELETE FROM course_notice WHERE adjustment_request_id BETWEEN 980000 AND 989999");
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid LIKE 'adjust-e2e-%'");
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 980000"
                + " AND 989999");
        execute("DELETE FROM course_schedule_adjustment_target WHERE target_id BETWEEN 980000"
                + " AND 989999");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 980000"
                + " AND 989999");
        execute("DELETE FROM resource_booking WHERE plan_id=" + PLAN);
        execute("DELETE FROM enrollment WHERE enrollment_id=" + ENROLLMENT);
        execute("DELETE FROM course_occurrence WHERE plan_id=" + PLAN);
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 980501 AND 980599");
        execute("DELETE FROM course_schedule_rule WHERE plan_id=" + PLAN);
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id=" + PLAN);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=" + CALENDAR);
        execute("DELETE FROM schedule_plan WHERE id=" + PLAN);
        execute("DELETE FROM schedule_resource WHERE id=" + ROOM_RESOURCE);
        execute("DELETE FROM course_offering WHERE offering_id IN (" + OFFERING + ","
                + OFFERING_RACE + ")");
        execute("DELETE FROM course WHERE course_id=" + COURSE);
        execute("DELETE FROM classroom WHERE id=" + ROOM);
        execute("DELETE FROM calendar_date WHERE calendar_id=" + CALENDAR);
        execute("DELETE FROM period_definition WHERE id BETWEEN 980900 AND 980999");
        execute("DELETE FROM day_template WHERE id BETWEEN 980900 AND 980999");
        execute("DELETE FROM teaching_calendar WHERE id=" + CALENDAR);
        execute("DELETE FROM tbl_user WHERE UID LIKE 'adjust-e2e-%'");
    }

    /** Canonical text of the published base plan the approval must never mutate. */
    private static String basePlanChecksum() throws Exception {
        StringBuilder checksum = new StringBuilder();
        checksum.append(text("SELECT CONCAT(id,'|',status,'|',revision) FROM schedule_plan"
                + " WHERE id=" + PLAN));
        checksum.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',weekday,'|',start_period,'|',"
                + "end_period,'|',status) ORDER BY id SEPARATOR ',') FROM course_schedule_rule"
                + " WHERE plan_id=" + PLAN));
        checksum.append(text("SELECT GROUP_CONCAT(CONCAT(rule_id,'|',week_no) ORDER BY rule_id,week_no"
                + " SEPARATOR ',') FROM course_schedule_rule_week WHERE rule_id BETWEEN 980501"
                + " AND 980599"));
        checksum.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',rule_id,'|',start_at,'|',end_at,'|',"
                + "week_no,'|',teaching_weekday) ORDER BY id SEPARATOR ',') FROM course_occurrence"
                + " WHERE plan_id=" + PLAN));
        checksum.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',occurrence_id,'|',resource_id,'|',"
                + "resource_role) ORDER BY id SEPARATOR ',') FROM resource_booking WHERE plan_id="
                + PLAN));
        return checksum.toString();
    }

    // ------------------------------------------------------------------- helpers

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = DBUtil.class.getClassLoader()
                .getResourceAsStream("resources/db.properties")) {
            require(stream != null, "db.properties is unavailable on the runtime classpath");
            properties.load(stream);
        }
        String url = properties.getProperty("db.url");
        require(url != null && !url.isBlank(), "db.url is not configured");
        String raw = url.startsWith("jdbc:") ? url.substring(5) : url;
        String path = URI.create(raw).getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing adjustment e2e: the JDBC URL must target the guarded schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing adjustment e2e outside the guarded schema");
    }

    private static void requireCode(Message response, MessageCode expected, String what) {
        require(response.getCode() == expected,
                what + " must be " + expected + ", saw " + response.getCode() + " ("
                        + response.getMessage() + ")");
    }

    private static int number(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String describe(List<dto.course.admin.schedule.ScheduleConflictDTO> conflicts) {
        List<String> values = new ArrayList<>();
        for (dto.course.admin.schedule.ScheduleConflictDTO conflict : conflicts) {
            values.add(conflict.getType() + "/w" + conflict.getWeek() + "/d"
                    + conflict.getDayOfWeek() + "/p" + conflict.getStartPeriod() + "-"
                    + conflict.getEndPeriod() + "/subject=" + conflict.getSubjectId() + "/"
                    + conflict.getSeverity());
        }
        return values.toString();
    }

    /** Minimal real-socket client: one JSON line per message, logged in with a real token. */
    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private JsonLineClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(15_000);
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String login(String uid, String role) throws IOException {
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireCode(captcha, MessageCode.SUCCESS, "get captcha for " + uid);
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", PASSWORD, "role", role,
                            "captchaId", captchaId,
                            "captchaCode", CaptchaTestBridge.codeFor(captchaId)));
            requireCode(response, MessageCode.SUCCESS, "login for " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must supply a token");
            return (String) token;
        }

        private Message admin(String action, String token, Map<String, Object> data)
                throws IOException {
            return request("courseAdmin", action, token, data);
        }

        private Message request(String module, String action, String token,
                                Map<String, Object> data) throws IOException {
            Message request = new Message(MessageType.REQUEST, module, action);
            request.setToken(token);
            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    request.putData(entry.getKey(), entry.getValue());
                }
            }
            writer.write(GSON.toJson(request));
            writer.newLine();
            writer.flush();
            String line = reader.readLine();
            require(line != null, "server must answer " + module + "." + action);
            return GSON.fromJson(line, Message.class);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
