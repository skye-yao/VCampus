package service;

import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import util.DBUtil;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Guarded MySQL coverage for the roster export path behind {@code requestRosterExport}:
 * {@link TeacherCourseQueryService#listAllOfferingStudents} and
 * {@link dao.TeacherCourseQueryDAO#listStudentsForExport}.
 *
 * <p>Three claims are pinned here, none of which had a test before this task:
 * <ul>
 *   <li><b>Ownership</b> — the export enters through the same {@link TeacherAccessPolicy} gate as the
 *       roster list, so a teacher who is not on the class and a student both get an access denial
 *       instead of another teacher's roster.</li>
 *   <li><b>The cap is never a silent truncation</b> — the DAO deliberately fetches
 *       {@code MAX_ROWS + 1} rows in <em>one</em> query (no count-then-fetch window), and the service
 *       turns {@code 5001} into an explicit error rather than a file with the first 5000 students in
 *       it. Exactly {@code 5000} still exports all 5000, so the boundary is proven on both sides.</li>
 *   <li><b>Filter and order parity</b> — the export and the list share {@code ROSTER_SELECT} and
 *       {@code rosterFilter}, so the same query/status filters and the same {@code (uid, enrollment_id)}
 *       ordering come back row for row. This is the half a drifted copy of the SQL would break, and it
 *       is exactly the half a hand-written fixture cannot see.</li>
 * </ul>
 *
 * <p>Fixtures live in the 9851xx/9852xx id ranges with enrollment ids 991000-996200 and the
 * {@code tce985-} UID prefix — none of the bands the neighbouring suites claim (see the id survey in
 * {@code TeacherGradeImportSocketEndToEndTest} and {@code TeacherAdjustmentApplicationMySqlTest}) —
 * and only {@code virtual_campus_course_test} is allowed. The 5001-row roster is inserted in batches
 * and every fixture row is deleted again. Without a {@code mysql} argument the test prints SKIP and is
 * never reported as passing.
 */
public final class TeacherCourseExportMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tce985-";
    private static final String TEACHER_A = PREFIX + "teacher-a";
    private static final String TEACHER_B = PREFIX + "teacher-b";
    private static final String STUDENT = PREFIX + "student";

    private static final long COURSE_BIG = 985101L;
    private static final long COURSE_MIXED = 985102L;
    private static final long OFFERING_BIG = 985201L;
    private static final long OFFERING_MIXED = 985202L;

    /** 名单上限 + 1：服务端就是为了这一次查询内的超限判定才多取一行的。 */
    private static final int OVER_CAP = TeacherSpreadsheetService.MAX_ROWS + 1;
    private static final long ENROLL_BIG_FIRST = 991000L;
    private static final long ENROLL_MIXED_FIRST = 996100L;
    private static final int MIXED_STUDENTS = 6;
    /** 第 3 位学生已退课：导出与列表共用同一个状态过滤。 */
    private static final int MIXED_DROPPED = 3;
    private static final int ENROLLED_STATUS = 2;
    private static final int DROPPED_STATUS = 3;
    private static final int BATCH = 500;

    private static final String CAP_MESSAGE = "名单超过 " + TeacherSpreadsheetService.MAX_ROWS
            + " 行导出上限，请缩小筛选范围后重试";

    private TeacherCourseExportMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher course export test was not "
                    + "run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            TeacherCourseQueryService queries = new TeacherCourseQueryService();
            verifyOwnershipIsEnforced(queries);
            verifyTheCapIsNeverASilentTruncation(queries);
            verifyExportMatchesListFilterAndOrder(queries);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher course export MySQL test passed.");
    }

    // ------------------------------------------------------------------ 归属

    /** 导出与名单列表同一个归属入口：别人（教师或学生）拿不到这份名单。 */
    private static void verifyOwnershipIsEnforced(TeacherCourseQueryService queries) {
        TeacherAccessPolicy.AccessDeniedException forOtherTeacher = expectDenied(() -> queries
                .listAllOfferingStudents(TEACHER_B, Long.toString(OFFERING_MIXED), null, null),
                "another teacher must not export a colleague's roster");
        require("没有查看该教学班的权限".equals(forOtherTeacher.getMessage()),
                "the denial must be the shared access message, saw " + forOtherTeacher.getMessage());
        expectDenied(() -> queries.listAllOfferingStudents(STUDENT,
                        Long.toString(OFFERING_MIXED), null, null),
                "a student must not export a roster");
        expectRejected(() -> queries.listAllOfferingStudents(TEACHER_A, "0", null, null),
                "an invalid offering id must be rejected");
        require(queries.listAllOfferingStudents(TEACHER_A, Long.toString(OFFERING_MIXED), null, null)
                        .size() == MIXED_STUDENTS,
                "the owning teacher must get the whole roster");
    }

    // ------------------------------------------------------------------ 上限

    /**
     * 5001 行必须明确报错，5000 行必须整份导出：一次查询内的超限判定不允许退化成静默截断。
     */
    private static void verifyTheCapIsNeverASilentTruncation(TeacherCourseQueryService queries)
            throws Exception {
        String offeringId = Long.toString(OFFERING_BIG);
        require(count("SELECT COUNT(*) FROM enrollment WHERE offering_id=" + OFFERING_BIG)
                        == OVER_CAP,
                "the fixture must hold " + OVER_CAP + " enrollments");

        IllegalArgumentException overCap = expectRejected(
                () -> queries.listAllOfferingStudents(TEACHER_A, offeringId, null, null),
                "a roster over the export cap must fail instead of being truncated");
        require(CAP_MESSAGE.equals(overCap.getMessage()),
                "the cap failure must be the documented message, saw " + overCap.getMessage());

        // 过滤之后剩下的行数在限内：上限判定针对的是过滤后的结果，不是整张表。
        require(queries.listAllOfferingStudents(TEACHER_A, offeringId, "b0001", null).size() == 1,
                "the export filter must be applied before the cap is judged");

        // 删掉一行正好落在上限上：边界两侧都必须是确定的（不少一行，也不多报错）。
        execute("DELETE FROM enrollment WHERE enrollment_id="
                + (ENROLL_BIG_FIRST + OVER_CAP - 1));
        List<TeacherRosterRowDTO> exactlyAtCap =
                queries.listAllOfferingStudents(TEACHER_A, offeringId, null, null);
        require(exactlyAtCap.size() == TeacherSpreadsheetService.MAX_ROWS,
                "exactly " + TeacherSpreadsheetService.MAX_ROWS + " rows must export in full, saw "
                        + exactlyAtCap.size());
        require(exactlyAtCap.get(0).getStudentUid().endsWith("b0001")
                        && exactlyAtCap.get(exactlyAtCap.size() - 1).getStudentUid()
                        .endsWith("b5000"),
                "the whole page-less roster must come back in order, saw "
                        + exactlyAtCap.get(0).getStudentUid() + "..." + exactlyAtCap
                        .get(exactlyAtCap.size() - 1).getStudentUid());
    }

    // --------------------------------------------------------- 过滤与排序一致

    /** 导出与列表必须给出同样的行、同样的顺序——过滤条件只有一份实现。 */
    private static void verifyExportMatchesListFilterAndOrder(TeacherCourseQueryService queries) {
        String offeringId = Long.toString(OFFERING_MIXED);
        List<TeacherRosterRowDTO> exported =
                queries.listAllOfferingStudents(TEACHER_A, offeringId, null, null);
        TeacherPageDTO<TeacherRosterRowDTO> page =
                queries.listOfferingStudents(TEACHER_A, offeringId, null, null, 1, 100);
        require(page.getTotalCount() == MIXED_STUDENTS && page.getItems().size() == MIXED_STUDENTS,
                "the list must report the whole mixed roster");
        require(sameRows(exported, page.getItems()),
                "the unfiltered export must equal the list page row for row, saw " + text(exported)
                        + " vs " + text(page.getItems()));
        require(exported.get(0).getStudentUid().endsWith("m1")
                        && exported.get(exported.size() - 1).getStudentUid().endsWith("m6"),
                "the export must keep the (uid, enrollment id) order, saw " + text(exported));
        require("DROPPED".equals(exported.get(MIXED_DROPPED - 1).getEnrollmentStatus()),
                "the unfiltered export must include the dropped student, saw "
                        + exported.get(MIXED_DROPPED - 1).getEnrollmentStatus());

        // 学号过滤：导出与列表拿到同一个集合、同一个顺序。
        List<TeacherRosterRowDTO> byUid = queries.listAllOfferingStudents(TEACHER_A, offeringId, "m2",
                null);
        TeacherPageDTO<TeacherRosterRowDTO> byUidPage =
                queries.listOfferingStudents(TEACHER_A, offeringId, "m2", null, 1, 100);
        require(byUid.size() == 1 && byUidPage.getItems().size() == 1
                        && sameRows(byUid, byUidPage.getItems()),
                "the uid filter must reach both paths, saw " + text(byUid) + " vs "
                        + text(byUidPage.getItems()));

        // 姓名过滤（同一个 LIKE 分支的另一半）。
        List<TeacherRosterRowDTO> byName = queries.listAllOfferingStudents(TEACHER_A, offeringId,
                "Tce985 Mixed 04", null);
        require(byName.size() == 1 && !byName.get(0).getStudentUid().endsWith("m2"),
                "the name filter must match by student name, saw " + text(byName));

        // 状态过滤：退课行只在 status=3 时出现，正常行只在 status=2 时出现（两条路径同源）。
        List<TeacherRosterRowDTO> dropped = queries.listAllOfferingStudents(TEACHER_A, offeringId,
                null, DROPPED_STATUS);
        List<TeacherRosterRowDTO> enrolled = queries.listAllOfferingStudents(TEACHER_A, offeringId,
                null, ENROLLED_STATUS);
        require(dropped.size() == 1 && dropped.get(0).getStudentUid().endsWith("m"
                        + MIXED_DROPPED)
                        && enrolled.size() == MIXED_STUDENTS - 1,
                "the status filter must select exactly one side, saw " + text(dropped) + " / "
                        + enrolled.size() + " rows");
        require(sameRows(dropped, queries.listOfferingStudents(TEACHER_A, offeringId, null,
                        DROPPED_STATUS, 1, 100).getItems()),
                "the dropped filter must agree with the list");
        expectRejected(() -> queries.listAllOfferingStudents(TEACHER_A, offeringId, null, 1),
                "an unsupported enrollment status must be rejected");
    }

    private static boolean sameRows(List<TeacherRosterRowDTO> left, List<TeacherRosterRowDTO> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            TeacherRosterRowDTO a = left.get(index);
            TeacherRosterRowDTO b = right.get(index);
            if (!equal(a.getEnrollmentId(), b.getEnrollmentId())
                    || !equal(a.getStudentUid(), b.getStudentUid())
                    || !equal(a.getStudentName(), b.getStudentName())
                    || !equal(a.getMajor(), b.getMajor())
                    || !equal(a.getEnrollmentStatus(), b.getEnrollmentStatus())
                    || !equal(a.getSelectedAt(), b.getSelectedAt())
                    || !equal(a.getDroppedAt(), b.getDroppedAt())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String text(List<TeacherRosterRowDTO> rows) {
        List<String> values = new ArrayList<>();
        for (TeacherRosterRowDTO row : rows) {
            values.add(row.getStudentUid() + "(" + row.getEnrollmentStatus() + ")");
        }
        return values.toString();
    }

    // ------------------------------------------------------------------ fixtures

    private static void insertFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_A + "','Tce985 Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Tce985 Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + STUDENT + "','Tce985 Student','x','x',2,'Engineering','CS')");
        // 大量学生的姓名/专业各自不同：过滤与排序断言据此区分是哪一行。
        for (int offset = 0; offset < OVER_CAP; offset += BATCH) {
            int last = Math.min(offset + BATCH, OVER_CAP);
            StringBuilder users = new StringBuilder("INSERT INTO tbl_user(UID,name,password,salt,"
                    + "role,college,major) VALUES");
            for (int index = offset + 1; index <= last; index++) {
                if (index > offset + 1) users.append(',');
                users.append("('").append(bigUid(index)).append("','Tce985 Big ")
                        .append(String.format("%04d", index)).append("','x','x',2,'Engineering','CS')");
            }
            execute(users.toString());
        }
        for (int index = 1; index <= MIXED_STUDENTS; index++) {
            execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES('"
                    + mixedUid(index) + "','Tce985 Mixed " + String.format("%02d", index)
                    + "','x','x',2,'Engineering','CS')");
        }
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_BIG + ",'TCE985BIG','Teacher Course Export"
                + " Big',3.00,48,1,'ACTIVE'),(" + COURSE_MIXED + ",'TCE985MIX','Teacher Course Export"
                + " Mixed',2.00,32,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_BIG + ",'TCE985-BIG'," + COURSE_BIG
                + ",2026,3,6000,2),(" + OFFERING_MIXED + ",'TCE985-MIX'," + COURSE_MIXED
                + ",2026,3,40,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_BIG
                + ",'" + TEACHER_A + "',0),(" + OFFERING_MIXED + ",'" + TEACHER_A + "',0)");
        for (int offset = 0; offset < OVER_CAP; offset += BATCH) {
            int last = Math.min(offset + BATCH, OVER_CAP);
            StringBuilder enrollments = new StringBuilder("INSERT INTO enrollment(enrollment_id,"
                    + "offering_id,course_id,academic_year,semester,uid,status,select_time) VALUES");
            for (int index = offset + 1; index <= last; index++) {
                if (index > offset + 1) enrollments.append(',');
                enrollments.append('(').append(ENROLL_BIG_FIRST + index - 1).append(',')
                        .append(OFFERING_BIG).append(',').append(COURSE_BIG)
                        .append(",2026,3,'").append(bigUid(index))
                        .append("',2,'2026-09-14 01:00:00')");
            }
            execute(enrollments.toString());
        }
        // 混合名单的插入顺序刻意打乱：导出的顺序只能来自查询自己的 ORDER BY。
        for (int index : List.of(3, 1, 6, 2, 5, 4)) {
            execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,"
                    + "semester,uid,status,select_time,drop_time) VALUES("
                    + (ENROLL_MIXED_FIRST + index - 1) + "," + OFFERING_MIXED + "," + COURSE_MIXED
                    + ",2026,3,'" + mixedUid(index) + "',"
                    + (index == MIXED_DROPPED ? DROPPED_STATUS : ENROLLED_STATUS)
                    + ",'2026-09-14 01:00:00',"
                    + (index == MIXED_DROPPED ? "'2026-09-20 01:00:00'" : "NULL") + ")");
        }
    }

    private static String bigUid(int index) {
        return PREFIX + "b" + String.format("%04d", index);
    }

    private static String mixedUid(int index) {
        return PREFIX + "m" + index;
    }

    private static void cleanup() throws Exception {
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 985200 AND 985299"
                + " OR enrollment_id BETWEEN 991000 AND 996200");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 985200 AND 985299");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 985200 AND 985299");
        execute("DELETE FROM course WHERE course_id BETWEEN 985100 AND 985199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws Exception {
        require(count("SELECT COUNT(*) FROM enrollment WHERE offering_id BETWEEN 985200 AND 985299"
                        + " OR enrollment_id BETWEEN 991000 AND 996200") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 985200 AND 985299") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 985100 AND"
                        + " 985199") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'")
                        == 0,
                "cleanup must leave no fixture row behind");
    }

    // ------------------------------------------------------------------ helpers

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
        require(GUARDED_DATABASE.equals(database),
                "Refusing teacher course export test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher course export test outside the guarded schema");
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
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static TeacherAccessPolicy.AccessDeniedException expectDenied(ThrowingRun action,
            String message) {
        try {
            action.run();
        } catch (TeacherAccessPolicy.AccessDeniedException expected) {
            return expected;
        } catch (Throwable failure) {
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    private static IllegalArgumentException expectRejected(ThrowingRun action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return expected;
        } catch (Throwable failure) {
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
