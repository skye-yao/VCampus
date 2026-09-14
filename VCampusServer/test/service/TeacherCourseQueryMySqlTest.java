package service;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import util.DBUtil;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教师归属与教学班查询的真实 MySQL 测试。
 *
 * <p>本类自己重建受保护的 {@code virtual_campus_course_test} 架构（init.sql 的 tbl_user →
 * V001 → V002 → V003 → seed-course-test.sql → V004 → V005），再插入私有 ID/UID 范围的
 * fixture，只清理自己的行。未传入 {@code mysql} 时只打印 SKIP 并返回，绝不把 SKIP 当成 PASS。
 *
 * <p>四组核心 fixture：任课教师 role=0、助教 role=1、安排级任课教师、无关联教师；
 * 另有生效调课任课教师与已撤销调课教师作为边界。
 */
public final class TeacherCourseQueryMySqlTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private TeacherCourseQueryMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        boolean withMySql = false;
        Path config = root.resolve(DEFAULT_CONFIG);
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) {
                withMySql = true;
            } else if (argument.startsWith("--config=")) {
                config = resolveConfig(root, argument.substring("--config=".length()));
            } else {
                throw new AssertionError("Unsupported argument: " + argument);
            }
        }

        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the guarded-schema query test was "
                    + "not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String testUrl = withTestAuthentication(url);
        ensureTestSchema(testUrl, properties);
        pointDBUtilAt(testUrl, properties);

        try (Connection connection = DBUtil.getConnection()) {
            require(TEST_DATABASE.equals(currentDatabase(connection)),
                    "Connected schema changed after URL validation");
            rebuildSchema(connection, root);
            insertFixtures();
            try {
                verifyPolicy(connection);
                verifyService();
            } finally {
                cleanFixtures();
            }
            require(count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'tq-%'") == 0,
                    "cleanup must remove every fixture teacher and student");
            require(count("SELECT COUNT(*) FROM course_offering"
                    + " WHERE offering_id BETWEEN 9201 AND 9208") == 0,
                    "cleanup must remove every fixture offering");
            require(count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 9101 AND 9103") == 0,
                    "cleanup must remove every fixture course");
            require(count("SELECT COUNT(*) FROM course_schedule_arrangement"
                    + " WHERE arrangement_id BETWEEN 9501 AND 9507") == 0,
                    "cleanup must remove every fixture arrangement");
            require(count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 9401 AND 9403") == 0,
                    "cleanup must remove every fixture plan");
            require(count("SELECT COUNT(*) FROM enrollment"
                    + " WHERE enrollment_id BETWEEN 9801 AND 9805") == 0,
                    "cleanup must remove every fixture enrollment");
            require(count("SELECT COUNT(*) FROM major WHERE major_id=9011") == 0
                            && count("SELECT COUNT(*) FROM teaching_calendar"
                            + " WHERE id IN (9301,9302)") == 0,
                    "cleanup must remove the fixture major and calendars");
        }
        System.out.println("Teacher course query MySQL test passed in " + TEST_DATABASE + ".");
    }

    // ------------------------------------------------------------------ policy

    private static void verifyPolicy(Connection connection) throws Exception {
        TeacherAccessPolicy policy = new TeacherAccessPolicy();

        // Allowed: role=0, role=1 assistant of a real teacher, arrangement-level teacher under a
        // PUBLISHED plan, and the teacher of an effective (ACTIVE) adjustment.
        policy.requireViewOffering(connection, "tq-owner", 9201);
        policy.requireViewOffering(connection, "tq-assistant", 9201);
        policy.requireViewOffering(connection, "tq-arranged", 9202);
        policy.requireViewOffering(connection, "tq-adjusted", 9204);
        policy.requireEditGrades(connection, "tq-owner", 9201);

        // Only role=0 may edit grades.
        expectDenied(() -> policy.requireEditGrades(connection, "tq-assistant", 9201),
                "an assistant must not edit grades");
        expectDenied(() -> policy.requireEditGrades(connection, "tq-arranged", 9202),
                "an arrangement-level teacher must not edit grades");
        expectDenied(() -> policy.requireEditGrades(connection, "tq-adjusted", 9204),
                "an adjustment-level teacher must not edit grades");
        expectDenied(() -> policy.requireEditGrades(connection, "tq-outsider", 9201),
                "an unrelated teacher must not edit grades");

        // Read permission must revert to this offering only.
        expectDenied(() -> policy.requireViewOffering(connection, "tq-outsider", 9201),
                "an unrelated teacher must not view another offering");
        expectDenied(() -> policy.requireViewOffering(connection, "tq-owner", 9202),
                "the teacher of 9201 must not view 9202");
        expectDenied(() -> policy.requireViewOffering(connection, "tq-owner", 9203),
                "the teacher of 9201 must not view 9203");
        expectDenied(() -> policy.requireViewOffering(connection, "tq-owner", 9204),
                "a NULL arrangement teacher must not grant view");

        // A DRAFT plan arrangement and a CANCELLED adjustment are not current permissions.
        expectDenied(() -> policy.requireViewOffering(connection, "tq-draft-arranged", 9204),
                "a DRAFT plan arrangement must not grant view");
        expectDenied(() -> policy.requireViewOffering(connection, "tq-cancelled", 9204),
                "a CANCELLED adjustment must not grant view");

        // The system role must be 教师 before any teaching relation counts.
        expectDenied(() -> policy.requireViewOffering(connection, "tq-student-in-teacher", 9208),
                "a student listed as a teacher must still be denied");
        expectDenied(() -> policy.requireEditGrades(connection, "tq-student-in-teacher", 9208),
                "a student listed as a teacher must not edit grades");

        expectDenied(() -> policy.requireViewOffering(connection, "tq-owner", 999999),
                "an unknown offering must be denied, not reported as a missing row");
        expectDenied(() -> policy.requireViewOffering(connection, "no-such-teacher", 9201),
                "an unknown user must be denied");
    }

    // ----------------------------------------------------------------- service

    private static void verifyService() throws Exception {
        TeacherCourseQueryService service = new TeacherCourseQueryService();

        verifyTerms(service);
        verifyOfferingList(service);
        verifyOfferingDetail(service);
        verifyRoster(service);
        verifySchedules(service);
    }

    private static void verifyTerms(TeacherCourseQueryService service) throws Exception {
        List<CourseTermDTO> ownerTerms = service.listTerms("tq-owner");
        require(ownerTerms.size() == 2,
                "the owner must see both of their terms, observed " + ownerTerms.size());
        require(ownerTerms.get(0).getAcademicYear() == 2026 && ownerTerms.get(0).getSemester() == 3
                        && ownerTerms.get(1).getAcademicYear() == 2025
                        && ownerTerms.get(1).getSemester() == 2,
                "terms must be newest first");
        require("2026-2027 春学期".equals(ownerTerms.get(0).getDisplayName()),
                "terms must reuse the existing display convention, observed "
                        + ownerTerms.get(0).getDisplayName());

        require(service.listTerms("tq-outsider").isEmpty(),
                "an unrelated teacher must have no term");
        require(service.listTerms("tq-arranged").size() == 1
                        && service.listTerms("tq-arranged").get(0).getAcademicYear() == 2026,
                "an arrangement-level teacher must see the arranged term");
        require(service.listTerms("tq-adjusted").size() == 1
                        && service.listTerms("tq-adjusted").get(0).getAcademicYear() == 2026,
                "an effective-adjustment teacher must see the term");
        require(service.listTerms("no-such-teacher").isEmpty(),
                "an unknown teacher must have no term");
        require(service.listTerms("tq-student-in-teacher").isEmpty(),
                "a student listed as a teacher must not see teacher terms");

        require(count("SELECT COUNT(*) FROM course_selection_window"
                        + " WHERE (academic_year=2026 AND semester=3)"
                        + " OR (academic_year=2025 AND semester=2)") == 0,
                "the fixture terms must have no selection window, so teacher terms cannot depend on one");
    }

    private static void verifyOfferingList(TeacherCourseQueryService service) throws Exception {
        TeacherPageDTO<TeacherOfferingDTO> page1 =
                service.listOfferings("tq-owner", 2026, 3, "", 1, 2);
        require(page1.getPage() == 1 && page1.getSize() == 2 && page1.getTotalCount() == 3,
                "total count must be the filtered count, not the page size, observed "
                        + page1.getTotalCount());
        require(offeringIds(page1.getItems()).equals(List.of("9201", "9207")),
                "page one must be stable and ordered, observed "
                        + offeringIds(page1.getItems()));
        TeacherPageDTO<TeacherOfferingDTO> page2 =
                service.listOfferings("tq-owner", 2026, 3, null, 2, 2);
        require(page2.getTotalCount() == 3
                        && offeringIds(page2.getItems()).equals(List.of("9206")),
                "page two must return the remaining offering");
        require(service.listOfferings("tq-owner", 2026, 3, null, 3, 2).getItems().isEmpty(),
                "a page past the end must be empty but keep the total");

        require(service.listOfferings("tq-outsider", 2026, 3, null, 1, 20).getTotalCount() == 0,
                "an unrelated teacher must see no offering");
        require(service.listOfferings("no-such-teacher", 2026, 3, null, 1, 20).getTotalCount() == 0,
                "an unknown teacher must see no offering");
        require(service.listOfferings("tq-student-in-teacher", 2026, 3, null, 1, 20)
                        .getTotalCount() == 0,
                "a student listed as a teacher must see no offering");
        require(offeringIds(service.listOfferings("tq-owner", 2025, 2, null, 1, 20).getItems())
                        .equals(List.of("9205")),
                "the history term must be filtered independently");
        require(service.listOfferings("tq-owner", 2026, 3, "TQ101", 1, 20).getTotalCount() == 1,
                "a course-code search must bind the filter");
        require(service.listOfferings("tq-owner", 2026, 3, "TQ Course Two", 1, 20).getTotalCount() == 1,
                "a course-name search must bind the filter");
        require(service.listOfferings("tq-owner", 2026, 3, "%", 1, 20).getTotalCount() == 0,
                "a bare percent must be escaped, not treated as a wildcard");
        require(service.listOfferings("tq-owner", 2026, 3, "_", 1, 20).getTotalCount() == 0,
                "a bare underscore must be escaped, not treated as a wildcard");

        TeacherOfferingDTO ownerOffering = findOffering(page1.getItems(), "9201");
        require(ownerOffering.isCanEditGrades() && ownerOffering.isCanRequestAdjustment(),
                "a role=0 teacher may edit grades and request adjustments");
        require(ownerOffering.getEnrolledCount() == 9 && ownerOffering.getCapacity() == 30,
                "the enrolled count must come from course_offering.enrolled_count, observed "
                        + ownerOffering.getEnrolledCount());
        require("OPEN".equals(ownerOffering.getStatus()),
                "the offering status must reuse the shared label, observed "
                        + ownerOffering.getStatus());
        require("TQ101-2026-3-A".equals(ownerOffering.getOfferingCode())
                        && "TQ Course One".equals(ownerOffering.getCourseName())
                        && ownerOffering.getCredit() == 3.0,
                "the offering summary must carry course identity");
        require("TQ101-2026-3-A".equals(ownerOffering.getOfferingName())
                        || ownerOffering.getOfferingName().contains("TQ Course One"),
                "the display name must be derived from the course name and offering code, observed "
                        + ownerOffering.getOfferingName());

        TeacherOfferingDTO assistantOffering = findOffering(
                service.listOfferings("tq-assistant", 2026, 3, null, 1, 20).getItems(), "9201");
        require(!assistantOffering.isCanEditGrades() && !assistantOffering.isCanRequestAdjustment(),
                "an assistant must be read-only");

        TeacherOfferingDTO arrangedOffering =
                service.listOfferings("tq-arranged", 2026, 3, null, 1, 20).getItems().get(0);
        require("9202".equals(arrangedOffering.getOfferingId())
                        && !arrangedOffering.isCanEditGrades()
                        && arrangedOffering.isCanRequestAdjustment(),
                "an arrangement-level teacher may query and request adjustments, but not edit grades");

        TeacherOfferingDTO adjustedOffering =
                service.listOfferings("tq-adjusted", 2026, 3, null, 1, 20).getItems().get(0);
        require("9204".equals(adjustedOffering.getOfferingId())
                        && !adjustedOffering.isCanEditGrades()
                        && adjustedOffering.isCanRequestAdjustment(),
                "an effective-adjustment teacher may query their adjusted offering");

        require(offeringIds(service.listOfferings("tq-other", 2026, 3, null, 1, 20).getItems())
                        .equals(List.of("9203")),
                "each teacher must only see their own offerings");

        expectInvalid(() -> service.listOfferings("tq-owner", 2026, 3, null, 0, 20),
                "page zero must be rejected");
        expectInvalid(() -> service.listOfferings("tq-owner", 2026, 3, null, 1, 0),
                "size zero must be rejected");
        expectInvalid(() -> service.listOfferings("tq-owner", 2026, 3, null, 1, 101),
                "size above one hundred must be rejected");
        expectInvalid(() -> service.listOfferings("tq-owner", 2026, 4, null, 1, 20),
                "an unknown semester must be rejected");
        expectInvalid(() -> service.listOfferings("tq-owner", 0, 3, null, 1, 20),
                "a non-positive year must be rejected");
    }

    private static void verifyOfferingDetail(TeacherCourseQueryService service) throws Exception {
        TeacherOfferingDetailDTO detail = service.getOffering("tq-owner", "9201");
        require("9201".equals(detail.getOffering().getOfferingId())
                        && detail.getOffering().getEnrolledCount() == 9,
                "the detail must embed the real offering summary");
        require(detail.getOfferingCollege() == null,
                "an unmaintained offering_college must stay null, never the teacher's own college");
        require(detail.getDescription() != null && detail.getDescription().contains("One"),
                "the detail must carry the course description, observed " + detail.getDescription());
        require(detail.getTeachers().size() == 2,
                "the detail must list the offering staff, observed " + detail.getTeachers().size());
        ScheduleResourceDTO firstTeacher = detail.getTeachers().get(0);
        require("tq-owner".equals(firstTeacher.getBusinessId())
                        && "TQ Owner Teacher".equals(firstTeacher.getName())
                        && "teacher".equals(firstTeacher.getResourceType())
                        && firstTeacher.getResourceId().equals(firstTeacher.getBusinessId()),
                "the role=0 teacher must come first in the staff list");
        require("tq-assistant".equals(detail.getTeachers().get(1).getBusinessId()),
                "the assistant must follow the role=0 teacher");

        TeacherOfferingDetailDTO arrangedDetail = service.getOffering("tq-arranged", "9202");
        require("TQ Engineering College".equals(arrangedDetail.getOfferingCollege()),
                "a maintained offering_college must be returned verbatim");
        require(arrangedDetail.getTeachers().isEmpty(),
                "an offering with no staff rows must expose an empty teacher list, not an invented one");

        expectDenied(() -> service.getOffering("tq-owner", "9202"),
                "teacher A must not read teacher B's detail");
        expectDenied(() -> service.getOffering("tq-outsider", "9201"),
                "an unrelated teacher must not read a detail");
        expectInvalid(() -> service.getOffering("tq-owner", "abc"),
                "a non-numeric offering id must be rejected");
        expectInvalid(() -> service.getOffering("tq-owner", "0"),
                "a non-positive offering id must be rejected");
        expectInvalid(() -> service.getOffering("tq-owner", null),
                "a null offering id must be rejected");
    }

    private static void verifyRoster(TeacherCourseQueryService service) throws Exception {
        TeacherPageDTO<TeacherRosterRowDTO> page1 =
                service.listOfferingStudents("tq-owner", "9201", null, null, 1, 2);
        require(page1.getTotalCount() == 4 && page1.getItems().size() == 2,
                "the roster must page over both normal and dropped rows, observed "
                        + page1.getTotalCount());
        require(enrollmentIds(page1.getItems()).equals(List.of("9801", "9802")),
                "the roster order must be stable by uid, observed "
                        + enrollmentIds(page1.getItems()));
        require(enrollmentIds(service.listOfferingStudents("tq-owner", "9201", "", null, 2, 2)
                .getItems()).equals(List.of("9803", "9804")),
                "the second roster page must return the remaining rows");

        TeacherRosterRowDTO enrolled = page1.getItems().get(0);
        require("tq-student-1".equals(enrolled.getStudentUid())
                        && "TQ Student One".equals(enrolled.getStudentName())
                        && "Robotics".equals(enrolled.getMajor())
                        && "ENROLLED".equals(enrolled.getEnrollmentStatus())
                        && enrolled.getDroppedAt() == null
                        && enrolled.getSelectedAt().startsWith("2026-09-01"),
                "a normal row must resolve the academic-profile major and keep droppedAt null, observed "
                        + enrolled.getMajor() + "/" + enrolled.getSelectedAt());

        TeacherRosterRowDTO fallbackMajor = service.listOfferingStudents(
                "tq-owner", "9201", "tq-student-3", null, 1, 20).getItems().get(0);
        require("Fallback Major".equals(fallbackMajor.getMajor()),
                "a student without an academic profile must fall back to the base major, observed "
                        + fallbackMajor.getMajor());

        TeacherRosterRowDTO dropped = service.listOfferingStudents(
                "tq-owner", "9201", "tq-student-4", null, 1, 20).getItems().get(0);
        require("DROPPED".equals(dropped.getEnrollmentStatus())
                        && dropped.getDroppedAt() != null
                        && dropped.getDroppedAt().startsWith("2026-10-01"),
                "a dropped row must carry its drop time, observed " + dropped.getDroppedAt());

        require(service.listOfferingStudents("tq-owner", "9201", null, 2, 1, 20).getTotalCount() == 3,
                "the normal status filter must only return status 2");
        require(service.listOfferingStudents("tq-owner", "9201", null, 3, 1, 20).getTotalCount() == 1,
                "the dropped status filter must only return status 3");
        require(enrollmentIds(service.listOfferingStudents("tq-owner", "9201", null, 3, 1, 20)
                .getItems()).equals(List.of("9804")),
                "the dropped filter must select the dropped row");

        require(service.listOfferingStudents("tq-owner", "9201", "TQ Student Two", null, 1, 20)
                .getTotalCount() == 1, "a name search must bind the filter");
        require(service.listOfferingStudents("tq-owner", "9201", "tq-student-1", null, 1, 20)
                .getTotalCount() == 1, "a student-number search must bind the filter");
        require(service.listOfferingStudents("tq-owner", "9201", "%", null, 1, 20)
                .getTotalCount() == 0, "a bare percent in the roster search must be escaped");
        require(service.listOfferingStudents("tq-owner", "9201", "_", null, 1, 20)
                .getTotalCount() == 0, "a bare underscore in the roster search must be escaped");

        expectInvalid(() -> service.listOfferingStudents("tq-owner", "9201", null, 1, 1, 20),
                "enrollment status 1 must be rejected, not silently ignored");
        expectInvalid(() -> service.listOfferingStudents("tq-owner", "9201", null, 0, 1, 20),
                "enrollment status 0 must be rejected");
        expectInvalid(() -> service.listOfferingStudents("tq-owner", "9201", null, 4, 1, 20),
                "enrollment status 4 must be rejected");
        expectInvalid(() -> service.listOfferingStudents("tq-owner", "9201", null, null, 0, 20),
                "roster page zero must be rejected");
        expectInvalid(() -> service.listOfferingStudents("tq-owner", "9201", null, null, 1, 101),
                "roster size above one hundred must be rejected");
        expectInvalid(() -> service.listOfferingStudents("tq-owner", "abc", null, null, 1, 20),
                "a non-numeric offering id must be rejected for the roster too");

        // Teacher A must not be able to read teacher B's roster.
        expectDenied(() -> service.listOfferingStudents("tq-owner", "9203", null, null, 1, 20),
                "teacher A must not read teacher B's roster");
        expectDenied(() -> service.listOfferingStudents("tq-outsider", "9201", null, null, 1, 20),
                "an unrelated teacher must not read a roster");
        require(service.listOfferingStudents("tq-other", "9203", null, null, 1, 20)
                        .getTotalCount() == 1,
                "the owning teacher must still read their own roster");
    }

    private static void verifySchedules(TeacherCourseQueryService service) throws Exception {
        List<ScheduleArrangementDTO> ownerSchedules =
                service.listOfferingSchedules("tq-owner", "9201");
        require(ownerSchedules.size() == 1,
                "only the PUBLISHED ACTIVE arrangement may be exposed, observed "
                        + ownerSchedules.size());
        ScheduleArrangementDTO arrangement = ownerSchedules.get(0);
        require("9503".equals(arrangement.getArrangementId())
                        && "9401".equals(arrangement.getPlanId())
                        && "9201".equals(arrangement.getOfferingId())
                        && "ACTIVE".equals(arrangement.getStatus()),
                "the published arrangement identity must be preserved");
        require(arrangement.getTeacher() != null
                        && "tq-owner".equals(arrangement.getTeacher().getBusinessId())
                        && arrangement.getTeacher().getName().equals("TQ Owner Teacher"),
                "the arrangement teacher must be resolved");
        require(arrangement.getClassroom() == null,
                "a NULL classroom must stay null instead of a fabricated room");
        require(arrangement.getStartWeek() == 1 && arrangement.getEndWeek() == 2,
                "the arrangement weeks must come from course_schedule_rule_week");
        require(arrangement.getSlots().size() == 1,
                "the arrangement must carry its single slot");
        ScheduleSlotDTO slot = arrangement.getSlots().get(0);
        require(slot.getDayOfWeek() == 2 && slot.getStartPeriod() == 1 && slot.getEndPeriod() == 2,
                "the slot coordinates must be preserved");

        require(service.listOfferingSchedules("tq-owner", "9205").isEmpty(),
                "a term with no PUBLISHED plan must return an empty schedule, never a DRAFT one");
        List<ScheduleArrangementDTO> arrangedSchedules =
                service.listOfferingSchedules("tq-arranged", "9202");
        require(arrangedSchedules.size() == 1
                        && "9501".equals(arrangedSchedules.get(0).getArrangementId())
                        && assistedBy(arrangedSchedules.get(0), "tq-arranged"),
                "an arrangement-level teacher must read the published arrangement they teach");
        List<ScheduleArrangementDTO> adjustedSchedules =
                service.listOfferingSchedules("tq-adjusted", "9204");
        require(adjustedSchedules.size() == 1
                        && "9506".equals(adjustedSchedules.get(0).getArrangementId())
                        && adjustedSchedules.get(0).getTeacher() == null,
                "an effective-adjustment teacher must read their offering's published arrangements");

        expectDenied(() -> service.listOfferingSchedules("tq-owner", "9202"),
                "teacher A must not read teacher B's schedule");
        expectDenied(() -> service.listOfferingSchedules("tq-outsider", "9201"),
                "an unrelated teacher must not read a schedule");
        expectDenied(() -> service.listOfferingSchedules("tq-draft-arranged", "9204"),
                "a DRAFT plan arrangement must not be reachable through the schedule query");
        expectInvalid(() -> service.listOfferingSchedules("tq-owner", "nope"),
                "a non-numeric offering id must be rejected for the schedule too");
    }

    private static boolean assistedBy(ScheduleArrangementDTO arrangement, String uid) {
        return arrangement.getTeacher() != null && uid.equals(arrangement.getTeacher().getBusinessId());
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('tq-owner','TQ Owner Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-assistant','TQ Assistant Teacher','x','x',1,'TQ College','Assistant'),"
                + "('tq-arranged','TQ Arranged Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-draft-arranged','TQ Draft Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-adjusted','TQ Adjusted Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-cancelled','TQ Cancelled Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-outsider','TQ Outsider Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-other','TQ Other Teacher','x','x',1,'TQ College','Professor'),"
                + "('tq-student-in-teacher','TQ Not A Teacher','x','x',2,'TQ College','Student'),"
                + "('tq-student-1','TQ Student One','x','x',2,'TQ College','Enrolled Base Major'),"
                + "('tq-student-2','TQ Student Two','x','x',2,'TQ College','Enrolled Base Major'),"
                + "('tq-student-3','TQ Student Three','x','x',2,'TQ College','Fallback Major'),"
                + "('tq-student-4','TQ Student Four','x','x',2,'TQ College','Legacy Major')");
        execute("INSERT INTO major(major_id,major_code,major_name,college)"
                + " VALUES(9011,'TQR','Robotics','TQ Engineering')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,description,status) VALUES"
                + "(9101,'TQ101','TQ Course One',3.00,48,1,'TQ One description','ACTIVE'),"
                + "(9102,'TQ102','TQ Course Two',2.00,32,1,'TQ Two description','ACTIVE'),"
                + "(9103,'TQ103','TQ Course Three',1.00,16,1,'TQ Three description','ACTIVE')");
        execute("UPDATE course SET offering_college='TQ Engineering College' WHERE course_id=9102");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,enrolled_count,status) VALUES"
                + "(9201,'TQ101-2026-3-A',9101,2026,3,30,9,2),"
                + "(9202,'TQ102-2026-3-A',9102,2026,3,30,0,2),"
                + "(9203,'TQ103-2026-3-A',9103,2026,3,30,1,2),"
                + "(9204,'TQ101-2026-3-B',9101,2026,3,30,0,2),"
                + "(9205,'TQ102-2025-2-A',9102,2025,2,30,0,2),"
                + "(9206,'TQ103-2026-3-B',9103,2026,3,30,0,2),"
                + "(9207,'TQ102-2026-3-B',9102,2026,3,30,0,2),"
                + "(9208,'TQ103-2026-3-C',9103,2026,3,30,0,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES"
                + "(9201,'tq-owner',0),(9201,'tq-assistant',1),"
                + "(9203,'tq-other',0),"
                + "(9205,'tq-owner',0),(9206,'tq-owner',0),(9207,'tq-owner',0),"
                + "(9208,'tq-student-in-teacher',0)");
        execute("INSERT INTO student_academic_profile(profile_id,uid,major_id,cohort_year,status)"
                + " VALUES(9051,'tq-student-1',9011,2026,'ACTIVE'),"
                + "(9052,'tq-student-2',9011,2026,'ACTIVE')");
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,"
                + "semester,uid,status,select_time,drop_time) VALUES"
                + "(9801,9201,9101,2026,3,'tq-student-1',2,'2026-09-01 00:00:00.100001',NULL),"
                + "(9802,9201,9101,2026,3,'tq-student-2',2,'2026-09-01 00:00:00.100002',NULL),"
                + "(9803,9201,9101,2026,3,'tq-student-3',2,'2026-09-01 00:00:00.100003',NULL),"
                + "(9804,9201,9101,2026,3,'tq-student-4',3,'2026-09-01 00:00:00.100004',"
                + "'2026-10-01 00:00:00.100005'),"
                + "(9805,9203,9103,2026,3,'tq-student-1',2,'2026-09-01 00:00:00.100006',NULL)");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES"
                + "(9301,'TQ calendar 2026-3',2026,3,'2026-09-07','Asia/Shanghai',1,'PUBLISHED'),"
                + "(9302,'TQ calendar 2025-2',2025,2,'2025-09-01','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES"
                + "(9401,'TQ published plan',9301,1,'PUBLISHED','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00'),"
                + "(9402,'TQ draft plan',9301,1,'DRAFT','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00'),"
                + "(9403,'TQ history draft plan',9302,1,'DRAFT','2025-08-01 00:00:00',"
                + "'2025-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=9401 WHERE id=9301");
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,classroom_id,status,version) VALUES"
                + "(9501,9401,9202,'tq-arranged',NULL,'ACTIVE',1),"
                + "(9502,9402,9204,'tq-draft-arranged',NULL,'ACTIVE',1),"
                + "(9503,9401,9201,'tq-owner',NULL,'ACTIVE',1),"
                + "(9504,9402,9201,'tq-owner',NULL,'ACTIVE',1),"
                + "(9505,9401,9201,'tq-owner',NULL,'DISABLED',1),"
                + "(9506,9401,9204,NULL,NULL,'ACTIVE',1),"
                + "(9507,9403,9205,'tq-owner',NULL,'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES"
                + "(9551,9401,9202,9501,2,1,2,'ACTIVE'),"
                + "(9553,9401,9201,9503,2,1,2,'ACTIVE'),"
                + "(9554,9402,9201,9504,4,3,4,'ACTIVE'),"
                + "(9555,9401,9201,9505,5,1,2,'ACTIVE'),"
                + "(9557,9401,9204,9506,3,3,4,'ACTIVE'),"
                + "(9558,9403,9205,9507,2,1,2,'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES"
                + "(9551,1),(9553,1),(9553,2),(9554,1),(9555,1),(9557,1),(9558,1)");
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES"
                + "(9601,9557,9401,'2026-12-16 02:00:00','2026-12-16 03:35:00',1,3)");
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,submitted_at,reviewed_by,"
                + "reviewed_at,review_comment) VALUES"
                + "(9701,9204,'tq-adjusted','TQ adjustment',1,'APPROVED',3,3,4,'tq-adjusted',"
                + "NULL,NULL,'2026-12-01 00:00:00','admin-alpha','2026-12-02 00:00:00','approved')");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES"
                + "(9751,9701,9601,'2026-12-16 02:00:00','2026-12-16 03:35:00','tq-adjusted',"
                + "NULL,NULL,'ACTIVE'),"
                + "(9752,9701,9601,'2026-12-17 02:00:00','2026-12-17 03:35:00','tq-cancelled',"
                + "NULL,NULL,'CANCELLED')");
    }

    private static void cleanFixtures() throws Exception {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id IN (9751,9752)");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id=9701");
        execute("DELETE FROM course_occurrence WHERE id=9601");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 9551 AND 9558");
        execute("DELETE FROM course_schedule_rule WHERE id BETWEEN 9551 AND 9558");
        execute("DELETE FROM course_schedule_arrangement WHERE arrangement_id BETWEEN 9501 AND 9507");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=9301");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 9401 AND 9403");
        execute("DELETE FROM enrollment WHERE enrollment_id BETWEEN 9801 AND 9805");
        execute("DELETE FROM student_academic_profile WHERE profile_id BETWEEN 9051 AND 9052");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 9201 AND 9208");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 9201 AND 9208");
        execute("DELETE FROM course WHERE course_id BETWEEN 9101 AND 9103");
        execute("DELETE FROM major WHERE major_id=9011");
        execute("DELETE FROM teaching_calendar WHERE id IN (9301,9302)");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'tq-%'");
    }

    // ------------------------------------------------------------------ checks

    private static List<String> offeringIds(List<TeacherOfferingDTO> items) {
        List<String> ids = new ArrayList<>();
        for (TeacherOfferingDTO item : items) ids.add(item.getOfferingId());
        return ids;
    }

    private static List<String> enrollmentIds(List<TeacherRosterRowDTO> items) {
        List<String> ids = new ArrayList<>();
        for (TeacherRosterRowDTO item : items) ids.add(item.getEnrollmentId());
        return ids;
    }

    private static TeacherOfferingDTO findOffering(List<TeacherOfferingDTO> items, String offeringId) {
        for (TeacherOfferingDTO item : items) {
            if (offeringId.equals(item.getOfferingId())) return item;
        }
        throw new AssertionError("Offering " + offeringId + " not found in " + offeringIds(items));
    }

    private static void expectDenied(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (TeacherAccessPolicy.AccessDeniedException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectInvalid(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    // ----------------------------------------------------------------- helpers

    private static void rebuildSchema(Connection connection, Path root) throws Exception {
        resetTestSchema(connection);
        applyTblUser(connection, root.resolve("VCampusServer/src/resources/init.sql"));
        applyScript(connection, root.resolve(
                "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
        applyScript(connection, root.resolve(
                "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
        applyScript(connection, root.resolve(
                "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
        // V004 backfills course_schedule_rule.arrangement_id before tightening it, so the seed
        // must be loaded first; V005 then adds the teacher foundation structures.
        applyScript(connection, root.resolve("VCampusServer/src/resources/seed-course-test.sql"));
        applyScript(connection, root.resolve(
                "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
        applyScript(connection, root.resolve(
                "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
    }

    private static void pointDBUtilAt(String testUrl, Properties properties) throws Exception {
        setStatic(DBUtil.class, "url", testUrl);
        setStatic(DBUtil.class, "username", requiredProperty(properties, "db.username"));
        setStatic(DBUtil.class, "password", requiredProperty(properties, "db.password"));
    }

    private static void setStatic(Class<?> type, String name, String value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
    }

    private static void applyTblUser(Connection connection, Path initSql) throws Exception {
        Matcher matcher = TBL_USER.matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "Authoritative tbl_user definition not found");
        execute(connection, matcher.group());
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "Missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            try {
                execute(connection, statements.get(i));
            } catch (SQLException failure) {
                throw new SQLException("Failed applying " + path.getFileName()
                        + " statement " + (i + 1) + ": " + readWarnings(connection), failure);
            }
        }
    }

    private static String readWarnings(Connection connection) {
        List<String> warnings = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW WARNINGS")) {
            while (result.next()) {
                warnings.add(result.getInt("Code") + " " + result.getString("Message"));
            }
        } catch (SQLException ignored) {
            return "warning details unavailable";
        }
        return warnings.toString();
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

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
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

    private static void ensureTestSchema(String testUrl, Properties properties) throws SQLException {
        int queryIndex = testUrl.indexOf('?');
        String base = queryIndex >= 0 ? testUrl.substring(0, queryIndex) : testUrl;
        String query = queryIndex >= 0 ? testUrl.substring(queryIndex) : "";
        int databaseSlash = base.lastIndexOf('/');
        require(databaseSlash > "jdbc:mysql://".length(), "Invalid MySQL JDBC URL");
        String serverUrl = base.substring(0, databaseSlash + 1) + query;

        try (Connection connection = DriverManager.getConnection(
                serverUrl,
                requiredProperty(properties, "db.username"),
                requiredProperty(properties, "db.password"))) {
            setUtc(connection);
            execute(connection, "CREATE DATABASE IF NOT EXISTS `" + TEST_DATABASE
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing teacher query test: JDBC database must be exactly " + TEST_DATABASE);
    }

    private static Properties loadProperties(Path path) throws Exception {
        require(Files.isRegularFile(path), "Missing ignored local db.properties: " + path);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "Missing database property: " + key);
        return value;
    }

    private static void setUtc(Connection connection) throws SQLException {
        execute(connection, "SET time_zone = '+00:00'");
        require("+00:00".equals(queryString(connection, "SELECT @@session.time_zone")),
                "the test connection must use UTC");
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return queryString(connection, "SELECT DATABASE()");
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getString(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("Repository root was not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
