package service;

import dao.AdminCourseOperationDAO;
import dao.ScheduleAdjustmentDAO;
import dao.TeacherAdjustmentDAO;
import dao.TeacherCourseOperationDAO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static dto.course.AdjustmentRequestStatusDTO.APPROVED;
import static dto.course.AdjustmentRequestStatusDTO.PENDING;
import static dto.course.AdjustmentRequestStatusDTO.WITHDRAWN;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Guarded MySQL coverage for teacher adjustment submissions: options domain, preview, submit,
 * withdraw, ownership, replay/digest conflicts, duplicate pending targets, the all-or-nothing
 * submit transaction and the withdraw-versus-approval race.
 *
 * <p>Fixtures live in the 945000-945899 band (CourseConflictMySqlTest uses 9000xx-9002xx,
 * TeacherAdjustmentConflictMySqlTest 9003xx-9008xx, ScheduleManagementMySqlTest 930xxx/940001,
 * ScheduleAdjustmentApprovalMySqlTest 970xxx, GradeApprovalMySqlTest 974xxx and the integration
 * tests 95xxxx/96xxxx/98xxxx/990xxx), plus requester-scoped cleanup for rows the service inserts
 * with database-assigned identifiers. Without a {@code mysql} argument the test prints SKIP and is
 * never reported as passing.
 */
public final class TeacherAdjustmentApplicationMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T06:30:00Z"), ZoneOffset.UTC);
    /** The clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-14 06:30:00";
    private static final String WEEK1_START = "2026-09-07";
    private static final String REQUESTER_PREFIX = "tadj945-";

    private static final String TEACHER_A = "tadj945-teacher-a";
    private static final String TEACHER_B = "tadj945-teacher-b";
    private static final String TEACHER_C = "tadj945-teacher-c";
    private static final String ASSISTANT = "tadj945-assistant";
    private static final String ADMIN = "tadj945-admin";

    private static final long CALENDAR = 945001L;
    private static final long TEMPLATE = 945002L;
    private static final long PLAN = 945003L;
    private static final long NON_TEACHING_DATE = 945090L;

    private static final long ROOM_MAIN = 945101L;
    private static final long ROOM_SMALL = 945102L;
    private static final long ROOM_SPARE = 945103L;
    private static final long ROOM_OTHER = 945104L;

    private static final long COURSE_MAIN = 945201L;
    private static final long COURSE_OTHER = 945202L;
    private static final long OFFERING_MAIN = 945301L;
    private static final long OFFERING_OTHER = 945302L;

    private static final long ARRANGEMENT_MAIN = 945401L;
    private static final long ARRANGEMENT_SHARED = 945402L;
    private static final long ARRANGEMENT_OTHER = 945403L;

    private static final long RULE_MAIN_TUE = 945501L;
    private static final long RULE_SHARED_THU = 945502L;
    private static final long RULE_OTHER_WED = 945503L;
    private static final long RULE_MAIN_MON_LATE = 945504L;

    private static final long OCC_MAIN_W1D2 = 945601L;
    private static final long OCC_MAIN_W2D2 = 945602L;
    private static final long OCC_MAIN_W3D2 = 945603L;
    private static final long OCC_MAIN_W4D2 = 945604L;
    private static final long OCC_SHARED_W3D4 = 945613L;
    private static final long OCC_OTHER_W3D3 = 945623L;
    private static final long OCC_MAIN_W2D1 = 945632L;
    private static final long OCC_MAIN_W3D1 = 945633L;
    private static final long OCC_MAIN_W4D1 = 945634L;

    private static final long REQUEST_APPROVED = 945801L;
    private static final long LEGACY_REQUEST = 945802L;
    private static final long LEGACY_TARGET = 945803L;
    private static final long ADJUSTMENT_ACTIVE = 945701L;

    private static long cleanRequestId;
    private static long pendingRequestId;

    private TeacherAdjustmentApplicationMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher adjustment application test "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            TeacherAdjustmentApplicationService service = service(new TeacherAdjustmentDAO());
            verifyOptions(service);
            verifySubmitCleanAndReplay(service);
            verifyValidationFailures(service);
            verifyOwnership(service);
            verifyActiveAdjustedTarget(service);
            verifyPendingDuplicateTarget(service);
            verifyLegacyTargetDate(service);
            verifyDateDomain(service);
            verifyWithdraw(service);
            verifyWithdrawnIsNotApprovable();
            verifyInjectedFailureRollback();
            verifyConcurrentDuplicateSubmit(service);
            verifyListMineAndGet(service);
            verifyDetailSurvivesUnavailablePlan(service);
            verifyWithdrawApproveRace(service);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher adjustment application MySQL test passed.");
    }

    // ------------------------------------------------------------------ options

    private static void verifyOptions(TeacherAdjustmentApplicationService service) throws Exception {
        TeacherAdjustmentOptionsDTO options = service.options(TEACHER_A,
                Long.toString(OFFERING_MAIN), Long.toString(OCC_MAIN_W3D2));
        require(Long.toString(CALENDAR).equals(options.getCalendarId())
                        && "Asia/Shanghai".equals(options.getTimezone()),
                "options report the offering's published-plan calendar and its timezone");

        require(options.getDates().size() == 20
                        && options.getDates().stream().allMatch(TeacherCalendarDateDTO::isTeachingDay),
                "options offer only the calendar's teaching days (observed "
                        + options.getDates().size() + ")");
        TeacherCalendarDateDTO first = options.getDates().get(0);
        require("2026-09-07".equals(first.getDate()) && first.getWeek() == 1
                        && first.getTeachingWeekday() == 1,
                "dates are ordered by week then teaching weekday");
        require(options.getDates().stream().noneMatch(date -> "2026-09-19".equals(date.getDate())),
                "a non-teaching day is never offered as a target date");

        require(options.getPeriods().size() == 80
                        && options.getPeriods().stream().anyMatch(period ->
                        "2026-09-07".equals(period.getDate()) && period.getPeriod() == 1
                                && "08:00:00".equals(period.getStartTime())
                                && "08:45:00".equals(period.getEndTime())),
                "periods come from the day template of each offered date (observed "
                        + options.getPeriods().size() + ")");
        Set<String> rooms = options.getClassrooms().stream()
                .map(ScheduleResourceDTO::getResourceId).collect(Collectors.toCollection(LinkedHashSet::new));
        require(rooms.containsAll(Set.of(Long.toString(ROOM_MAIN), Long.toString(ROOM_SMALL),
                        Long.toString(ROOM_SPARE), Long.toString(ROOM_OTHER))),
                "classrooms list every classroom resource (observed " + rooms + ")");

        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.options(TEACHER_A, Long.toString(OFFERING_MAIN),
                        Long.toString(OCC_OTHER_W3D3)),
                "an occurrence of another offering is not an option domain");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.options(TEACHER_C, Long.toString(OFFERING_MAIN),
                        Long.toString(OCC_MAIN_W3D2)),
                "an unrelated teacher cannot read options");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.options(ASSISTANT, Long.toString(OFFERING_MAIN),
                        Long.toString(OCC_MAIN_W3D2)),
                "an offering assistant cannot read options");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.options(TEACHER_A, "945399", Long.toString(OCC_MAIN_W3D2)),
                "a missing offering is not visible");
        require(service.options(TEACHER_B, Long.toString(OFFERING_MAIN),
                        Long.toString(OCC_SHARED_W3D4)) != null,
                "a teacher present through the current arrangement may read their own occurrence");
    }

    // ----------------------------------------------------- submit, replay, digest

    private static void verifySubmitCleanAndReplay(TeacherAdjustmentApplicationService service)
            throws Exception {
        String operationId = op(1);
        String targetDate = localDate(3, 5);
        TeacherAdjustmentWriteDTO request = write(operationId, OFFERING_MAIN,
                List.of(target(OCC_MAIN_W3D2, targetDate)), 3, 4, ROOM_MAIN, "教师出差");

        TeacherAdjustmentPreviewDTO preview = service.preview(TEACHER_A, request);
        require(preview.getConflicts().isEmpty() && preview.isCanSubmit(),
                "a free target slot previews without conflicts (observed "
                        + describe(preview.getConflicts()) + ")");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                service.submit(TEACHER_A, request);
        require(!result.isReplayed() && "调课申请已提交".equals(result.getMessage()),
                "a first submit is not a replay");
        AdjustmentRequestDetailDTO detail = result.getValue();
        require(detail.getStatus() == PENDING && detail.getVersion() == 1
                        && Long.toString(OFFERING_MAIN).equals(detail.getOfferingId())
                        && TEACHER_A.equals(detail.getApplicantUid())
                        && "教师出差".equals(detail.getReason()),
                "the stored request head maps back to the teacher");
        require(detail.getNewDayOfWeek() == 5 && detail.getNewStartPeriod() == 3
                        && detail.getNewEndPeriod() == 4 && detail.getNewClassroom() != null
                        && Long.toString(ROOM_MAIN).equals(detail.getNewClassroom().getResourceId())
                        && detail.getNewTeacher() == null && detail.getNewAssistant() == null,
                "the request keeps the server-side derived teacher/assistant and stores the room");
        require(detail.getTargets().size() == 1
                        && targetDate.equals(detail.getTargets().get(0).getTargetDate())
                        && detail.getTargets().get(0).getWeek() == 3
                        && Long.toString(OCC_MAIN_W3D2)
                        .equals(detail.getTargets().get(0).getOriginalOccurrenceId()),
                "the target carries its explicit calendar date");
        require(instantText(CLOCK_TEXT).equals(detail.getSubmittedAt())
                        && detail.getReviewedBy() == null && detail.getReviewedAt() == null,
                "the submit stamps the injected clock and stays undecided");
        cleanRequestId = Long.parseLong(detail.getRequestId());

        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + cleanRequestId + " AND offering_id=" + OFFERING_MAIN
                        + " AND requested_by='" + TEACHER_A + "' AND reason='教师出差'"
                        + " AND version=1 AND status='PENDING' AND new_weekday=5"
                        + " AND new_start_period=3 AND new_end_period=4"
                        + " AND new_classroom_id=" + ROOM_MAIN
                        + " AND new_teacher_uid IS NULL AND new_assistant_uid IS NULL"
                        + " AND submitted_at='" + CLOCK_TEXT + "'") == 1,
                "the request row is written exactly as validated");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE request_id="
                        + cleanRequestId + " AND original_occurrence_id=" + OCC_MAIN_W3D2
                        + " AND original_week_no=3"
                        + " AND original_start_at='" + utcText(localDate(3, 2), "08:00:00") + "'"
                        + " AND original_end_at='" + utcText(localDate(3, 2), "09:30:00") + "'"
                        + " AND original_teacher_uid='" + TEACHER_A + "'"
                        + " AND original_classroom_id=" + ROOM_MAIN
                        + " AND target_calendar_date_id=" + dateId(3, 5)) == 1,
                "the target snapshots the occurrence and stores the real calendar date id");
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER_A + "' AND operation_id='" + operationId
                        + "' AND action='submitAdjustment' AND target_type='ADJUSTMENT_REQUEST'"
                        + " AND target_id='" + cleanRequestId + "' AND result_code='OK'"
                        + " AND CHAR_LENGTH(request_digest)=64 AND response_json IS NOT NULL") == 1,
                "the submit logs one operation row with the canonical digest");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replay =
                service.submit(TEACHER_A, write(operationId, OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, targetDate)), 3, 4, ROOM_MAIN, "教师出差"));
        require(replay.isReplayed()
                        && Long.toString(cleanRequestId).equals(replay.getValue().getRequestId())
                        && replay.getValue().getVersion() == 1,
                "the same operationId and digest replays the stored response");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + cleanRequestId) == 1
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE request_id="
                        + cleanRequestId) == 1
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER_A + "' AND operation_id='" + operationId + "'") == 1,
                "a replay duplicates no request, target or operation row");

        expect(TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.submit(TEACHER_A, write(operationId, OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, targetDate)), 3, 4, ROOM_MAIN, "改过的理由")),
                "the same operationId with different content is a summary conflict");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE requested_by='"
                        + TEACHER_A + "' AND reason='改过的理由'") == 0,
                "a summary conflict writes nothing");
    }

    // -------------------------------------------------------------- validation

    private static void verifyValidationFailures(TeacherAdjustmentApplicationService service)
            throws Exception {
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write(op(2), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "  ")),
                "a submit without a reason is rejected");
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write(op(3), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, null)),
                "a null reason is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(4), OFFERING_MAIN,
                        List.of(), 3, 4, ROOM_MAIN, "教师出差")),
                "an empty target list is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(5), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5)),
                                target(OCC_MAIN_W3D2, localDate(3, 4))),
                        3, 4, ROOM_MAIN, "教师出差")),
                "the same occurrence twice in one request is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(6), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, "下周三")), 3, 4, ROOM_MAIN, "教师出差")),
                "a non-ISO target date is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(7), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, null)), 3, 4, ROOM_MAIN, "教师出差")),
                "a target without a date is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(8), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 4, 3, ROOM_MAIN, "教师出差")),
                "an inverted period range is rejected");
        expect(IllegalArgumentException.class,
                () -> service.preview(TEACHER_A, write(op(9), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 0, 1, ROOM_MAIN, "教师出差")),
                "period zero is rejected");
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write("op-not-a-uuid", OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "a submit operationId must be a UUID");
        expect(IllegalArgumentException.class,
                () -> service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(
                        "op-not-a-uuid", Long.toString(cleanRequestId), 1)),
                "a withdraw operationId must be a UUID");

        // The same date, periods and room as the original position is not an adjustment.
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write(op(10), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 2))), 1, 2, null, "教师出差")),
                "an unchanged position is rejected");
        // A past target date cannot be requested.
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write(op(11), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(1, 2))), 3, 4, ROOM_MAIN, "教师出差")),
                "a target date before the clock is rejected");
        // An original occurrence that already started cannot be requested either.
        expect(IllegalArgumentException.class,
                () -> service.submit(TEACHER_A, write(op(12), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W1D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "an original occurrence in the past is rejected");
        int requests = count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                + " requested_by='" + TEACHER_A + "' AND request_id NOT IN (" + REQUEST_APPROVED
                + "," + LEGACY_REQUEST + ")");
        require(requests == 1,
                "every validation failure leaves the single clean request untouched (observed "
                        + requests + " rows: " + text("SELECT GROUP_CONCAT(CONCAT(request_id,':',"
                        + "reason) ORDER BY request_id) FROM course_schedule_adjustment_request"
                        + " WHERE requested_by='" + TEACHER_A + "'") + ")");
    }

    // --------------------------------------------------------------- ownership

    private static void verifyOwnership(TeacherAdjustmentApplicationService service) {
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.preview(TEACHER_B, write(op(13), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "a co-teacher cannot act on an occurrence they do not teach");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.preview(TEACHER_C, write(op(14), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "an unrelated teacher cannot submit");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.preview(ASSISTANT, write(op(15), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D2, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "an offering assistant cannot submit");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.submit(TEACHER_A, write(op(16), OFFERING_MAIN,
                        List.of(target(OCC_OTHER_W3D3, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差")),
                "an occurrence of another offering is out of scope even for its own teacher");

        TeacherAdjustmentPreviewDTO coTeacher = service.preview(TEACHER_B,
                write(op(17), OFFERING_MAIN,
                        List.of(target(OCC_SHARED_W3D4, localDate(3, 5))), 3, 4, ROOM_SPARE, "教师出差"));
        require(coTeacher.getConflicts().isEmpty() && coTeacher.isCanSubmit(),
                "a teacher present through the current arrangement may act on their own occurrence"
                        + " (observed " + describe(coTeacher.getConflicts()) + ")");
    }

    // -------------------------------------------------- active/pending targets

    private static void verifyActiveAdjustedTarget(TeacherAdjustmentApplicationService service)
            throws Exception {
        TeacherAdjustmentWriteDTO request = write(op(18), OFFERING_MAIN,
                List.of(target(OCC_MAIN_W4D2, localDate(4, 5))), 3, 4, ROOM_SPARE, "教师出差");
        TeacherAdjustmentPreviewDTO preview = service.preview(TEACHER_A, request);
        require(!preview.isCanSubmit() && preview.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentApprovalService.TARGET_ADJUSTED.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "an occurrence with an ACTIVE adjustment previews as a typed blocking conflict"
                        + " (observed " + describe(preview.getConflicts()) + ")");
        TeacherAdjustmentApplicationService.ConflictException refusal = expect(
                TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.submit(TEACHER_A, request),
                "an occurrence with an ACTIVE adjustment cannot be requested again");
        require(refusal.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentApprovalService.TARGET_ADJUSTED.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "the refusal carries the typed target-adjustment conflict");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE"
                        + " original_occurrence_id=" + OCC_MAIN_W4D2 + " AND request_id<>"
                        + REQUEST_APPROVED) == 0,
                "the refused request writes no target");
    }

    private static void verifyPendingDuplicateTarget(TeacherAdjustmentApplicationService service)
            throws Exception {
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> first = service.submit(TEACHER_A,
                write(op(19), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W2D2, localDate(4, 5))), 3, 4, ROOM_MAIN, "教师出差"));
        pendingRequestId = Long.parseLong(first.getValue().getRequestId());

        TeacherAdjustmentApplicationService.ConflictException duplicate = expect(
                TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.submit(TEACHER_A, write(op(20), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W2D2, localDate(3, 5))), 1, 2, ROOM_SPARE, "教师出差")),
                "a second PENDING request for the same occurrence is refused at submit");
        require(duplicate.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentApprovalService.TARGET_ADJUSTED.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "the duplicate pending target is reported as the target-adjustment family");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE"
                        + " original_occurrence_id=" + OCC_MAIN_W2D2) == 1,
                "the refused duplicate leaves exactly one pending target");
    }

    /**
     * The term's published plan can disappear after submission. The request is still the teacher's
     * own row, so the detail (and the version withdraw needs) must stay readable and only the fresh
     * conflict snapshot degrades to empty, never to BAD_REQUEST.
     */
    private static void verifyDetailSurvivesUnavailablePlan(
            TeacherAdjustmentApplicationService service) throws Exception {
        execute("UPDATE schedule_plan SET status='DRAFT' WHERE id=" + PLAN);
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id=" + PLAN
                        + " AND status='DRAFT'") == 1,
                "the fixture plan must actually be DRAFT, otherwise the unavailable-plan"
                        + " assertions below would pass vacuously");
        try {
            AdjustmentRequestDetailDTO detail = service.get(TEACHER_A,
                    Long.toString(pendingRequestId));
            require(detail.getStatus() == PENDING && detail.getVersion() == 1
                            && Long.toString(pendingRequestId).equals(detail.getRequestId())
                            && detail.getTargets().size() == 1,
                    "a pending detail stays readable while the term has no published plan");
            require(detail.getConflicts().isEmpty(),
                    "the unverifiable conflict snapshot degrades to empty, never an error");
            require(containsId(service.listMine(TEACHER_A, AdjustmentRequestStatusDTO.PENDING, 1, 100),
                            pendingRequestId),
                    "the pending list still shows the request without a published plan");
            // The version the detail still exposes is exactly what withdraw needs.
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> withdrawn = service.withdraw(
                    TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(36),
                            Long.toString(pendingRequestId), detail.getVersion()));
            require(withdrawn.getValue().getStatus() == WITHDRAWN,
                    "the readable version still allows withdrawing without a published plan");
        } finally {
            execute("UPDATE schedule_plan SET status='PUBLISHED' WHERE id=" + PLAN);
        }
    }

    /**
     * A legacy target without an explicit V006 date resolves through
     * {@code original_week_no + new_weekday}; when that lands on a non-teaching day the approval is
     * a blocking slot conflict and the teacher detail reports it too. Requiring a teaching day is
     * the documented semantics, so this pins the behaviour instead of inheriting it by accident.
     */
    private static void verifyLegacyTargetDate(TeacherAdjustmentApplicationService service)
            throws Exception {
        AdjustmentRequestDetailDTO detail = service.get(TEACHER_A, Long.toString(LEGACY_REQUEST));
        require(detail.getStatus() == PENDING && detail.getVersion() == 1
                        && detail.getTargets().size() == 1
                        && detail.getTargets().get(0).getTargetDate() == null
                        && detail.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentConflictService.SLOT_INVALID.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "a legacy target maps in the teacher detail and reports its blocking slot conflict"
                        + " (observed " + describe(detail.getConflicts()) + ")");

        ScheduleAdjustmentApprovalService approvals = approvals();
        ScheduleAdjustmentApprovalService.ConflictException refusal = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> approvals.review(ADMIN, new ApprovalDecisionRequestDTO(op(37),
                        Long.toString(LEGACY_REQUEST), 1, true, false, null, null)),
                "a legacy target resolving to a non-teaching day is not approvable");
        require(refusal.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentApprovalService.SLOT_INVALID.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity() && conflict.getWeek() == 2),
                "the refusal carries the blocking slot conflict (observed "
                        + describe(refusal.getConflicts()) + ")");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + LEGACY_REQUEST) == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                        + " request_id=" + LEGACY_REQUEST + " AND status='PENDING' AND version=1") == 1,
                "the refused legacy approval writes nothing");
    }

    private static void verifyDateDomain(TeacherAdjustmentApplicationService service) throws Exception {
        TeacherAdjustmentPreviewDTO nonTeaching = service.preview(TEACHER_A,
                write(op(21), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, "2026-09-19")), 3, 4, ROOM_MAIN, "教师出差"));
        require(!nonTeaching.isCanSubmit() && nonTeaching.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentConflictService.SLOT_INVALID.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "a non-teaching date is a blocking slot conflict (observed "
                        + describe(nonTeaching.getConflicts()) + ")");
        TeacherAdjustmentPreviewDTO unknown = service.preview(TEACHER_A,
                write(op(22), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, "2026-12-31")), 3, 4, ROOM_MAIN, "教师出差"));
        require(!unknown.isCanSubmit() && unknown.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentConflictService.SLOT_INVALID.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "a date outside the teaching calendar is a blocking slot conflict");
        TeacherAdjustmentPreviewDTO outOfRange = service.preview(TEACHER_A,
                write(op(23), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(3, 5))), 9, 10, ROOM_MAIN, "教师出差"));
        require(!outOfRange.isCanSubmit() && outOfRange.getConflicts().stream().anyMatch(conflict ->
                        ScheduleAdjustmentConflictService.SLOT_INVALID.equals(conflict.getType())
                                && BLOCKING == conflict.getSeverity()),
                "a period the calendar cannot place is a blocking slot conflict");
        TeacherAdjustmentPreviewDTO small = service.preview(TEACHER_A,
                write(op(24), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(3, 3))), 3, 4, ROOM_SMALL, "教师出差"));
        require(!small.isCanSubmit() && small.getConflicts().stream().anyMatch(conflict ->
                        "CLASSROOM_CAPACITY".equals(conflict.getType())
                                && OVERRIDABLE == conflict.getSeverity()),
                "an overridable capacity conflict still blocks a teacher submit without force"
                        + " (observed " + describe(small.getConflicts()) + ")");
        expect(TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.submit(TEACHER_A, write(op(35), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(3, 3))), 3, 4, ROOM_SMALL, "教师出差")),
                "a teacher cannot force even an overridable conflict");

        // A preview ignores the operationId entirely.
        TeacherAdjustmentPreviewDTO ignored = service.preview(TEACHER_A,
                write("ignored-preview-id", OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(3, 5))), 3, 4, ROOM_MAIN, "教师出差"));
        require(ignored.isCanSubmit(),
                "preview ignores the operationId (observed " + describe(ignored.getConflicts()) + ")");
    }

    // --------------------------------------------------------------- withdraw

    private static void verifyWithdraw(TeacherAdjustmentApplicationService service) throws Exception {
        expect(TeacherAdjustmentApplicationService.NotFoundException.class,
                () -> service.withdraw(TEACHER_B, new WithdrawTeacherAdjustmentRequestDTO(op(25),
                        Long.toString(cleanRequestId), 1)),
                "another teacher's request is not visible");
        expect(TeacherAdjustmentApplicationService.NotFoundException.class,
                () -> service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(26),
                        "945899", 1)),
                "a missing request is not found");
        TeacherAdjustmentApplicationService.ConflictException stale = expect(
                TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(27),
                        Long.toString(cleanRequestId), 5)),
                "a stale version conflicts");
        require(stale.getEntity() != null && stale.getEntity().getStatus() == PENDING
                        && stale.getEntity().getVersion() == 1,
                "the stale conflict carries the latest visible request");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result = service.withdraw(TEACHER_A,
                new WithdrawTeacherAdjustmentRequestDTO(op(28), Long.toString(cleanRequestId), 1));
        require(!result.isReplayed() && result.getValue().getStatus() == WITHDRAWN
                        && result.getValue().getVersion() == 2,
                "withdrawing a pending request advances it to WITHDRAWN");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + cleanRequestId + " AND status='WITHDRAWN' AND version=2"
                        + " AND withdrawn_at='" + CLOCK_TEXT + "'"
                        + " AND reviewed_at IS NULL AND reviewed_by IS NULL") == 1,
                "the withdraw writes withdrawn_at and never fakes a review");
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER_A + "' AND operation_id='" + op(28)
                        + "' AND action='withdrawAdjustment' AND target_type='ADJUSTMENT_REQUEST'"
                        + " AND target_id='" + cleanRequestId + "' AND result_code='OK'") == 1,
                "the withdraw logs its operation");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replay = service.withdraw(TEACHER_A,
                new WithdrawTeacherAdjustmentRequestDTO(op(28), Long.toString(cleanRequestId), 1));
        require(replay.isReplayed() && replay.getValue().getStatus() == WITHDRAWN
                        && replay.getValue().getVersion() == 2,
                "the same withdraw operation replays the stored response");
        expect(TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(28),
                        Long.toString(pendingRequestId), 1)),
                "a reused withdraw operationId with another request conflicts");

        TeacherAdjustmentApplicationService.ConflictException terminal = expect(
                TeacherAdjustmentApplicationService.ConflictException.class,
                () -> service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(29),
                        Long.toString(cleanRequestId), 2)),
                "a withdrawn request is terminal");
        require(terminal.getEntity() != null && terminal.getEntity().getStatus() == WITHDRAWN,
                "the terminal conflict carries the withdrawn entity");
    }

    private static void verifyWithdrawnIsNotApprovable() throws Exception {
        ScheduleAdjustmentApprovalService approvals = approvals();
        expect(ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> approvals.review(ADMIN, new ApprovalDecisionRequestDTO(op(30),
                        Long.toString(cleanRequestId), 2, true, false, null, null)),
                "an administrator cannot approve a withdrawn request");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + cleanRequestId) == 0,
                "the refused approval writes no adjustment");
    }

    // ------------------------------------------------------- injected failure

    private static void verifyInjectedFailureRollback() throws Exception {
        TeacherAdjustmentApplicationService failing = service(new FailingTargetDao());
        String operationId = op(31);
        expect(DatabaseException.class,
                () -> failing.submit(TEACHER_A, write(operationId, OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(3, 5))), 1, 2, ROOM_MAIN, "回滚夹具")),
                "an injected failure after the request insert surfaces as a database failure");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE requested_by='"
                        + TEACHER_A + "' AND reason='回滚夹具'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE"
                        + " original_occurrence_id=" + OCC_MAIN_W3D1) == 0
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER_A + "' AND operation_id='" + operationId + "'") == 0,
                "the failed submit rolls the request, its targets and the operation log back");
    }

    // ------------------------------------------------- concurrent duplicate submit

    /**
     * Two identical submits (same teacher, same operationId, same payload) race on the operation
     * log primary key: exactly one commits and the other must recover through
     * {@code auditOrRecover} into a replay of the winner's stored result, not a driver error and
     * not a second request.
     */
    private static void verifyConcurrentDuplicateSubmit(TeacherAdjustmentApplicationService service)
            throws Exception {
        String operationId = op(38);
        TeacherAdjustmentWriteDTO request = write(operationId, OFFERING_MAIN,
                List.of(target(OCC_MAIN_W4D1, localDate(4, 5))), 3, 4, ROOM_MAIN, "并发提交夹具");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>> results =
                Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = new ArrayList<>();
        Runnable attempt = () -> {
            try {
                barrier.await();
                results.add(service.submit(TEACHER_A, request));
            } catch (Throwable other) {
                synchronized (unexpected) {
                    unexpected.add(other);
                }
            }
        };
        Thread first = new Thread(attempt, "duplicate-submit-a");
        Thread second = new Thread(attempt, "duplicate-submit-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(),
                "a concurrent identical submit must not surface a driver error (" + unexpected + ")");
        require(results.size() == 2, "both concurrent submits return a result");
        long requestIds = results.stream()
                .map(result -> result.getValue().getRequestId()).distinct().count();
        long committed = results.stream().filter(result -> !result.isReplayed()).count();
        long replayed = results.stream().filter(TeacherOperationResultDTO::isReplayed).count();
        require(requestIds == 1 && committed == 1 && replayed == 1,
                "one concurrent submit commits and the other replays the winner's result (observed "
                        + results.stream().map(result -> result.getValue().getRequestId()
                        + "/replayed=" + result.isReplayed()).toList() + ")");
        long requestId = Long.parseLong(results.get(0).getValue().getRequestId());
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE requested_by='"
                        + TEACHER_A + "' AND reason='并发提交夹具'") == 1
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE"
                        + " request_id=" + requestId) == 1
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER_A + "' AND operation_id='" + operationId + "'") == 1,
                "the race leaves exactly one request, one target and one operation row");
    }

    /** Overridable write hook proves the whole submit rolls back, not only the first insert. */
    private static final class FailingTargetDao extends TeacherAdjustmentDAO {
        @Override
        public long insertTarget(Connection connection, long requestId, long calendarDateId,
                                 long occurrenceId, int weekNo, Timestamp startAt, Timestamp endAt,
                                 String teacherUid, String assistantUid, Long classroomId)
                throws SQLException {
            throw new SQLException("injected target failure");
        }
    }

    // ------------------------------------------------------- listMine and get

    private static void verifyListMineAndGet(TeacherAdjustmentApplicationService service)
            throws Exception {
        expect(IllegalArgumentException.class, () -> service.listMine(TEACHER_A, null, 0, 10),
                "page 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listMine(TEACHER_A, null, 1, 0),
                "size 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listMine(TEACHER_A, null, 1, 101),
                "size above 100 is rejected");
        require(service.listMine(ASSISTANT, null, 1, 10).getItems().stream().noneMatch(summary ->
                        Long.toString(pendingRequestId).equals(summary.getRequestId())
                                || Long.toString(cleanRequestId).equals(summary.getRequestId())),
                "another account's application list never exposes these requests");
        expect(IllegalArgumentException.class, () -> service.get(TEACHER_A, "abc"),
                "a non-decimal request id is rejected");
        expect(TeacherAdjustmentApplicationService.NotFoundException.class,
                () -> service.get(TEACHER_A, "945899"), "a missing request is not found");
        expect(TeacherAdjustmentApplicationService.NotFoundException.class,
                () -> service.get(TEACHER_B, Long.toString(cleanRequestId)),
                "another teacher's request detail is never visible");

        TeacherPageDTO<AdjustmentRequestSummaryDTO> pending =
                service.listMine(TEACHER_A, AdjustmentRequestStatusDTO.PENDING, 1, 100);
        require(pending.getItems().stream().allMatch(summary ->
                        summary.getStatus() == PENDING && TEACHER_A.equals(summary.getApplicantUid()))
                        && containsId(pending, pendingRequestId),
                "the pending list is scoped to the requester (observed "
                        + ids(pending) + ")");
        TeacherPageDTO<AdjustmentRequestSummaryDTO> withdrawn =
                service.listMine(TEACHER_A, WITHDRAWN, 1, 100);
        require(containsId(withdrawn, cleanRequestId), "the withdrawn request appears under its status");
        TeacherPageDTO<AdjustmentRequestSummaryDTO> foreign =
                service.listMine(TEACHER_B, null, 1, 100);
        require(foreign.getItems().stream().noneMatch(summary ->
                        Long.toString(pendingRequestId).equals(summary.getRequestId())
                                || Long.toString(cleanRequestId).equals(summary.getRequestId())),
                "another teacher's list never contains these requests");

        AdjustmentRequestDetailDTO withdrawnDetail = service.get(TEACHER_A,
                Long.toString(cleanRequestId));
        require(withdrawnDetail.getStatus() == WITHDRAWN && withdrawnDetail.getVersion() == 2
                        && withdrawnDetail.getTargets().size() == 1
                        && localDate(3, 5).equals(withdrawnDetail.getTargets().get(0).getTargetDate())
                        && withdrawnDetail.getConflicts().isEmpty(),
                "a terminal request maps back without fresh conflicts");
        AdjustmentRequestDetailDTO pendingDetail = service.get(TEACHER_A,
                Long.toString(pendingRequestId));
        require(pendingDetail.getStatus() == PENDING && pendingDetail.getTargets().size() == 1
                        && localDate(4, 5).equals(pendingDetail.getTargets().get(0).getTargetDate())
                        && pendingDetail.getNewClassroom() != null
                        && Long.toString(ROOM_MAIN).equals(
                        pendingDetail.getNewClassroom().getResourceId()),
                "a pending request detail maps its explicit target date and proposed room");
        require(pendingDetail.getConflicts().isEmpty(),
                "a pending request over a free slot reports no fresh conflicts (observed "
                        + describe(pendingDetail.getConflicts()) + ")");
    }

    // ------------------------------------------------------- withdraw vs approve

    private static void verifyWithdrawApproveRace(TeacherAdjustmentApplicationService service)
            throws Exception {
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> submitted = service.submit(TEACHER_A,
                write(op(32), OFFERING_MAIN,
                        List.of(target(OCC_MAIN_W3D1, localDate(4, 5))), 3, 4, ROOM_MAIN, "竞争夹具"));
        long requestId = Long.parseLong(submitted.getValue().getRequestId());

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> winner = new AtomicReference<>();
        AtomicReference<Throwable> refusal = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Thread withdraw = new Thread(() -> {
            try {
                barrier.await();
                service.withdraw(TEACHER_A, new WithdrawTeacherAdjustmentRequestDTO(op(33),
                        Long.toString(requestId), 1));
                winner.set("WITHDRAW");
            } catch (TeacherAdjustmentApplicationService.ConflictException expected) {
                refusal.set(expected);
            } catch (Throwable other) {
                synchronized (unexpected) {
                    unexpected.add(other);
                }
            }
        }, "teacher-withdraw");
        Thread approve = new Thread(() -> {
            try {
                barrier.await();
                approvals().review(ADMIN, new ApprovalDecisionRequestDTO(op(34),
                        Long.toString(requestId), 1, true, false, null, null));
                winner.set("APPROVE");
            } catch (ScheduleAdjustmentApprovalService.ConflictException expected) {
                refusal.set(expected);
            } catch (Throwable other) {
                synchronized (unexpected) {
                    unexpected.add(other);
                }
            }
        }, "admin-approve");
        withdraw.start();
        approve.start();
        withdraw.join();
        approve.join();

        require(unexpected.isEmpty(),
                "both racing actors only observe a success or a typed conflict (" + unexpected + ")");
        require(refusal.get() != null && winner.get() != null,
                "exactly one of the racing state transitions wins");
        if ("WITHDRAW".equals(winner.get())) {
            require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                            + requestId + " AND status='WITHDRAWN' AND withdrawn_at='" + CLOCK_TEXT
                            + "' AND reviewed_at IS NULL") == 1
                            && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                            + requestId) == 0
                            && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                            + requestId) == 0,
                    "the winning withdraw leaves a withdrawn request and no approval side effects");
        } else {
            require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                            + requestId + " AND status='APPROVED' AND reviewed_at='" + CLOCK_TEXT
                            + "' AND withdrawn_at IS NULL") == 1
                            && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                            + requestId + " AND status='ACTIVE'") == 1
                            && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                            + requestId + " AND notice_type='RESCHEDULED'") == 1,
                    "the winning approval writes the adjustment and its single notice");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static TeacherAdjustmentApplicationService service(TeacherAdjustmentDAO dao) {
        return new TeacherAdjustmentApplicationService(dao, new ScheduleAdjustmentDAO(),
                new TeacherCourseOperationDAO(), new ScheduleAdjustmentConflictService(), CLOCK);
    }

    private static ScheduleAdjustmentApprovalService approvals() {
        return new ScheduleAdjustmentApprovalService(new ScheduleAdjustmentDAO(),
                new AdminCourseOperationDAO(), new ScheduleAdjustmentConflictService(), CLOCK);
    }

    private static TeacherAdjustmentWriteDTO write(String operationId, long offeringId,
            List<TeacherAdjustmentTargetInputDTO> targets, int startPeriod, int endPeriod,
            Long classroomId, String reason) {
        return new TeacherAdjustmentWriteDTO(operationId, Long.toString(offeringId), targets,
                startPeriod, endPeriod, classroomId == null ? null : Long.toString(classroomId),
                reason);
    }

    private static TeacherAdjustmentTargetInputDTO target(long occurrenceId, String date) {
        return new TeacherAdjustmentTargetInputDTO(Long.toString(occurrenceId), date);
    }

    private static boolean containsId(TeacherPageDTO<AdjustmentRequestSummaryDTO> page, long requestId) {
        return ids(page).contains(Long.toString(requestId));
    }

    private static List<String> ids(TeacherPageDTO<AdjustmentRequestSummaryDTO> page) {
        List<String> values = new ArrayList<>();
        for (AdjustmentRequestSummaryDTO summary : page.getItems()) {
            values.add(summary.getRequestId());
        }
        return values;
    }

    private static String describe(List<ScheduleConflictDTO> conflicts) {
        List<String> values = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            values.add(conflict.getType() + "/w" + conflict.getWeek() + "/d"
                    + conflict.getDayOfWeek() + "/p" + conflict.getStartPeriod() + "-"
                    + conflict.getEndPeriod() + "/subject=" + conflict.getSubjectId() + "/related="
                    + conflict.getRelatedOfferingId() + "/" + conflict.getSeverity());
        }
        return values.toString();
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_A + "','Adj945 Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Adj945 Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_C + "','Adj945 Teacher C','x','x',1,'Engineering','Professor'),"
                + "('" + ASSISTANT + "','Adj945 Assistant','x','x',1,'Engineering','Assistant'),"
                + "('" + ADMIN + "','Adj945 Admin','x','x',0,'Administration','Registrar')");

        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(945201,'ADJ945A','Adjustment 945 Course A',3.00,48,1,"
                + "'ACTIVE'),(945202,'ADJ945B','Adjustment 945 Course B',3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'ADJ945-A',945201,2026,3,"
                + "30,2),(" + OFFERING_OTHER + ",'ADJ945-B',945202,2026,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_MAIN
                + ",'" + TEACHER_A + "',0),(" + OFFERING_MAIN + ",'" + ASSISTANT + "',1),("
                + OFFERING_OTHER + ",'" + TEACHER_B + "',0)");

        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR + ",'Adjustment 945 calendar',"
                + "2026,3,'" + WEEK1_START + "','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO day_template(id,name,version) VALUES(" + TEMPLATE
                + ",'Adjustment 945 template',1)");
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(945010," + TEMPLATE + ",1,'08:00:00','08:45:00'),"
                + "(945011," + TEMPLATE + ",2,'08:45:00','09:30:00'),"
                + "(945012," + TEMPLATE + ",3,'09:30:00','10:15:00'),"
                + "(945013," + TEMPLATE + ",4,'10:15:00','11:00:00')");
        for (int week = 1; week <= 4; week++) {
            for (int day = 1; day <= 5; day++) {
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                        + "day_template_id,is_teaching_day) VALUES(" + dateId(week, day) + ","
                        + CALENDAR + ",'" + localDate(week, day) + "'," + week + "," + day + ","
                        + TEMPLATE + ",1)");
            }
        }
        execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                + "day_template_id,is_teaching_day) VALUES(" + NON_TEACHING_DATE + "," + CALENDAR
                + ",'2026-09-19',2,6," + TEMPLATE + ",0)");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,updated_at)"
                + " VALUES(" + PLAN + ",'Adjustment 945 published plan'," + CALENDAR
                + ",1,'PUBLISHED','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN + " WHERE id="
                + CALENDAR);

        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(" + ROOM_MAIN
                + ",'Adj945 Room Main',60,1),(" + ROOM_SMALL + ",'Adj945 Room Small',10,1),("
                + ROOM_SPARE + ",'Adj945 Room Spare',60,1),(" + ROOM_OTHER
                + ",'Adj945 Room Other',60,1)");

        arrangement(ARRANGEMENT_MAIN, OFFERING_MAIN, TEACHER_A, null, ROOM_MAIN);
        arrangement(ARRANGEMENT_SHARED, OFFERING_MAIN, TEACHER_B, null, ROOM_SPARE);
        arrangement(ARRANGEMENT_OTHER, OFFERING_OTHER, TEACHER_B, null, ROOM_OTHER);
        rule(RULE_MAIN_TUE, OFFERING_MAIN, ARRANGEMENT_MAIN, 2, 1, 2);
        rule(RULE_SHARED_THU, OFFERING_MAIN, ARRANGEMENT_SHARED, 4, 1, 2);
        rule(RULE_OTHER_WED, OFFERING_OTHER, ARRANGEMENT_OTHER, 3, 3, 4);
        rule(RULE_MAIN_MON_LATE, OFFERING_MAIN, ARRANGEMENT_MAIN, 1, 3, 4);

        for (int week = 1; week <= 4; week++) {
            occur(945600L + week, RULE_MAIN_TUE, TEACHER_A, null, ROOM_MAIN, week, 2, 1, 2);
            occur(945610L + week, RULE_SHARED_THU, TEACHER_B, null, ROOM_SPARE, week, 4, 1, 2);
            occur(945620L + week, RULE_OTHER_WED, TEACHER_B, null, ROOM_OTHER, week, 3, 3, 4);
            occur(945630L + week, RULE_MAIN_MON_LATE, TEACHER_A, null, ROOM_MAIN, week, 1, 3, 4);
        }

        // An already approved request leaves one ACTIVE adjustment on occurrence 945604, so a new
        // request for it must be refused with the existing TARGET_ADJUSTED family.
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,"
                + "reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_classroom_id,submitted_at,reviewed_by,reviewed_at,review_comment) VALUES("
                + REQUEST_APPROVED + "," + OFFERING_MAIN + ",'" + TEACHER_A + "','历史调课',1,"
                + "'APPROVED',5,1,2," + ROOM_MAIN + ",'2026-09-10 05:00:00','" + ADMIN
                + "','2026-09-11 05:00:00','同意')");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(" + ADJUSTMENT_ACTIVE + "," + REQUEST_APPROVED + ","
                + OCC_MAIN_W4D2 + ",'" + utcText(localDate(4, 5), "08:00:00") + "','"
                + utcText(localDate(4, 5), "09:30:00") + "','" + TEACHER_A + "',NULL," + ROOM_MAIN
                + ",'ACTIVE')");

        // A legacy target without V006 target_calendar_date_id: its day is derived from
        // original_week_no + request.new_weekday (week 2, Saturday) and lands on the calendar's
        // non-teaching day, which must be refused instead of silently approved.
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,"
                + "reason,version,status,new_weekday,new_start_period,new_end_period,submitted_at)"
                + " VALUES(" + LEGACY_REQUEST + "," + OFFERING_MAIN + ",'" + TEACHER_A
                + "','历史目标夹具',1,'PENDING',6,3,4,'2026-09-10 06:00:00')");
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES("
                + LEGACY_TARGET + "," + LEGACY_REQUEST + "," + OCC_MAIN_W2D1 + ",2,'"
                + utcText(localDate(2, 1), "09:30:00") + "','" + utcText(localDate(2, 1), "11:00:00")
                + "','" + TEACHER_A + "',NULL," + ROOM_MAIN + ")");
    }

    private static void arrangement(long arrangementId, long offeringId, String teacher,
                                    String assistant, long classroomId) throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,assistant_uid,classroom_id,status,version) VALUES(" + arrangementId
                + "," + PLAN + "," + offeringId + ",'" + teacher + "'," + sql(assistant) + ","
                + classroomId + ",'ACTIVE',1)");
    }

    private static void rule(long ruleId, long offeringId, long arrangementId, int weekday,
                             int startPeriod, int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + ruleId + "," + PLAN + ","
                + offeringId + "," + arrangementId + "," + weekday + "," + startPeriod + ","
                + endPeriod + ",'ACTIVE')");
    }

    private static void occur(long occurrenceId, long ruleId, String teacher, String assistant,
                              long classroomId, int week, int weekday, int startPeriod,
                              int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + ruleId + ","
                + week + ")");
        String date = localDate(week, weekday);
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + occurrenceId + "," + ruleId + "," + PLAN + ",'"
                + utcText(date, periodTime(startPeriod, true)) + "','"
                + utcText(date, periodTime(endPeriod, false)) + "'," + week + "," + weekday + ")");
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + REQUESTER_PREFIX
                + "%'");
        // The withdraw-versus-approval race runs the administrator approval service too, and its
        // audit row references tbl_user, so it must go before the fixture users are deleted.
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM course_notice WHERE adjustment_request_id IN (SELECT request_id FROM"
                + " course_schedule_adjustment_request WHERE requested_by LIKE '" + REQUESTER_PREFIX
                + "%')");
        execute("DELETE FROM course_schedule_adjustment WHERE request_id IN (SELECT request_id FROM"
                + " course_schedule_adjustment_request WHERE requested_by LIKE '" + REQUESTER_PREFIX
                + "%')");
        execute("DELETE FROM course_schedule_adjustment_target WHERE request_id IN (SELECT"
                + " request_id FROM course_schedule_adjustment_request WHERE requested_by LIKE '"
                + REQUESTER_PREFIX + "%')");
        execute("DELETE FROM course_schedule_adjustment_request WHERE requested_by LIKE '"
                + REQUESTER_PREFIX + "%'");
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 945000 AND 945099");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 945500 AND 945599");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 945000 AND 945099");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 945000 AND 945099");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=" + CALENDAR);
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 945000 AND 945099");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 945300"
                + " AND 945399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 945300 AND 945399");
        execute("DELETE FROM course WHERE course_id BETWEEN 945200 AND 945299");
        execute("DELETE FROM classroom WHERE id BETWEEN 945100 AND 945199");
        execute("DELETE FROM calendar_date WHERE calendar_id BETWEEN 945000 AND 945099");
        execute("DELETE FROM period_definition WHERE id BETWEEN 945000 AND 945099");
        execute("DELETE FROM day_template WHERE id BETWEEN 945000 AND 945099");
        execute("DELETE FROM teaching_calendar WHERE id BETWEEN 945000 AND 945099");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + REQUESTER_PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid LIKE '"
                        + REQUESTER_PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                        + ADMIN + "'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                        + " requested_by LIKE '" + REQUESTER_PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target t JOIN"
                        + " course_schedule_adjustment_request r ON r.request_id=t.request_id"
                        + " WHERE r.requested_by LIKE '" + REQUESTER_PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment j JOIN"
                        + " course_schedule_adjustment_request r ON r.request_id=j.request_id"
                        + " WHERE r.requested_by LIKE '" + REQUESTER_PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                        + " request_id BETWEEN 945800 AND 945899") == 0
                        && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id BETWEEN 945000"
                        + " AND 945099") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_rule WHERE plan_id BETWEEN"
                        + " 945000 AND 945099") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id"
                        + " BETWEEN 945000 AND 945099") == 0
                        && count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 945000"
                        + " AND 945099") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 945300 AND 945399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 945200"
                        + " AND 945299") == 0
                        && count("SELECT COUNT(*) FROM classroom WHERE id BETWEEN 945100"
                        + " AND 945199") == 0
                        && count("SELECT COUNT(*) FROM teaching_calendar WHERE id BETWEEN 945000"
                        + " AND 945099") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + REQUESTER_PREFIX
                        + "%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    private static String localDate(int week, int weekday) {
        return LocalDate.parse(WEEK1_START).plusDays((week - 1) * 7L + weekday - 1).toString();
    }

    private static long dateId(int week, int weekday) {
        return 945020L + (week - 1) * 5L + weekday - 1;
    }

    private static String periodTime(int periodNo, boolean start) {
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

    /** Mirrors the service's DATETIME(6) to {@code Instant} reading (UTC wall clock). */
    private static String instantText(String utcLiteral) {
        return DateTimeFormatter.ISO_INSTANT.format(
                Timestamp.valueOf(utcLiteral).toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String op(int value) {
        return UUID.fromString(String.format("94500000-0000-0000-0000-%012d", value)).toString();
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
                "Refusing teacher adjustment application test: the JDBC URL must target the guarded"
                        + " schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher adjustment application test outside the guarded schema");
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
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
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
