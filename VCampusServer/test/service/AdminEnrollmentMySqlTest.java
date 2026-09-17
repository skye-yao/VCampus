package service;

import com.google.gson.Gson;
import dao.AdminCourseOperationDAO;
import dao.AdminEnrollmentDAO;
import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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

/** Real MySQL scenarios. Fixtures are private to this test and never reset shared seed rows. */
public final class AdminEnrollmentMySqlTest {
    private static final String ADMIN = "ae2-admin";
    private static final String ADMIN_B = "ae2-admin-b";
    private static final String TEACHER = "ae2-teacher";
    private static final String A = "ae2-a";
    private static final String B = "ae2-b";
    private static final String C = "ae2-c";
    private static final String D = "213242798";
    private static final long TARGET = 820011;
    private static final long SAME_COURSE = 820012;
    private static final long OTHER = 820013;
    private static final long PREREQUISITE = 820014;
    private static final long SECOND_PREREQUISITE = 820015;
    private static final long CALENDAR = 820021;
    private static final long PLAN = 820031;
    private static final long OLD_PLAN = 820032;
    private static final Instant NOW = Instant.parse("2028-09-04T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Gson GSON = new Gson();

    private AdminEnrollmentMySqlTest() { }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        try {
            run("student search and pagination", AdminEnrollmentMySqlTest::verifySearchAndPagination);
            run("add, exact replay and historical restoration", AdminEnrollmentMySqlTest::verifyAddReplayAndRestore);
            run("capacity preview, force audit and recheck", AdminEnrollmentMySqlTest::verifyCapacityForceAndRecheck);
            run("same-course and invalid-resource blocking", AdminEnrollmentMySqlTest::verifyBlockingRisks);
            run("exact prerequisite tokens and published passing grades", AdminEnrollmentMySqlTest::verifyPrerequisites);
            run("effective adjusted schedule and half-open intervals", AdminEnrollmentMySqlTest::verifyEffectiveSchedule);
            run("current published plan and legacy fallback", AdminEnrollmentMySqlTest::verifyPublishedPlanPriority);
            run("offered reservations and waitlist settlement", AdminEnrollmentMySqlTest::verifyWaitlistReservations);
            run("grade workflow removal restrictions", AdminEnrollmentMySqlTest::verifyGradeLocks);
            run("removal commit, count and one trigger", AdminEnrollmentMySqlTest::verifyRemovalAndTrigger);
            run("real default waitlist trigger", AdminEnrollmentMySqlTest::verifyDefaultWaitlistTrigger);
            run("DAO and audit failure rollback", AdminEnrollmentMySqlTest::verifyRollback);
            run("same-operation concurrency", AdminEnrollmentMySqlTest::verifyOperationConcurrency);
            run("capacity and same-course concurrency", AdminEnrollmentMySqlTest::verifyEnrollmentConcurrency);
            System.out.println("Admin enrollment MySQL test passed (14 scenario groups).");
        } finally {
            cleanup();
        }
    }

    private static void verifySearchAndPagination() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        AdminEnrollmentPageDTO<StudentSearchResultDTO> exact = service.searchStudents(A, 1, 2);
        require(exact.getTotalCount() == 1 && exact.getItems().get(0).getUid().equals(A),
                "exact UID must identify one student");
        StudentSearchResultDTO student = exact.getItems().get(0);
        require(student.getName().equals("Enrollment Student A")
                        && student.getMajor().equals("Enrollment Test Major")
                        && student.getCohortYear() == 2026
                        && student.getAcademicStatus().equals("ACTIVE"),
                "search must map the authoritative academic profile");
        AdminEnrollmentPageDTO<StudentSearchResultDTO> first =
                service.searchStudents("Enrollment Student", 1, 2);
        AdminEnrollmentPageDTO<StudentSearchResultDTO> second =
                service.searchStudents("Enrollment Student", 2, 2);
        require(first.getTotalCount() == 3 && first.getPageNumber() == 1
                        && first.getPageSize() == 2 && first.getItems().size() == 2,
                "search pagination must carry total and requested metadata");
        require(first.getItems().get(0).getUid().equals(A)
                        && first.getItems().get(1).getUid().equals(B)
                        && second.getTotalCount() == 3 && second.getItems().size() == 1
                        && second.getItems().get(0).getUid().equals(C),
                "search must be stable, distinct and exclude non-students/inactive profiles");
        require(service.searchStudents("Enrollment Student A", 1, 10).getTotalCount() == 1,
                "full-name search must find its student");
        require(service.searchStudents(D.substring(0, 5), 1, 10).getTotalCount() == 1
                        && service.searchStudents(D.substring(0, 5), 1, 10).getItems().get(0)
                                .getUid().equals(D),
                "a student-ID prefix must find its student, not just an exact ID");
        require(service.searchStudents(D, 1, 10).getTotalCount() == 1,
                "the full student ID must keep working");
        require(service.searchStudents(D + "9", 1, 10).getTotalCount() == 0,
                "a non-matching ID must stay empty");
        require(service.searchStudents("Enrollment Student", 3, 2).getItems().isEmpty(),
                "a page beyond the end must be empty");
        require(service.searchStudents("' OR 1=1 --", 1, 100).getTotalCount() == 0,
                "search input must remain data");
        expect(IllegalArgumentException.class, () -> service.searchStudents("", 0, 10));
        expect(IllegalArgumentException.class, () -> service.searchStudents("", 1, 101));
        expect(AdminEnrollmentService.NotFoundException.class,
                () -> service.listOfferingStudents("999999999", "", 1, 10));
        require(service.listOfferingStudents(id(TARGET), "", 1, 10).getTotalCount() == 0,
                "empty offering must return an empty page");
    }

    private static void verifyAddReplayAndRestore() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        AdminEnrollmentRequestDTO request = request(1, TARGET, A, false, null);
        AdminOperationResultDTO<OfferingStudentDTO> first = service.addStudentToOffering(ADMIN, request);
        require(first.getOutcomeCode().equals("OK") && first.getEntity().getUid().equals(A)
                        && first.getEntity().getEnrollmentStatus().equals("ENROLLED"),
                "successful add must return the current student entity");
        String enrollmentId = first.getEntity().getEnrollmentId();
        require(GSON.toJson(first).equals(GSON.toJson(service.addStudentToOffering(ADMIN, request))),
                "an identical operation must replay the exact stored response");
        service.addStudentToOffering(ADMIN, request(2, TARGET, A, false, null));
        require(countOf(TARGET) == 1 && enrollmentRows(A, TARGET) == 1,
                "repeat add with a new operation must not increment or duplicate enrollment");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?",
                ADMIN) == 2, "replay must not add an audit row");
        execute("UPDATE enrollment SET status=3,drop_time='2028-09-03 00:00:00',"
                + "select_time='2028-09-01 00:00:00' WHERE enrollment_id=?", enrollmentId);
        execute("UPDATE course_offering SET enrolled_count=0 WHERE offering_id=?", TARGET);
        OfferingStudentDTO restored = service.addStudentToOffering(ADMIN,
                request(3, TARGET, A, false, null)).getEntity();
        require(restored.getEnrollmentId().equals(enrollmentId) && enrollmentRows(A, TARGET) == 1
                        && countOf(TARGET) == 1 && status(A, TARGET) == 2,
                "restoration must reuse the historical enrollment ID and increment once");
        require(text("SELECT drop_time FROM enrollment WHERE enrollment_id=?", enrollmentId) == null
                        && instant("SELECT select_time FROM enrollment WHERE enrollment_id=?",
                                enrollmentId).equals(NOW),
                "restoration must clear drop_time and use the injected UTC selection time");
        AdminEnrollmentService.ConflictException mismatch = expect(
                AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(1, TARGET, B, false, null)));
        require(mismatch.getConflicts() != null && enrollmentRows(B, TARGET) == 0
                        && countOf(TARGET) == 1, "changed digest must conflict without mutation");
        expect(IllegalArgumentException.class, () -> service.addStudentToOffering(ADMIN,
                new AdminEnrollmentRequestDTO("not-uuid", id(TARGET), B, false, null)));
    }

    private static void verifyCapacityForceAndRecheck() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=?", TARGET);
        service.addStudentToOffering(ADMIN, request(10, TARGET, A, false, null));
        AdminEnrollmentPreviewDTO preview = service.previewAdminEnrollment(id(TARGET), B);
        requireRisk(preview.getRisks(), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        long audits = count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?", ADMIN);
        require(countOf(TARGET) == 1 && enrollmentRows(B, TARGET) == 0,
                "preview must not write enrollment or count");
        AdminEnrollmentService.ConflictException full = expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(11, TARGET, B, false, null)));
        requireRisk(full.getConflicts(), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        expect(IllegalArgumentException.class,
                () -> service.addStudentToOffering(ADMIN, request(12, TARGET, B, true, "  ")));
        expect(IllegalArgumentException.class,
                () -> service.addStudentToOffering(ADMIN, request(13, TARGET, B, true, "x".repeat(501))));
        AdminOperationResultDTO<OfferingStudentDTO> forced = service.addStudentToOffering(ADMIN,
                request(14, TARGET, B, true, "  approved extra seat  "));
        require(countOf(TARGET) == 2 && status(B, TARGET) == 2,
                "force must allow enrolled_count above capacity");
        requireRisk(forced.getConflicts(), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        require(count("SELECT forced FROM admin_course_operation_log WHERE admin_uid=? AND operation_id=?",
                        ADMIN, op(14)) == 1
                        && "approved extra seat".equals(text("SELECT override_reason"
                                + " FROM admin_course_operation_log WHERE admin_uid=? AND operation_id=?",
                                ADMIN, op(14)))
                        && text("SELECT conflict_snapshot_json FROM admin_course_operation_log"
                                + " WHERE admin_uid=? AND operation_id=?", ADMIN, op(14)).contains("CAPACITY"),
                "forced audit must retain trimmed reason and actual risk snapshot");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?", ADMIN)
                        == audits + 1, "rejected requests and preview must not create success audit rows");
        service.removeStudentFromOffering(ADMIN, request(15, TARGET, B, false, null));
        require(countOf(TARGET) == 1, "removal must work when reducing an over-capacity count");
        require(service.previewAdminEnrollment(id(OTHER), C).getRisks().isEmpty(),
                "initial preview must be clear");
        execute("UPDATE course_offering SET capacity=0 WHERE offering_id=?", OTHER);
        AdminEnrollmentService.ConflictException changed = expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(16, OTHER, C, false, null)));
        requireRisk(changed.getConflicts(), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        require(enrollmentRows(C, OTHER) == 0, "mutation must re-evaluate after preview");
    }

    private static void verifyBlockingRisks() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        service.addStudentToOffering(ADMIN, request(20, TARGET, A, false, null));
        List<ScheduleConflictDTO> previewRisks = service.previewAdminEnrollment(id(SAME_COURSE), A).getRisks();
        requireRisk(previewRisks, "SAME_COURSE_ACTIVE", ScheduleConflictSeverityDTO.BLOCKING);
        require(previewRisks.stream().anyMatch(r -> r.getType().equals("SAME_COURSE_ACTIVE")
                        && id(TARGET).equals(r.getRelatedOfferingId())),
                "same-course preview must identify the already-enrolled offering, not the requested offering");
        AdminEnrollmentService.ConflictException duplicate = expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(21, SAME_COURSE, A, true, "requested")));
        requireRisk(duplicate.getConflicts(), "SAME_COURSE_ACTIVE", ScheduleConflictSeverityDTO.BLOCKING);
        require(duplicate.getConflicts().stream().anyMatch(r -> r.getType().equals("SAME_COURSE_ACTIVE")
                        && id(TARGET).equals(r.getRelatedOfferingId())),
                "same-course mutation must identify the already-enrolled offering too");
        require(countOf(SAME_COURSE) == 0, "force cannot bypass same-course uniqueness");
        for (String invalid : List.of("ae2-suspended", TEACHER, "ae2-no-profile")) {
            requireRisk(service.previewAdminEnrollment(id(TARGET), invalid).getRisks(),
                    "INVALID_STUDENT_STATUS", ScheduleConflictSeverityDTO.BLOCKING);
            AdminEnrollmentService.ConflictException blocked = expect(AdminEnrollmentService.ConflictException.class,
                    () -> service.addStudentToOffering(ADMIN, request(22, TARGET, invalid, true, "requested")));
            requireRisk(blocked.getConflicts(), "INVALID_STUDENT_STATUS", ScheduleConflictSeverityDTO.BLOCKING);
        }
        execute("UPDATE course_offering SET status=4 WHERE offering_id=?", OTHER);
        requireRisk(service.previewAdminEnrollment(id(OTHER), B).getRisks(),
                "CANCELLED_OFFERING", ScheduleConflictSeverityDTO.BLOCKING);
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(23, OTHER, B, true, "requested")));
        execute("UPDATE course SET status='ARCHIVED' WHERE course_id=820001");
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(24, TARGET, B, true, "requested")));
        expect(AdminEnrollmentService.NotFoundException.class,
                () -> service.previewAdminEnrollment("999999999", A));
    }

    private static void verifyPrerequisites() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        for (String noRequirement : List.of("", "无", "NONE", "无先修要求")) {
            execute("UPDATE course SET prerequisites=? WHERE course_id=820001", noRequirement);
            require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "PREREQUISITE"),
                    "explicitly absent prerequisites must not warn");
        }
        execute("UPDATE course SET prerequisites='AE2-PRE;\nSecond Prerequisite' WHERE course_id=820001");
        requireRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(),
                "PREREQUISITE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(30, TARGET, A, false, null)));
        long firstGrade = enroll(A, PREREQUISITE);
        long secondGrade = enroll(A, SECOND_PREREQUISITE);
        execute("INSERT INTO grade(enrollment_id,score,is_published,publish_time) VALUES(?,60,1,?),"
                + "(?,100,0,NULL)", firstGrade, timestamp(NOW), secondGrade);
        require(hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "PREREQUISITE"),
                "one passing requirement cannot satisfy another unpublished requirement");
        execute("UPDATE grade SET is_published=1,publish_time=?,score=59.99 WHERE enrollment_id=?",
                timestamp(NOW), secondGrade);
        require(hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "PREREQUISITE"),
                "published grade below 60 must not pass");
        execute("UPDATE grade SET score=60 WHERE enrollment_id=?", secondGrade);
        require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "PREREQUISITE"),
                "every exact token with a published score >= 60 must satisfy prerequisites");
        execute("UPDATE course SET prerequisites='AE2-PRE-UNKNOWN' WHERE course_id=820001");
        requireRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(),
                "PREREQUISITE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        execute("UPDATE course SET prerequisites='建议具备一定数学基础' WHERE course_id=820001");
        requireRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(),
                "PREREQUISITE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        service.addStudentToOffering(ADMIN, request(31, TARGET, A, true, "manual prerequisite review"));
        require(status(A, TARGET) == 2, "unknown free text must remain explicitly overridable");
    }

    private static void verifyEffectiveSchedule() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        enroll(A, OTHER);
        scheduled(820101, TARGET, PLAN, 1, "2028-09-04 00:00:00", "2028-09-04 01:00:00");
        scheduled(820102, OTHER, PLAN, 1, "2028-09-04 00:30:00", "2028-09-04 01:30:00");
        List<ScheduleConflictDTO> risks = service.previewAdminEnrollment(id(TARGET), A).getRisks();
        requireRisk(risks, "STUDENT_SCHEDULE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        ScheduleConflictDTO conflict = risks.stream().filter(r -> r.getType().equals("STUDENT_SCHEDULE"))
                .findFirst().orElseThrow();
        require(conflict.getRelatedOfferingId().equals(id(OTHER)) && conflict.getWeek() == 1,
                "schedule warning must identify the enrolled class and actual teaching week");
        AdminEnrollmentService.ConflictException blocked = expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(40, TARGET, A, false, null)));
        requireRisk(blocked.getConflicts(), "STUDENT_SCHEDULE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        adjustment(820102, OTHER, "2028-09-04 01:00:00", "2028-09-04 02:00:00");
        require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "STUDENT_SCHEDULE"),
                "replaced original must stop occupying time and exact boundary touch must be allowed");
        execute("UPDATE course_schedule_adjustment SET start_at_utc='2028-09-04 00:45:00'"
                + " WHERE original_occurrence_id=820102");
        require(hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "STUDENT_SCHEDULE"),
                "an ACTIVE replacement moved into the target must occupy its new time");
        adjustment(820101, TARGET, "2028-09-11 00:00:00", "2028-09-11 01:00:00");
        require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "STUDENT_SCHEDULE"),
                "adjustment on the target must be applied too; different actual dates do not overlap");
        execute("UPDATE course_schedule_adjustment SET status='CANCELLED' WHERE original_occurrence_id=820101");
        require(hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "STUDENT_SCHEDULE"),
                "cancelling target adjustment must restore its original occupied time");
        service.addStudentToOffering(ADMIN, request(41, TARGET, A, true, "schedule agreed"));
        require(status(A, TARGET) == 2 && status(A, OTHER) == 2,
                "forced schedule warning must not silently drop the student's other course");
    }

    private static void verifyPublishedPlanPriority() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        enroll(A, OTHER);
        scheduled(820111, TARGET, OLD_PLAN, 1, "2028-09-04 00:00:00", "2028-09-04 01:00:00");
        scheduled(820112, OTHER, OLD_PLAN, 1, "2028-09-04 00:30:00", "2028-09-04 01:30:00");
        scheduled(820113, TARGET, PLAN, 1, "2028-09-04 00:00:00", "2028-09-04 01:00:00");
        scheduled(820114, OTHER, PLAN, 2, "2028-09-11 00:00:00", "2028-09-11 01:00:00");
        require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "STUDENT_SCHEDULE"),
                "calendar current published plan must outrank an older window plan");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=?", CALENDAR);
        requireRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(),
                "STUDENT_SCHEDULE", ScheduleConflictSeverityDTO.OVERRIDABLE);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", PLAN, CALENDAR);
        execute("UPDATE schedule_plan SET status='DRAFT' WHERE id=?", PLAN);
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(45, TARGET, A, false, null)));
        require(enrollmentRows(A, TARGET) == 0,
                "invalid non-null current pointer must not silently fall back to another plan");
    }

    private static void verifyWaitlistReservations() throws Exception {
        AdminEnrollmentService service = service(WaitlistAdvanceTrigger.NO_OP);
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=?", TARGET);
        waitlist(A, TARGET, "OFFERED");
        requireRisk(service.previewAdminEnrollment(id(TARGET), B).getRisks(),
                "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.addStudentToOffering(ADMIN, request(50, TARGET, B, false, null)));
        require(!hasRisk(service.previewAdminEnrollment(id(TARGET), A).getRisks(), "CAPACITY"),
                "the target's own active reservation must not be counted twice");
        service.addStudentToOffering(ADMIN, request(51, TARGET, A, false, null));
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist WHERE uid=? AND offering_id=?",
                        A, TARGET))
                        && text("SELECT expires_at FROM course_waitlist WHERE uid=? AND offering_id=?", A, TARGET) == null
                        && count("SELECT COUNT(*) FROM course_plan_item WHERE uid=? AND offering_id=?", A, TARGET) == 0
                        && countOf(TARGET) == 1, "consumed OFFERED row must settle without losing history");
        waitlist(B, OTHER, "WAITING");
        service.addStudentToOffering(ADMIN, request(52, OTHER, B, false, null));
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist WHERE uid=? AND offering_id=?",
                        B, OTHER)) && count("SELECT COUNT(*) FROM course_plan_item WHERE uid=? AND offering_id=?",
                        B, OTHER) == 0, "administrative enrollment must settle a WAITING row too");
        waitlist(C, SAME_COURSE, "OFFERED");
        execute("UPDATE course_waitlist SET offered_at=?,expires_at=? WHERE uid=? AND offering_id=?",
                timestamp(NOW.minusSeconds(60)), timestamp(NOW), C, SAME_COURSE);
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=?", SAME_COURSE);
        require(!hasRisk(service.previewAdminEnrollment(id(SAME_COURSE), B).getRisks(), "CAPACITY"),
                "expired reservations must not consume capacity");
    }

    private static void verifyGradeLocks() throws Exception {
        AtomicInteger triggers = new AtomicInteger();
        AdminEnrollmentService service = service(id -> triggers.incrementAndGet());
        long aEnrollment = enroll(A, TARGET);
        long bEnrollment = enroll(B, TARGET);
        submission(820201, aEnrollment, "PENDING", 1);
        assertRemovalBlocked(service, A, 60);
        execute("UPDATE grade_submission SET status='APPROVED',reviewed_at=? WHERE submission_id=820201",
                timestamp(NOW));
        assertRemovalBlocked(service, A, 61);
        execute("UPDATE grade_submission SET status='REJECTED' WHERE submission_id=820201");
        execute("INSERT INTO grade(enrollment_id,score,is_published,publish_time) VALUES(?,75,1,?)",
                aEnrollment, timestamp(NOW));
        assertRemovalBlocked(service, A, 62);
        // A PENDING batch exists but contains only B: it must not lock A after its grade is unpublished.
        execute("UPDATE grade SET is_published=0,publish_time=NULL WHERE enrollment_id=?", aEnrollment);
        submission(820202, bEnrollment, "PENDING", 2);
        AdminEnrollmentPageDTO<OfferingStudentDTO> page = service.listOfferingStudents(id(TARGET), "", 1, 1);
        AdminEnrollmentPageDTO<OfferingStudentDTO> second = service.listOfferingStudents(id(TARGET), "", 2, 1);
        require(page.getTotalCount() == 2 && page.getItems().get(0).getUid().equals(A)
                        && page.getItems().get(0).isRemovable()
                        && second.getTotalCount() == 2 && second.getItems().get(0).getUid().equals(B)
                        && !second.getItems().get(0).isRemovable(),
                "student listing must paginate active rows and evaluate grades per enrollment");
        require(service.listOfferingStudents(id(TARGET), "Enrollment Student B", 1, 10)
                        .getTotalCount() == 1, "offering list must support name filtering");
        service.removeStudentFromOffering(ADMIN, request(63, TARGET, A, false, null));
        require(status(A, TARGET) == 3 && countOf(TARGET) == 1 && triggers.get() == 1,
                "rejected submissions and unpublished grade must not block unrelated removal");
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE enrollment_id=?", aEnrollment) == 1,
                "removal must retain grade-submission history");
    }

    private static void assertRemovalBlocked(AdminEnrollmentService service, String uid, int operation)
            throws Exception {
        AdminEnrollmentService.ConflictException blocked = expect(AdminEnrollmentService.ConflictException.class,
                () -> service.removeStudentFromOffering(ADMIN, request(operation, TARGET, uid, true, "requested")));
        requireRisk(blocked.getConflicts(), "GRADE_WORKFLOW_LOCKED", ScheduleConflictSeverityDTO.BLOCKING);
        require(blocked.getEntity() != null && !blocked.getEntity().isRemovable()
                        && blocked.getEntity().getBlockedReason() != null,
                "removal conflict must carry the latest entity and visible reason");
        require(status(uid, TARGET) == 2 && countOf(TARGET) == 2,
                "blocked removal must preserve status and count");
        OfferingStudentDTO listed = service.listOfferingStudents(id(TARGET), uid, 1, 10).getItems().get(0);
        require(!listed.isRemovable() && listed.getBlockedReason() != null,
                "listing must explain the same grade restriction before clicking remove");
    }

    private static void verifyRemovalAndTrigger() throws Exception {
        long enrollment = enroll(A, TARGET);
        AtomicInteger triggers = new AtomicInteger();
        AdminEnrollmentService service = service(offering -> {
            require(offering == TARGET, "trigger must identify the released offering");
            try {
                require(status(A, TARGET) == 3 && countOf(TARGET) == 0
                                && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?"
                                        + " AND operation_id=?", ADMIN, op(70)) == 1,
                        "a separate connection must see all committed changes before trigger");
            } catch (SQLException failure) { throw new AssertionError(failure); }
            triggers.incrementAndGet();
        });
        AdminEnrollmentRequestDTO request = request(70, TARGET, A, false, null);
        AdminOperationResultDTO<OfferingStudentDTO> removed = service.removeStudentFromOffering(ADMIN, request);
        require(removed.getEntity().getEnrollmentId().equals(id(enrollment))
                        && removed.getEntity().getEnrollmentStatus().equals("DROPPED")
                        && enrollmentRows(A, TARGET) == 1
                        && instant("SELECT drop_time FROM enrollment WHERE enrollment_id=?", enrollment).equals(NOW),
                "removal must retain history and record the injected UTC drop time");
        require(GSON.toJson(removed).equals(GSON.toJson(service.removeStudentFromOffering(ADMIN, request)))
                        && triggers.get() == 1, "removal replay must not fire another trigger");
        expect(AdminEnrollmentService.ConflictException.class,
                () -> service.removeStudentFromOffering(ADMIN, request(71, TARGET, A, false, null)));
        require(triggers.get() == 1 && countOf(TARGET) == 0,
                "already dropped removal must not decrement below zero or trigger");
        enroll(B, TARGET);
        AdminOperationResultDTO<OfferingStudentDTO> committed = service(ignored -> {
            throw new IllegalStateException("injected after-commit failure");
        }).removeStudentFromOffering(ADMIN, request(72, TARGET, B, false, null));
        require(committed.getOutcomeCode().equals("OK") && status(B, TARGET) == 3 && countOf(TARGET) == 0,
                "after-commit trigger failure must not turn a committed removal into failure");
        enroll(C, TARGET);
        execute("UPDATE course_offering SET enrolled_count=0 WHERE offering_id=?", TARGET);
        expect(DatabaseException.class,
                () -> service.removeStudentFromOffering(ADMIN, request(73, TARGET, C, false, null)));
        require(status(C, TARGET) == 2 && countOf(TARGET) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=? AND operation_id=?",
                                ADMIN, op(73)) == 0, "count underflow must roll back state and audit");
    }

    private static void verifyDefaultWaitlistTrigger() throws Exception {
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=?", TARGET);
        execute("UPDATE course_selection_window SET schedule_plan_id=? WHERE academic_year=2028 AND semester=2", PLAN);
        enroll(A, TARGET);
        waitlist(B, TARGET, "WAITING");
        new AdminEnrollmentService().removeStudentFromOffering(ADMIN, request(80, TARGET, A, false, null));
        require(status(A, TARGET) == 3 && status(B, TARGET) == 2 && countOf(TARGET) == 1
                        && "ENROLLED".equals(text("SELECT status FROM course_waitlist WHERE uid=? AND offering_id=?",
                                B, TARGET)), "default constructor must wire real after-commit FIFO advancement");
    }

    private static void verifyRollback() throws Exception {
        AtomicInteger triggers = new AtomicInteger();
        AdminCourseOperationDAO failingLog = new AdminCourseOperationDAO() {
            @Override
            public void insert(Connection connection, String adminUid, String operationId, String action,
                               String targetType, String targetId, String digest, Object request,
                               String resultCode, Object response, Instant completedAt,
                               Object conflicts, boolean forced, String overrideReason) throws SQLException {
                super.insert(connection, adminUid, operationId, action, targetType, targetId, digest,
                        request, resultCode, response, completedAt, conflicts, forced, overrideReason);
                throw new SQLException("injected log failure after insert");
            }
        };
        waitlist(A, TARGET, "OFFERED");
        AdminEnrollmentDAO dao = new AdminEnrollmentDAO();
        AdminEnrollmentService service = new AdminEnrollmentService(dao, failingLog,
                new AdminEnrollmentRiskService(dao, new CourseConflictService()), CLOCK,
                ignored -> triggers.incrementAndGet());
        expect(DatabaseException.class,
                () -> service.addStudentToOffering(ADMIN, request(90, TARGET, A, false, null)));
        require(enrollmentRows(A, TARGET) == 0 && countOf(TARGET) == 0
                        && "OFFERED".equals(text("SELECT status FROM course_waitlist WHERE uid=? AND offering_id=?", A, TARGET))
                        && count("SELECT COUNT(*) FROM course_plan_item WHERE uid=? AND offering_id=?", A, TARGET) == 1
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?", ADMIN) == 0,
                "audit failure must roll back enrollment, count, reservation, plan and audit");
        enroll(B, OTHER);
        expect(DatabaseException.class,
                () -> service.removeStudentFromOffering(ADMIN, request(91, OTHER, B, false, null)));
        require(status(B, OTHER) == 2 && countOf(OTHER) == 1 && triggers.get() == 0,
                "failed removal must roll back count and state and never trigger");
        AdminEnrollmentDAO failingCount = new AdminEnrollmentDAO() {
            @Override
            public void changeEnrolledCount(Connection connection, long offeringId, int delta) throws SQLException {
                super.changeEnrolledCount(connection, offeringId, delta);
                throw new SQLException("injected count failure after update");
            }
        };
        AdminEnrollmentService failingService = new AdminEnrollmentService(failingCount,
                new AdminCourseOperationDAO(), new AdminEnrollmentRiskService(failingCount,
                new CourseConflictService()), CLOCK, ignored -> triggers.incrementAndGet());
        expect(DatabaseException.class,
                () -> failingService.removeStudentFromOffering(ADMIN, request(92, OTHER, B, false, null)));
        require(status(B, OTHER) == 2 && countOf(OTHER) == 1 && triggers.get() == 0,
                "DAO failure after count update must roll back earlier enrollment changes");
    }

    private static void verifyOperationConcurrency() throws Exception {
        AdminEnrollmentRequestDTO same = request(100, TARGET, A, false, null);
        List<Object> replay = race(
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN, same),
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN, same));
        require(replay.get(0) instanceof AdminOperationResultDTO<?>
                        && replay.get(1) instanceof AdminOperationResultDTO<?>
                        && GSON.toJson(replay.get(0)).equals(GSON.toJson(replay.get(1)))
                        && countOf(TARGET) == 1 && enrollmentRows(A, TARGET) == 1,
                "concurrent identical operations must return one exact result and one enrollment");
        List<Object> changed = race(
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN,
                        request(101, TARGET, B, false, null)),
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN,
                        request(101, OTHER, C, false, null)));
        require(successes(changed) == 1 && conflicts(changed) == 1,
                "same admin/operation on different students and offerings must yield one CONFLICT, not SQL ERROR");
        require(enrollmentRows(B, TARGET) + enrollmentRows(C, OTHER) == 1
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=? AND operation_id=?",
                                ADMIN, op(101)) == 1, "losing operation scope must commit no partial state");
    }

    private static void verifyEnrollmentConcurrency() throws Exception {
        execute("UPDATE course_offering SET capacity=1 WHERE offering_id=?", TARGET);
        List<Object> capacity = race(
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN,
                        request(110, TARGET, A, false, null)),
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN_B,
                        request(111, TARGET, B, false, null)));
        require(successes(capacity) == 1 && conflicts(capacity) == 1 && countOf(TARGET) == 1,
                "offering lock must prevent two unforced winners for one seat");
        execute("UPDATE course_offering SET capacity=3 WHERE offering_id=?", TARGET);
        List<Object> sameCourse = race(
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN,
                        request(112, TARGET, C, true, "requested")),
                () -> service(WaitlistAdvanceTrigger.NO_OP).addStudentToOffering(ADMIN_B,
                        request(113, SAME_COURSE, C, true, "requested")));
        require(successes(sameCourse) == 1 && conflicts(sameCourse) == 1
                        && count("SELECT COUNT(*) FROM enrollment WHERE uid=? AND course_id=820001 AND status=2", C) == 1,
                "student profile lock must serialize same-course decisions across offerings");
    }

    private static AdminEnrollmentService service(WaitlistAdvanceTrigger trigger) {
        AdminEnrollmentDAO dao = new AdminEnrollmentDAO();
        return new AdminEnrollmentService(dao, new AdminCourseOperationDAO(),
                new AdminEnrollmentRiskService(dao, new CourseConflictService()), CLOCK, trigger);
    }

    private static void run(String name, CheckedRun action) throws Exception {
        cleanup();
        fixtures();
        action.run();
        System.out.println("PASS " + name);
    }

    private static void fixtures() throws SQLException {
        execute("INSERT INTO major(major_id,major_code,major_name,college)"
                + " VALUES(820001,'AE2','Enrollment Test Major','Test College')");
        user(ADMIN, "Enrollment Administrator", 0, false, "ACTIVE");
        user(ADMIN_B, "Enrollment Administrator B", 0, false, "ACTIVE");
        user(TEACHER, "Enrollment Student Teacher", 1, true, "ACTIVE");
        user(A, "Enrollment Student A", 2, true, "ACTIVE");
        user(B, "Enrollment Student B", 2, true, "ACTIVE");
        user(C, "Enrollment Student C", 2, true, "ACTIVE");
        user("ae2-suspended", "Enrollment Student Suspended", 2, true, "SUSPENDED");
        user("ae2-no-profile", "Enrollment Student Missing", 2, false, "ACTIVE");
        user(D, "Delta Student Nine", 2, true, "ACTIVE");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,allow_cross_major,status) VALUES"
                + "(820001,'AE2-TARGET','Enrollment Target',2,32,3,1,'ACTIVE'),"
                + "(820002,'AE2-OTHER','Enrollment Other',2,32,3,1,'ACTIVE'),"
                + "(820003,'AE2-PRE','First Prerequisite',2,32,3,1,'ACTIVE'),"
                + "(820004,'AE2-PRE2','Second Prerequisite',2,32,3,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES"
                + "(820011,'AE2-TARGET-A',820001,2028,2,3,2),"
                + "(820012,'AE2-TARGET-B',820001,2028,2,3,2),"
                + "(820013,'AE2-OTHER-A',820002,2028,2,3,2),"
                + "(820014,'AE2-PRE-A',820003,2027,2,3,2),"
                + "(820015,'AE2-PRE2-A',820004,2027,2,3,2)");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,timezone,"
                + "version,status) VALUES(?,'Enrollment test calendar',2028,2,'2028-09-04','Asia/Shanghai',1,'PUBLISHED')",
                CALENDAR);
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status) VALUES"
                + "(?,'Enrollment current plan',?,2,'PUBLISHED'),(?,'Enrollment old plan',?,1,'PUBLISHED')",
                PLAN, CALENDAR, OLD_PLAN, CALENDAR);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?", PLAN, CALENDAR);
        execute("INSERT INTO course_selection_window(academic_year,semester,schedule_plan_id,plan_open_at,"
                + "plan_close_at,selection_open_at,selection_close_at,drop_deadline)"
                + " VALUES(2028,2,?,'2020-01-01','2020-01-02','2020-01-03','2040-01-01','2040-02-01')", OLD_PLAN);
    }

    private static void user(String uid, String name, int role, boolean profile, String status) throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES(?,?,'x','x',?,'Test','Legacy Major')",
                uid, name, role);
        if (profile) execute("INSERT INTO student_academic_profile(uid,major_id,cohort_year,status) VALUES(?,820001,2026,?)",
                uid, status);
    }

    private static long enroll(String uid, long offeringId) throws SQLException {
        execute("INSERT INTO enrollment(offering_id,course_id,academic_year,semester,uid,status,select_time)"
                + " SELECT offering_id,course_id,academic_year,semester,?,2,? FROM course_offering WHERE offering_id=?",
                uid, timestamp(NOW), offeringId);
        execute("UPDATE course_offering SET enrolled_count=enrolled_count+1 WHERE offering_id=?", offeringId);
        return count("SELECT enrollment_id FROM enrollment WHERE uid=? AND offering_id=?", uid, offeringId);
    }

    private static void waitlist(String uid, long offeringId, String state) throws SQLException {
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES(?,?,'FULL')", uid, offeringId);
        execute("INSERT INTO course_waitlist(uid,offering_id,status,queue_time,offered_at,expires_at) VALUES(?,?,?,?,?,?)",
                uid, offeringId, state, timestamp(NOW.minusSeconds(60)),
                state.equals("OFFERED") ? timestamp(NOW) : null,
                state.equals("OFFERED") ? timestamp(NOW.plusSeconds(300)) : null);
    }

    private static void scheduled(long identity, long offering, long plan, int week, String start, String end)
            throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,teacher_uid,status,version)"
                + " VALUES(?,?,?,?,'ACTIVE',1)", identity, plan, offering, TEACHER);
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,weekday,"
                + "start_period,end_period,status) VALUES(?,?,?,?,1,1,2,'ACTIVE')", identity, plan, offering, identity);
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(?,?)", identity, week);
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,teaching_weekday)"
                + " VALUES(?,?,?,?,?,?,1)", identity, identity, plan, start, end, week);
    }

    private static void adjustment(long original, long offering, String start, String end) throws SQLException {
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,reason,"
                + "status,new_weekday,new_start_period,new_end_period,reviewed_by,reviewed_at)"
                + " VALUES(?,?,?,'enrollment test','APPROVED',1,1,2,?,?)", original, offering, TEACHER, ADMIN, timestamp(NOW));
        execute("INSERT INTO course_schedule_adjustment(request_id,original_occurrence_id,start_at_utc,"
                + "end_at_utc,teacher_uid,status) VALUES(?,?,?,?,?,'ACTIVE')", original, original, start, end, TEACHER);
    }

    private static void submission(long submission, long enrollment, String status, int version) throws SQLException {
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,status,reviewed_at)"
                + " VALUES(?,?,?,?,?,?)", submission, TARGET, version, TEACHER, status,
                status.equals("PENDING") ? null : timestamp(NOW));
        execute("INSERT INTO grade_submission_item(submission_id,enrollment_id,score) VALUES(?,?,75)",
                submission, enrollment);
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM course_event_outbox WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid IN (?,?)", ADMIN, ADMIN_B);
        execute("DELETE FROM course_schedule_adjustment_request WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM grade_submission WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE g FROM grade g JOIN enrollment e ON e.enrollment_id=g.enrollment_id"
                + " WHERE e.offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM course_waitlist WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM course_plan_item WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM course_selection_window WHERE academic_year=2028 AND semester=2 AND schedule_plan_id IN (?,?)",
                PLAN, OLD_PLAN);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=?", CALENDAR);
        execute("DELETE FROM course_schedule_rule WHERE plan_id IN (?,?)", PLAN, OLD_PLAN);
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id IN (?,?)", PLAN, OLD_PLAN);
        execute("DELETE FROM schedule_plan WHERE id IN (?,?)", PLAN, OLD_PLAN);
        execute("DELETE FROM teaching_calendar WHERE id=?", CALENDAR);
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 820011 AND 820015");
        execute("DELETE FROM course WHERE course_id BETWEEN 820001 AND 820004");
        execute("DELETE FROM student_academic_profile WHERE major_id=820001");
        execute("DELETE FROM tbl_user WHERE UID IN (?,?,?,?,?,?,?,?,?)", ADMIN, ADMIN_B, TEACHER,
                A, B, C, D, "ae2-suspended", "ae2-no-profile");
        execute("DELETE FROM major WHERE major_id=820001");
    }

    private static List<Object> race(CheckedCall first, CheckedCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> left = pool.submit(() -> capture(start, first));
            Future<Object> right = pool.submit(() -> capture(start, second));
            start.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(25, TimeUnit.SECONDS), "concurrent test workers must finish before fixture cleanup");
        }
    }

    private static Object capture(CountDownLatch start, CheckedCall call) throws Exception {
        require(start.await(5, TimeUnit.SECONDS), "concurrent start gate timed out");
        try { return call.run(); }
        catch (AdminEnrollmentService.ConflictException conflict) { return conflict; }
    }

    private static long successes(List<Object> values) {
        return values.stream().filter(v -> v instanceof AdminOperationResultDTO<?>).count();
    }

    private static long conflicts(List<Object> values) {
        return values.stream().filter(v -> v instanceof AdminEnrollmentService.ConflictException).count();
    }

    private static long enrollmentRows(String uid, long offering) throws SQLException {
        return count("SELECT COUNT(*) FROM enrollment WHERE uid=? AND offering_id=?", uid, offering);
    }

    private static long countOf(long offering) throws SQLException {
        return count("SELECT enrolled_count FROM course_offering WHERE offering_id=?", offering);
    }

    private static long status(String uid, long offering) throws SQLException {
        return count("SELECT status FROM enrollment WHERE uid=? AND offering_id=?", uid, offering);
    }

    private static AdminEnrollmentRequestDTO request(int operation, long offering, String uid, boolean force, String reason) {
        return new AdminEnrollmentRequestDTO(op(operation), id(offering), uid, force, reason);
    }

    private static String id(long id) { return Long.toString(id); }
    private static String op(int operation) { return String.format("82000000-0000-0000-0000-%012d", operation); }
    private static Timestamp timestamp(Instant value) { return Timestamp.valueOf(value.atOffset(ZoneOffset.UTC).toLocalDateTime()); }

    private static boolean hasRisk(List<ScheduleConflictDTO> risks, String type) {
        return risks.stream().anyMatch(r -> r.getType().equals(type));
    }

    private static void requireRisk(List<ScheduleConflictDTO> risks, String type, ScheduleConflictSeverityDTO severity) {
        require(risks.stream().anyMatch(r -> r.getType().equals(type) && r.getSeverity() == severity),
                "expected " + type + " / " + severity + ", got " + GSON.toJson(risks));
    }

    private static void requireTestDatabase() throws SQLException {
        require("virtual_campus_course_test".equals(text("SELECT DATABASE()")),
                "Refusing admin enrollment test outside virtual_campus_course_test");
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row");
                return rows.getLong(1);
            }
        }
    }

    private static String text(String sql, Object... args) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row");
                return rows.getString(1);
            }
        }
    }

    private static Instant instant(String sql, Object... args) throws SQLException {
        try (Connection connection = DBUtil.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row");
                return rows.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }

    private static <T extends Throwable> T expect(Class<T> type, CheckedRun action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface CheckedRun { void run() throws Exception; }
    @FunctionalInterface private interface CheckedCall { Object run() throws Exception; }
}
