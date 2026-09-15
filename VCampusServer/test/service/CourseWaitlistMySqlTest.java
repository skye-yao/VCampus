package service;

import dto.course.CourseMutationResultDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import handler.CourseHandler;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.SessionManager;
import session.UserSession;
import util.DBUtil;

import java.sql.Connection;
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

public final class CourseWaitlistMySqlTest {
    private static final CourseTermDTO TERM = new CourseTermDTO(2026, 2, "test");
    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private CourseWaitlistMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        try {
            verifyJoinBranchesAndRequeueOrder();
            verifyPromotionSkipsInvalidHead();
            verifyAcceptDropsConflictsAtomically();
            verifyAbandonTimeoutAndClosedWindow();
            verifyConcurrentReservationInvariant();
            verifySelectionAndHandlerRespectWaitlistOwnership();
            System.out.println("Course waitlist MySQL test passed.");
        } finally {
            resetState();
        }
    }

    private static void verifyJoinBranchesAndRequeueOrder() throws Exception {
        resetState();
        CourseWaitlistService service = service();
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-beta',2001,'FULL')");
        CourseMutationResultDTO direct = service.joinWaitlist(
                "student-beta", TERM, 2001L, op(1));
        require(direct.getFinalState() == SelectionStateDTO.ENROLLED,
                "vacancy without queue or conflict must enroll directly");
        require(count("SELECT COUNT(*) FROM course_waitlist WHERE uid='student-beta'"
                + " AND offering_id=2001 AND status IN ('WAITING','OFFERED')") == 0,
                "direct enrollment must not leave an active waitlist row");

        resetState();
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-alpha',2002,'FULL')"
                + " ON DUPLICATE KEY UPDATE status='FULL'");
        CourseMutationResultDTO offered = service.joinWaitlist(
                "student-alpha", TERM, 2002L, op(2));
        require(offered.getFinalState() == SelectionStateDTO.WAITLIST_OFFERED,
                "vacancy with a conflict must create an offer");
        require((NOW.plusSeconds(300)).toString().equals(
                        offered.getItem().getOffering().getExpiresAt()),
                "offer deadline must be exactly five minutes from the injected clock");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-alpha'"
                + " AND offering_id=2002 AND status=2") == 0,
                "OFFERED must not create enrollment early");
        assertOutbox("student-alpha", "WAITLIST_OFFERED", 2002L,
                "2026-09-11T02:05:00Z");

        resetState();
        addStudent("student-gamma");
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=2001");
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES"
                + "('student-gamma',2001,'FULL'),('student-beta',2001,'FULL')");
        service.joinWaitlist("student-beta", TERM, 2001L, op(3));
        service.cancelWaitlist("student-beta", TERM, 2001L, op(4));
        execute("UPDATE course_offering SET capacity=3 WHERE offering_id=2001");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time)"
                + " VALUES('student-gamma',2001,'WAITING','2026-09-11 02:00:00')");
        CourseMutationResultDTO queued = service.joinWaitlist(
                "student-beta", TERM, 2001L, op(5));
        require(queued.getFinalState() == SelectionStateDTO.WAITLISTED,
                "an existing FIFO waiter must prevent direct enrollment despite vacancy");
        require("student-gamma".equals(text("SELECT uid FROM course_waitlist"
                + " WHERE offering_id=2001 AND status='WAITING'"
                + " ORDER BY queue_time,waitlist_id LIMIT 1")),
                "rejoin after cancellation must move behind existing waiters");
        require("FULL".equals(text("SELECT status FROM course_plan_item"
                + " WHERE uid='student-beta' AND offering_id=2001")),
                "cancelling and rejoining must retain the FULL plan item");
    }

    private static void verifyPromotionSkipsInvalidHead() throws Exception {
        resetState();
        addStudent("student-gamma");
        execute("UPDATE course_offering SET capacity=1,enrolled_count=1 WHERE offering_id=2001");
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES"
                + "('student-beta',2001,'FULL'),('student-gamma',2001,'FULL')");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time) VALUES"
                + "('student-beta',2001,'WAITING','2026-09-11 01:00:00'),"
                + "('student-gamma',2001,'WAITING','2026-09-11 01:01:00')");
        execute("UPDATE student_academic_profile SET status='SUSPENDED' WHERE uid='student-beta'");
        execute("UPDATE enrollment SET status=3,drop_time='2026-09-11 02:00:00'"
                + " WHERE uid='student-alpha' AND offering_id=2001");
        execute("UPDATE course_offering SET enrolled_count=0 WHERE offering_id=2001");

        service().offeringFreed(2001L);

        require("CANCELLED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-beta' AND offering_id=2001")),
                "an ineligible FIFO head must be cancelled");
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-gamma' AND offering_id=2001")),
                "promotion must continue to the next eligible FIFO waiter");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-gamma'"
                + " AND offering_id=2001 AND status=2") == 1,
                "eligible successor must be enrolled exactly once");
        require(count("SELECT COUNT(*) FROM course_event_outbox WHERE offering_id=2001") == 2,
                "cancelled head and enrolled successor must each create an outbox event");
    }

    private static void verifyAcceptDropsConflictsAtomically() throws Exception {
        resetState();
        addStudent("student-gamma");
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=2001");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-alpha',2002,'FULL')"
                + " ON DUPLICATE KEY UPDATE status='FULL'");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-gamma',2001,'FULL')");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time)"
                + " VALUES('student-gamma',2001,'WAITING','2026-09-11 01:00:00')");
        CourseWaitlistService service = service();
        service.joinWaitlist("student-alpha", TERM, 2002L, op(10));
        CourseMutationResultDTO accepted = service.resolveWaitlistOffer(
                "student-alpha", TERM, 2002L, op(11), "ACCEPT");

        require(accepted.getFinalState() == SelectionStateDTO.ENROLLED,
                "ACCEPT before deadline must enroll the offered class");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-alpha'"
                + " AND offering_id=2001 AND status=3") == 1,
                "ACCEPT must drop every conflicting active enrollment");
        require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-gamma'"
                + " AND offering_id=2001 AND status=2") == 1,
                "ACCEPT must trigger FIFO advancement for every dropped conflict after commit");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 1,
                "the freed conflict seat must be filled without count drift");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2002") == 1,
                "accepted offer must increment target count");
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-alpha' AND offering_id=2002")),
                "accepted waitlist row must become ENROLLED");
    }

    private static void verifyAbandonTimeoutAndClosedWindow() throws Exception {
        resetState();
        addStudent("student-gamma");
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES"
                + "('student-alpha',2002,'FULL'),('student-gamma',2002,'FULL')"
                + " ON DUPLICATE KEY UPDATE status='FULL'");
        CourseWaitlistService service = service();
        service.joinWaitlist("student-alpha", TERM, 2002L, op(20));
        service.joinWaitlist("student-gamma", TERM, 2002L, op(21));
        CourseMutationResultDTO abandoned = service.resolveWaitlistOffer(
                "student-alpha", TERM, 2002L, op(22), "ABANDON");
        require(abandoned.getFinalState() == SelectionStateDTO.FULL,
                "ABANDON must restore the FULL plan state");
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-gamma' AND offering_id=2002")),
                "ABANDON must release and immediately advance the next waiter");
        assertOutbox("student-alpha", "WAITLIST_OFFER_ABANDONED", 2002L, null);

        resetState();
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-alpha',2002,'FULL')"
                + " ON DUPLICATE KEY UPDATE status='FULL'");
        service.joinWaitlist("student-alpha", TERM, 2002L, op(23));
        require(service.expireOverdueOffers(NOW.plusSeconds(300), 10) == 1,
                "offer is overdue at its exact expires_at boundary");
        require("EXPIRED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-alpha' AND offering_id=2002")),
                "timeout must expire the offer");
        boolean rejected = false;
        try {
            new CourseWaitlistService(Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC))
                    .resolveWaitlistOffer("student-alpha", TERM, 2002L, op(24), "ACCEPT");
        } catch (CourseSelectionService.ConflictException expected) {
            rejected = true;
        }
        require(rejected, "ACCEPT at or after expires_at must fail");

        resetState();
        execute("UPDATE course_offering SET capacity=1,enrolled_count=1 WHERE offering_id=2001");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-beta',2001,'FULL')");
        service.joinWaitlist("student-beta", TERM, 2001L, op(25));
        execute("UPDATE course_selection_window SET selection_close_at='2026-09-11 01:59:59'"
                + " WHERE academic_year=2026 AND semester=2");
        service.advanceOpenVacancies(NOW, 10);
        require("EXPIRED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-beta' AND offering_id=2001")),
                "window close must expire WAITING rows instead of promoting them");

        resetState();
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-alpha',2002,'FULL')"
                + " ON DUPLICATE KEY UPDATE status='FULL'");
        service.joinWaitlist("student-alpha", TERM, 2002L, op(26));
        execute("UPDATE course_selection_window SET selection_close_at='2026-09-11 01:59:59'"
                + " WHERE academic_year=2026 AND semester=2");
        service.advanceOpenVacancies(NOW, 10);
        require("OFFERED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-alpha' AND offering_id=2002")),
                "window close must preserve an unexpired pre-existing offer");
        require(service.resolveWaitlistOffer("student-alpha", TERM, 2002L, op(27), "ACCEPT")
                        .getFinalState() == SelectionStateDTO.ENROLLED,
                "a pre-existing offer remains acceptable before its own deadline");

        resetState();
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-beta',2001,'FULL')");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time)"
                + " VALUES('student-beta',2001,'WAITING','2026-09-11 01:00:00')");
        execute("UPDATE course_offering SET status=3,enrolled_count=0 WHERE offering_id=2001");
        service.offeringFreed(2001L);
        require("CANCELLED".equals(text("SELECT status FROM course_waitlist"
                + " WHERE uid='student-beta' AND offering_id=2001")),
                "a closed teaching class must never promote its FIFO head");
    }

    private static void verifyConcurrentReservationInvariant() throws Exception {
        resetState();
        addStudent("student-gamma");
        execute("UPDATE course_offering SET capacity=2,enrolled_count=1 WHERE offering_id=2001");
        execute("INSERT INTO enrollment(offering_id,course_id,academic_year,semester,uid,status,select_time)"
                + " VALUES(2002,1001,2026,2,'student-beta',2,'2026-09-11 01:00:00'),"
                + "(2002,1001,2026,2,'student-gamma',2,'2026-09-11 01:00:01')");
        execute("UPDATE course_offering SET enrolled_count=2 WHERE offering_id=2002");
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES"
                + "('student-beta',2001,'FULL'),('student-gamma',2001,'FULL')");
        CourseWaitlistService service = service();
        List<CourseMutationResultDTO> results = race(
                () -> service.joinWaitlist("student-beta", TERM, 2001L, op(30)),
                () -> service.joinWaitlist("student-gamma", TERM, 2001L, op(31)));

        require(results.stream().filter(r -> r.getFinalState()
                        == SelectionStateDTO.WAITLIST_OFFERED).count() == 1,
                "one contender may reserve the final seat with an offer");
        require(results.stream().filter(r -> r.getFinalState()
                        == SelectionStateDTO.WAITLISTED).count() == 1,
                "the other contender must wait behind the reservation");
        int used = count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001")
                + count("SELECT COUNT(*) FROM course_waitlist WHERE offering_id=2001"
                + " AND status='OFFERED' AND expires_at>'2026-09-11 02:00:00'");
        require(used == 2, "enrollment plus live reservations must equal, never exceed, capacity");
    }

    private static void verifySelectionAndHandlerRespectWaitlistOwnership() throws Exception {
        resetState();
        addStudent("student-gamma");
        execute("UPDATE course_offering SET capacity=2,enrolled_count=1 WHERE offering_id=2001");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time,offered_at,expires_at)"
                + " VALUES('student-beta',2001,'OFFERED','2026-09-11 01:00:00',"
                + "'2026-09-11 01:01:00','2090-09-11 01:06:00')");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-gamma',2001,'PLANNED')");
        CourseMutationResultDTO blocked = new CourseSelectionService(CLOCK,
                WaitlistAdvanceTrigger.NO_OP).selectOffering(
                "student-gamma", TERM, 2001L, op(40));
        require(blocked.getFinalState() == SelectionStateDTO.FULL,
                "Task 5 direct selection must not consume an actively reserved final seat");
        require(count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001") == 1,
                "reserved final-seat rejection must not drift enrolled_count");

        resetState();
        addStudent("student-gamma");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-gamma',2001,'FULL')");
        UserSession session = SessionManager.getInstance().createSession("student-gamma", "学生");
        try {
            Message request = new Message(MessageType.REQUEST, "course", "joinWaitlist");
            request.setToken(session.getToken());
            request.setSender("student-beta");
            request.putData("uid", "student-beta");
            request.putData("academicYear", 2026);
            request.putData("semester", 2);
            request.putData("offeringId", "2001");
            request.putData("operationId", op(41));
            Message response = new CourseHandler().handle(request);
            require(response.getCode() == MessageCode.SUCCESS,
                    "CourseHandler must route the waitlist mutation");
            require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-gamma'"
                    + " AND offering_id=2001 AND status=2") == 1,
                    "Handler must derive waitlist identity from the authenticated session");
            require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                    + " AND offering_id=2001 AND status=2") == 0,
                    "Handler must ignore sender and data.uid for waitlist mutations");
        } finally {
            SessionManager.getInstance().removeSession(session.getToken());
        }

        resetState();
        execute("UPDATE course_offering SET capacity=1,enrolled_count=1 WHERE offering_id=2001");
        execute("INSERT INTO course_plan_item(uid,offering_id,status)"
                + " VALUES('student-beta',2001,'FULL')");
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time)"
                + " VALUES('student-beta',2001,'WAITING','2026-09-11 01:00:00')");
        UserSession dropping = SessionManager.getInstance().createSession("student-alpha", "学生");
        try {
            Message drop = new Message(MessageType.REQUEST, "course", "dropOffering");
            drop.setToken(dropping.getToken());
            drop.putData("academicYear", 2026);
            drop.putData("semester", 2);
            drop.putData("offeringId", "2001");
            drop.putData("operationId", op(42));
            require(new CourseHandler().handle(drop).getCode() == MessageCode.SUCCESS,
                    "default Handler drop must succeed");
            require(count("SELECT COUNT(*) FROM enrollment WHERE uid='student-beta'"
                    + " AND offering_id=2001 AND status=2") == 1,
                    "default Handler must bind Task 5 drops to the Task 6 waitlist trigger");
        } finally {
            SessionManager.getInstance().removeSession(dropping.getToken());
        }
    }

    private static CourseWaitlistService service() {
        return new CourseWaitlistService(CLOCK);
    }

    private static List<CourseMutationResultDTO> race(ThrowingCall first,
                                                       ThrowingCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CourseMutationResultDTO> a = pool.submit(() -> atBarrier(first, ready, start));
            Future<CourseMutationResultDTO> b = pool.submit(() -> atBarrier(second, ready, start));
            require(ready.await(5, TimeUnit.SECONDS), "workers did not reach race barrier");
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static CourseMutationResultDTO atBarrier(ThrowingCall call, CountDownLatch ready,
                                                       CountDownLatch start) throws Exception {
        ready.countDown();
        require(start.await(5, TimeUnit.SECONDS), "race start timed out");
        return call.run();
    }

    private static void assertOutbox(String uid, String type, long offeringId,
                                     String deadline) throws SQLException {
        String prefix = "SELECT COUNT(*) FROM course_event_outbox WHERE uid='" + uid
                + "' AND event_type='" + type + "' AND offering_id=" + offeringId
                + " AND academic_year=2026 AND semester=2"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.occurredAt'))='" + NOW + "'";
        if (deadline != null) {
            prefix += " AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.expiresAt'))='" + deadline + "'";
        }
        require(count(prefix) >= 1, "outbox payload must contain authenticated UID, term and UTC times");
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
                statement.executeUpdate("DELETE FROM student_academic_profile"
                        + " WHERE uid NOT IN ('student-alpha','student-beta')");
                statement.executeUpdate("DELETE FROM tbl_user"
                        + " WHERE UID NOT IN ('student-alpha','student-beta','teacher-alpha','teacher-beta')");
                statement.executeUpdate("UPDATE student_academic_profile SET status='ACTIVE'"
                        + " WHERE uid IN ('student-alpha','student-beta')");
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
                        + " WHEN 2004 THEN 1 ELSE 0 END,status=2"
                        + " WHERE offering_id IN (2001,2002,2003,2004)");
                statement.executeUpdate("INSERT INTO enrollment"
                        + "(enrollment_id,offering_id,course_id,academic_year,semester,uid,status,select_time) VALUES"
                        + "(6001,2001,1001,2026,2,'student-alpha',2,'2026-09-01 00:00:00.100001'),"
                        + "(6002,2004,1002,2026,2,'student-beta',2,'2026-09-01 00:00:00.100002')");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void addStudent(String uid) throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES('"
                + uid + "','Waitlist Test','x','x',2,'Engineering','Computer Science')");
        execute("INSERT INTO student_academic_profile(uid,major_id,cohort_year,status) VALUES('"
                + uid + "',10,2026,'ACTIVE')");
    }

    private static void requireTestDatabase() throws SQLException {
        require("virtual_campus_course_test".equals(text("SELECT DATABASE()")),
                "Refusing waitlist test outside virtual_campus_course_test");
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); Statement statement = connection.createStatement();
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
        return String.format("30000000-0000-0000-0000-%012d", value);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        CourseMutationResultDTO run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
