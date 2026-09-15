package integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dao.AdminOfferingDAO;
import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * T1 教师查询的端到端验证：真实 TCP 登录 → {@code courseTeacher} 查询 → 客户端 DTO 映射。
 *
 * <p>只有 JDBC 数据库正好是 {@code virtual_campus_course_test} 时才继续。测试自己重建受保护的
 * 架构（完整 init.sql → V001 → V002 → V003 → seed-course-test.sql → V004 → V005），
 * 再插入私有 ID 范围（教学班 9902xx、选课 9903xx、学籍 9909xx、UID 前缀 {@code tqt-student-}）的
 * fixture，结束时只删除自己的行。写入测试必须串行运行，本类不做任何并行。
 *
 * <p>随后启动真实 {@link Server}（OS 分配的 loopback 端口）并驱动真实 JSON-line 协议，断言的是
 * 具体数据与失败码，而不是“响应非空”：本人教学班与详情的真实字段、正常/退课名单与分页、
 * 正式方案中的全部安排、旧学期切换，以及越权与伪造身份的 403/401/400。
 *
 * <p>伪造身份用例在 socket 层再验一遍：请求体声称别的 uid/teacherId 也不能替换 token 对应的
 * 会话身份——若服务端信任请求体，访问 teacher B 的教学班就会从 FORBIDDEN 变成成功。
 */
public final class TeacherCourseQuerySocketEndToEndTest {

    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String LOGIN_PASSWORD = "course-test-only";
    private static final String TEACHER_ROLE = "教师";
    private static final String STUDENT_ROLE = "学生";
    private static final String TEACHER_A = "teacher-alpha";
    private static final String TEACHER_B = "teacher-beta";
    private static final String STUDENT = "student-alpha";
    private static final String OTHER_STUDENT = "student-beta";

    private static final int CURRENT_YEAR = 2026;
    private static final int CURRENT_SEMESTER = 2;
    private static final int OLD_YEAR = 2025;
    private static final int OLD_SEMESTER = 2;
    private static final int UNRELATED_YEAR = 2027;
    private static final int UNRELATED_SEMESTER = 3;

    /** Seeded fixtures this test reads but does not own. */
    private static final String SEEDED_OFFERING = "2001";
    private static final String SEEDED_OFFERING_CODE = "CS101-2026-2-A";
    private static final String SEEDED_COURSE_NAME = "Programming Fundamentals";
    private static final String OTHER_TEACHER_OFFERING = "2002";
    private static final String OTHER_TEACHER_OFFERING_CODE = "CS101-2026-2-B";
    private static final String MISSING_OFFERING = "999999999";

    /** Own fixture range: nothing outside 9902xx/9903xx/9909xx and the {@code tqt-student-} prefix. */
    private static final long OWN_OFFERING_CURRENT = 990201L;
    private static final long OWN_OFFERING_OLD = 990202L;
    private static final String OWN_OFFERING_CURRENT_CODE = "TEO9-2026-2-A";
    private static final String OWN_OFFERING_OLD_CODE = "TEO9-2025-2-A";
    private static final long[] OWN_ENROLLMENTS = {990301L, 990302L, 990303L, 990304L, 990305L};
    private static final long[] OWN_PROFILES = {990901L, 990902L, 990903L};
    private static final String[] OWN_STUDENTS = {"tqt-student-1", "tqt-student-2", "tqt-student-3"};
    private static final String OWN_STUDENT_MAJOR = "Computer Science";
    /** Seed-course-test.sql 的测试口令哈希，仅用于测试 schema 内的新学生账号。 */
    private static final String SEEDED_PASSWORD_HASH =
            "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String SEEDED_PASSWORD_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";

    private static final Gson GSON = new Gson();
    private static final Type TERM_LIST = new TypeToken<List<CourseTermDTO>>() { }.getType();
    private static final Type OFFERING_PAGE =
            new TypeToken<TeacherPageDTO<TeacherOfferingDTO>>() { }.getType();
    private static final Type ROSTER_PAGE =
            new TypeToken<TeacherPageDTO<TeacherRosterRowDTO>>() { }.getType();
    private static final Type OFFERING_DETAIL =
            new TypeToken<TeacherOfferingDetailDTO>() { }.getType();
    private static final Type SCHEDULE_LIST =
            new TypeToken<List<ScheduleArrangementDTO>>() { }.getType();
    private TeacherCourseQuerySocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path config = root.resolve("VCampusServer/src/resources/db.properties");
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) {
                continue;
            }
            if (argument.startsWith("--config=")) {
                config = resolveConfig(root, argument.substring("--config=".length()));
                continue;
            }
            throw new AssertionError("Unsupported argument: " + argument);
        }
        Properties properties = loadProperties(config);
        requireTestDatabase(requiredProperty(properties, "db.url"));
        String jdbc = withTestAuthentication(requiredProperty(properties, "db.url"));
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");
        Class.forName(requiredProperty(properties, "db.driver"));

        System.out.println("[E2E] reset + fresh install order"
                + " (init.sql -> V001 -> V002 -> V003 -> seed -> V004 -> V005)");
        resetAndSeed(jdbc, username, password, root);
        insertFixtures(jdbc, username, password);
        System.out.println("[E2E] fresh post-V005 schema with the teacher query fixtures in place");

        Harness harness = new Harness();
        try {
            harness.start();
            System.out.println("[E2E] server listening on 127.0.0.1:" + harness.port());
            runScenarios(harness, jdbc, username, password);
        } finally {
            harness.stop();
        }
        cleanFixtures(jdbc, username, password);
        System.out.println("Teacher course query socket end-to-end test passed.");
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    private static void runScenarios(Harness harness, String jdbc, String user, String pass)
            throws Exception {
        try (JsonLineClient teacherA = harness.client();
             JsonLineClient teacherB = harness.client();
             JsonLineClient student = harness.client()) {
            String tokenA = teacherA.login(TEACHER_A, TEACHER_ROLE);
            String tokenB = teacherB.login(TEACHER_B, TEACHER_ROLE);
            String studentToken = student.login(STUDENT, STUDENT_ROLE);

            missingOrInvalidTokenIsUnauthorized(teacherA, studentToken);
            nonTeacherTokenIsForbidden(teacherA, studentToken);
            listTerms(teacherA, tokenA);
            listOwnOfferings(teacherA, tokenA);
            readOfferingDetail(teacherA, tokenA, jdbc, user, pass);
            readRoster(teacherA, tokenA);
            readSchedules(teacherA, tokenA);
            switchToOlderTerm(teacherA, tokenA);
            otherTeacherSeesOnlyTheirOwnOfferings(teacherB, tokenB);
            crossTeacherAccessIsForbidden(teacherA, teacherB, tokenA, tokenB);
            forgedIdentityCannotReplaceTheSession(teacherA, tokenA);
            invalidInputIsRejected(teacherA, tokenA);
        }
    }

    private static void missingOrInvalidTokenIsUnauthorized(JsonLineClient client, String token) {
        Message withoutToken = client.teacher(TeacherCourseActions.LIST_TERMS, null, Map.of());
        require(withoutToken.getCode() == MessageCode.UNAUTHORIZED,
                "a request without a token must be UNAUTHORIZED but was " + withoutToken.getCode());

        Message forgedToken = client.teacher(TeacherCourseActions.LIST_TERMS, "not-a-real-token",
                Map.of());
        require(forgedToken.getCode() == MessageCode.UNAUTHORIZED,
                "an unknown token must be UNAUTHORIZED but was " + forgedToken.getCode());
        System.out.println("[E2E] missing/unknown token -> UNAUTHORIZED");
    }

    private static void nonTeacherTokenIsForbidden(JsonLineClient client, String studentToken) {
        Map<String, Object> detail = Map.of("offeringId", SEEDED_OFFERING);
        Message terms = client.teacher(TeacherCourseActions.LIST_TERMS, studentToken, Map.of());
        require(terms.getCode() == MessageCode.FORBIDDEN,
                "a student token must be FORBIDDEN on listTerms but was " + terms.getCode());
        Message offering = client.teacher(TeacherCourseActions.GET_OFFERING, studentToken, detail);
        require(offering.getCode() == MessageCode.FORBIDDEN,
                "a student token must be FORBIDDEN on getOffering but was " + offering.getCode());
        System.out.println("[E2E] student token -> FORBIDDEN on every courseTeacher action");
    }

    private static void listTerms(JsonLineClient client, String token) {
        List<CourseTermDTO> terms = termList(
                client.teacher(TeacherCourseActions.LIST_TERMS, token, Map.of()), "listTerms");
        require(terms.size() == 2, "teacher A must see exactly two terms but saw " + terms.size());
        requireTerm(terms.get(0), CURRENT_YEAR, CURRENT_SEMESTER, "2026-2027 秋学期");
        requireTerm(terms.get(1), OLD_YEAR, OLD_SEMESTER, "2025-2026 秋学期");
        require(terms.stream().noneMatch(term -> term.getAcademicYear() == UNRELATED_YEAR),
                "a term without any relation to teacher A must not be listed");
        System.out.println("[E2E] teacher A listTerms -> " + terms.size() + " own terms");
    }

    private static void listOwnOfferings(JsonLineClient client, String token) {
        TeacherPageDTO<TeacherOfferingDTO> page = offeringPage(client.teacher(
                TeacherCourseActions.LIST_OFFERINGS, token,
                offeringQuery(CURRENT_YEAR, CURRENT_SEMESTER, null, 1, 20)), "listOfferings");

        require(page.getPage() == 1 && page.getSize() == 20,
                "the page must echo page 1/size 20 but was " + page.getPage() + "/" + page.getSize());
        require(page.getTotalCount() == 2,
                "teacher A must own two current-term classes but totalCount was "
                        + page.getTotalCount());
        require(page.getItems().size() == 2,
                "the first page must hold both classes but had " + page.getItems().size());
        require(page.getItems().stream()
                        .noneMatch(offering -> OTHER_TEACHER_OFFERING_CODE.equals(
                                offering.getOfferingCode())),
                "teacher A must never see teacher B's class " + OTHER_TEACHER_OFFERING_CODE);

        TeacherOfferingDTO seeded = page.getItems().get(0);
        require(SEEDED_OFFERING_CODE.equals(seeded.getOfferingCode()),
                "the classes must be ordered by offering code, first was "
                        + seeded.getOfferingCode());
        require(SEEDED_OFFERING.equals(seeded.getOfferingId()),
                "offering id must round-trip as a decimal string but was "
                        + seeded.getOfferingId());
        require(SEEDED_COURSE_NAME.equals(seeded.getCourseName())
                        && "CS101".equals(seeded.getCourseCode()),
                "the seeded course fields must round-trip, saw " + seeded.getCourseCode() + "/"
                        + seeded.getCourseName());
        require(SEEDED_COURSE_NAME.concat(" ").concat(SEEDED_OFFERING_CODE)
                        .equals(seeded.getOfferingName()),
                "the display name must be course name + offering code but was "
                        + seeded.getOfferingName());
        require(seeded.getCredit() == 3.0 && seeded.getAcademicYear() == CURRENT_YEAR
                        && seeded.getSemester() == CURRENT_SEMESTER,
                "credit/term must round-trip on the seeded class");
        require(seeded.getEnrolledCount() == 1 && seeded.getCapacity() == 30,
                "the seeded class must carry its real 1/30 enrollment but was "
                        + seeded.getEnrolledCount() + "/" + seeded.getCapacity());
        require(AdminOfferingDAO.statusLabel(2).equals(seeded.getStatus()),
                "course_offering.status 2 must map to OPEN but was " + seeded.getStatus());
        require(seeded.isCanEditGrades() && seeded.isCanRequestAdjustment(),
                "a role=0 owner must be able to edit grades and request adjustments");

        TeacherOfferingDTO own = page.getItems().get(1);
        require(OWN_OFFERING_CURRENT_CODE.equals(own.getOfferingCode())
                        && Long.toString(OWN_OFFERING_CURRENT).equals(own.getOfferingId()),
                "the second class must be the fixture " + OWN_OFFERING_CURRENT_CODE + " but was "
                        + own.getOfferingCode());
        require(AdminOfferingDAO.statusLabel(1).equals(own.getStatus()),
                "status 1 must map to NOT_OPEN but was " + own.getStatus());
        require(own.getEnrolledCount() == 0 && own.getCapacity() == 20,
                "the fixture class must carry its real 0/20 enrollment but was "
                        + own.getEnrolledCount() + "/" + own.getCapacity());
        System.out.println("[E2E] teacher A listOfferings -> " + SEEDED_OFFERING_CODE + ", "
                + OWN_OFFERING_CURRENT_CODE);
    }

    private static void readOfferingDetail(JsonLineClient client, String token, String jdbc,
                                           String user, String pass) throws SQLException {
        TeacherOfferingDetailDTO detail = offeringDetail(client.teacher(
                TeacherCourseActions.GET_OFFERING, token,
                Map.of("offeringId", SEEDED_OFFERING)), "getOffering");

        require(detail.getOffering() != null, "the detail must embed the offering summary");
        require(SEEDED_OFFERING_CODE.equals(detail.getOffering().getOfferingCode()),
                "the detail must describe " + SEEDED_OFFERING_CODE + " but was "
                        + detail.getOffering().getOfferingCode());
        require("Programming and problem solving".equals(detail.getDescription()),
                "the course description must round-trip but was " + detail.getDescription());
        require(detail.getOfferingCollege() == null,
                "V005 adds course.offering_college without backfilling history, so the seeded"
                        + " class must report null rather than the teacher's own college but was "
                        + detail.getOfferingCollege());
        require(detail.getTeachers().size() == 1,
                "the seeded class has exactly one role=0 teacher but had "
                        + detail.getTeachers().size());
        ScheduleResourceDTO teacher = detail.getTeachers().get(0);
        require(TEACHER_A.equals(teacher.getBusinessId()),
                "the detail teacher must be " + TEACHER_A + " but was " + teacher.getBusinessId());

        assertDatabase(jdbc, user, pass, connection -> require(
                queryString(connection, "SELECT offering_college FROM course WHERE course_id=1001")
                        == null,
                "the guarded schema must keep offering_college NULL for the seeded course"));
        System.out.println("[E2E] teacher A getOffering 2001 -> " + SEEDED_OFFERING_CODE
                + " with a null offering college");
    }

    private static void readRoster(JsonLineClient client, String token) {
        Message raw = client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                rosterQuery(Long.toString(OWN_OFFERING_OLD), null, null, 1, 2));
        TeacherPageDTO<TeacherRosterRowDTO> page = rosterPage(raw, "listOfferingStudents");
        require(page.getTotalCount() == 5,
                "the fixture class must hold five enrollment rows but totalCount was "
                        + page.getTotalCount());
        require(page.getItems().size() == 2,
                "page 1 of size 2 must be full but had " + page.getItems().size());

        TeacherRosterRowDTO enrolled = page.getItems().get(0);
        require(STUDENT.equals(enrolled.getStudentUid()),
                "rows are ordered by uid, so student-alpha comes first but was "
                        + enrolled.getStudentUid());
        require("Course Test Student A".equals(enrolled.getStudentName()),
                "the roster must carry the account name but was " + enrolled.getStudentName());
        require("ENROLLED".equals(enrolled.getEnrollmentStatus()) && enrolled.getDroppedAt() == null,
                "a status=2 row must be ENROLLED with no drop time");
        require(OWN_STUDENT_MAJOR.equals(enrolled.getMajor()),
                "the major must come from the academic profile but was " + enrolled.getMajor());
        require(enrolled.getSelectedAt() != null && enrolled.getSelectedAt().endsWith("Z"),
                "the selection time must be a UTC ISO-8601 string but was "
                        + enrolled.getSelectedAt());

        TeacherRosterRowDTO dropped = page.getItems().get(1);
        require(OTHER_STUDENT.equals(dropped.getStudentUid())
                        && "DROPPED".equals(dropped.getEnrollmentStatus()),
                "the second row must be the dropped history of " + OTHER_STUDENT);
        require(dropped.getDroppedAt() != null,
                "a status=3 row must keep its drop time");

        long[] expectedSizes = {2, 2, 1};
        for (int pageNumber = 1; pageNumber <= expectedSizes.length; pageNumber++) {
            TeacherPageDTO<TeacherRosterRowDTO> slice = rosterPage(
                    client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                            rosterQuery(Long.toString(OWN_OFFERING_OLD), null, null, pageNumber, 2)),
                    "listOfferingStudents page " + pageNumber);
            require(slice.getItems().size() == expectedSizes[pageNumber - 1],
                    "page " + pageNumber + " must hold " + expectedSizes[pageNumber - 1]
                            + " rows but had " + slice.getItems().size());
            require(slice.getPage() == pageNumber && slice.getSize() == 2
                            && slice.getTotalCount() == 5,
                    "every page must echo its own page/size and the same totalCount");
        }

        TeacherPageDTO<TeacherRosterRowDTO> droppedOnly = rosterPage(
                client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                        rosterQuery(Long.toString(OWN_OFFERING_OLD), null, 3, 1, 20)),
                "listOfferingStudents status=3");
        require(droppedOnly.getTotalCount() == 1 && droppedOnly.getItems().size() == 1
                        && OTHER_STUDENT.equals(droppedOnly.getItems().get(0).getStudentUid()),
                "enrollmentStatus 3 must return exactly the dropped row");

        TeacherPageDTO<TeacherRosterRowDTO> enrolledOnly = rosterPage(
                client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                        rosterQuery(Long.toString(OWN_OFFERING_OLD), null, 2, 1, 20)),
                "listOfferingStudents status=2");
        require(enrolledOnly.getTotalCount() == 4 && enrolledOnly.getItems().size() == 4,
                "enrollmentStatus 2 must return the four normal rows but had "
                        + enrolledOnly.getItems().size());
        require(enrolledOnly.getItems().stream().allMatch(
                        row -> "ENROLLED".equals(row.getEnrollmentStatus())
                                && row.getDroppedAt() == null),
                "the normal filter must exclude every dropped row");

        TeacherPageDTO<TeacherRosterRowDTO> searched = rosterPage(
                client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                        rosterQuery(Long.toString(OWN_OFFERING_OLD), "tqt-student-2", null, 1, 20)),
                "listOfferingStudents query");
        require(searched.getTotalCount() == 1 && searched.getItems().size() == 1
                        && OWN_STUDENTS[1].equals(searched.getItems().get(0).getStudentUid()),
                "the uid filter must return exactly the matching student");

        TeacherPageDTO<TeacherRosterRowDTO> seeded = rosterPage(
                client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                        rosterQuery(SEEDED_OFFERING, null, null, 1, 20)),
                "listOfferingStudents on the seeded class");
        require(seeded.getTotalCount() == 1 && seeded.getItems().size() == 1,
                "the seeded class must hold its one real enrollment but had "
                        + seeded.getTotalCount());
        System.out.println("[E2E] teacher A listOfferingStudents -> 5 rows (4 normal, 1 dropped),"
                + " paged and filtered");
    }

    private static void readSchedules(JsonLineClient client, String token) {
        List<ScheduleArrangementDTO> arrangements = scheduleList(client.teacher(
                TeacherCourseActions.LIST_OFFERING_SCHEDULES, token,
                Map.of("offeringId", SEEDED_OFFERING)), "listOfferingSchedules");
        require(arrangements.size() == 2,
                "the seeded class has two arrangements in the PUBLISHED plan but had "
                        + arrangements.size());
        require("4101".equals(arrangements.get(0).getArrangementId())
                        && "4102".equals(arrangements.get(1).getArrangementId()),
                "the arrangements must be the seeded 4101/4102 but were "
                        + arrangements.get(0).getArrangementId() + "/"
                        + arrangements.get(1).getArrangementId());
        require(arrangements.stream().allMatch(arrangement ->
                        "4001".equals(arrangement.getPlanId())
                                && SEEDED_OFFERING.equals(arrangement.getOfferingId())
                                && "ACTIVE".equals(arrangement.getStatus())),
                "every arrangement must belong to the PUBLISHED plan 4001 and be ACTIVE");
        ScheduleArrangementDTO first = arrangements.get(0);
        require(first.getSlots().size() == 1
                        && first.getSlots().get(0).getDayOfWeek() == 2
                        && first.getSlots().get(0).getStartPeriod() == 1
                        && first.getSlots().get(0).getEndPeriod() == 2,
                "arrangement 4101 must expose its 周二 1-2 slot but was " + first.getSlots());
        require(first.getTeacher() == null && first.getClassroom() == null,
                "the seeded plan assigns no teacher_uid/classroom_id, so the DTO must report null"
                        + " rather than inventing a resource name but was " + first.getTeacher()
                        + "/" + first.getClassroom());
        require(first.getStartWeek() == 1 && first.getEndWeek() == 1,
                "the arrangement week range must come from the seeded rule weeks but was "
                        + first.getStartWeek() + "-" + first.getEndWeek());

        List<ScheduleArrangementDTO> unrelated = scheduleList(client.teacher(
                TeacherCourseActions.LIST_OFFERING_SCHEDULES, token,
                Map.of("offeringId", Long.toString(OWN_OFFERING_OLD))), "listOfferingSchedules");
        require(unrelated.isEmpty(),
                "a class whose term has no PUBLISHED plan must return an empty list, never a"
                        + " DRAFT plan or another term's arrangements but had " + unrelated.size());
        System.out.println("[E2E] teacher A listOfferingSchedules -> 2 published arrangements,"
                + " empty for a term without a published plan");
    }

    private static void switchToOlderTerm(JsonLineClient client, String token) {
        TeacherPageDTO<TeacherOfferingDTO> older = offeringPage(client.teacher(
                TeacherCourseActions.LIST_OFFERINGS, token,
                offeringQuery(OLD_YEAR, OLD_SEMESTER, null, 1, 20)), "listOfferings older term");
        require(older.getTotalCount() == 1 && older.getItems().size() == 1,
                "the older term must list exactly the fixture class but had "
                        + older.getItems().size());
        TeacherOfferingDTO offering = older.getItems().get(0);
        require(OWN_OFFERING_OLD_CODE.equals(offering.getOfferingCode())
                        && offering.getAcademicYear() == OLD_YEAR
                        && offering.getSemester() == OLD_SEMESTER,
                "the older term must return " + OWN_OFFERING_OLD_CODE + " but returned "
                        + offering.getOfferingCode());
        require(offering.getEnrolledCount() == 4 && offering.getCapacity() == 30,
                "the fixture class must report its real 4/30 enrollment but was "
                        + offering.getEnrolledCount() + "/" + offering.getCapacity());

        TeacherPageDTO<TeacherOfferingDTO> unrelated = offeringPage(client.teacher(
                TeacherCourseActions.LIST_OFFERINGS, token,
                offeringQuery(UNRELATED_YEAR, UNRELATED_SEMESTER, null, 1, 20)),
                "listOfferings unrelated term");
        require(unrelated.getTotalCount() == 0 && unrelated.getItems().isEmpty(),
                "a term with no relation to teacher A must be empty even though another teacher's"
                        + " class exists in it, saw " + unrelated.getTotalCount());
        System.out.println("[E2E] teacher A listOfferings " + OLD_YEAR + "/" + OLD_SEMESTER
                + " -> " + OWN_OFFERING_OLD_CODE + "; " + UNRELATED_YEAR + "/"
                + UNRELATED_SEMESTER + " -> empty");
    }

    private static void otherTeacherSeesOnlyTheirOwnOfferings(JsonLineClient client, String token) {
        TeacherPageDTO<TeacherOfferingDTO> page = offeringPage(client.teacher(
                TeacherCourseActions.LIST_OFFERINGS, token,
                offeringQuery(CURRENT_YEAR, CURRENT_SEMESTER, null, 1, 20)),
                "teacher B listOfferings");
        require(page.getTotalCount() == 1 && page.getItems().size() == 1,
                "teacher B owns exactly one current-term class but totalCount was "
                        + page.getTotalCount());
        require(OTHER_TEACHER_OFFERING_CODE.equals(page.getItems().get(0).getOfferingCode()),
                "teacher B must see " + OTHER_TEACHER_OFFERING_CODE + " but saw "
                        + page.getItems().get(0).getOfferingCode());
        require(page.getItems().stream().noneMatch(offering ->
                        SEEDED_OFFERING_CODE.equals(offering.getOfferingCode())
                                || OWN_OFFERING_CURRENT_CODE.equals(offering.getOfferingCode())),
                "teacher B must not see any of teacher A's classes");
        System.out.println("[E2E] teacher B listOfferings -> " + OTHER_TEACHER_OFFERING_CODE
                + " only (differs from teacher A)");
    }

    private static void crossTeacherAccessIsForbidden(JsonLineClient teacherA,
                                                      JsonLineClient teacherB, String tokenA,
                                                      String tokenB) {
        Message deniedDetail = teacherA.teacher(TeacherCourseActions.GET_OFFERING, tokenA,
                Map.of("offeringId", OTHER_TEACHER_OFFERING));
        require(deniedDetail.getCode() == MessageCode.FORBIDDEN,
                "teacher A on teacher B's class must be FORBIDDEN for getOffering but was "
                        + deniedDetail.getCode());
        Message deniedRoster = teacherA.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS,
                tokenA, rosterQuery(OTHER_TEACHER_OFFERING, null, null, 1, 20));
        require(deniedRoster.getCode() == MessageCode.FORBIDDEN,
                "teacher A on teacher B's class must be FORBIDDEN for listOfferingStudents but"
                        + " was " + deniedRoster.getCode());
        Message deniedSchedules = teacherA.teacher(TeacherCourseActions.LIST_OFFERING_SCHEDULES,
                tokenA, Map.of("offeringId", OTHER_TEACHER_OFFERING));
        require(deniedSchedules.getCode() == MessageCode.FORBIDDEN,
                "teacher A on teacher B's class must be FORBIDDEN for listOfferingSchedules but"
                        + " was " + deniedSchedules.getCode());
        Message missing = teacherA.teacher(TeacherCourseActions.GET_OFFERING, tokenA,
                Map.of("offeringId", MISSING_OFFERING));
        require(missing.getCode() == MessageCode.FORBIDDEN,
                "an unknown class id must be FORBIDDEN, not an existence oracle, but was "
                        + missing.getCode() + "/" + missing.getMessage());
        Message reverse = teacherB.teacher(TeacherCourseActions.GET_OFFERING, tokenB,
                Map.of("offeringId", SEEDED_OFFERING));
        require(reverse.getCode() == MessageCode.FORBIDDEN,
                "teacher B on teacher A's class must be FORBIDDEN but was " + reverse.getCode());
        System.out.println("[E2E] cross-teacher and unknown class ids -> FORBIDDEN");
    }

    /**
     * 请求体声称别的身份（其他教师、学生）时，服务端必须只认 token 的会话身份。若它信任请求体，
     * 后面两个断言会从 FORBIDDEN 变成成功，本用例因此是决定性的。
     */
    private static void forgedIdentityCannotReplaceTheSession(JsonLineClient client, String token) {
        Map<String, Object> forgedListing = offeringQuery(CURRENT_YEAR, CURRENT_SEMESTER, null, 1, 20);
        forgedListing.put("uid", TEACHER_B);
        forgedListing.put("teacherId", TEACHER_B);
        forgedListing.put("sender", TEACHER_B);
        TeacherPageDTO<TeacherOfferingDTO> page = offeringPage(
                client.teacher(TeacherCourseActions.LIST_OFFERINGS, token, forgedListing),
                "forged listOfferings");
        require(page.getTotalCount() == 2 && page.getItems().stream().noneMatch(offering ->
                        OTHER_TEACHER_OFFERING_CODE.equals(offering.getOfferingCode())),
                "a body claiming teacher B must still be served as teacher A but returned "
                        + page.getTotalCount() + " classes");

        Map<String, Object> forgedDetail = new LinkedHashMap<>();
        forgedDetail.put("offeringId", OTHER_TEACHER_OFFERING);
        forgedDetail.put("uid", TEACHER_B);
        forgedDetail.put("teacherId", TEACHER_B);
        Message denied = client.teacher(TeacherCourseActions.GET_OFFERING, token, forgedDetail);
        require(denied.getCode() == MessageCode.FORBIDDEN,
                "a body claiming teacher B must not unlock teacher B's class but was "
                        + denied.getCode());

        Map<String, Object> forgedStudent = new LinkedHashMap<>();
        forgedStudent.put("offeringId", SEEDED_OFFERING);
        forgedStudent.put("uid", STUDENT);
        Message asStudent = client.teacher(TeacherCourseActions.GET_OFFERING, token,
                forgedStudent);
        require(asStudent.getCode() == MessageCode.SUCCESS,
                "a teacher token claiming a student uid must still be served as the teacher but"
                        + " was " + asStudent.getCode());
        System.out.println("[E2E] forged body identity (teacher B / student) -> session identity"
                + " wins, teacher B's class stays FORBIDDEN");
    }

    private static void invalidInputIsRejected(JsonLineClient client, String token) {
        requireBadRequest(client.teacher(TeacherCourseActions.LIST_OFFERINGS, token,
                        offeringQuery(CURRENT_YEAR, CURRENT_SEMESTER, null, 0, 20)),
                "page 0");
        requireBadRequest(client.teacher(TeacherCourseActions.LIST_OFFERINGS, token,
                        offeringQuery(CURRENT_YEAR, CURRENT_SEMESTER, null, 1, 101)),
                "size 101");
        requireBadRequest(client.teacher(TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                        rosterQuery(SEEDED_OFFERING, null, 1, 1, 20)),
                "enrollmentStatus 1");
        requireBadRequest(client.teacher(TeacherCourseActions.GET_OFFERING, token,
                        Map.of("offeringId", "abc")),
                "a non-decimal offering id");
        requireBadRequest(client.teacher("deleteOffering", token, Map.of()),
                "an unsupported action");
        System.out.println("[E2E] malformed input -> BAD_REQUEST with no SQL leakage");
    }

    private static void requireBadRequest(Message response, String label) {
        require(response.getCode() == MessageCode.BAD_REQUEST,
                label + " must be BAD_REQUEST but was " + response.getCode() + "/"
                        + response.getMessage());
        String message = response.getMessage() == null ? "" : response.getMessage();
        require(!message.toLowerCase().contains("select")
                        && !message.toLowerCase().contains("sql")
                        && !message.toLowerCase().contains("exception"),
                label + " must not leak SQL or a stack trace but said " + message);
    }

    // ------------------------------------------------------------------
    // Server harness
    // ------------------------------------------------------------------

    private static final class Harness {
        private final OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        private final Server server;
        private final Thread thread;

        Harness() {
            // 无参 MessageDispatcher 已经默认装配 TeacherCourseHandler（Task 3 的 Ruling D），
            // 所以这里不需要任何额外装配；本用例的实际运行就是这条路由的验证。
            this.server = new Server(0, registry, new MessageDispatcher(), null, null);
            this.thread = new Thread(server::start, "teacher-e2e-server");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.isRunning() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            require(server.isRunning(), "server must start listening");
        }

        void stop() {
            server.stop();
        }

        int port() {
            return server.getPort();
        }

        JsonLineClient client() throws IOException {
            return new JsonLineClient("127.0.0.1", port());
        }
    }

    // ------------------------------------------------------------------
    // Real JSON-line client
    // ------------------------------------------------------------------

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

        JsonLineClient(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(this::readLoop, "teacher-e2e-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message message = GSON.fromJson(line, Message.class);
                    if (message == null || message.getType() == MessageType.PUSH) {
                        continue;
                    }
                    CompletableFuture<Message> future = pending.remove(message.getUID());
                    if (future != null) {
                        future.complete(message);
                    }
                }
            } catch (IOException closed) {
                // socket closed by the test
            }
        }

        String login(String uid, String role) {
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireSuccess(captcha, "get captcha for " + uid);
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            String captchaCode = CaptchaTestBridge.codeFor(captchaId);
            Message response = request("user", "login", null, Map.of(
                    "cardNo", uid, "password", LOGIN_PASSWORD, "role", role,
                    "captchaId", captchaId, "captchaCode", captchaCode));
            requireSuccess(response, "login " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must return a token");
            require(role.equals(response.getData("role")),
                    "login must report the requested role " + role);
            return (String) token;
        }

        Message teacher(String action, String token, Map<String, Object> data) {
            return request("courseTeacher", action, token, data);
        }

        Message request(String module, String action, String token, Map<String, Object> data) {
            Message message = new Message(MessageType.REQUEST, module, action);
            if (token != null) {
                message.setToken(token);
            }
            if (data != null) {
                data.forEach(message::putData);
            }
            CompletableFuture<Message> future = new CompletableFuture<>();
            pending.put(message.getUID(), future);
            try {
                synchronized (writer) {
                    writer.write(GSON.toJson(message));
                    writer.write("\n");
                    writer.flush();
                }
            } catch (IOException failure) {
                pending.remove(message.getUID());
                throw new IllegalStateException("send failed", failure);
            }
            try {
                return future.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("no response for " + module + "/" + action, failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ------------------------------------------------------------------
    // Wire payloads and response helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> offeringQuery(int academicYear, int semester, String query,
                                                     int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicYear", academicYear);
        data.put("semester", semester);
        if (query != null) {
            data.put("query", query);
        }
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static Map<String, Object> rosterQuery(String offeringId, String query,
                                                   Integer enrollmentStatus, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offeringId", offeringId);
        if (query != null) {
            data.put("query", query);
        }
        if (enrollmentStatus != null) {
            data.put("enrollmentStatus", enrollmentStatus);
        }
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static List<CourseTermDTO> termList(Message response, String label) {
        return parseData(response, label, "terms", TERM_LIST);
    }

    private static TeacherPageDTO<TeacherOfferingDTO> offeringPage(Message response, String label) {
        return parseData(response, label, "offerings", OFFERING_PAGE);
    }

    private static TeacherPageDTO<TeacherRosterRowDTO> rosterPage(Message response, String label) {
        return parseData(response, label, "students", ROSTER_PAGE);
    }

    private static TeacherOfferingDetailDTO offeringDetail(Message response, String label) {
        return parseData(response, label, "offering", OFFERING_DETAIL);
    }

    private static List<ScheduleArrangementDTO> scheduleList(Message response, String label) {
        return parseData(response, label, "schedules", SCHEDULE_LIST);
    }

    private static <T> T parseData(Message response, String label, String key, Type type) {
        requireSuccess(response, label);
        Object raw = response.getData(key);
        require(raw != null, label + " must carry the response key " + key);
        T parsed = GSON.fromJson(GSON.toJson(raw), type);
        require(parsed != null, label + " must deserialize the " + key + " payload");
        return parsed;
    }

    private static void requireSuccess(Message response, String label) {
        require(response != null, label + " must return a response");
        require(response.getCode() == MessageCode.SUCCESS,
                label + " must succeed but was " + response.getCode() + ": "
                        + response.getMessage());
    }

    private static void requireTerm(CourseTermDTO term, int academicYear, int semester,
                                    String displayName) {
        require(term.getAcademicYear() == academicYear && term.getSemester() == semester
                        && displayName.equals(term.getDisplayName()),
                "expected the term " + academicYear + "/" + semester + " '" + displayName
                        + "' but was " + term.getAcademicYear() + "/" + term.getSemester() + " '"
                        + term.getDisplayName() + "'");
    }

    // ------------------------------------------------------------------
    // Fixtures and database helpers
    // ------------------------------------------------------------------

    private static void insertFixtures(String jdbc, String user, String pass) throws SQLException {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "INSERT INTO course_offering"
                    + " (offering_id,offering_code,course_id,academic_year,semester,capacity,"
                    + "wanted_count,enrolled_count,website,status) VALUES"
                    + " (" + OWN_OFFERING_CURRENT + ",'" + OWN_OFFERING_CURRENT_CODE
                    + "',1002," + CURRENT_YEAR + "," + CURRENT_SEMESTER + ",20,0,0,NULL,1),"
                    + " (" + OWN_OFFERING_OLD + ",'" + OWN_OFFERING_OLD_CODE
                    + "',1002," + OLD_YEAR + "," + OLD_SEMESTER + ",30,0,4,NULL,2)");
            execute(connection, "INSERT INTO course_offering_teacher (offering_id,uid,role) VALUES"
                    + " (" + OWN_OFFERING_CURRENT + ",'" + TEACHER_A + "',0),"
                    + " (" + OWN_OFFERING_OLD + ",'" + TEACHER_A + "',0)");
            for (int index = 0; index < OWN_STUDENTS.length; index++) {
                execute(connection, "INSERT INTO tbl_user"
                        + " (UID,name,password,salt,role,college,major) VALUES ('"
                        + OWN_STUDENTS[index] + "','Teacher Query Student " + (index + 1) + "','"
                        + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                        + "',2,'Engineering','" + OWN_STUDENT_MAJOR + "')");
                execute(connection, "INSERT INTO student_academic_profile"
                        + " (profile_id,uid,major_id,cohort_year,status) VALUES ("
                        + OWN_PROFILES[index] + ",'" + OWN_STUDENTS[index] + "',10,2026,'ACTIVE')");
            }
            execute(connection, "INSERT INTO enrollment"
                    + " (enrollment_id,offering_id,course_id,academic_year,semester,uid,status,"
                    + "select_time,drop_time) VALUES"
                    + " (" + OWN_ENROLLMENTS[0] + "," + OWN_OFFERING_OLD + ",1002," + OLD_YEAR + ","
                    + OLD_SEMESTER + ",'" + STUDENT + "',2,'2025-09-01 00:00:01.100001',NULL),"
                    + " (" + OWN_ENROLLMENTS[1] + "," + OWN_OFFERING_OLD + ",1002," + OLD_YEAR + ","
                    + OLD_SEMESTER + ",'" + OTHER_STUDENT + "',3,'2025-09-01 00:00:02.100001',"
                    + "'2025-09-10 00:00:03.100001'),"
                    + " (" + OWN_ENROLLMENTS[2] + "," + OWN_OFFERING_OLD + ",1002," + OLD_YEAR + ","
                    + OLD_SEMESTER + ",'" + OWN_STUDENTS[0]
                    + "',2,'2025-09-01 00:00:03.100001',NULL),"
                    + " (" + OWN_ENROLLMENTS[3] + "," + OWN_OFFERING_OLD + ",1002," + OLD_YEAR + ","
                    + OLD_SEMESTER + ",'" + OWN_STUDENTS[1]
                    + "',2,'2025-09-01 00:00:04.100001',NULL),"
                    + " (" + OWN_ENROLLMENTS[4] + "," + OWN_OFFERING_OLD + ",1002," + OLD_YEAR + ","
                    + OLD_SEMESTER + ",'" + OWN_STUDENTS[2]
                    + "',2,'2025-09-01 00:00:05.100001',NULL)");
        });
    }

    private static void cleanFixtures(String jdbc, String user, String pass) throws SQLException {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "DELETE FROM course_offering_teacher WHERE offering_id IN ("
                    + OWN_OFFERING_CURRENT + "," + OWN_OFFERING_OLD + ")");
            execute(connection, "DELETE FROM enrollment WHERE enrollment_id BETWEEN "
                    + OWN_ENROLLMENTS[0] + " AND " + OWN_ENROLLMENTS[OWN_ENROLLMENTS.length - 1]);
            execute(connection, "DELETE FROM student_academic_profile WHERE profile_id BETWEEN "
                    + OWN_PROFILES[0] + " AND " + OWN_PROFILES[OWN_PROFILES.length - 1]);
            execute(connection, "DELETE FROM course_offering WHERE offering_id IN ("
                    + OWN_OFFERING_CURRENT + "," + OWN_OFFERING_OLD + ")");
            execute(connection, "DELETE FROM tbl_user WHERE UID LIKE 'tqt-student-%'");
        });
        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering"
                            + " WHERE offering_id BETWEEN 990201 AND 990202") == 0,
                    "cleanup must remove this test's own offerings");
            require(queryInt(connection, "SELECT COUNT(*) FROM enrollment"
                            + " WHERE enrollment_id BETWEEN 990301 AND 990399") == 0,
                    "cleanup must remove this test's own enrollments");
            require(queryInt(connection, "SELECT COUNT(*) FROM tbl_user"
                            + " WHERE UID LIKE 'tqt-student-%'") == 0,
                    "cleanup must remove this test's own students");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering_teacher"
                            + " WHERE offering_id BETWEEN 990201 AND 990202") == 0,
                    "cleanup must remove this test's own teacher rows");
        });
    }

    @FunctionalInterface
    private interface SqlAssertion {
        void run(Connection connection) throws SQLException;
    }

    private static void assertDatabase(String jdbc, String user, String pass, SqlAssertion assertion)
            throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            assertion.run(connection);
        }
    }

    private static Connection connect(String jdbc, String user, String pass) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbc, user, pass);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

    private static int queryInt(Connection connection, String sql, Object... params)
            throws SQLException {
        return ((Number) queryScalar(connection, sql, params)).intValue();
    }

    private static String queryString(Connection connection, String sql, Object... params)
            throws SQLException {
        Object value = queryScalar(connection, sql, params);
        return value == null ? null : String.valueOf(value);
    }

    private static Object queryScalar(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row: " + sql);
                return rows.getObject(1);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // Schema reset and fresh-order seed
    // ------------------------------------------------------------------

    private static void resetAndSeed(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            Object liveDatabase = queryScalar(connection, "SELECT DATABASE()");
            require(TEST_DATABASE.equals(liveDatabase),
                    "refusing live end-to-end test: connected database must be exactly "
                            + TEST_DATABASE + " but was " + liveDatabase);
            requireTestDatabase(jdbc);
            resetTestSchema(connection);
            applyScript(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/seed-course-test.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
        }
    }

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            String normalized = statements.get(i)
                    .replaceAll("(?m)^\\s*--.*$", "")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            if ("init.sql".equals(path.getFileName().toString())
                    && (normalized.startsWith("CREATE DATABASE") || normalized.startsWith("USE "))) {
                continue;
            }
            try {
                requireTestSchema(connection);
                execute(connection, statements.get(i));
                requireTestSchema(connection);
            } catch (SQLException failure) {
                throw new SQLException("failed applying " + path.getFileName()
                        + " statement " + (i + 1), failure);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    current.append(' ');
                }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!single && !quotedIdentifier && c == '#') {
                lineComment = true;
                continue;
            }
            if (!single && !quotedIdentifier && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (!quotedIdentifier && c == '\'') {
                current.append(c);
                if (single && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    single = !single;
                }
                continue;
            }
            if (!single && c == '`') {
                quotedIdentifier = !quotedIdentifier;
                current.append(c);
                continue;
            }
            if (!single && !quotedIdentifier && c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // Guard, config and misc helpers
    // ------------------------------------------------------------------

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "refusing live end-to-end test: JDBC database must be"
                + " exactly " + TEST_DATABASE + " but was " + database);
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static Properties loadProperties(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing ignored local db.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
    }

    private static void requireTestSchema(Connection connection) throws SQLException {
        Object database = queryScalar(connection, "SELECT DATABASE()");
        require(TEST_DATABASE.equals(database),
                "SQL fixture escaped the protected test schema: " + database);
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "missing database property: " + key);
        return value;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("repository root was not found");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
