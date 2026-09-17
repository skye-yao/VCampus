package service;

import dao.AdminScheduleConflictDAO;
import dao.AdminScheduleDAO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import util.DBUtil;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Properties;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;

/**
 * Guarded MySQL coverage for the scheduling aggregate: structural validation, save/update,
 * idempotent replay, force handling, delete, publish, and read surfaces. Fixtures live in the
 * 93xxxx id range and are removed by {@code cleanup}.
 */
public final class ScheduleManagementMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String ADMIN = "admin-test";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final long CALENDAR = 930001L;
    private static final long TEMPLATE = 930002L;
    private static final long PLAN_DRAFT = 930003L;
    private static final long PLAN_PUBLISH = 930004L;
    private static final long CALENDAR_B = 930100L;
    private static final long PLAN_SWITCH = 930102L;
    private static final long PLAN_OTHER = 930103L;
    private static final long PLAN_CONFLICT = 930005L;
    // The demo shape: a draft plan whose row 930460 names no teacher while its sibling row is
    // complete. Reading such a plan is what the admin dialog does; publishing it must still refuse.
    private static final long PLAN_INCOMPLETE = 930104L;
    private static final long CALENDAR_INCOMPLETE = 930110L;
    private static final long ROOM_A = 930200L;
    private static final long ROOM_B = 930201L;
    private static final long ROOM_SMALL = 930202L;
    private static final long ARCHIVED_COURSE = 930300L;
    private static final long ARCHIVED_OFFERING = 930301L;
    private static final long CANCELLED_OFFERING = 930302L;
    private static final long WINDOW = 940001L;

    private static final long OFFERING_SELF = 2001L;
    private static final long OFFERING_OTHER = 2004L;

    private ScheduleManagementMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            ScheduleManagementService service =
                    new ScheduleManagementService(new AdminScheduleDAO(),
                            new AdminScheduleConflictDAO(), CLOCK);
            verifyResources(service);
            verifyStructureRules(service);
            String arrangementId = verifyCreateUpdateAndReplay(service);
            verifyForceRules(service);
            verifyDelete(service, arrangementId);
            verifyAdjustmentHistoryGuard(service);
            verifyPublish(service);
            verifyOfferingConflictRebuild(service);
            verifyLoadPlan(service);
            verifyIncompleteArrangementReadsAndPublication(service);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Schedule management MySQL test passed.");
    }

    private static void verifyResources(ScheduleManagementService service) {
        List<ScheduleResourceDTO> teachers = service.listResources("teacher", "teacher-al");
        require(teachers.stream().anyMatch(resource -> "teacher-alpha".equals(resource.getBusinessId())
                        && "Course Test Teacher A".equals(resource.getName())
                        && "teacher".equals(resource.getResourceType())),
                "teacher resources resolve by UID prefix");
        require(service.listResources("classroom", "Schedule Room").stream()
                        .anyMatch(resource -> "Schedule Room A".equals(resource.getName())
                                && resource.getCapacity() == 60),
                "classroom resources carry capacity");
        require(service.listResources(null, null).stream()
                        .anyMatch(resource -> "teacher".equals(resource.getResourceType()))
                        && service.listResources(null, null).stream()
                        .anyMatch(resource -> "classroom".equals(resource.getResourceType())),
                "an untyped resource query returns teachers and classrooms");
        expect(IllegalArgumentException.class, () -> service.listResources("bogus", null),
                "an unknown resource type is rejected");
    }

    private static void verifyStructureRules(ScheduleManagementService service) throws Exception {
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(1), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(), 1, 2, false, null)),
                "empty slots are rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(2), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(8, 1, 1)), 1, 2, false, null)),
                "weekday outside 1-7 is rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(3), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 9)), 1, 2, false, null)),
                "a period outside the day template is rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(4), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 3, 1)), 1, 2, false, null)),
                "start greater than end is rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(5), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 9, 10, false, null)),
                "weeks outside the teaching calendar are rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(6), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1), slot(2, 1, 1)),
                        1, 2, false, null)),
                "duplicate slots are rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(7), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 2), slot(2, 2, 3)),
                        1, 2, false, null)),
                "overlapping slots within one arrangement are rejected");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(8), null, 0, PLAN_DRAFT, CANCELLED_OFFERING,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a cancelled offering is rejected");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(9), null, 0, PLAN_DRAFT, ARCHIVED_OFFERING,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "an offering whose course is archived is rejected");
        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(10), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "student-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a non-teacher teacher is rejected");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.save(ADMIN, request(op(11), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, 999999999L, List.of(slot(2, 1, 1)), 1, 2,
                        false, null)),
                "a missing classroom is rejected");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.save(ADMIN, request(op(12), null, 0, PLAN_DRAFT, 999999999L,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a missing offering is rejected");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(13), null, 0, PLAN_OTHER, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a published plan is not editable");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.save(ADMIN, request(op(14), null, 0, 999999999L, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a missing plan is not found");
        require(count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id=" + PLAN_DRAFT)
                == 0, "rejected saves write nothing");

        expect(IllegalArgumentException.class,
                () -> service.checkArrangement(request(op(15), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(), 1, 2, false, null)),
                "a preview of an empty slot list is rejected like the write path");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.checkArrangement(request(op(16), null, 0, PLAN_DRAFT,
                        ARCHIVED_OFFERING, "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 1)),
                        1, 2, false, null)),
                "a preview of an archived offering is rejected like the write path");
        expect(IllegalArgumentException.class,
                () -> service.checkArrangement(request(op(17), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "student-alpha", null, ROOM_A, List.of(slot(2, 1, 1)), 1, 2, false, null)),
                "a preview naming a non-teacher is rejected like the write path");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.checkArrangement(request(op(18), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, 999999999L, List.of(slot(2, 1, 1)), 1, 2, false,
                        null)),
                "a preview naming a missing classroom is rejected like the write path");
    }

    private static String verifyCreateUpdateAndReplay(ScheduleManagementService service)
            throws Exception {
        AdminOperationResultDTO<ScheduleArrangementDTO> created = service.save(ADMIN,
                request(op(20), null, 0, PLAN_DRAFT, OFFERING_SELF, "teacher-alpha",
                        "teacher-beta", ROOM_A, List.of(slot(2, 1, 1), slot(4, 1, 1)), 1, 2,
                        false, null));
        ScheduleArrangementDTO arrangement = created.getEntity();
        require("OK".equals(created.getOutcomeCode()) && created.getConflicts().isEmpty(),
                "a clean save succeeds without conflicts");
        require(arrangement.getVersion() == 1 && "ACTIVE".equals(arrangement.getStatus()),
                "a created arrangement is ACTIVE v1");
        require(arrangement.getSlots().size() == 2 && arrangement.getStartWeek() == 1
                && arrangement.getEndWeek() == 2, "create persists slots and shared weeks");
        require("teacher-alpha".equals(arrangement.getTeacher().getBusinessId())
                && "teacher-beta".equals(arrangement.getAssistant().getBusinessId())
                && "Schedule Room A".equals(arrangement.getClassroom().getName()),
                "create persists teacher, assistant, and classroom");
        String id = arrangement.getArrangementId();
        require(count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=" + PLAN_DRAFT)
                == 4, "two slots over two weeks generate four occurrences");
        require(count("SELECT COUNT(*) FROM course_schedule_rule WHERE arrangement_id=" + id)
                == 2, "two rules are written");
        require(count("SELECT COUNT(*) FROM course_schedule_rule_week w JOIN course_schedule_rule r"
                + " ON r.id=w.rule_id WHERE r.arrangement_id=" + id) == 4,
                "the shared contiguous week set is written per rule");
        require(count("SELECT COUNT(*) FROM resource_booking WHERE plan_id=" + PLAN_DRAFT)
                == 12, "teacher, assistant, and classroom bookings are written per occurrence");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "' AND operation_id='" + op(20) + "' AND target_type='ARRANGEMENT'"
                + " AND target_id='" + id + "' AND forced=0 AND override_reason IS NULL"
                + " AND conflict_snapshot_json IS NOT NULL") == 1,
                "the save writes one operation row with a conflict snapshot");

        AdminOperationResultDTO<ScheduleArrangementDTO> replay = service.save(ADMIN,
                request(op(20), null, 0, PLAN_DRAFT, OFFERING_SELF, "teacher-alpha",
                        "teacher-beta", ROOM_A, List.of(slot(2, 1, 1), slot(4, 1, 1)), 1, 2,
                        false, null));
        require(id.equals(replay.getEntity().getArrangementId())
                && replay.getEntity().getVersion() == 1,
                "a same-digest replay returns the stored response");
        require(count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id="
                + PLAN_DRAFT) == 1 && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id="
                + PLAN_DRAFT) == 4, "a replay writes no duplicate rows");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "' AND operation_id='" + op(20) + "'") == 1, "a replay keeps one operation row");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(20), null, 0, PLAN_DRAFT, OFFERING_OTHER,
                        "teacher-alpha", null, ROOM_B, List.of(slot(3, 1, 1)), 1, 1, false, null)),
                "a different digest under the same operationId is a conflict");

        AdminOperationResultDTO<ScheduleArrangementDTO> updated = service.save(ADMIN,
                request(op(21), id, 1, PLAN_DRAFT, OFFERING_SELF, "teacher-beta", null, ROOM_B,
                        List.of(slot(2, 1, 2), slot(4, 3, 3)), 1, 2, false, null));
        require(updated.getEntity().getVersion() == 2
                && "teacher-beta".equals(updated.getEntity().getTeacher().getBusinessId())
                && updated.getEntity().getAssistant() == null,
                "update increments the version and replaces the aggregate");
        require(count("SELECT COUNT(*) FROM course_schedule_rule WHERE arrangement_id=" + id) == 2
                && count("SELECT COUNT(*) FROM resource_booking WHERE plan_id=" + PLAN_DRAFT)
                == 8, "update replaces only its own normalized children");

        ScheduleManagementService.ConflictException stale = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(22), id, 1, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-beta", null, ROOM_B, List.of(slot(2, 1, 2)), 1, 2, false, null)),
                "a stale expectedVersion conflicts");
        require(stale.getEntity() instanceof ScheduleArrangementDTO
                        && ((ScheduleArrangementDTO) stale.getEntity()).getVersion() == 2,
                "a stale version conflict carries the latest arrangement");

        List<ScheduleConflictDTO> preview = service.checkArrangement(
                request(op(23), id, 2, PLAN_DRAFT, OFFERING_SELF, "teacher-beta", null, ROOM_B,
                        List.of(slot(2, 1, 2), slot(4, 3, 3)), 1, 2, false, null));
        require(preview.isEmpty(),
                "a preview excludes the edited arrangement's own occurrences");
        return id;
    }

    private static void verifyForceRules(ScheduleManagementService service) throws Exception {
        SaveArrangementRequestDTO overridable = request(op(30), null, 0, PLAN_DRAFT,
                OFFERING_OTHER, "teacher-beta", null, ROOM_A, List.of(slot(2, 1, 2)), 1, 1,
                false, null);
        ScheduleManagementService.ConflictException rejected = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, overridable),
                "an overridable conflict is rejected without force");
        require(type(rejected.getConflicts(), "TEACHER_OVERLAP") != null,
                "the rejection carries the typed teacher conflict");
        require(rejected.getEntity() == null, "a rejected create carries no entity");
        require(count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id="
                + PLAN_DRAFT) == 1, "a rejected save writes nothing");

        expect(IllegalArgumentException.class,
                () -> service.save(ADMIN, request(op(31), null, 0, PLAN_DRAFT, OFFERING_OTHER,
                        "teacher-beta", null, ROOM_A, List.of(slot(2, 1, 2)), 1, 1, true, "   ")),
                "force without a reason is rejected");

        AdminOperationResultDTO<ScheduleArrangementDTO> forced = service.save(ADMIN,
                request(op(32), null, 0, PLAN_DRAFT, OFFERING_OTHER, "teacher-beta", null, ROOM_A,
                        List.of(slot(2, 1, 2)), 1, 1, true, "  已协调教师  "));
        require(type(forced.getConflicts(), "TEACHER_OVERLAP") != null,
                "a forced save still returns the overridable conflicts");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "' AND operation_id='" + op(32) + "' AND forced=1"
                + " AND override_reason='已协调教师' AND conflict_snapshot_json IS NOT NULL")
                == 1, "a forced save records the trimmed reason and snapshot");

        ScheduleManagementService.ConflictException blocking = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(33), null, 0, PLAN_DRAFT, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_SMALL, List.of(slot(2, 1, 2)), 1, 1, true,
                        "试图覆盖")),
                "a blocking conflict is rejected even with force");
        require(blocking.getConflicts().stream()
                        .anyMatch(conflict -> BLOCKING == conflict.getSeverity()),
                "the blocking rejection carries a blocking conflict");
    }

    private static void verifyDelete(ScheduleManagementService service, String arrangementId)
            throws Exception {
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.delete(ADMIN, arrangementId, 1, op(40)),
                "a stale version rejects delete");
        AdminOperationResultDTO<Void> deleted =
                service.delete(ADMIN, arrangementId, 2, op(41));
        require("OK".equals(deleted.getOutcomeCode()) && deleted.getEntity() == null,
                "a draft arrangement deletes with a null entity");
        require(count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE arrangement_id="
                + arrangementId) == 0
                && count("SELECT COUNT(*) FROM course_schedule_rule WHERE arrangement_id="
                + arrangementId) == 0
                && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=" + PLAN_DRAFT) == 1
                && count("SELECT COUNT(*) FROM resource_booking WHERE plan_id=" + PLAN_DRAFT) == 2,
                "delete cascades through rules, weeks, occurrences, and bookings");
        AdminOperationResultDTO<Void> replay = service.delete(ADMIN, arrangementId, 2, op(41));
        require("OK".equals(replay.getOutcomeCode())
                && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "' AND operation_id='" + op(41) + "'") == 1,
                "delete replay returns the stored result once");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.delete(ADMIN, "930430", 1, op(42)),
                "an arrangement in a published plan is not deletable");
    }

    private static void verifyAdjustmentHistoryGuard(ScheduleManagementService service)
            throws Exception {
        ScheduleManagementService.ConflictException rewrite = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.save(ADMIN, request(op(43), "930440", 1, PLAN_SWITCH, OFFERING_SELF,
                        "teacher-alpha", null, ROOM_A, List.of(slot(2, 1, 2)), 1, 1, false, null)),
                "an arrangement whose occurrence carries adjustment history cannot be rewritten");
        require(rewrite.getEntity() instanceof ScheduleArrangementDTO,
                "the rewrite refusal carries the arrangement it refused");
        ScheduleManagementService.ConflictException removal = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.delete(ADMIN, "930440", 1, op(44)),
                "an arrangement whose occurrence carries adjustment history cannot be deleted");
        require(removal.getEntity() instanceof ScheduleArrangementDTO,
                "the delete refusal carries the arrangement it refused");
        require(count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE arrangement_id=930440")
                == 1
                && count("SELECT COUNT(*) FROM course_occurrence WHERE id=930640") == 1
                && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id=930900"
                + " AND status='ACTIVE'") == 1,
                "a refused rewrite or delete leaves the guarded aggregate intact");
    }

    private static void verifyOfferingConflictRebuild(ScheduleManagementService service)
            throws Exception {
        // The earlier publication of PLAN_PUBLISH rebuilt its own pair from its two surviving
        // overlapping arrangements, and both of those rows must be untouched by this rebuild.
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_PUBLISH
                        + " AND course_offering_a_id=" + OFFERING_SELF
                        + " AND course_offering_b_id=" + OFFERING_OTHER
                        + " AND conflict_count=1") == 1,
                "publishing a plan writes the pair its own arrangements overlap on");
        // A stale row for the plan about to be rebuilt, and an unrelated plan's row.
        execute("INSERT INTO course_offering_conflict(plan_id,course_offering_a_id,"
                + "course_offering_b_id,conflict_count,first_conflict_at,last_conflict_at) VALUES("
                + PLAN_CONFLICT + "," + OFFERING_SELF + "," + OFFERING_OTHER + ",99,"
                + "'2000-01-01 00:00:00','2000-01-02 00:00:00')");
        execute("INSERT INTO course_offering_conflict(plan_id,course_offering_a_id,"
                + "course_offering_b_id,conflict_count,first_conflict_at,last_conflict_at) VALUES("
                + PLAN_DRAFT + "," + OFFERING_SELF + "," + OFFERING_OTHER + ",5,"
                + "'2000-01-01 00:00:00','2000-01-02 00:00:00')");

        AdminOperationResultDTO<SchedulePlanDTO> published = service.publish(ADMIN,
                Long.toString(PLAN_CONFLICT), 1, op(60), false, null);
        require("PUBLISHED".equals(published.getEntity().getStatus())
                        && published.getEntity().isCurrent(),
                "publishing the rebuild plan succeeds and makes it current");
        require(count("SELECT current_schedule_plan_id FROM teaching_calendar WHERE id=" + CALENDAR)
                        == PLAN_CONFLICT,
                "the pointer advances only after the conflict rebuild");

        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_CONFLICT)
                        == 1,
                "the rebuild writes exactly one offering pair for the plan");
        require(count("SELECT conflict_count FROM course_offering_conflict WHERE plan_id="
                        + PLAN_CONFLICT + " AND course_offering_a_id=" + OFFERING_SELF
                        + " AND course_offering_b_id=" + OFFERING_OTHER) == 2,
                "the conflict count spans the effective day-2 replacement and the day-4 occurrence");
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_CONFLICT
                        + " AND course_offering_a_id=" + OFFERING_OTHER
                        + " AND course_offering_b_id=" + OFFERING_SELF) == 0,
                "the stored pair is ordered, so the reverse orientation holds no row");
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_CONFLICT
                        + " AND course_offering_a_id=" + OFFERING_SELF
                        + " AND course_offering_b_id=" + OFFERING_OTHER
                        + " AND first_conflict_at='" + utcText("2026-09-08", "08:00:00")
                        + "' AND last_conflict_at='" + utcText("2026-09-10", "08:00:00") + "'") == 1,
                "first and last conflict instants come from the effective occurrences");
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_CONFLICT
                        + " AND conflict_count=99") == 0,
                "the stale row for this plan is replaced, not accumulated");

        require(conflictPairs(PLAN_CONFLICT, OFFERING_SELF, OFFERING_OTHER) == 1
                        && conflictPairs(PLAN_CONFLICT, OFFERING_OTHER, OFFERING_SELF) == 1,
                "the pair stays visible from both sides of the student-facing query");
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_DRAFT
                        + " AND conflict_count=5") == 1
                        && count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id="
                        + PLAN_PUBLISH + " AND conflict_count=1") == 1,
                "another plan's precomputed rows are untouched");

        // The adjusted original must no longer count: it overlaps 2004's day-1 slot, which is
        // absent from the two counted pairs.
        require(count("SELECT COUNT(*) FROM course_offering_conflict WHERE plan_id=" + PLAN_CONFLICT
                        + " AND conflict_count=3") == 0,
                "the adjusted original occurrence is excluded from the rebuild");
    }

    /** The student-facing orientation predicate, evaluated for one offering pair. */
    private static int conflictPairs(long planId, long enrolled, long target) throws SQLException {
        return count("SELECT COUNT(*) FROM course_offering_conflict c WHERE c.plan_id=" + planId
                + " AND ((c.course_offering_a_id=" + enrolled + " AND c.course_offering_b_id="
                + target + ") OR (c.course_offering_b_id=" + enrolled
                + " AND c.course_offering_a_id=" + target + "))");
    }

    private static void verifyPublish(ScheduleManagementService service) throws Exception {
        require(count("SELECT COUNT(*) FROM course_selection_window WHERE window_id=" + WINDOW
                        + " AND UTC_TIMESTAMP(6)>=plan_open_at AND UTC_TIMESTAMP(6)<=drop_deadline")
                        == 1,
                "the window fixture must sit inside plan_open_at..drop_deadline");
        require(count("SELECT COUNT(*) FROM course_selection_window WHERE window_id=" + WINDOW
                        + " AND UTC_TIMESTAMP(6)<selection_open_at") == 1,
                "the window fixture must still be before selection_open_at");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(50), true, "试图发布"),
                "a blocking conflict rejects publication even when forced");

        execute("DELETE FROM course_occurrence WHERE id IN (930610,930611)");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id IN (930510,930511)");
        execute("DELETE FROM course_schedule_rule WHERE id IN (930510,930511)");
        execute("DELETE FROM course_schedule_arrangement WHERE arrangement_id IN (930410,930411)");

        ScheduleManagementService.ConflictException overridable = expect(
                ScheduleManagementService.ConflictException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(51), false, null),
                "an overridable conflict rejects publication without force");
        require(type(overridable.getConflicts(), "TEACHER_OVERLAP") != null,
                "the publication rejection carries the typed conflict");
        expect(IllegalArgumentException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(52), true, "  "),
                "force without a reason is rejected");

        AdminOperationResultDTO<SchedulePlanDTO> published =
                service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(53), true, "已协调教师");
        require("PUBLISHED".equals(published.getEntity().getStatus())
                && published.getEntity().isCurrent(), "publication returns the current plan");
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN_PUBLISH
                + " AND status='PUBLISHED' AND published_by='" + ADMIN
                + "' AND published_at IS NOT NULL") == 1,
                "publication records the auditor and UTC instant");
        require(count("SELECT current_schedule_plan_id FROM teaching_calendar WHERE id="
                + CALENDAR) == PLAN_PUBLISH,
                "publication advances the teaching calendar pointer");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "' AND operation_id='" + op(53) + "' AND target_type='SCHEDULE_PLAN'"
                + " AND target_id='" + PLAN_PUBLISH + "' AND forced=1"
                + " AND override_reason='已协调教师'") == 1,
                "publication writes one forced operation row");
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN_OTHER
                + " AND status='PUBLISHED'") == 1,
                "previous published plans stay immutable history");
        AdminOperationResultDTO<SchedulePlanDTO> replay =
                service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(53), true, "已协调教师");
        require(replay.getEntity().isCurrent()
                && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                + op(53) + "'") == 1, "publication replay returns the stored response once");

        expect(ScheduleManagementService.ConflictException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_SWITCH), 1, op(54), true, "切换方案"),
                "an open selection window bound elsewhere rejects publication");
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN_SWITCH
                + " AND status='DRAFT'") == 1, "a rejected publication leaves the plan in DRAFT");
        expect(ScheduleManagementService.ConflictException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_PUBLISH), 1, op(55), true, "重复发布"),
                "an already published plan is not republished");
    }

    private static void verifyLoadPlan(ScheduleManagementService service) {
        SchedulePlanDTO plan = service.loadPlan(2026, 3);
        require(Long.toString(PLAN_DRAFT).equals(plan.getPlanId())
                && "DRAFT".equals(plan.getStatus()) && !plan.isCurrent(),
                "loadPlan prefers the editable draft over the published plan");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.loadPlan(2030, 3), "an unknown term has no plan");
        List<ScheduleArrangementDTO> arrangements =
                service.listArrangements(Long.toString(PLAN_DRAFT), null);
        require(arrangements.size() == 1 && arrangements.get(0).getSlots().size() == 1,
                "listArrangements returns the surviving draft arrangement");
        require(service.listArrangements(Long.toString(PLAN_DRAFT),
                        Long.toString(OFFERING_OTHER)).size() == 1,
                "listArrangements filters by offering");
        expect(ScheduleManagementService.NotFoundException.class,
                () -> service.listArrangements("999999999", null),
                "a missing plan has no arrangements");
    }

    /**
     * A plan an admin is still assembling legitimately holds arrangements that cannot form a
     * candidate — here one with no teacher at all. Reading such a plan must skip that row instead
     * of running the publication gate, and the row must stay visible so the client can render it
     * as pending. Publishing the very same plan must still refuse.
     */
    private static void verifyIncompleteArrangementReadsAndPublication(
            ScheduleManagementService service) throws Exception {
        SchedulePlanDTO plan = service.loadPlan(2027, 1);
        require(Long.toString(PLAN_INCOMPLETE).equals(plan.getPlanId())
                        && "DRAFT".equals(plan.getStatus()) && !plan.isCurrent(),
                "loadPlan returns a draft plan holding an arrangement without a teacher");
        require(plan.getConflicts().isEmpty(),
                "an arrangement without a teacher is skipped, not reported as a conflict");
        List<ScheduleArrangementDTO> arrangements =
                service.listArrangements(Long.toString(PLAN_INCOMPLETE), null);
        require(arrangements.size() == 2,
                "the incomplete arrangement stays visible beside the complete one");
        require(arrangements.get(0).getTeacher() == null
                        && arrangements.get(0).getClassroom().getName().equals("Schedule Room A")
                        && arrangements.get(0).getSlots().size() == 1,
                "the client can still render the arrangement without a teacher as pending");
        require("teacher-alpha".equals(arrangements.get(1).getTeacher().getBusinessId()),
                "the complete arrangement of the same plan is unaffected");

        IllegalArgumentException refusal = expect(IllegalArgumentException.class,
                () -> service.publish(ADMIN, Long.toString(PLAN_INCOMPLETE), 1, op(61), false, null),
                "an arrangement without a teacher must not be publishable");
        require("教学安排缺少任课教师或时间段，无法发布".equals(refusal.getMessage()),
                "publication refuses it with the message the gate has always used, got "
                        + refusal.getMessage());
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN_INCOMPLETE
                        + " AND status='DRAFT'") == 1,
                "a refused publication leaves the plan in DRAFT");
    }

    private static ScheduleSlotDTO slot(int dayOfWeek, int startPeriod, int endPeriod) {
        return new ScheduleSlotDTO(dayOfWeek, startPeriod, endPeriod);
    }

    private static SaveArrangementRequestDTO request(String operationId, String arrangementId,
                                                     int expectedVersion, long planId,
                                                     long offeringId, String teacherUid,
                                                     String assistantUid, long classroomId,
                                                     List<ScheduleSlotDTO> slots, int startWeek,
                                                     int endWeek, boolean force,
                                                     String overrideReason) {
        return new SaveArrangementRequestDTO(operationId, arrangementId, expectedVersion,
                Long.toString(planId), Long.toString(offeringId), teacherUid, assistantUid,
                Long.toString(classroomId), slots, startWeek, endWeek, force, overrideReason);
    }

    private static ScheduleConflictDTO type(List<ScheduleConflictDTO> conflicts, String name) {
        for (ScheduleConflictDTO conflict : conflicts) {
            if (name.equals(conflict.getType())) return conflict;
        }
        return null;
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + ADMIN + "','Course Test Admin','x','x',0,'Admin','Admin')");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR + ",'Schedule test calendar',"
                + "2026,3,'2026-09-07','Asia/Shanghai',1,'PUBLISHED'),(" + CALENDAR_B
                + ",'Schedule switch calendar',2026,1,'2026-09-07','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO day_template(id,name,version) VALUES(" + TEMPLATE
                + ",'Schedule test template',1)");
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(930010," + TEMPLATE + ",1,'08:00:00','08:45:00'),"
                + "(930011," + TEMPLATE + ",2,'08:45:00','09:30:00'),"
                + "(930012," + TEMPLATE + ",3,'09:30:00','10:15:00'),"
                + "(930013," + TEMPLATE + ",4,'10:15:00','11:00:00')");
        int dateId = 930020;
        for (long calendar : new long[] { CALENDAR, CALENDAR_B }) {
            for (int week = 1; week <= 3; week++) {
                for (int day = 1; day <= 5; day++) {
                    String date = LocalDate.parse("2026-09-07")
                            .plusDays((week - 1) * 7L + day - 1).toString();
                    execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                            + "teaching_weekday,day_template_id,is_teaching_day) VALUES(" + dateId++
                            + "," + calendar + ",'" + date + "'," + week + "," + day + ","
                            + TEMPLATE + ",1)");
                }
            }
        }
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN_DRAFT + ",'Schedule draft plan'," + CALENDAR
                + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00'),(" + PLAN_PUBLISH
                + ",'Schedule publish plan'," + CALENDAR + ",1,'DRAFT','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00'),(" + PLAN_SWITCH + ",'Schedule switch plan',"
                + CALENDAR_B + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00'),("
                + PLAN_OTHER + ",'Schedule current plan'," + CALENDAR_B
                + ",1,'PUBLISHED','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES"
                + "(" + ROOM_A + ",'Schedule Room A',60,1),(" + ROOM_B
                + ",'Schedule Room B',60,1),(" + ROOM_SMALL + ",'Schedule Room Small',5,1)");
        // Pinned resource rows so ensureResource reuses them instead of creating auto-increment
        // rows this test's cleanup could not name.
        execute("INSERT INTO schedule_resource(id,resource_type,business_id,conflict_mode) VALUES"
                + "(930401,'classroom','930200','EXCLUSIVE'),"
                + "(930402,'classroom','930201','EXCLUSIVE'),"
                + "(930403,'classroom','930202','EXCLUSIVE')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + ARCHIVED_COURSE + ",'CS-HIST','Archived',"
                + "1.00,16,3,'ARCHIVED')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + ARCHIVED_OFFERING + ",'CS-HIST-A',"
                + ARCHIVED_COURSE + ",2030,3,30,2),(" + CANCELLED_OFFERING + ",'CS101-CANC',1001,"
                + "2030,3,30,4)");
        // The window is open under the plan_open_at..drop_deadline reading but NOT yet open for
        // selection, so only the wider interpretation lets it block a plan switch.
        execute("INSERT INTO course_selection_window(window_id,academic_year,semester,"
                + "schedule_plan_id,plan_open_at,plan_close_at,selection_open_at,selection_close_at,"
                + "drop_deadline) VALUES(" + WINDOW + ",2026,1," + PLAN_OTHER
                + ",'2020-01-01 00:00:00.000000','2098-12-01 00:00:00.000000',"
                + "'2099-01-01 00:00:00.000000','2099-11-01 00:00:00.000000',"
                + "'2099-12-31 00:00:00.000000')");

        // Blocking fixture: two arrangements of one offering overlapping inside PLAN_PUBLISH. Kept
        // on its own weekday so the unique slot identity never clashes with the overridable pair.
        scheduled(930410L, 930510L, 930610L, PLAN_PUBLISH, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 3, 1, 2);
        scheduled(930411L, 930511L, 930611L, PLAN_PUBLISH, OFFERING_SELF, "teacher-alpha", ROOM_B,
                1, 3, 2, 3);
        // Overridable fixture: same teacher, different offering and room.
        scheduled(930420L, 930520L, 930620L, PLAN_PUBLISH, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 2, 1, 2);
        scheduled(930421L, 930521L, 930621L, PLAN_PUBLISH, OFFERING_OTHER, "teacher-alpha", ROOM_B,
                1, 2, 2, 3);
        // Published-plan fixture for the delete guard.
        scheduled(930430L, 930530L, 930630L, PLAN_OTHER, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 2, 1, 1);

        // A DRAFT-plan arrangement carrying temporary-adjustment history. Both the adjustment and
        // its target reference this occurrence with ON DELETE RESTRICT.
        scheduled(930440L, 930540L, 930640L, PLAN_SWITCH, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 5, 1, 1);
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,reviewed_by,reviewed_at)"
                + " VALUES(930700," + OFFERING_SELF + ",'teacher-alpha','保存守卫夹具',1,'APPROVED',"
                + "5,1,1,NULL,NULL,NULL,'" + ADMIN + "',NOW(6))");
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES"
                + "(930800,930700,930640,1,'" + utcText("2026-09-11", "08:00:00") + "','"
                + utcText("2026-09-11", "08:45:00") + "','teacher-alpha',NULL," + ROOM_A + ")");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(930900,930700,930640,'"
                + utcText("2026-09-11", "10:15:00") + "','" + utcText("2026-09-11", "11:00:00")
                + "','teacher-alpha',NULL," + ROOM_A + ",'ACTIVE')");

        // Publication rebuild fixtures. Offerings 2001 and 2004 each occupy one period-1 slot:
        // 2001 on days 1 and 4, 2004 on days 1, 2 and 4. An ACTIVE adjustment moves 2001's day-1
        // occurrence onto 2004's day-2 slot, so the effective pair overlaps on days 2 and 4 only.
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN_CONFLICT + ",'Schedule conflict rebuild plan',"
                + CALENDAR + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        scheduled(930450L, 930550L, 930650L, PLAN_CONFLICT, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 1, 1, 1);
        scheduled(930452L, 930552L, 930652L, PLAN_CONFLICT, OFFERING_SELF, "teacher-alpha", ROOM_A,
                1, 4, 1, 1);
        scheduled(930451L, 930551L, 930651L, PLAN_CONFLICT, OFFERING_OTHER, "teacher-beta", ROOM_B,
                1, 1, 1, 1);
        scheduled(930453L, 930553L, 930653L, PLAN_CONFLICT, OFFERING_OTHER, "teacher-beta", ROOM_B,
                1, 2, 1, 1);
        scheduled(930455L, 930555L, 930655L, PLAN_CONFLICT, OFFERING_OTHER, "teacher-beta", ROOM_B,
                1, 4, 1, 1);
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,reviewed_by,reviewed_at)"
                + " VALUES(930710," + OFFERING_SELF + ",'teacher-alpha','冲突重建夹具',1,'APPROVED',"
                + "2,1,1,NULL,NULL,NULL,'" + ADMIN + "',NOW(6))");
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES"
                + "(930810,930710,930650,1,'" + utcText("2026-09-07", "08:00:00") + "','"
                + utcText("2026-09-07", "08:45:00") + "','teacher-alpha',NULL," + ROOM_A + ")");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(930910,930710,930650,'"
                + utcText("2026-09-08", "08:00:00") + "','" + utcText("2026-09-08", "08:45:00")
                + "','teacher-alpha',NULL," + ROOM_A + ",'ACTIVE')");

        // The incomplete-plan fixture: a second draft plan in its own term, whose first
        // arrangement carries slots but no teacher (the shape the demo seed ships in plan 4001)
        // and whose second is complete. Written by hand rather than through scheduled() so the
        // NULL teacher is explicit.
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR_INCOMPLETE
                + ",'Schedule incomplete calendar',2027,1,'2026-09-07','Asia/Shanghai',1,"
                + "'PUBLISHED')");
        for (int day = 1; day <= 5; day++) {
            execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                    + "teaching_weekday,day_template_id,is_teaching_day) VALUES("
                    + (930050 + day - 1) + "," + CALENDAR_INCOMPLETE + ",'"
                    + LocalDate.parse("2026-09-07").plusDays(day - 1L) + "',1," + day + ","
                    + TEMPLATE + ",1)");
        }
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN_INCOMPLETE + ",'Schedule incomplete plan',"
                + CALENDAR_INCOMPLETE + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,classroom_id,status,version) VALUES(930460," + PLAN_INCOMPLETE + ","
                + OFFERING_SELF + ",NULL," + ROOM_A + ",'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(930560," + PLAN_INCOMPLETE + ","
                + OFFERING_SELF + ",930460,2,1,2,'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(930560,1)");
        scheduled(930461L, 930561L, 930661L, PLAN_INCOMPLETE, OFFERING_SELF, "teacher-alpha",
                ROOM_B, 1, 4, 1, 2);
    }

    private static void scheduled(long arrangementId, long ruleId, long occurrenceId, long planId,
                                  long offeringId, String teacher, long classroomId, int week,
                                  int weekday, int startPeriod, int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,classroom_id,status,version) VALUES(" + arrangementId + "," + planId
                + "," + offeringId + ",'" + teacher + "'," + classroomId + ",'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + ruleId + "," + planId + ","
                + offeringId + "," + arrangementId + "," + weekday + "," + startPeriod + ","
                + endPeriod + ",'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + ruleId + ","
                + week + ")");
        String date = LocalDate.parse("2026-09-07")
                .plusDays((week - 1) * 7L + weekday - 1).toString();
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + occurrenceId + "," + ruleId + "," + planId + ",'"
                + utcText(date, period(startPeriod, true)) + "','"
                + utcText(date, period(endPeriod, false)) + "'," + week + "," + weekday + ")");
    }

    private static String period(int periodNo, boolean start) {
        return switch (periodNo) {
            case 1 -> start ? "08:00:00" : "08:45:00";
            case 2 -> start ? "08:45:00" : "09:30:00";
            case 3 -> start ? "09:30:00" : "10:15:00";
            case 4 -> start ? "10:15:00" : "11:00:00";
            default -> throw new IllegalArgumentException("no fixture period " + periodNo);
        };
    }

    private static String utcText(String date, String time) {
        Instant instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE)
                .toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace('T', ' ');
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM course_selection_window WHERE window_id=" + WINDOW);
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 930000"
                + " AND 939999");
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 930500 AND 930599");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 930000 AND 939999");
        execute("DELETE FROM resource_booking WHERE plan_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM schedule_resource WHERE resource_type='classroom'"
                + " AND business_id IN ('930200','930201','930202')");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM course WHERE course_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM classroom WHERE id BETWEEN 930000 AND 939999");
        execute("DELETE FROM calendar_date WHERE calendar_id BETWEEN 930000 AND 939999");
        execute("DELETE FROM period_definition WHERE id BETWEEN 930000 AND 939999");
        execute("DELETE FROM day_template WHERE id BETWEEN 930000 AND 939999");
        execute("DELETE FROM teaching_calendar WHERE id BETWEEN 930000 AND 939999");
        execute("DELETE FROM tbl_user WHERE UID='" + ADMIN + "'");
    }

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
                "Refusing schedule test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing schedule test outside the guarded schema");
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
        return String.format("60000000-0000-0000-0000-%012d", value);
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

    /** Proves {@code cleanup} leaves nothing behind, including auto-created resource rows. */
    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_arrangement"
                + " WHERE plan_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_rule"
                + " WHERE plan_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_occurrence"
                + " WHERE plan_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM resource_booking"
                + " WHERE plan_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM schedule_resource WHERE resource_type='classroom'"
                + " AND business_id IN ('930200','930201','930202')") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment"
                + " WHERE adjustment_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                + " WHERE request_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment_target"
                + " WHERE target_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_offering"
                + " WHERE offering_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM course_selection_window WHERE window_id=940001") == 0
                && count("SELECT COUNT(*) FROM course_offering_conflict"
                + " WHERE plan_id BETWEEN 930000 AND 939999") == 0
                && count("SELECT COUNT(*) FROM admin_course_operation_log"
                + " WHERE admin_uid='" + ADMIN + "'") == 0
                && count("SELECT COUNT(*) FROM tbl_user WHERE UID='" + ADMIN + "'") == 0,
                "cleanup must leave no fixture row behind");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
