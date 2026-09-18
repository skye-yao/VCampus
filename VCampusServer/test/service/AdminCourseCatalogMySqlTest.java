package service;

import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import util.DBUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class AdminCourseCatalogMySqlTest {
    private static final String ADMIN = "admin-test";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC);

    private AdminCourseCatalogMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('admin-test','Course Test Admin','x','x',0,'Admin','Admin')");
        try {
            AdminCourseCatalogService catalog = new AdminCourseCatalogService(CLOCK);
            String courseId = verifyCreateUpdateAndStaleVersion(catalog);
            verifyArchiveAndRestore(catalog, courseId);
            verifyReplayAndList(catalog);
        } finally {
            cleanup();
        }
        System.out.println("Admin course catalog MySQL test passed.");
    }

    private static String verifyCreateUpdateAndStaleVersion(AdminCourseCatalogService catalog)
            throws Exception {
        AdminOperationResultDTO<AdminCourseDTO> created = catalog.create(ADMIN,
                request(op(1), null, 0, "CS999", "测试课程", "选修", 2.0, 32, "说明", "无",
                        false, true));
        AdminCourseDTO course = created.getEntity();
        require("OK".equals(created.getOutcomeCode()), "create outcome must be OK");
        require(op(1).equals(created.getOperationId()), "create echoes operationId");
        require(course.getVersion() == 1, "new course version");
        require("ACTIVE".equals(course.getStatus()), "new course is ACTIVE");
        require("选修".equals(course.getCourseType()), "course type label round trip");
        require(course.getCredit() == 2.0 && course.getCreditHours() == 32, "credit fields");
        require(course.getOfferingCount() == 0, "new course has no offerings");
        require(count("SELECT COUNT(*) FROM course WHERE course_code='CS999'") == 1,
                "create inserts one row");
        require(count("SELECT course_type FROM course WHERE course_code='CS999'") == 3,
                "course type persisted as code");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                + ADMIN + "' AND operation_id='" + op(1) + "' AND target_type='COURSE'"
                + " AND target_id='" + course.getCourseId() + "' AND result_code='OK'"
                + " AND completed_at IS NOT NULL") == 1, "create writes one operation row");

        AdminOperationResultDTO<AdminCourseDTO> updated = catalog.update(ADMIN,
                request(op(2), course.getCourseId(), 1, "CS999", "测试课程二", "限选", 3.0, 48,
                        "新说明", null, true, false));
        require(updated.getEntity().getVersion() == 2, "update increments version");
        require("测试课程二".equals(updated.getEntity().getCourseName()), "update persists name");
        require("限选".equals(updated.getEntity().getCourseType())
                && updated.getEntity().isAllowCrossMajor()
                && !updated.getEntity().isFinalExam(), "update persists all fields");
        require(count("SELECT version FROM course WHERE course_code='CS999'") == 2,
                "database version is 2");

        AdminCourseCatalogService.ConflictException stale = expect(
                AdminCourseCatalogService.ConflictException.class,
                () -> catalog.update(ADMIN, request(op(3), course.getCourseId(), 1, "CS999",
                        "过期", "选修", 2.0, 32, null, null, false, true)),
                "stale expectedVersion must conflict");
        require(stale.getLatest() != null && stale.getLatest().getVersion() == 2,
                "conflict carries latest entity");
        require("测试课程二".equals(text("SELECT course_name FROM course WHERE course_code='CS999'")),
                "stale update must not mutate");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                + op(3) + "'") == 0, "failed mutation is not logged");

        expect(IllegalArgumentException.class,
                () -> catalog.update(ADMIN, request(op(4), course.getCourseId(), 2, "CS998",
                        "改码", "选修", 2.0, 32, null, null, false, true)),
                "course code is immutable");
        expect(IllegalArgumentException.class,
                () -> catalog.create(ADMIN, request(op(5), null, 0, "CS996", "坏类型", "必选",
                        2.0, 32, null, null, false, true)),
                "unknown course type rejected");
        expect(IllegalArgumentException.class,
                () -> catalog.create(ADMIN, request(op(7), null, 0, "CS996", "学分过大", "选修",
                        10.0, 32, null, null, false, true)),
                "credit of 10 or more rejected before reaching DECIMAL(3,2)");
        expect(IllegalArgumentException.class,
                () -> catalog.create(ADMIN, request("not-a-uuid", null, 0, "CS996", "坏ID",
                        "选修", 2.0, 32, null, null, false, true)),
                "operationId must be a UUID");
        expect(AdminCourseCatalogService.ConflictException.class,
                () -> catalog.create(ADMIN, request(op(6), null, 0, "CS101", "重复", "选修",
                        2.0, 32, null, null, false, true)),
                "duplicate course code conflicts");
        require(count("SELECT COUNT(*) FROM course WHERE course_code='CS101'") == 1,
                "duplicate create must not insert");
        return course.getCourseId();
    }

    private static void verifyArchiveAndRestore(AdminCourseCatalogService catalog,
                                                String courseId) throws Exception {
        int seededVersion = count("SELECT version FROM course WHERE course_id=1001");
        int seededOfferings = count("SELECT COUNT(*) FROM course_offering"
                + " WHERE course_id=1001 AND status<>4");
        require(seededOfferings > 0, "seeded course 1001 must have an active offering");
        AdminCourseCatalogService.ConflictException active = expect(
                AdminCourseCatalogService.ConflictException.class,
                () -> catalog.archive(ADMIN, "1001", seededVersion, op(10)),
                "archive rejects active offerings");
        require(active.getLatest() != null && "1001".equals(active.getLatest().getCourseId())
                && active.getLatest().getOfferingCount() == seededOfferings,
                "conflict exposes offering count");
        require("ACTIVE".equals(text("SELECT status FROM course WHERE course_id=1001"))
                && count("SELECT version FROM course WHERE course_id=1001") == seededVersion,
                "seeded course untouched");

        AdminOperationResultDTO<AdminCourseDTO> archived =
                catalog.archive(ADMIN, courseId, 2, op(11));
        require("ARCHIVED".equals(archived.getEntity().getStatus()), "archive");
        require(archived.getEntity().getVersion() == 3, "archive bumps version");
        require(ADMIN.equals(text("SELECT archived_by FROM course WHERE course_id=" + courseId))
                && text("SELECT archived_at FROM course WHERE course_id=" + courseId) != null,
                "archive audit columns");

        AdminCourseCatalogService.ConflictException archivedUpdate = expect(
                AdminCourseCatalogService.ConflictException.class,
                () -> catalog.update(ADMIN, request(op(12), courseId, 3, "CS999", "已归档",
                        "选修", 2.0, 32, null, null, false, true)),
                "update on archived course conflicts");
        require("ARCHIVED".equals(archivedUpdate.getLatest().getStatus()),
                "conflict carries archived status");
        expect(AdminCourseCatalogService.ConflictException.class,
                () -> catalog.archive(ADMIN, courseId, 3, op(13)),
                "archive twice conflicts");

        AdminOperationResultDTO<AdminCourseDTO> restored =
                catalog.restore(ADMIN, courseId, 3, op(14));
        require("ACTIVE".equals(restored.getEntity().getStatus())
                && restored.getEntity().getVersion() == 4, "restore returns ACTIVE v4");
        require(text("SELECT archived_by FROM course WHERE course_id=" + courseId) == null,
                "restore clears archived_by");
        expect(AdminCourseCatalogService.NotFoundException.class,
                () -> catalog.restore(ADMIN, "999999999", 1, op(15)),
                "restore of missing course is not found");
        expect(IllegalArgumentException.class,
                () -> catalog.archive(ADMIN, "abc", 1, op(16)),
                "non-numeric id rejected");
    }

    private static void verifyReplayAndList(AdminCourseCatalogService catalog) throws Exception {
        String replayId = op(20);
        CourseEditorRequestDTO request = request(replayId, null, 0, "CS997", "重放课程", "通选",
                1.5, 24, null, null, true, true);
        AdminOperationResultDTO<AdminCourseDTO> first = catalog.create(ADMIN, request);
        AdminOperationResultDTO<AdminCourseDTO> replay = catalog.create(ADMIN, request);
        require(first.getEntity().getCourseId().equals(replay.getEntity().getCourseId())
                && first.getOutcomeCode().equals(replay.getOutcomeCode())
                && first.getMessage().equals(replay.getMessage())
                && first.getEntity().getVersion() == replay.getEntity().getVersion(),
                "replay returns the identical result");
        require(count("SELECT COUNT(*) FROM course WHERE course_code='CS997'") == 1,
                "replay must not create a second course");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                + ADMIN + "' AND operation_id='" + replayId + "'") == 1,
                "replay keeps one operation row");
        expect(IllegalArgumentException.class,
                () -> catalog.create(ADMIN, request(replayId, null, 0, "CS995", "不同请求", "通选",
                        1.5, 24, null, null, true, true)),
                "same operationId with a different request is rejected");

        String courseId = first.getEntity().getCourseId();
        AdminOperationResultDTO<AdminCourseDTO> archived =
                catalog.archive(ADMIN, courseId, 1, op(21));
        AdminOperationResultDTO<AdminCourseDTO> archivedAgain =
                catalog.archive(ADMIN, courseId, 1, op(21));
        require(archived.getEntity().getVersion() == 2
                && archivedAgain.getEntity().getVersion() == 2
                && count("SELECT version FROM course WHERE course_id=" + courseId) == 2,
                "archive replay does not bump version twice");

        List<AdminCourseDTO> archivedOnly = catalog.list("CS99", "ARCHIVED", null, null);
        require(archivedOnly.stream().anyMatch(course -> courseId.equals(course.getCourseId()))
                        && archivedOnly.stream().allMatch(course ->
                                "ARCHIVED".equals(course.getStatus())
                                        && (course.getCourseCode().contains("CS99")
                                        || course.getCourseName().contains("CS99"))),
                "list filters by query and status");
        require(catalog.list("CS99_", null, null, null).stream()
                        .noneMatch(course -> "CS999".equals(course.getCourseCode())),
                "LIKE wildcards in the query are escaped");
        require(catalog.list("测试课程", null, null, null).stream()
                        .anyMatch(course -> "CS999".equals(course.getCourseCode())),
                "list matches course name");
        int seededOfferings = count("SELECT COUNT(*) FROM course_offering o JOIN course c"
                + " ON c.course_id=o.course_id WHERE c.course_code='CS101' AND o.status<>4");
        require(catalog.list(null, null, null, null).stream()
                        .anyMatch(course -> "CS101".equals(course.getCourseCode())
                                && course.getOfferingCount() == seededOfferings),
                "list without filters includes seeded course with offering count");
        expect(IllegalArgumentException.class, () -> catalog.list(null, "BOGUS", null, null),
                "unknown status filter rejected");

        verifyTermScopedOfferingCount(catalog);
    }

    /**
     * 按学期筛选时，课程行的 "教学班 N 个" 必须只数该学期的非取消教学班，
     * 与展开后看到的行数一致——这正是丁2 里两个数字打架的地方。
     */
    private static void verifyTermScopedOfferingCount(AdminCourseCatalogService catalog)
            throws Exception {
        execute("INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,status) VALUES('CS999-C-2033',1001,2033,1,10,2)");
        try {
            int scoped = count("SELECT COUNT(*) FROM course_offering WHERE course_id=1001"
                    + " AND status<>4 AND academic_year=2033 AND semester=1");
            AdminCourseDTO scopedRow = catalog.list(null, null, 2033, 1).stream()
                    .filter(course -> "1001".equals(course.getCourseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("course 1001 must be listed"));
            require(scopedRow.getOfferingCount() == scoped,
                    "a term-scoped list must count only that term, expected " + scoped
                            + " but saw " + scopedRow.getOfferingCount());

            // 计数子查询写在 SELECT 列表里，位置参数先于 WHERE 的；带 query 调用才会让两组
            // 占位符**同时**出现，把绑定顺序钉住（两个学期参数都传 null 时 params 是空的）。
            AdminCourseDTO queryScopedRow = catalog.list("CS", null, 2033, 1).stream()
                    .filter(course -> "1001".equals(course.getCourseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "a term-scoped list with a query must still match course 1001"));
            require(queryScopedRow.getOfferingCount() == scoped,
                    "a term-scoped list with a query must count only that term, expected "
                            + scoped + " but saw " + queryScopedRow.getOfferingCount());

            int allTerms = count("SELECT COUNT(*) FROM course_offering WHERE course_id=1001"
                    + " AND status<>4");
            require(allTerms > scoped, "the fixture must add a term the course did not have");
            AdminCourseDTO unscopedRow = catalog.list(null, null, null, null).stream()
                    .filter(course -> "1001".equals(course.getCourseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("course 1001 must be listed"));
            require(unscopedRow.getOfferingCount() == allTerms,
                    "a list without a term must keep counting every term, expected " + allTerms
                            + " but saw " + unscopedRow.getOfferingCount());

            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, 2027, null),
                    "an academic year without a semester must be rejected");
            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, null, 3),
                    "a semester without an academic year must be rejected");
            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, 2027, 4),
                    "a semester outside 1..3 must be rejected");
        } finally {
            execute("DELETE FROM course_offering WHERE offering_code='CS999-C-2033'");
        }
    }

    private static CourseEditorRequestDTO request(String operationId, String courseId,
                                                  int expectedVersion, String code, String name,
                                                  String type, double credit, int hours,
                                                  String description, String prerequisites,
                                                  boolean allowCrossMajor, boolean finalExam) {
        return new CourseEditorRequestDTO(operationId, courseId, expectedVersion, code, name,
                type, credit, hours, description, prerequisites, allowCrossMajor, finalExam);
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM course WHERE course_code IN ('CS999','CS997','CS996','CS995')");
        execute("DELETE FROM tbl_user WHERE UID='" + ADMIN + "'");
    }

    private static void requireTestDatabase() throws SQLException {
        require("virtual_campus_course_test".equals(text("SELECT DATABASE()")),
                "Refusing admin catalog test outside virtual_campus_course_test");
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String op(int value) {
        return String.format("30000000-0000-0000-0000-%012d", value);
    }

    private static <X extends Throwable> X expect(Class<X> type, ThrowingRun action,
                                                  String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
