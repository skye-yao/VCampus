package service;

import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
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

public final class AdminOfferingMySqlTest {
    private static final String ADMIN = "admin-test";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC);

    private AdminOfferingMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('admin-test','Course Test Admin','x','x',0,'Admin','Admin')");
        execute("INSERT INTO course(course_code,course_name,credit,credit_hours,course_type,status)"
                + " VALUES('CS998','Archived Course',1.00,16,3,'ARCHIVED')");
        try {
            AdminOfferingService offerings = new AdminOfferingService(CLOCK);
            String offeringId = verifyCreateAndReplay(offerings);
            verifyUpdateRules(offerings, offeringId);
            verifyCancelAndDelete(offerings, offeringId);
        } finally {
            cleanup();
        }
        System.out.println("Admin offering MySQL test passed.");
    }

    private static String verifyCreateAndReplay(AdminOfferingService offerings) throws Exception {
        OfferingEditorRequestDTO request = request(op(1), null, 0, "1001", "CS999-T-A",
                2027, 3, 40, "teacher-alpha", "teacher-beta", 1);
        AdminOperationResultDTO<AdminOfferingDTO> created = offerings.create(ADMIN, request);
        AdminOfferingDTO offering = created.getEntity();
        require("OK".equals(created.getOutcomeCode()) && created.getConflicts().isEmpty(),
                "create outcome");
        require(offering.getVersion() == 1 && "NOT_OPEN".equals(offering.getStatus()),
                "new offering is draft v1");
        require("CS999-T-A".equals(offering.getOfferingCode()) && "1001".equals(offering.getCourseId())
                && offering.getAcademicYear() == 2027 && offering.getSemester() == 3
                && offering.getCapacity() == 40 && offering.getEnrolledCount() == 0,
                "create persists offering fields");
        require("teacher-alpha".equals(offering.getTeacherUid())
                && "Course Test Teacher A".equals(offering.getTeacherName())
                && "teacher-beta".equals(offering.getAssistantUid())
                && "Course Test Teacher B".equals(offering.getAssistantName()),
                "staff names resolved");
        require("UNSCHEDULED".equals(offering.getScheduleStatus()), "new offering unscheduled");
        require(ADMIN.equals(text("SELECT created_by FROM course_offering WHERE offering_id="
                + offering.getOfferingId())), "created_by recorded");
        require(count("SELECT COUNT(*) FROM course_offering_teacher WHERE offering_id="
                + offering.getOfferingId()) == 2, "teacher and assistant rows");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                + ADMIN + "' AND operation_id='" + op(1) + "' AND target_type='OFFERING'"
                + " AND target_id='" + offering.getOfferingId() + "'") == 1,
                "create writes one operation row");

        AdminOperationResultDTO<AdminOfferingDTO> replay = offerings.create(ADMIN, request);
        require(offering.getOfferingId().equals(replay.getEntity().getOfferingId())
                && created.getMessage().equals(replay.getMessage()),
                "replay returns the same offering");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_code='CS999-T-A'") == 1,
                "replay of create does not create a second offering");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                + op(1) + "'") == 1, "replay keeps one operation row");

        String archivedCourseId = text("SELECT course_id FROM course WHERE course_code='CS998'");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.create(ADMIN, request(op(2), null, 0, archivedCourseId,
                        "CS999-T-X", 2027, 3, 10, "teacher-alpha", null, 1)),
                "archived course rejects new offerings");
        expect(AdminOfferingService.NotFoundException.class,
                () -> offerings.create(ADMIN, request(op(3), null, 0, "999999999",
                        "CS999-T-X", 2027, 3, 10, "teacher-alpha", null, 1)),
                "missing course is not found");
        expect(IllegalArgumentException.class,
                () -> offerings.create(ADMIN, request(op(4), null, 0, "1001",
                        "CS999-T-X", 2027, 3, 10, "student-alpha", null, 1)),
                "teacher must have teacher role");
        expect(IllegalArgumentException.class,
                () -> offerings.create(ADMIN, request(op(5), null, 0, "1001",
                        "CS999-T-X", 2027, 3, 10, "teacher-alpha", "teacher-alpha", 1)),
                "assistant must differ from teacher");
        expect(IllegalArgumentException.class,
                () -> offerings.create(ADMIN, request(op(6), null, 0, "1001",
                        "CS999-T-X", 2027, 3, 10, "teacher-alpha", null, 3)),
                "create accepts only status 1 or 2");
        expect(IllegalArgumentException.class,
                () -> offerings.create(ADMIN, request(op(7), null, 0, "1001",
                        "CS999-T-X", 2027, 4, 10, "teacher-alpha", null, 1)),
                "semester must be 1..3");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.create(ADMIN, request(op(8), null, 0, "1001",
                        "CS999-T-A", 2027, 3, 10, "teacher-alpha", null, 1)),
                "duplicate offering code conflicts");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_code LIKE 'CS999-T-%'") == 1,
                "rejected creates insert nothing");

        List<AdminOfferingDTO> listed = offerings.list("1001");
        require(listed.stream().anyMatch(item -> item.getOfferingId().equals(offering.getOfferingId())),
                "list includes new offering");
        require(listed.stream().filter(item -> item.getOfferingId().equals(offering.getOfferingId()))
                        .count() == 1, "list returns each offering once");
        int seededEnrolled = count("SELECT enrolled_count FROM course_offering WHERE offering_id=2001");
        require(listed.stream().anyMatch(item -> "2001".equals(item.getOfferingId())
                        && "SCHEDULED".equals(item.getScheduleStatus())
                        && "OPEN".equals(item.getStatus())
                        && "teacher-alpha".equals(item.getTeacherUid())
                        && item.getEnrolledCount() == seededEnrolled),
                "list maps seeded offering");
        return offering.getOfferingId();
    }

    private static void verifyUpdateRules(AdminOfferingService offerings, String offeringId)
            throws Exception {
        AdminOperationResultDTO<AdminOfferingDTO> updated = offerings.update(ADMIN,
                request(op(10), offeringId, 1, "1001", "CS999-T-A", 2027, 3, 50,
                        "teacher-beta", null, 2));
        AdminOfferingDTO offering = updated.getEntity();
        require(offering.getVersion() == 2 && "OPEN".equals(offering.getStatus())
                && offering.getCapacity() == 50, "update persists fields and bumps version");
        require("teacher-beta".equals(offering.getTeacherUid()) && offering.getAssistantUid() == null,
                "update replaces staff");
        require(count("SELECT COUNT(*) FROM course_offering_teacher WHERE offering_id="
                + offeringId) == 1, "assistant row removed");

        AdminOfferingService.ConflictException stale = expect(
                AdminOfferingService.ConflictException.class,
                () -> offerings.update(ADMIN, request(op(11), offeringId, 1, "1001",
                        "CS999-T-A", 2027, 3, 50, "teacher-beta", null, 2)),
                "stale expectedVersion conflicts");
        require(stale.getLatest() != null && stale.getLatest().getVersion() == 2,
                "conflict carries latest offering");

        int seededVersion = count("SELECT version FROM course_offering WHERE offering_id=2001");
        int seededYear = count("SELECT academic_year FROM course_offering WHERE offering_id=2001");
        int seededCapacity = count("SELECT capacity FROM course_offering WHERE offering_id=2001");
        expect(IllegalArgumentException.class,
                () -> offerings.update(ADMIN, request(op(12), "2001", seededVersion, "1001",
                        "CS101-2026-2-A", seededYear + 1, 2, seededCapacity, "teacher-alpha", null, 2)),
                "academic year immutable after enrollment");
        expect(IllegalArgumentException.class,
                () -> offerings.update(ADMIN, request(op(13), "2001", seededVersion, "1002",
                        "CS101-2026-2-A", seededYear, 2, seededCapacity, "teacher-alpha", null, 2)),
                "course immutable after enrollment");
        expect(IllegalArgumentException.class,
                () -> offerings.update(ADMIN, request(op(14), "2001", seededVersion, "1001",
                        "CS101-2026-2-A", seededYear, 2, 0, "teacher-alpha", null, 2)),
                "capacity below enrolled rejected");
        require(count("SELECT version FROM course_offering WHERE offering_id=2001") == seededVersion
                && count("SELECT academic_year FROM course_offering WHERE offering_id=2001") == seededYear
                && count("SELECT capacity FROM course_offering WHERE offering_id=2001") == seededCapacity,
                "rejected updates leave seeded offering untouched");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id IN ('"
                + op(11) + "','" + op(12) + "','" + op(13) + "','" + op(14) + "')") == 0,
                "failed mutations are not logged");
        expect(AdminOfferingService.NotFoundException.class,
                () -> offerings.update(ADMIN, request(op(15), "999999999", 1, "1001",
                        "CS999-T-X", 2027, 3, 10, "teacher-alpha", null, 1)),
                "missing offering is not found");
    }

    private static void verifyCancelAndDelete(AdminOfferingService offerings, String offeringId)
            throws Exception {
        AdminOperationResultDTO<AdminOfferingDTO> cancelled =
                offerings.cancel(ADMIN, offeringId, 2, op(20));
        require("CANCELLED".equals(cancelled.getEntity().getStatus())
                && cancelled.getEntity().getVersion() == 3, "cancel sets CANCELLED v3");
        require(count("SELECT status FROM course_offering WHERE offering_id=" + offeringId) == 4
                && ADMIN.equals(text("SELECT cancelled_by FROM course_offering WHERE offering_id="
                        + offeringId))
                && text("SELECT cancelled_at FROM course_offering WHERE offering_id="
                        + offeringId) != null,
                "cancel audit columns");
        AdminOperationResultDTO<AdminOfferingDTO> cancelReplay =
                offerings.cancel(ADMIN, offeringId, 2, op(20));
        require(cancelReplay.getEntity().getVersion() == 3
                && count("SELECT version FROM course_offering WHERE offering_id=" + offeringId) == 3,
                "cancel replay does not bump version");

        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.update(ADMIN, request(op(21), offeringId, 3, "1001",
                        "CS999-T-A", 2027, 3, 50, "teacher-beta", null, 2)),
                "cancelled offering rejects update");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.cancel(ADMIN, offeringId, 3, op(22)),
                "cancelled offering rejects cancel");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.deleteDraft(ADMIN, offeringId, 3, op(23)),
                "cancelled offering rejects delete");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_id=" + offeringId) == 1,
                "rejected delete keeps the row");

        execute("INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,status) VALUES('CS999-T-B',1001,2027,3,10,1)");
        String rawDraftId = text("SELECT offering_id FROM course_offering WHERE offering_code='CS999-T-B'");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES("
                + rawDraftId + ",'teacher-alpha',0)");
        execute("INSERT INTO course_schedule_arrangement(plan_id,offering_id,status,version)"
                + " VALUES(4001," + rawDraftId + ",'ACTIVE',1)");
        String arrangementId = text("SELECT arrangement_id FROM course_schedule_arrangement"
                + " WHERE offering_id=" + rawDraftId);
        execute("INSERT INTO course_schedule_rule(plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(4001," + rawDraftId + ","
                + arrangementId + ",3,1,2,'ACTIVE')");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.deleteDraft(ADMIN, rawDraftId, 1, op(24)),
                "scheduled draft rejects delete");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_id=" + rawDraftId) == 1,
                "scheduled draft still exists");
        execute("DELETE FROM course_schedule_rule WHERE course_offering_id=" + rawDraftId);
        execute("DELETE FROM course_schedule_arrangement WHERE offering_id=" + rawDraftId);
        // Legacy data may carry two role-0 rows; list must still return the offering once.
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES("
                + rawDraftId + ",'teacher-beta',0)");
        require(offerings.list("1001").stream()
                        .filter(item -> rawDraftId.equals(item.getOfferingId())).count() == 1,
                "two role-0 teachers do not duplicate the offering in list");
        AdminOperationResultDTO<Void> rawDeleted = offerings.deleteDraft(ADMIN, rawDraftId, 1, op(25));
        require("OK".equals(rawDeleted.getOutcomeCode()) && rawDeleted.getEntity() == null
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id="
                                + rawDraftId) == 0,
                "unscheduled raw draft delete succeeds");

        // Draft created and updated THROUGH the service: its own createOffering/updateOffering
        // log rows must not block deleteDraft (amended overlay ruling 10).
        AdminOfferingDTO draft = offerings.create(ADMIN, request(op(30), null, 0, "1001",
                "CS999-T-C", 2027, 3, 10, "teacher-alpha", "teacher-beta", 1)).getEntity();
        String draftId = draft.getOfferingId();
        draft = offerings.update(ADMIN, request(op(31), draftId, 1, "1001", "CS999-T-C",
                2027, 3, 12, "teacher-alpha", "teacher-beta", 1)).getEntity();
        require(draft.getVersion() == 2 && "NOT_OPEN".equals(draft.getStatus()),
                "draft updated to v2 and still NOT_OPEN");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE target_type='OFFERING'"
                + " AND target_id='" + draftId + "' AND action IN ('createOffering','updateOffering')")
                == 2, "draft has its own lifecycle log rows");

        AdminOfferingService.ConflictException staleDelete = expect(
                AdminOfferingService.ConflictException.class,
                () -> offerings.deleteDraft(ADMIN, draftId, 7, op(32)),
                "stale version rejects delete");
        require(staleDelete.getLatest() != null && staleDelete.getLatest().getVersion() == 2
                        && "teacher-alpha".equals(staleDelete.getLatest().getTeacherUid())
                        && "teacher-beta".equals(staleDelete.getLatest().getAssistantUid()),
                "stale delete conflict carries latest offering with staff intact");
        require(count("SELECT COUNT(*) FROM course_offering_teacher WHERE offering_id="
                + draftId) == 2, "stale delete leaves staff rows");

        execute("INSERT INTO admin_course_operation_log(admin_uid,operation_id,action,target_type,"
                + "target_id,request_digest,request_json,result_code,response_json,completed_at)"
                + " VALUES('" + ADMIN + "','" + op(90) + "','addStudentToOffering','OFFERING','"
                + draftId + "','foreign','{}','OK','{}',NOW(6))");
        expect(AdminOfferingService.ConflictException.class,
                () -> offerings.deleteDraft(ADMIN, draftId, 2, op(33)),
                "draft referenced by a foreign admin action rejects delete");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_id=" + draftId) == 1,
                "foreign-referenced draft still exists");
        execute("DELETE FROM admin_course_operation_log WHERE operation_id='" + op(90) + "'");

        AdminOperationResultDTO<Void> deleted = offerings.deleteDraft(ADMIN, draftId, 2, op(34));
        require("OK".equals(deleted.getOutcomeCode()) && deleted.getEntity() == null,
                "API-created empty draft delete succeeds with null entity");
        require(count("SELECT COUNT(*) FROM course_offering WHERE offering_id=" + draftId) == 0
                && count("SELECT COUNT(*) FROM course_offering_teacher WHERE offering_id="
                        + draftId) == 0,
                "delete removes offering and staff rows");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                + op(34) + "' AND action='deleteDraftOffering' AND target_type='OFFERING'"
                + " AND target_id='" + draftId + "' AND result_code='OK'") == 1,
                "delete is logged once");
        AdminOperationResultDTO<Void> deleteReplay = offerings.deleteDraft(ADMIN, draftId, 2, op(34));
        require("OK".equals(deleteReplay.getOutcomeCode()) && deleteReplay.getEntity() == null,
                "delete replay returns the stored result");
        expect(AdminOfferingService.NotFoundException.class,
                () -> offerings.deleteDraft(ADMIN, draftId, 2, op(35)),
                "deleting a missing offering is not found");
    }

    private static OfferingEditorRequestDTO request(String operationId, String offeringId,
                                                    int expectedVersion, String courseId,
                                                    String code, int academicYear, int semester,
                                                    int capacity, String teacherUid,
                                                    String assistantUid, int status) {
        return new OfferingEditorRequestDTO(operationId, offeringId, expectedVersion, courseId,
                code, academicYear, semester, capacity, teacherUid, assistantUid, status);
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM course_schedule_rule WHERE course_offering_id IN"
                + " (SELECT offering_id FROM course_offering WHERE offering_code LIKE 'CS999-T-%')");
        execute("DELETE FROM course_schedule_arrangement WHERE offering_id IN"
                + " (SELECT offering_id FROM course_offering WHERE offering_code LIKE 'CS999-T-%')");
        execute("DELETE FROM course_offering_teacher WHERE offering_id IN"
                + " (SELECT offering_id FROM course_offering WHERE offering_code LIKE 'CS999-T-%')");
        execute("DELETE FROM course_offering WHERE offering_code LIKE 'CS999-T-%'");
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM course WHERE course_code='CS998'");
        execute("DELETE FROM tbl_user WHERE UID='" + ADMIN + "'");
    }

    private static void requireTestDatabase() throws SQLException {
        require("virtual_campus_course_test".equals(text("SELECT DATABASE()")),
                "Refusing admin offering test outside virtual_campus_course_test");
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
        return String.format("40000000-0000-0000-0000-%012d", value);
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
