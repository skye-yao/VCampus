package service;

import dto.course.CourseMutationResultDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CourseSelectionMySqlTest {
    private static final CourseTermDTO TERM =
            new CourseTermDTO(2026, 2, "2026-2027 秋学期");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);

    private CourseSelectionMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        try {
            verifyPlanRoundTrip();
            verifySelectCapacityAndQueueRules();
            verifyCourseAndScheduleConflicts();
            verifyDropDeadlineAndPostCommitTrigger();
            verifyOperationReplayAndScope();
            verifyConcurrentSerializationAndFinalSeat();
        } finally {
            resetState();
        }
        System.out.println("Course selection MySQL test passed.");
    }

    private static void verifyPlanRoundTrip() throws Exception {
        resetState();
        CourseSelectionService service = service(new AtomicInteger());
        CourseMutationResultDTO added = service.addToPlan(
                "student-beta", TERM, 2001L, op(1));
        require(added.getFinalState() == SelectionStateDTO.PLANNED,
                "AVAILABLE must become PLANNED");
        require(count("SELECT COUNT(*) FROM course_plan_item WHERE uid='student-beta'"
                + " AND offering_id=2001 AND status='PLANNED'") == 1, "plan row required");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "add plan must not create enrollment history");

        CourseMutationResultDTO removed = service.removeFromPlan(
                "student-beta", TERM, 2001L, op(2));
        require(removed.getFinalState() == SelectionStateDTO.AVAILABLE,
                "PLANNED must become AVAILABLE");
        require(count("SELECT COUNT(*) FROM course_plan_item WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "plan row must be removed");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "remove plan must not create enrollment history");
    }

    private static void verifySelectCapacityAndQueueRules() throws Exception {
        resetState();
        CourseSelectionService service = service(new AtomicInteger());
        service.addToPlan("student-beta", TERM, 2001L, op(10));
        CourseMutationResultDTO enrolled = service.selectOffering(
                "student-beta", TERM, 2001L, op(11));
        require(enrolled.getFinalState() == SelectionStateDTO.ENROLLED,
                "PLANNED with capacity must enroll");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 2,
                "successful select must increment count once");
        require(count("SELECT COUNT(*) FROM course_plan_item WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "successful select removes current plan");

        resetState();
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=2001");
        service.addToPlan("student-beta", TERM, 2001L, op(12));
        CourseMutationResultDTO full = service.selectOffering(
                "student-beta", TERM, 2001L, op(13));
        require(full.getFinalState() == SelectionStateDTO.FULL,
                "full offering must produce FULL without implicit waitlist");
        require(count("SELECT COUNT(*) FROM course_waitlist WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "FULL must not create waitlist");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                + " AND offering_id=2001") == 0, "FULL must not create enrollment history");

        resetState();
        addGamma();
        service.addToPlan("student-gamma", TERM, 2002L, op(14));
        CourseMutationResultDTO queuedSeat = service.selectOffering(
                "student-gamma", TERM, 2002L, op(15));
        require(queuedSeat.getFinalState() == SelectionStateDTO.FULL,
                "ordinary select must not bypass an active waiter");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2002") == 0,
                "transient vacancy belongs to the waitlist");
    }

    private static void verifyCourseAndScheduleConflicts() throws Exception {
        resetState();
        CourseSelectionService service = service(new AtomicInteger());
        CourseMutationResultDTO sameCourse = service.selectOffering(
                "student-alpha", TERM, 2002L, op(20));
        require(sameCourse.getFinalState() == SelectionStateDTO.PLANNED
                        && "SAME_COURSE_CONFLICT".equals(sameCourse.getOutcomeCode()),
                "same-course conflict must preserve PLANNED with a stable reason");
        require(text("SELECT last_failure_reason FROM course_plan_item"
                + " WHERE uid='student-alpha' AND offering_id=2002") != null,
                "same-course conflict reason must be persisted");

        resetState();
        execute("DELETE FROM course_waitlist WHERE offering_id=2002");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-beta',2002,'PLANNED')");
        execute("INSERT INTO course_offering_conflict"
                + "(plan_id,course_offering_a_id,course_offering_b_id,conflict_count,"
                + " first_conflict_at,last_conflict_at) VALUES"
                + "(4001,2002,2004,1,'2026-09-10 00:00:00','2026-09-10 00:00:00')");
        CourseMutationResultDTO schedule = service.selectOffering(
                "student-beta", TERM, 2002L, op(21));
        require(schedule.getFinalState() == SelectionStateDTO.PLANNED
                        && "SCHEDULE_CONFLICT".equals(schedule.getOutcomeCode()),
                "schedule conflict must preserve PLANNED with a stable reason");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2002") == 0,
                "conflicts must not change capacity");
    }

    private static void verifyDropDeadlineAndPostCommitTrigger() throws Exception {
        resetState();
        AtomicInteger triggered = new AtomicInteger();
        CourseSelectionService service = service(triggered);
        CourseMutationResultDTO dropped = service.dropOffering(
                "student-alpha", TERM, 2001L, op(30));
        require("DROPPED".equals(dropped.getOutcomeCode()), "drop outcome required");
        require(count("SELECT status FROM enrollment WHERE uid='student-alpha'"
                + " AND offering_id=2001") == 3, "drop must retain DROPPED history");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 0,
                "drop must decrement count");
        require(count("SELECT COUNT(*) FROM course_plan_item WHERE uid='student-alpha'"
                + " AND offering_id=2001") == 0, "drop must not restore a plan");
        require(triggered.get() == 1, "waitlist trigger must run once after committed drop");

        resetState();
        triggered.set(0);
        execute("UPDATE course_selection_window SET selection_close_at='2025-01-01 00:00:00',"
                + " drop_deadline='2025-01-02 00:00:00' WHERE academic_year=2026 AND semester=2");
        CourseMutationResultDTO closed = service.dropOffering(
                "student-alpha", TERM, 2001L, op(31));
        require("DROP_CLOSED".equals(closed.getOutcomeCode()),
                "drop after deadline must be a stable conflict");
        require(count("SELECT status FROM enrollment WHERE uid='student-alpha'"
                + " AND offering_id=2001") == 2, "closed drop must not mutate enrollment");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 1,
                "closed drop must not change count");
        require(triggered.get() == 0, "closed drop must not trigger waitlist advancement");
    }

    private static void verifyOperationReplayAndScope() throws Exception {
        resetState();
        addGamma();
        CourseSelectionService service = service(new AtomicInteger());
        String replayId = op(40);
        CourseMutationResultDTO first = service.addToPlan("student-beta", TERM, 2001L, replayId);
        CourseMutationResultDTO replay = service.addToPlan("student-beta", TERM, 2001L, replayId);
        require(first.getOutcomeCode().equals(replay.getOutcomeCode())
                        && first.getFinalState() == replay.getFinalState()
                        && first.getMessage().equals(replay.getMessage()),
                "same operation must replay the identical business result");
        require(count("SELECT COUNT(*) FROM course_operation_log WHERE uid='student-beta'"
                + " AND operation_id='" + replayId + "'") == 1,
                "replay must keep one operation row");

        boolean mismatch = false;
        try {
            service.addToPlan("student-beta", TERM, 2002L, replayId);
        } catch (CourseSelectionService.OperationConflictException expected) {
            mismatch = true;
        }
        require(mismatch, "same operation ID with different request must be rejected");

        String shared = op(41);
        service.addToPlan("student-beta", TERM, 2001L, shared);
        service.addToPlan("student-gamma", TERM, 2002L, shared);
        require(count("SELECT COUNT(*) FROM course_operation_log WHERE operation_id='"
                + shared + "'") == 2, "operation scope must include authenticated UID");
    }

    private static void verifyConcurrentSerializationAndFinalSeat() throws Exception {
        resetState();
        CourseSelectionService service = service(new AtomicInteger());
        service.addToPlan("student-beta", TERM, 2001L, op(50));
        List<CourseMutationResultDTO> sameStudent = race(
                () -> service.selectOffering("student-beta", TERM, 2001L, op(51)),
                () -> service.selectOffering("student-beta", TERM, 2001L, op(52)));
        require(sameStudent.stream().filter(
                        result -> result.getFinalState() == SelectionStateDTO.ENROLLED).count() >= 1,
                "one same-student operation must observe enrollment");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                + " AND offering_id=2001 AND status=2") == 1,
                "same-student race must create one active enrollment");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 2,
                "same-student race must increment once");

        resetState();
        addGamma();
        execute("UPDATE course_offering SET capacity=2 WHERE offering_id=2001");
        service.addToPlan("student-beta", TERM, 2001L, op(53));
        service.addToPlan("student-gamma", TERM, 2001L, op(54));
        List<CourseMutationResultDTO> finalSeat = race(
                () -> service.selectOffering("student-beta", TERM, 2001L, op(55)),
                () -> service.selectOffering("student-gamma", TERM, 2001L, op(56)));
        require(finalSeat.stream().filter(
                        result -> result.getFinalState() == SelectionStateDTO.ENROLLED).count() == 1,
                "exactly one student must win the final seat");
        require(finalSeat.stream().filter(
                        result -> result.getFinalState() == SelectionStateDTO.FULL).count() == 1,
                "final-seat loser must remain FULL without implicit waitlist");
        require(finalSeat.stream().allMatch(result -> result.getItem() != null
                        && result.getItem().getOffering().getEnrolledCount() == 2),
                "both final-seat responses must expose the committed capacity count");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 2,
                "final-seat race must not drift count");
        require(count("SELECT COUNT(*) FROM enrollment WHERE offering_id=2001 AND status=2") == 2,
                "capacity and active enrollment rows must agree");
    }

    private static CourseSelectionService service(AtomicInteger triggerCount) {
        return new CourseSelectionService(CLOCK,
                offeringId -> triggerCount.incrementAndGet());
    }

    private static List<CourseMutationResultDTO> race(ThrowingCall first,
                                                       ThrowingCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CourseMutationResultDTO> a = pool.submit(() -> runAtBarrier(first, ready, start));
            Future<CourseMutationResultDTO> b = pool.submit(() -> runAtBarrier(second, ready, start));
            require(ready.await(5, TimeUnit.SECONDS), "workers did not reach race barrier");
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static CourseMutationResultDTO runAtBarrier(ThrowingCall call,
                                                         CountDownLatch ready,
                                                         CountDownLatch start) throws Exception {
        ready.countDown();
        require(start.await(5, TimeUnit.SECONDS), "race start timed out");
        return call.run();
    }

    private static void resetState() throws SQLException {
        try (Connection connection = DBUtil.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("DELETE FROM grade");
                statement.executeUpdate("DELETE FROM course_event_outbox");
                statement.executeUpdate("DELETE FROM course_operation_log");
                statement.executeUpdate("DELETE FROM course_waitlist");
                statement.executeUpdate("DELETE FROM course_plan_item");
                statement.executeUpdate("DELETE FROM enrollment");
                statement.executeUpdate("DELETE FROM student_academic_profile WHERE uid='student-gamma'");
                statement.executeUpdate("DELETE FROM tbl_user WHERE UID='student-gamma'");
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
                statement.executeUpdate("UPDATE course_offering SET capacity=30,"
                        + " enrolled_count=CASE offering_id WHEN 2001 THEN 1"
                        + " WHEN 2004 THEN 1 ELSE 0 END, status=2"
                        + " WHERE offering_id IN (2001,2002,2003,2004)");
                statement.executeUpdate("INSERT INTO enrollment"
                        + "(enrollment_id,offering_id,course_id,academic_year,semester,uid,status,select_time)"
                        + " VALUES(6001,2001,1001,2026,2,'student-alpha',2,'2026-09-01 00:00:00.100001'),"
                        + "(6002,2004,1002,2026,2,'student-beta',2,'2026-09-01 00:00:00.100002')");
                statement.executeUpdate("INSERT INTO grade"
                        + "(grade_id,enrollment_id,daily_score,midterm_score,finalterm_score,"
                        + "experiment_score,score,grade_level,grade_point,is_published,publish_time)"
                        + " VALUES(6101,6002,88,86,92,90,89.5,4,4.0,1,'2026-09-05 00:00:00.123456')");
                statement.executeUpdate("INSERT INTO course_plan_item"
                        + "(plan_item_id,uid,offering_id,status,last_failure_reason)"
                        + " VALUES(6201,'student-alpha',2002,'PLANNED',NULL)");
                statement.executeUpdate("INSERT INTO course_waitlist"
                        + "(waitlist_id,uid,offering_id,status,queue_time)"
                        + " VALUES(6301,'student-beta',2002,'WAITING','2026-09-01 00:00:00.654321')");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void addGamma() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('student-gamma','Course Test Student G','x','x',2,'Engineering','Computer Science')");
        execute("INSERT INTO student_academic_profile(uid,major_id,cohort_year,status)"
                + " VALUES('student-gamma',10,2026,'ACTIVE')");
    }

    private static void requireTestDatabase() throws SQLException {
        require("virtual_campus_course_test".equals(text("SELECT DATABASE()")),
                "Refusing selection test outside virtual_campus_course_test");
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
        try (Connection connection = DBUtil.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String op(int value) {
        return String.format("20000000-0000-0000-0000-%012d", value);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        CourseMutationResultDTO run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
