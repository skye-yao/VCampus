package integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dto.course.ScheduleEntryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP/MySQL scheduling coverage. Run after V004 and seed-course-test.sql.
 * Only virtual_campus_course_test is allowed. The 95xxxx fixtures are owned by
 * this test and removed even on failure; the shared seed is not modified.
 */
public final class AdminScheduleSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String ADMIN = "admin-alpha";
    private static final String STUDENT = "student-alpha";
    private static final long CALENDAR = 950001;
    private static final long TEMPLATE = 950002;
    private static final long PLAN = 950003;
    private static final long LEGACY_PLAN = 950004;
    private static final long DRAFT_PLAN = 950005;
    private static final long LATER_CALENDAR = 950006;
    private static final long LATER_PLAN = 950007;
    private static final long WINDOW = 950100;
    private static final long COURSE = 950200;
    private static final long OFFERING = 950201;
    private static final long ENROLLMENT = 950202;
    private static final long ROOM = 950300;
    private static final long ROOM_RESOURCE = 950301;
    private static final Gson GSON = new Gson();

    private AdminScheduleSocketEndToEndTest() { }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        try {
            insertFixtures();
            Server server = new Server(0, new OnlineConnectionRegistry(),
                    new MessageDispatcher(), null, null);
            Thread serverThread = new Thread(server::start, "schedule-e2e-server");
            serverThread.setDaemon(true);
            try {
                serverThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                require(server.isRunning(), "real server must start");
                try (JsonLineClient admin = new JsonLineClient(server.getPort());
                     JsonLineClient student = new JsonLineClient(server.getPort())) {
                    String adminToken = admin.login(ADMIN, "管理员");
                    String studentToken = student.login(STUDENT, "学生");
                    verifyScheduling(admin, adminToken, student, studentToken);
                    verifyPointerCompatibility(student, studentToken);
                }
            } finally {
                server.stop();
                serverThread.join(5_000);
                require(!serverThread.isAlive(), "server must stop after the test");
            }
        } finally {
            cleanup();
        }
        require(number("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 950003 AND 950007") == 0,
                "test schedule plans must be removed");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log"
                + " WHERE operation_id LIKE '95000000-%'") == 0, "test operation logs must be removed");
        System.out.println("AdminScheduleSocketEndToEndTest: PASS");
    }

    private static void verifyScheduling(JsonLineClient admin, String adminToken,
            JsonLineClient student, String studentToken) throws Exception {
        require(number("SELECT COUNT(*) FROM course_selection_window"
                + " WHERE academic_year=2027 AND semester=1") == 0,
                "the new term must have no selection window");
        SaveArrangementRequestDTO create = saveRequest(1, null, 0);
        Message preview = admin.admin(AdminCourseActions.CHECK_ARRANGEMENT, adminToken,
                Map.of("request", create));
        requireCode(preview, MessageCode.SUCCESS, "preview two slots");
        require(GSON.toJsonTree(preview.getData("conflicts")).getAsJsonArray().isEmpty(),
                "the isolated schedule must have no conflicts");
        ScheduleArrangementDTO created = writeResult(
                admin.admin(AdminCourseActions.SAVE_ARRANGEMENT, adminToken, Map.of("request", create)),
                op(1), ScheduleArrangementDTO.class);
        require(created.getVersion() == 1 && created.getSlots().size() == 2
                        && created.getStartWeek() == 5 && created.getEndWeek() == 10,
                "save must return both slots and their shared weeks");
        verifyStoredArrangement(1);
        ScheduleArrangementDTO replay = writeResult(
                admin.admin(AdminCourseActions.SAVE_ARRANGEMENT, adminToken, Map.of("request", create)),
                op(1), ScheduleArrangementDTO.class);
        require(created.getArrangementId().equals(replay.getArrangementId()) && replay.getVersion() == 1,
                "identical operation must replay the original arrangement");
        verifyStoredArrangement(1);
        require(number("SELECT COUNT(*) FROM admin_course_operation_log"
                        + " WHERE admin_uid=? AND operation_id=?", ADMIN, op(1)) == 1,
                "replay must keep exactly one operation record");

        ScheduleArrangementDTO updated = writeResult(admin.admin(AdminCourseActions.SAVE_ARRANGEMENT,
                adminToken, Map.of("request", saveRequest(2, created.getArrangementId(), 1))),
                op(2), ScheduleArrangementDTO.class);
        require(updated.getVersion() == 2, "editing must advance the arrangement version");
        requireCode(admin.admin(AdminCourseActions.SAVE_ARRANGEMENT, adminToken,
                        Map.of("request", saveRequest(3, created.getArrangementId(), 1))),
                MessageCode.CONFLICT, "stale arrangement version");
        verifyStoredArrangement(2);
        System.out.println("[E2E] two-slot save, operation replay and stale version: PASS");

        Message loaded = admin.admin(AdminCourseActions.LOAD_SCHEDULE_PLAN, adminToken,
                Map.of("academicYear", 2027, "semester", 1));
        requireCode(loaded, MessageCode.SUCCESS, "load authoritative plan");
        SchedulePlanDTO plan = GSON.fromJson(GSON.toJsonTree(loaded.getData("plan")), SchedulePlanDTO.class);
        require(Long.toString(PLAN).equals(plan.getPlanId()) && "DRAFT".equals(plan.getStatus()),
                "the administrator must load the new draft plan");
        requireCode(student.admin(AdminCourseActions.SAVE_ARRANGEMENT, studentToken,
                        Map.of("request", saveRequest(31, created.getArrangementId(), 2))),
                MessageCode.FORBIDDEN, "student save");
        requireCode(student.admin(AdminCourseActions.DELETE_ARRANGEMENT, studentToken,
                        Map.of("arrangementId", created.getArrangementId(), "expectedVersion", 2,
                                "operationId", op(32))),
                MessageCode.FORBIDDEN, "student delete");
        requireCode(student.admin(AdminCourseActions.PUBLISH_SCHEDULE_PLAN, studentToken,
                        publishRequest(33, plan.getRevision())),
                MessageCode.FORBIDDEN, "student publish");
        verifyStoredArrangement(2);
        require("DRAFT".equals(text("SELECT status FROM schedule_plan WHERE id=?", PLAN))
                        && scalar("SELECT current_schedule_plan_id FROM teaching_calendar WHERE id=?",
                                CALENDAR) == null,
                "forbidden writes must not publish or change the plan pointer");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id IN (?,?,?)",
                op(31), op(32), op(33)) == 0, "forbidden writes must not create operation records");

        Map<String, Object> publication = publishRequest(4, plan.getRevision());
        SchedulePlanDTO published = writeResult(admin.admin(AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                adminToken, publication), op(4), SchedulePlanDTO.class);
        require("PUBLISHED".equals(published.getStatus()) && published.isCurrent(),
                "publication must return the current published plan");
        require(number("SELECT current_schedule_plan_id FROM teaching_calendar WHERE id=?", CALENDAR)
                        == PLAN, "publication must advance the calendar pointer");
        require("PUBLISHED".equals(text("SELECT status FROM schedule_plan WHERE id=?", PLAN)),
                "published status must be committed");
        SchedulePlanDTO publishReplay = writeResult(admin.admin(AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                adminToken, publication), op(4), SchedulePlanDTO.class);
        require(publishReplay.getRevision() == published.getRevision(),
                "publication replay must retain its original revision");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log"
                + " WHERE admin_uid=? AND operation_id=?", ADMIN, op(4)) == 1,
                "publication replay must not create a second audit record");
        require(number("SELECT COUNT(*) FROM course_selection_window"
                + " WHERE academic_year=2027 AND semester=1") == 0,
                "publishing must not fabricate a selection window");

        for (int week = 4; week <= 11; week++) {
            List<ScheduleEntryDTO> entries = schedule(student, studentToken, week);
            if (week == 4 || week == 11) {
                require(entries.isEmpty(), "slots must be absent outside weeks 5-10");
                continue;
            }
            require(entries.size() == 2, "each applicable week must expose exactly two slots");
            assertEntry(entries.get(0), week, 2, 1);
            assertEntry(entries.get(1), week, 4, 3);
        }
        System.out.println("[E2E] publication without a selection window, weeks 4-11 and permissions: PASS");
    }

    private static void assertEntry(ScheduleEntryDTO entry, int week, int day, int startPeriod) {
        require(Long.toString(OFFERING).equals(entry.getOfferingId())
                        && "SCHED-E2E".equals(entry.getCourseCode())
                        && "Schedule Socket Room".equals(entry.getLocation())
                        && entry.getTeacher() != null && !entry.getTeacher().isBlank(),
                "the enrolled student's schedule must contain the saved course and resources");
        require(entry.getDayOfWeek() == day && entry.getStartPeriod() == startPeriod
                        && entry.getPeriodCount() == 2
                        && entry.getStartWeek() == week && entry.getEndWeek() == week,
                "the returned slot must match its weekday, periods and queried week");
    }

    private static void verifyPointerCompatibility(JsonLineClient student, String token) throws Exception {
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status)"
                + " VALUES(?,'Schedule legacy empty plan',?,1,'PUBLISHED'),"
                + "(?,'Schedule unpublished plan',?,1,'DRAFT')", LEGACY_PLAN, CALENDAR, DRAFT_PLAN, CALENDAR);
        execute("INSERT INTO course_selection_window(window_id,academic_year,semester,schedule_plan_id,"
                + "plan_open_at,plan_close_at,selection_open_at,selection_close_at,drop_deadline)"
                + " VALUES(?,2027,1,?,'2020-01-01','2020-01-02','2020-01-03','2020-01-04','2020-01-05')",
                WINDOW, LEGACY_PLAN);
        require(schedule(student, token, 5).size() == 2,
                "the current calendar pointer must take precedence over an older window binding");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=?", CALENDAR);
        require(schedule(student, token, 5).isEmpty(),
                "a null current pointer must preserve the legacy window fallback");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", DRAFT_PLAN, CALENDAR);
        requireCode(scheduleResponse(student, token, 5), MessageCode.ERROR,
                "an unpublished current pointer must not fall back to a historical window");

        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(?,'Schedule later calendar',2027,1,'2027-06-07',"
                + "'Asia/Shanghai',2,'PUBLISHED')", LATER_CALENDAR);
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status)"
                + " VALUES(?,'Schedule later empty plan',?,1,'PUBLISHED')", LATER_PLAN, LATER_CALENDAR);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", LATER_PLAN, CALENDAR);
        requireCode(scheduleResponse(student, token, 5), MessageCode.ERROR,
                "a current pointer to another calendar must not be accepted or fall back");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", PLAN, CALENDAR);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", LATER_PLAN, LATER_CALENDAR);
        require(schedule(student, token, 5).isEmpty(),
                "the latest calendar version with a current pointer must win deterministically");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=?", LATER_CALENDAR);
        require(schedule(student, token, 5).size() == 2,
                "a later calendar without a current pointer must not hide the published schedule");
        System.out.println("[E2E] pointer priority, legacy fallback, invalid pointers and calendar versions: PASS");
    }

    private static SaveArrangementRequestDTO saveRequest(int operation, String arrangementId, int version) {
        return new SaveArrangementRequestDTO(op(operation), arrangementId, version, Long.toString(PLAN),
                Long.toString(OFFERING), "teacher-alpha", null, Long.toString(ROOM),
                List.of(new ScheduleSlotDTO(2, 1, 2), new ScheduleSlotDTO(4, 3, 4)),
                5, 10, false, null);
    }

    private static Map<String, Object> publishRequest(int operation, int revision) {
        return Map.of("planId", Long.toString(PLAN), "expectedRevision", revision,
                "operationId", op(operation), "force", false);
    }

    private static <T> T writeResult(Message response, String operationId, Class<T> entityType) {
        requireCode(response, MessageCode.SUCCESS, "administrator scheduling write");
        AdminOperationResultDTO<T> result = GSON.fromJson(GSON.toJsonTree(response.getData("result")),
                TypeToken.getParameterized(AdminOperationResultDTO.class, entityType).getType());
        require(result != null && operationId.equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode()) && result.getEntity() != null,
                "write must return its operation identity and authoritative entity");
        return result.getEntity();
    }

    private static Message scheduleResponse(JsonLineClient client, String token, int week) throws IOException {
        // A spoofed data UID must not override the authenticated student's identity.
        return client.request("course", "loadSchedule", token,
                Map.of("academicYear", 2027, "semester", 1, "week", week, "uid", "student-beta"));
    }

    private static List<ScheduleEntryDTO> schedule(JsonLineClient client, String token, int week)
            throws IOException {
        Message response = scheduleResponse(client, token, week);
        requireCode(response, MessageCode.SUCCESS, "student schedule for week " + week);
        List<ScheduleEntryDTO> entries = GSON.fromJson(GSON.toJsonTree(response.getData("schedule")),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
        require(entries != null, "successful schedule response must include its list");
        return entries;
    }

    private static void verifyStoredArrangement(int version) throws SQLException {
        require(number("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id=?", PLAN) == 1,
                "exactly one arrangement must persist");
        require(number("SELECT version FROM course_schedule_arrangement WHERE plan_id=?", PLAN) == version,
                "the arrangement version must match the committed write");
        require(number("SELECT COUNT(*) FROM course_schedule_rule WHERE plan_id=?", PLAN) == 2,
                "both slots must persist as rules");
        require(number("SELECT COUNT(*) FROM course_schedule_rule_week rw JOIN course_schedule_rule r"
                + " ON r.id=rw.rule_id WHERE r.plan_id=?", PLAN) == 12, "six weeks per slot must persist");
        require(number("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=?", PLAN) == 12,
                "both slots must generate an occurrence for each applicable week");
        require(number("SELECT COUNT(*) FROM resource_booking WHERE plan_id=?", PLAN) == 24,
                "each occurrence must book its teacher and classroom exactly once");
        require("2027-07-06 00:00:00".equals(text(
                "SELECT DATE_FORMAT(MIN(start_at),'%Y-%m-%d %H:%i:%s') FROM course_occurrence WHERE plan_id=?",
                PLAN)), "local calendar times must be stored in UTC");
    }

    private static void insertFixtures() throws SQLException {
        require(number("SELECT COUNT(*) FROM tbl_user WHERE UID IN ('admin-alpha','student-alpha',"
                + "'teacher-alpha')") == 3, "apply seed-course-test.sql before this test");
        require(number("SELECT COUNT(*) FROM schedule_resource WHERE id=4401"
                + " AND resource_type='teacher' AND business_id='teacher-alpha'") == 1,
                "reuse the seeded teacher resource");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,course_type)"
                + " VALUES(?,'SCHED-E2E','Scheduling socket test',2.00,32,3)", COURSE);
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,enrolled_count,status) VALUES(?,'SCHED-E2E-2027-1',?,2027,1,30,1,2)",
                OFFERING, COURSE);
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(?,'teacher-alpha',0)",
                OFFERING);
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,uid,status)"
                + " VALUES(?,?,?,2027,1,'student-alpha',2)", ENROLLMENT, OFFERING, COURSE);
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(?,'Schedule socket calendar',2027,1,'2027-06-07',"
                + "'Asia/Shanghai',1,'PUBLISHED')", CALENDAR);
        execute("INSERT INTO day_template(id,name,version) VALUES(?,'Schedule socket template',1)", TEMPLATE);
        String[] starts = { "08:00:00", "08:45:00", "09:30:00", "10:15:00" };
        String[] ends = { "08:45:00", "09:30:00", "10:15:00", "11:00:00" };
        for (int period = 1; period <= 4; period++) {
            execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                    + " VALUES(?,?,?,?,?)", 950010 + period, TEMPLATE, period, starts[period - 1], ends[period - 1]);
        }
        int dateId = 950020;
        for (int week = 5; week <= 10; week++) {
            for (int day : List.of(2, 4)) {
                LocalDate date = LocalDate.of(2027, 6, 7).plusDays((week - 1) * 7L + day - 1);
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                        + "day_template_id,is_teaching_day) VALUES(?,?,?,?,?,?,1)",
                        dateId++, CALENDAR, date, week, day, TEMPLATE);
            }
        }
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status)"
                + " VALUES(?,'Schedule socket draft',?,1,'DRAFT')", PLAN, CALENDAR);
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(?,'Schedule Socket Room',60,1)", ROOM);
        execute("INSERT INTO schedule_resource(id,resource_type,business_id,conflict_mode)"
                + " VALUES(?,'classroom',?,'EXCLUSIVE')", ROOM_RESOURCE, Long.toString(ROOM));
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE operation_id LIKE '95000000-%'");
        execute("DELETE FROM course_selection_window WHERE window_id=?", WINDOW);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id IN (?,?)",
                CALENDAR, LATER_CALENDAR);
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 950003 AND 950007");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 950003 AND 950007");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 950003 AND 950007");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 950003 AND 950007");
        execute("DELETE FROM enrollment WHERE enrollment_id=?", ENROLLMENT);
        execute("DELETE FROM course_offering WHERE offering_id=?", OFFERING);
        execute("DELETE FROM course WHERE course_id=?", COURSE);
        execute("DELETE FROM schedule_resource WHERE id=?", ROOM_RESOURCE);
        execute("DELETE FROM classroom WHERE id=?", ROOM);
        execute("DELETE FROM calendar_date WHERE calendar_id IN (?,?)", CALENDAR, LATER_CALENDAR);
        execute("DELETE FROM day_template WHERE id=?", TEMPLATE);
        execute("DELETE FROM teaching_calendar WHERE id IN (?,?)", CALENDAR, LATER_CALENDAR);
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = DBUtil.class.getClassLoader().getResourceAsStream("resources/db.properties")) {
            require(input != null, "database configuration is unavailable");
            properties.load(input);
        }
        String url = properties.getProperty("db.url", "");
        require(url.startsWith("jdbc:mysql:"), "a MySQL test URL is required");
        require(("/" + TEST_DATABASE).equals(URI.create(url.substring(5)).getPath()),
                "refusing scheduling test outside the dedicated test schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")), "connected database must be the test schema");
    }

    private static Object scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "database assertion must return a row");
                return rows.getObject(1);
            }
        }
    }

    private static long number(String sql, Object... parameters) throws SQLException {
        return ((Number) scalar(sql, parameters)).longValue();
    }

    private static String text(String sql, Object... parameters) throws SQLException {
        Object result = scalar(sql, parameters);
        return result == null ? null : result.toString();
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
    }

    private static String op(int value) {
        return String.format("95000000-0000-0000-0000-%012d", value);
    }

    private static void requireCode(Message response, MessageCode expected, String action) {
        require(response.getCode() == expected, action + ": expected " + expected
                + ", got " + response.getCode() + " (" + response.getMessage() + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private JsonLineClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String login(String uid, String role) throws IOException {
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", "course-test-only", "role", role));
            requireCode(response, MessageCode.SUCCESS, "test login");
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must supply a token");
            require(role.equals(response.getData("role")), "login must return the requested role");
            return (String) token;
        }

        private Message admin(String action, String token, Map<String, Object> data) throws IOException {
            return request("courseAdmin", action, token, data);
        }

        private Message request(String module, String action, String token, Map<String, Object> data)
                throws IOException {
            Message request = new Message(MessageType.REQUEST, module, action);
            request.setToken(token);
            request.setSender(ADMIN); // Student writes must still be rejected despite this spoofed sender.
            data.forEach(request::putData);
            writer.write(GSON.toJson(request));
            writer.newLine();
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                Message response = GSON.fromJson(line, Message.class);
                if (response.getType() == MessageType.PUSH) continue;
                require(response.getType() == MessageType.RESPONSE && request.getUID().equals(response.getUID()),
                        "response must correlate to its request");
                return response;
            }
            throw new IOException("server closed before responding to " + action);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
