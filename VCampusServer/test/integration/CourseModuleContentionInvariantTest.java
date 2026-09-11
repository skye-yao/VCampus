package integration;

import dto.course.CourseMutationResultDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import service.CourseSelectionService;
import util.DBUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Task 9 Step 3: repeat final-seat and duplicate-operation contention for at least 100
 * synchronized iterations and verify the database invariants after every iteration.
 *
 * <p>Aborts unless the JDBC database is exactly {@code virtual_campus_course_test}.
 */
public final class CourseModuleContentionInvariantTest {

    private static final int ITERATIONS = 100;
    private static final CourseTermDTO TERM = new CourseTermDTO(2026, 2, "2026-2027 秋学期");
    private static final String TEST_DATABASE = "virtual_campus_course_test";

    private CourseModuleContentionInvariantTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        ensureGamma();
        CourseSelectionService service = new CourseSelectionService();
        int enrollWins = 0;
        int fullLosses = 0;
        long started = System.currentTimeMillis();
        for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
            resetBaseline();

            String opBeta = op(iteration, 1);
            String opGamma = op(iteration, 2);
            List<CourseMutationResultDTO> seats = race(
                    () -> service.selectOffering("student-beta", TERM, 2001L, opBeta),
                    () -> service.selectOffering("student-gamma", TERM, 2001L, opGamma));
            for (CourseMutationResultDTO result : seats) {
                if (result.getFinalState() == SelectionStateDTO.ENROLLED) {
                    enrollWins++;
                } else if (result.getFinalState() == SelectionStateDTO.FULL) {
                    fullLosses++;
                }
            }
            require(enrollWins == iteration,
                    "iteration " + iteration + ": exactly one final-seat winner per iteration");
            require(fullLosses == iteration,
                    "iteration " + iteration + ": the final-seat loser must observe FULL");
            require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 2,
                    "iteration " + iteration + ": final seat must settle the capacity count at 2");

            resetBaseline();
            String shared = op(iteration, 3);
            List<CourseMutationResultDTO> duplicates = race(
                    () -> service.addToPlan("student-beta", TERM, 2002L, shared),
                    () -> service.addToPlan("student-beta", TERM, 2002L, shared));
            require(duplicates.get(0).getOutcomeCode().equals(duplicates.get(1).getOutcomeCode())
                            && duplicates.get(0).getFinalState() == duplicates.get(1).getFinalState(),
                    "iteration " + iteration + ": duplicate operation must replay one result");
            require(count("SELECT COUNT(*) FROM course_operation_log WHERE uid='student-beta'"
                            + " AND operation_id='" + shared + "'") == 1,
                    "iteration " + iteration + ": duplicate operation must keep one row");

            verifyInvariants(iteration);
        }
        long elapsed = System.currentTimeMillis() - started;
        System.out.println("Contention invariant test passed: " + ITERATIONS + " iterations, "
                + (enrollWins + fullLosses) + " final-seat outcomes, " + elapsed + " ms");
    }

    private static void verifyInvariants(int iteration) throws SQLException {
        require(count("SELECT COUNT(*) FROM course_offering WHERE enrolled_count < 0") == 0,
                "iteration " + iteration + ": enrolled_count must never be negative");
        require(count("SELECT COUNT(*) FROM course_offering o WHERE o.enrolled_count + ("
                        + " SELECT COUNT(*) FROM course_waitlist w WHERE w.offering_id = o.offering_id"
                        + " AND w.status='OFFERED' AND w.expires_at > UTC_TIMESTAMP(6)) > o.capacity") == 0,
                "iteration " + iteration + ": capacity must exceed enrolled plus unexpired offers");
        require(count("SELECT COUNT(*) FROM (SELECT uid, academic_year, semester, course_id,"
                        + " COUNT(*) c FROM enrollment WHERE status = 2"
                        + " GROUP BY uid, academic_year, semester, course_id HAVING c > 1) active") == 0,
                "iteration " + iteration + ": at most one active enrollment per uid/term/course");
        require(count("SELECT COUNT(*) FROM (SELECT uid, operation_id, COUNT(*) c"
                        + " FROM course_operation_log GROUP BY uid, operation_id HAVING c > 1) ops") == 0,
                "iteration " + iteration + ": one operation result per uid/operation_id");
        require(count("SELECT COUNT(*) FROM enrollment WHERE status NOT IN (2, 3)") == 0,
                "iteration " + iteration + ": enrollment status must be 2 or 3");
    }

    private static void resetBaseline() throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("DELETE FROM grade");
                statement.executeUpdate("DELETE FROM course_event_outbox");
                statement.executeUpdate("DELETE FROM course_operation_log");
                statement.executeUpdate("DELETE FROM course_waitlist");
                statement.executeUpdate("DELETE FROM course_plan_item");
                statement.executeUpdate("DELETE FROM enrollment");
                statement.executeUpdate("DELETE FROM course_offering_conflict"
                        + " WHERE NOT (plan_id=4001 AND course_offering_a_id=2001"
                        + " AND course_offering_b_id=2002)");
                statement.executeUpdate("UPDATE schedule_plan SET status='PUBLISHED' WHERE id=4001");
                statement.executeUpdate("UPDATE course_selection_window SET"
                        + " plan_open_at='2020-01-01 00:00:00',"
                        + " plan_close_at='2021-01-01 00:00:00',"
                        + " selection_open_at='2021-01-01 00:00:00.000001',"
                        + " selection_close_at='2098-01-01 00:00:00',"
                        + " drop_deadline='2099-01-01 00:00:00'"
                        + " WHERE academic_year=2026 AND semester=2");
                statement.executeUpdate("UPDATE course_offering SET capacity=2,"
                        + " enrolled_count=CASE offering_id WHEN 2001 THEN 1 WHEN 2004 THEN 1"
                        + " ELSE 0 END, status=2"
                        + " WHERE offering_id IN (2001,2002,2003,2004)");
                statement.executeUpdate("INSERT INTO enrollment"
                        + "(enrollment_id,offering_id,course_id,academic_year,semester,uid,status,select_time)"
                        + " VALUES(6001,2001,1001,2026,2,'student-alpha',2,'2026-09-01 00:00:00.100001'),"
                        + "(6002,2004,1002,2026,2,'student-beta',2,'2026-09-01 00:00:00.100002')");
                statement.executeUpdate("INSERT INTO course_plan_item(uid,offering_id,status)"
                        + " VALUES('student-beta',2001,'PLANNED'),('student-gamma',2001,'PLANNED')");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void ensureGamma() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                + " VALUES('student-gamma','Course Test Student G','x','x',2,'Engineering',"
                + "'Computer Science') ON DUPLICATE KEY UPDATE name=VALUES(name)");
        execute("INSERT INTO student_academic_profile(uid,major_id,cohort_year,status)"
                + " VALUES('student-gamma',10,2026,'ACTIVE')"
                + " ON DUPLICATE KEY UPDATE status=VALUES(status)");
    }

    private static List<CourseMutationResultDTO> race(ThrowingCall first, ThrowingCall second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CourseMutationResultDTO> a = pool.submit(() -> runAtBarrier(first, ready, start));
            Future<CourseMutationResultDTO> b = pool.submit(() -> runAtBarrier(second, ready, start));
            require(ready.await(10, TimeUnit.SECONDS), "workers did not reach the race barrier");
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static CourseMutationResultDTO runAtBarrier(ThrowingCall call, CountDownLatch ready,
                                                        CountDownLatch start) throws Exception {
        ready.countDown();
        require(start.await(10, TimeUnit.SECONDS), "race start timed out");
        return call.run();
    }

    private static void requireTestDatabase() throws SQLException {
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing contention test outside " + TEST_DATABASE);
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

    private static String op(int iteration, int slot) {
        return String.format("90000000-0000-0000-0000-%04d%08d", iteration, slot);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        CourseMutationResultDTO run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
