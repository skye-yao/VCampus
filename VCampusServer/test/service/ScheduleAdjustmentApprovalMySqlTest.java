package service;

import dao.AdminCourseOperationDAO;
import dao.ScheduleAdjustmentDAO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
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
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static dto.course.AdjustmentRequestStatusDTO.APPROVED;
import static dto.course.AdjustmentRequestStatusDTO.PENDING;
import static dto.course.AdjustmentRequestStatusDTO.REJECTED;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Guarded MySQL coverage for temporary schedule adjustment approval: paged querying, detail
 * mapping with fresh effective-schedule conflicts, the all-or-nothing approval transaction,
 * rejection, replay, two-administrator racing, injected mid-transaction failure and null-proposed
 * resource inheritance across non-contiguous target weeks. Fixtures live in the 970xxx id range
 * and are removed by {@code cleanup}.
 */
public final class ScheduleAdjustmentApprovalMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T06:30:00Z"), ZoneOffset.UTC);
    /** The clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-14 06:30:00";

    private static final String ADMIN_A = "adj970-admin-a";
    private static final String ADMIN_B = "adj970-admin-b";
    private static final String APPLICANT = "adj970-applicant";
    private static final String TEACHER = "adj970-teacher";
    private static final String TEACHER_B = "adj970-teacher-b";
    private static final String TEACHER_C = "adj970-teacher-c";
    private static final String ASSISTANT = "adj970-assistant";

    private static final long CALENDAR = 970001L;
    private static final long TEMPLATE = 970002L;
    private static final long PLAN = 970003L;
    private static final long OFFERING_MAIN = 970301L;
    private static final long OFFERING_OTHER = 970302L;
    private static final long ARRANGEMENT_MAIN = 970401L;
    private static final long ARRANGEMENT_OTHER = 970403L;
    private static final long ROOM_A = 970200L;
    private static final long ROOM_B = 970201L;
    private static final long ROOM_SMALL = 970202L;

    private static final long RULE_TUE = 970501L;
    private static final long RULE_FRI_LATE = 970502L;
    private static final long RULE_MON = 970503L;
    private static final long RULE_WED = 970504L;
    private static final long RULE_MON_LATE = 970505L;
    private static final long RULE_OTHER_THU = 970513L;

    private static final long REQUEST_CLEAN = 970701L;
    private static final long REQUEST_OVERRIDABLE = 970702L;
    private static final long REQUEST_ROOM = 970703L;
    private static final long REQUEST_BLOCKING = 970704L;
    private static final long REQUEST_REJECT = 970705L;
    private static final long REQUEST_REPLAY = 970706L;
    private static final long REQUEST_RACE = 970707L;
    private static final long REQUEST_SPARSE = 970708L;
    private static final long REQUEST_ROLLBACK = 970709L;
    private static final long REQUEST_SLOT_INVALID = 970710L;
    private static final long REQUEST_ADJUSTED_TARGET = 970711L;
    private static final long REQUEST_DUPLICATE_WEEK = 970712L;
    private static final long REQUEST_RACE_FIRST = 970713L;
    private static final long REQUEST_RACE_SECOND = 970714L;

    private ScheduleAdjustmentApprovalMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            verifyListAndDetail();
            verifyOrdinaryApproval();
            verifyStaleVersionAndRejection();
            verifyBlockingSameOfferingConflict();
            verifyOverridableConflicts();
            verifyReplay();
            verifyConcurrentReview();
            verifyInjectedFailureRollback();
            verifySparseWeeksAndInheritedResources();
            verifySlotAndAdjustedTargetRefusals();
            verifyDuplicateTargetWeek();
            verifySharedOperationRace();
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Schedule adjustment approval MySQL test passed.");
    }

    // ------------------------------------------------------------------ group 1

    private static void verifyListAndDetail() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        // The guarded schema is shared, so the page assertions are anchored on what the database
        // actually holds instead of assuming this fixture is the only PENDING request in it.
        long strays = count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                + " WHERE status='PENDING' AND request_id NOT BETWEEN 970700 AND 970799");
        AdjustmentRequestPageDTO all = service.listRequests(null, 1, 100);
        require(all.getTotalCount() == 14 + strays && all.getItems().size() == 14 + strays
                        && all.getPageNumber() == 1 && all.getPageSize() == 100,
                "the default page filters PENDING and reports the server total (observed total="
                        + all.getTotalCount() + " items=" + all.getItems().size() + " expected="
                        + (14 + strays) + ")");
        List<String> ordered = ids(all);
        require(fixtures(ordered).equals(List.of("970714", "970713", "970712", "970711", "970710",
                        "970709", "970708", "970707", "970706", "970705", "970704", "970703",
                        "970702", "970701")),
                "the list is ordered by submitted_at then request_id descending (observed "
                        + fixtures(ordered) + ")");

        List<String> paged = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= (all.getTotalCount() + 1) / 2; pageNumber++) {
            AdjustmentRequestPageDTO slice = service.listRequests(AdjustmentRequestStatusDTO.PENDING,
                    pageNumber, 2);
            require(slice.getPageNumber() == pageNumber && slice.getPageSize() == 2
                            && slice.getTotalCount() == all.getTotalCount(),
                    "every page reports its own metadata");
            paged.addAll(ids(slice));
        }
        require(paged.equals(ordered),
                "paging at size 2 reproduces the whole ordered set (observed " + paged + ")");

        AdjustmentRequestSummaryDTO summary = summaryOf(all, REQUEST_REJECT);
        require("Adjustment Course A".equals(summary.getCourseName())
                        && "ADJ-A".equals(summary.getOfferingCode())
                        && APPLICANT.equals(summary.getApplicantUid())
                        && "Adj Applicant".equals(summary.getApplicantName())
                        && summary.getTargetWeekCount() == 1
                        && summary.getStatus() == AdjustmentRequestStatusDTO.PENDING
                        && instantText("2026-09-10 05:00:00").equals(summary.getSubmittedAt()),
                "summaries join course, offering and applicant display data");

        // The shared schema may hold decided rows outside this fixture, so the filter is checked by
        // what it returns rather than by a server-wide total against a fixture-only count.
        require(service.listRequests(AdjustmentRequestStatusDTO.APPROVED, 1, 100).getItems().stream()
                        .allMatch(item -> item.getStatus() == APPROVED)
                        && fixtures(ids(service.listRequests(AdjustmentRequestStatusDTO.APPROVED, 1, 100)))
                        .isEmpty(),
                "an explicit status filter returns only that status");
        require(fixtures(ids(service.listRequests(AdjustmentRequestStatusDTO.REJECTED, 1, 100))).isEmpty(),
                "nothing under test is rejected before the decision scenarios run");

        expect(IllegalArgumentException.class, () -> service.listRequests(null, 0, 10),
                "page 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listRequests(null, 1, 0),
                "size 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listRequests(null, 1, 101),
                "size above 100 is rejected");
        expect(IllegalArgumentException.class, () -> service.getRequest("abc"),
                "a non-decimal request id is rejected");
        expect(ScheduleAdjustmentApprovalService.NotFoundException.class,
                () -> service.getRequest("970799"), "a missing request is not found");

        AdjustmentRequestDetailDTO detail = service.getRequest(Long.toString(REQUEST_OVERRIDABLE));
        require(detail.getStatus() == AdjustmentRequestStatusDTO.PENDING && detail.getVersion() == 1
                        && Long.toString(OFFERING_MAIN).equals(detail.getOfferingId())
                        && APPLICANT.equals(detail.getApplicantUid())
                        && "临时调课夹具".equals(detail.getReason()),
                "the detail maps the request head");
        require(detail.getNewDayOfWeek() == 4 && detail.getNewStartPeriod() == 1
                        && detail.getNewEndPeriod() == 2,
                "the detail maps the proposed slot");
        require(detail.getNewTeacher() != null
                        && TEACHER_B.equals(detail.getNewTeacher().getResourceId())
                        && TEACHER_B.equals(detail.getNewTeacher().getBusinessId())
                        && "Adj Teacher B".equals(detail.getNewTeacher().getName())
                        && "teacher".equals(detail.getNewTeacher().getResourceType())
                        && detail.getNewAssistant() != null
                        && ASSISTANT.equals(detail.getNewAssistant().getBusinessId())
                        && detail.getNewClassroom() == null,
                "proposed resources use UID identity and existing display names");
        require(detail.getTargets().size() == 2
                        && "970603".equals(detail.getTargets().get(0).getOriginalOccurrenceId())
                        && detail.getTargets().get(0).getWeek() == 3
                        && "970604".equals(detail.getTargets().get(1).getOriginalOccurrenceId())
                        && detail.getTargets().get(1).getWeek() == 4,
                "targets sort by original week then occurrence id");
        AdjustmentTargetDTO target = detail.getTargets().get(0);
        require(instantText("2026-09-22 00:00:00").equals(target.getOriginalStartAt())
                        && instantText("2026-09-22 01:35:00").equals(target.getOriginalEndAt())
                        && "Adj Teacher A".equals(target.getOriginalTeacher())
                        && target.getOriginalAssistant() == null
                        && "Adj Room A".equals(target.getOriginalClassroom()),
                "target snapshots map to UTC instants and display names");
        require(instantText("2026-09-10 02:00:00").equals(detail.getSubmittedAt())
                        && detail.getReviewedBy() == null && detail.getReviewedAt() == null
                        && detail.getReviewComment() == null,
                "an undecided request carries no reviewer");
        require(detail.getConflicts().size() == 4,
                "fresh conflicts cover both target weeks for teacher and assistant");
        require("ASSISTANT_OVERLAP".equals(detail.getConflicts().get(0).getType())
                        && detail.getConflicts().get(0).getWeek() == 3
                        && OVERRIDABLE == detail.getConflicts().get(0).getSeverity()
                        && "adj970-assistant".equals(detail.getConflicts().get(0).getSubjectId())
                        && "970302".equals(detail.getConflicts().get(0).getRelatedOfferingId())
                        && "TEACHER_OVERLAP".equals(detail.getConflicts().get(1).getType())
                        && detail.getConflicts().get(1).getWeek() == 3
                        && detail.getConflicts().get(2).getWeek() == 4
                        && detail.getConflicts().get(3).getWeek() == 4,
                "conflicts merge in stable week then type order");

        AdjustmentRequestDetailDTO roomDetail = service.getRequest(Long.toString(REQUEST_ROOM));
        require(roomDetail.getTargets().size() == 1
                        && roomDetail.getNewClassroom() != null
                        && Long.toString(ROOM_B).equals(roomDetail.getNewClassroom().getResourceId())
                        && Long.toString(ROOM_B).equals(roomDetail.getNewClassroom().getBusinessId())
                        && "Adj Room B".equals(roomDetail.getNewClassroom().getName())
                        && roomDetail.getNewClassroom().getCapacity() == 10,
                "a proposed classroom uses its decimal id for both identities");
        require(roomDetail.getConflicts().size() == 2
                        && "CLASSROOM_CAPACITY".equals(roomDetail.getConflicts().get(0).getType())
                        && "CLASSROOM_OVERLAP".equals(roomDetail.getConflicts().get(1).getType()),
                "a small occupied room reports capacity and overlap conflicts");
    }

    // ------------------------------------------------------------------ group 2

    private static void verifyOrdinaryApproval() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());
        String before = scheduleSnapshot();

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> result = service.review(ADMIN_A,
                decision(op(1), REQUEST_CLEAN, 1, true, false, null, null));
        require("OK".equals(result.getOutcomeCode()) && result.getConflicts().isEmpty(),
                "a clean approval succeeds without conflicts");
        AdjustmentRequestDetailDTO entity = result.getEntity();
        require(entity.getStatus() == AdjustmentRequestStatusDTO.APPROVED && entity.getVersion() == 2
                        && ADMIN_A.equals(entity.getReviewedBy())
                        && instantText("2026-09-14 06:30:00").equals(entity.getReviewedAt()),
                "the approved entity advances the version and records the reviewer");

        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_CLEAN + " AND status='ACTIVE'") == 2,
                "an ordinary approval writes exactly one adjustment per target");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_CLEAN + " AND original_occurrence_id=970601"
                        + " AND start_at_utc='" + utcText(localDate(1, 5), "08:00:00")
                        + "' AND end_at_utc='" + utcText(localDate(1, 5), "09:35:00")
                        + "' AND teacher_uid='" + TEACHER + "' AND assistant_uid IS NULL"
                        + " AND classroom_id=" + ROOM_A) == 1,
                "the adjustment maps the proposed slot through the teaching calendar in its zone");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_CLEAN + " AND original_occurrence_id=970602"
                        + " AND start_at_utc='" + utcText(localDate(2, 5), "08:00:00") + "'") == 1,
                "each target week maps to its own concrete UTC window");

        require(count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_CLEAN + " AND notice_type='RESCHEDULED' AND status='PUBLISHED'"
                        + " AND week_no IS NULL AND created_by='" + ADMIN_A + "'"
                        + " AND published_at='" + CLOCK_TEXT + "' AND offering_id="
                        + OFFERING_MAIN) == 1,
                "an approval publishes exactly one linked RESCHEDULED notice");
        String content = text("SELECT content FROM course_notice WHERE adjustment_request_id="
                + REQUEST_CLEAN);
        require(content.contains("第1周") && content.contains("第2周")
                        && content.contains("原安排 09-08 08:00-09:35")
                        && content.contains("原安排 09-15 08:00-09:35")
                        && content.contains("新安排 星期5 第1-2节")
                        && content.contains("教师Adj Teacher A") && content.contains("教室Adj Room A"),
                "the notice lists every sorted target week with its original and new arrangement");
        require(content.indexOf("第1周") >= 0
                        && content.indexOf("第1周") < content.indexOf("第2周"),
                "the notice lists target weeks in ascending order");

        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(1) + "' AND action='reviewAdjustmentRequest'"
                        + " AND target_type='ADJUSTMENT_REQUEST' AND target_id='" + REQUEST_CLEAN
                        + "' AND forced=0 AND override_reason IS NULL"
                        + " AND conflict_snapshot_json IS NOT NULL AND result_code='OK'"
                        + " AND completed_at='" + CLOCK_TEXT + "'") == 1,
                "the approval writes exactly one audit row with its conflict snapshot");

        require(before.equals(scheduleSnapshot()),
                "approval never rewrites the published base plan, rules, weeks, occurrences or bookings");

        AdjustmentRequestPageDTO approved = service.listRequests(AdjustmentRequestStatusDTO.APPROVED, 1, 100);
        require(fixtures(ids(approved)).equals(List.of(Long.toString(REQUEST_CLEAN)))
                        && approved.getTotalCount() == count("SELECT COUNT(*) FROM"
                        + " course_schedule_adjustment_request WHERE status='APPROVED'"),
                "the decided request leaves the PENDING filter for the APPROVED one");

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> replay = service.review(ADMIN_A,
                decision(op(1), REQUEST_CLEAN, 1, true, false, null, null));
        require(APPROVED == replay.getEntity().getStatus()
                        && replay.getConflicts().isEmpty()
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                        + ADMIN_A + "' AND operation_id='" + op(1) + "'") == 1,
                "replaying the same decision returns the stored response once");
    }

    // ------------------------------------------------------------------ groups 3 and 6

    private static void verifyStaleVersionAndRejection() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        ScheduleAdjustmentApprovalService.ConflictException stale = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(2), REQUEST_REJECT, 9, true, false, null, null)),
                "a stale expectedVersion conflicts");
        require(stale.getEntity() instanceof AdjustmentRequestDetailDTO
                        && ((AdjustmentRequestDetailDTO) stale.getEntity()).getVersion() == 1
                        && PENDING == ((AdjustmentRequestDetailDTO) stale.getEntity()).getStatus(),
                "the stale conflict carries the latest detail");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_REJECT) == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_REJECT) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(2) + "'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + REQUEST_REJECT + " AND status='PENDING' AND version=1") == 1,
                "a stale decision makes no partial write");

        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(3), REQUEST_REJECT, 1, false, false, null, null)),
                "rejection without a comment is rejected");
        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(4), REQUEST_REJECT, 1, false, true, "   ", null)),
                "force is only meaningful for approval");
        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(5), REQUEST_REJECT, 1, false, false, null, "   ")),
                "a blank rejection comment is rejected");

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> rejected = service.review(ADMIN_A,
                decision(op(6), REQUEST_REJECT, 1, false, false, null, "  材料不足  "));
        require("OK".equals(rejected.getOutcomeCode())
                        && REJECTED == rejected.getEntity().getStatus()
                        && rejected.getEntity().getVersion() == 2
                        && "材料不足".equals(rejected.getEntity().getReviewComment())
                        && ADMIN_A.equals(rejected.getEntity().getReviewedBy()),
                "a rejection records the trimmed comment and reviewer");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_REJECT) == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_REJECT) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(6) + "' AND forced=0") == 1,
                "a rejection writes only the request decision and its audit row");
    }

    // ------------------------------------------------------------------ group 4

    private static void verifyBlockingSameOfferingConflict() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());
        ScheduleAdjustmentApprovalService.ConflictException blocking = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(7), REQUEST_BLOCKING, 1, true, true, "强行通过", null)),
                "a same-offering overlap is blocking even when forced");
        require(blocking.getConflicts().stream().anyMatch(conflict -> BLOCKING == conflict.getSeverity()
                        && "OFFERING_OVERLAP".equals(conflict.getType())
                        && conflict.getWeek() == 1),
                "the blocking rejection carries the typed same-offering conflict");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_BLOCKING) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(7) + "'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + REQUEST_BLOCKING + " AND status='PENDING'") == 1,
                "a blocking decision writes nothing");
    }

    // ------------------------------------------------------------------ group 5

    private static void verifyOverridableConflicts() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        ScheduleAdjustmentApprovalService.ConflictException withoutForce = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(8), REQUEST_OVERRIDABLE, 1, true, false, null, null)),
                "an overridable conflict is rejected without force");
        require(withoutForce.getConflicts().size() == 4
                        && withoutForce.getConflicts().stream().allMatch(
                        conflict -> OVERRIDABLE == conflict.getSeverity())
                        && withoutForce.getEntity() instanceof AdjustmentRequestDetailDTO,
                "the rejection carries only overridable typed conflicts and the latest detail");
        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(9), REQUEST_OVERRIDABLE, 1, true, true, "  ", null)),
                "force without an override reason is rejected");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_OVERRIDABLE) == 0,
                "a rejected overridable approval writes no adjustment");

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> forced = service.review(ADMIN_A,
                decision(op(10), REQUEST_OVERRIDABLE, 1, true, true, "  已协调教师  ", null));
        require(APPROVED == forced.getEntity().getStatus()
                        && forced.getConflicts().size() == 4,
                "a forced approval succeeds and returns the approval-time conflict snapshot");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(10) + "' AND forced=1"
                        + " AND override_reason='已协调教师'") == 1,
                "a forced approval records the normalized override reason");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_OVERRIDABLE + " AND status='ACTIVE'") == 2,
                "a forced approval still writes one adjustment per target");

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> room = service.review(ADMIN_A,
                decision(op(11), REQUEST_ROOM, 1, true, true, "  已协调教室  ", null));
        require(APPROVED == room.getEntity().getStatus()
                        && room.getConflicts().size() == 2
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_ROOM + " AND classroom_id=" + ROOM_B) == 1,
                "a forced capacity and overlap override stores the proposed room");
    }

    // ------------------------------------------------------------------ group 7

    private static void verifyReplay() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> first = service.review(ADMIN_A,
                decision(op(12), REQUEST_REPLAY, 1, true, false, null, null));
        require(APPROVED == first.getEntity().getStatus(),
                "the replay fixture approves once");
        int adjustments = count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                + REQUEST_REPLAY);
        int notices = count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                + REQUEST_REPLAY);

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> replay = service.review(ADMIN_A,
                decision(op(12), REQUEST_REPLAY, 1, true, false, null, null));
        require(APPROVED == replay.getEntity().getStatus()
                        && replay.getEntity().getVersion() == first.getEntity().getVersion(),
                "the same operation identity replays the stored decision");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_REPLAY) == adjustments
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_REPLAY) == notices
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                        + ADMIN_A + "' AND operation_id='" + op(12) + "'") == 1,
                "a replay duplicates no adjustment, notice or audit row");

        expect(ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(12), REQUEST_REJECT, 1, true, false, null, null)),
                "the same operationId with a different request digest conflicts");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_REJECT) == 0,
                "a digest conflict writes nothing");
    }

    // ------------------------------------------------------------------ group 8

    private static void verifyConcurrentReview() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<AdminOperationResultDTO<AdjustmentRequestDetailDTO>> success =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Runnable attemptA = attempt(service, ADMIN_A, op(13), REQUEST_RACE, barrier, success, failure,
                unexpected);
        Runnable attemptB = attempt(service, ADMIN_B, op(14), REQUEST_RACE, barrier, success, failure,
                unexpected);
        Thread first = new Thread(attemptA, "adjustment-approver-a");
        Thread second = new Thread(attemptB, "adjustment-approver-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(), "both racing administrations only saw a success or a conflict");
        require(failure.get() instanceof ScheduleAdjustmentApprovalService.ConflictException,
                "exactly one racing administration loses with a conflict");
        require(success.get() != null && APPROVED == success.get().getEntity().getStatus(),
                "exactly one racing administration commits the decision");
        AdjustmentRequestDetailDTO latest =
                (AdjustmentRequestDetailDTO) ((ScheduleAdjustmentApprovalService.ConflictException)
                        failure.get()).getEntity();
        require(latest != null && APPROVED == latest.getStatus(),
                "the losing administration receives the committed latest state");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_RACE + " AND status='ACTIVE'") == 1
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_RACE) == 1
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE target_id='"
                        + REQUEST_RACE + "'") == 1,
                "the race leaves exactly one adjustment, one notice and one audit row");
    }

    private static Runnable attempt(ScheduleAdjustmentApprovalService service, String admin,
                                    String operationId, long requestId, CyclicBarrier barrier,
                                    AtomicReference<AdminOperationResultDTO<AdjustmentRequestDetailDTO>> success,
                                    AtomicReference<Throwable> failure, List<Throwable> unexpected) {
        return () -> {
            try {
                barrier.await();
                success.set(service.review(admin,
                        decision(operationId, requestId, 1, true, false, null, null)));
            } catch (ScheduleAdjustmentApprovalService.ConflictException expected) {
                failure.set(expected);
            } catch (Throwable other) {
                synchronized (unexpected) {
                    unexpected.add(other);
                }
            }
        };
    }

    // ------------------------------------------------------------------ group 9

    private static void verifyInjectedFailureRollback() throws Exception {
        ScheduleAdjustmentApprovalService failing = service(new FailingNoticeDao());

        expect(exception.DatabaseException.class,
                () -> failing.review(ADMIN_A, decision(op(15), REQUEST_ROLLBACK, 1, true, false, null, null)),
                "an injected failure after the adjustment writes surfaces as a database failure");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + REQUEST_ROLLBACK + " AND status='PENDING' AND version=1"
                        + " AND reviewed_at IS NULL") == 1,
                "the failed transaction rolls the request decision back");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_ROLLBACK) == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_ROLLBACK) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(15) + "'") == 0,
                "the failed transaction rolls adjustments, notice and audit back");
    }

    // ----------------------------------------------------------------- group 10

    private static void verifySparseWeeksAndInheritedResources() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        AdminOperationResultDTO<AdjustmentRequestDetailDTO> result;
        try {
            result = service.review(ADMIN_A, decision(op(16), REQUEST_SPARSE, 1, true, false, null, null));
        } catch (ScheduleAdjustmentApprovalService.ConflictException failure) {
            throw new AssertionError("the sparse request was refused: " + failure.getMessage()
                    + " conflicts=" + describe(failure.getConflicts()), failure);
        }
        require(APPROVED == result.getEntity().getStatus() && result.getConflicts().isEmpty(),
                "non-contiguous target weeks with free slots approve cleanly");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SPARSE + " AND status='ACTIVE'") == 2,
                "both sparse target weeks produce an adjustment");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SPARSE + " AND original_occurrence_id=970671"
                        + " AND start_at_utc='" + utcText(localDate(1, 1), "10:00:00")
                        + "' AND end_at_utc='" + utcText(localDate(1, 1), "11:35:00")
                        + "' AND teacher_uid='" + TEACHER + "' AND assistant_uid IS NULL"
                        + " AND classroom_id=" + ROOM_A) == 1,
                "a null proposed teacher, assistant and classroom inherit the first target snapshot");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SPARSE + " AND original_occurrence_id=970683"
                        + " AND start_at_utc='" + utcText(localDate(3, 1), "10:00:00")
                        + "' AND end_at_utc='" + utcText(localDate(3, 1), "11:35:00")
                        + "' AND teacher_uid='" + TEACHER + "' AND classroom_id=" + ROOM_A) == 1,
                "each target inherits its own snapshot independently");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SPARSE + " AND start_at_utc='" + utcText(localDate(2, 1), "10:00:00")
                        + "'") == 0,
                "the unrequested intermediate week is never written");
    }

    // ----------------------------------------------------------------- group 11

    private static void verifySlotAndAdjustedTargetRefusals() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());

        AdjustmentRequestDetailDTO slot = service.getRequest(Long.toString(REQUEST_SLOT_INVALID));
        require(slot.getStatus() == PENDING && slot.getConflicts().size() == 1
                        && ScheduleAdjustmentApprovalService.SLOT_INVALID
                        .equals(slot.getConflicts().get(0).getType())
                        && BLOCKING == slot.getConflicts().get(0).getSeverity()
                        && slot.getConflicts().get(0).getWeek() == 3,
                "a proposed slot the teaching calendar cannot place stays viewable as a typed"
                        + " blocking conflict (observed " + describe(slot.getConflicts()) + ")");
        ScheduleAdjustmentApprovalService.ConflictException forced = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A,
                        decision(op(17), REQUEST_SLOT_INVALID, 1, true, true, "强行通过", null)),
                "an unplaceable proposed slot is blocking even when forced");
        require(forced.getConflicts().stream().anyMatch(c -> BLOCKING == c.getSeverity())
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SLOT_INVALID) == 0,
                "the refused unplaceable slot writes no adjustment");

        // A conflict may never make a pending request impossible to decline.
        AdminOperationResultDTO<AdjustmentRequestDetailDTO> rejected = service.review(ADMIN_A,
                decision(op(18), REQUEST_SLOT_INVALID, 1, false, false, null, "节次不在教学日历内"));
        require(REJECTED == rejected.getEntity().getStatus()
                        && rejected.getEntity().getVersion() == 2
                        && "节次不在教学日历内".equals(rejected.getEntity().getReviewComment()),
                "a request whose proposed slot is blocking can still be rejected");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_SLOT_INVALID) == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + REQUEST_SLOT_INVALID) == 0,
                "rejecting a conflicted request writes neither adjustment nor notice");

        AdjustmentRequestDetailDTO adjusted =
                service.getRequest(Long.toString(REQUEST_ADJUSTED_TARGET));
        require(adjusted.getConflicts().size() == 1
                        && ScheduleAdjustmentApprovalService.TARGET_ADJUSTED
                        .equals(adjusted.getConflicts().get(0).getType())
                        && BLOCKING == adjusted.getConflicts().get(0).getSeverity(),
                "an occurrence that already carries an ACTIVE adjustment is a typed blocking conflict"
                        + " (observed " + describe(adjusted.getConflicts()) + ")");
        ScheduleAdjustmentApprovalService.ConflictException occupied = expect(
                ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A,
                        decision(op(19), REQUEST_ADJUSTED_TARGET, 1, true, false, null, null)),
                "a second request for an already adjusted occurrence is refused");
        require(occupied.getConflicts().stream().anyMatch(c -> BLOCKING == c.getSeverity())
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_ADJUSTED_TARGET) == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE"
                        + " operation_id='" + op(19) + "'") == 0,
                "the second request writes no duplicate adjustment");
    }

    // ----------------------------------------------------------------- group 12

    private static void verifyDuplicateTargetWeek() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());
        AdjustmentRequestDetailDTO detail =
                service.getRequest(Long.toString(REQUEST_DUPLICATE_WEEK));
        require(detail.getTargets().size() == 2 && detail.getConflicts().size() == 1
                        && CourseConflictService.OFFERING_OVERLAP
                        .equals(detail.getConflicts().get(0).getType())
                        && BLOCKING == detail.getConflicts().get(0).getSeverity()
                        && detail.getConflicts().get(0).getWeek() == 1,
                "two targets in one week cannot share the single proposed slot (observed "
                        + describe(detail.getConflicts()) + ")");
        expect(ScheduleAdjustmentApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A,
                        decision(op(20), REQUEST_DUPLICATE_WEEK, 1, true, false, null, null)),
                "a duplicate target week is refused even without any external conflict");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + REQUEST_DUPLICATE_WEEK) == 0,
                "a duplicate target week writes no adjustment");
    }

    // ----------------------------------------------------------------- group 13

    private static void verifySharedOperationRace() throws Exception {
        ScheduleAdjustmentApprovalService service = service(new ScheduleAdjustmentDAO());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<AdminOperationResultDTO<AdjustmentRequestDetailDTO>> committed =
                new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Thread first = new Thread(attempt(service, ADMIN_A, op(21), REQUEST_RACE_FIRST, barrier,
                committed, refused, unexpected), "shared-operation-id-a");
        Thread second = new Thread(attempt(service, ADMIN_A, op(21), REQUEST_RACE_SECOND, barrier,
                committed, refused, unexpected), "shared-operation-id-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(),
                "reusing one operationId for two requests must not surface a database failure ("
                        + unexpected + ")");
        require(committed.get() != null && APPROVED == committed.get().getEntity().getStatus(),
                "one of the two requests claims the operation identity");
        require(refused.get() instanceof ScheduleAdjustmentApprovalService.ConflictException,
                "the other request loses with a conflict rather than a driver error");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(21) + "'") == 1,
                "one operationId keeps exactly one audit row");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id IN ("
                        + REQUEST_RACE_FIRST + "," + REQUEST_RACE_SECOND + ")") == 1
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id IN ("
                        + REQUEST_RACE_FIRST + "," + REQUEST_RACE_SECOND + ")") == 1
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                        + " request_id IN (" + REQUEST_RACE_FIRST + "," + REQUEST_RACE_SECOND
                        + ") AND status='PENDING'") == 1,
                "the losing request is fully rolled back to PENDING");
    }

    // ------------------------------------------------------------------ fixtures

    private static ScheduleAdjustmentApprovalService service(ScheduleAdjustmentDAO dao) {
        return new ScheduleAdjustmentApprovalService(dao, new AdminCourseOperationDAO(),
                new ScheduleAdjustmentConflictService(), CLOCK);
    }

    private static ApprovalDecisionRequestDTO decision(String operationId, long requestId,
                                                       int expectedVersion, boolean approved,
                                                       boolean force, String overrideReason,
                                                       String reviewComment) {
        return new ApprovalDecisionRequestDTO(operationId, Long.toString(requestId),
                expectedVersion, approved, force, overrideReason, reviewComment);
    }

    /** Overridable write hook proves the whole decision rolls back, not only the notice row. */
    private static final class FailingNoticeDao extends ScheduleAdjustmentDAO {
        @Override
        public long insertNotice(Connection connection, long requestId, long offeringId,
                                 String adminUid, String title, String content, Instant publishedAt)
                throws SQLException {
            throw new SQLException("injected notice failure");
        }
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + APPLICANT + "','Adj Applicant','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER + "','Adj Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Adj Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_C + "','Adj Teacher C','x','x',1,'Engineering','Professor'),"
                + "('" + ASSISTANT + "','Adj Assistant','x','x',1,'Engineering','Assistant'),"
                + "('" + ADMIN_A + "','Adj Admin A','x','x',0,'Administration','Registrar'),"
                + "('" + ADMIN_B + "','Adj Admin B','x','x',0,'Administration','Registrar')");

        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(970101,'ADJ101','Adjustment Course A',3.00,48,1,'ACTIVE'),"
                + "(970102,'ADJ102','Adjustment Course B',3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'ADJ-A',970101,2026,3,30,2),("
                + OFFERING_OTHER + ",'ADJ-B',970102,2026,3,30,2)");

        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR + ",'Adjustment test calendar',"
                + "2026,3,'2026-09-07','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO day_template(id,name,version) VALUES(" + TEMPLATE
                + ",'Adjustment test template',1)");
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(970010," + TEMPLATE + ",1,'08:00:00','08:45:00'),"
                + "(970011," + TEMPLATE + ",2,'08:50:00','09:35:00'),"
                + "(970012," + TEMPLATE + ",3,'10:00:00','10:45:00'),"
                + "(970013," + TEMPLATE + ",4,'10:50:00','11:35:00')");
        int dateId = 970020;
        for (int week = 1; week <= 4; week++) {
            for (int day = 1; day <= 5; day++) {
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                        + "day_template_id,is_teaching_day) VALUES(" + dateId++ + "," + CALENDAR + ",'"
                        + localDate(week, day) + "'," + week + "," + day + "," + TEMPLATE + ",1)");
            }
        }
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,updated_at)"
                + " VALUES(" + PLAN + ",'Adjustment published plan'," + CALENDAR
                + ",1,'PUBLISHED','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN + " WHERE id="
                + CALENDAR);

        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(" + ROOM_A
                + ",'Adj Room A',60,1),(" + ROOM_B + ",'Adj Room B',10,1),(" + ROOM_SMALL
                + ",'Adj Room Small',5,1)");

        arrangement(ARRANGEMENT_MAIN, OFFERING_MAIN, TEACHER, null, ROOM_A);
        arrangement(ARRANGEMENT_OTHER, OFFERING_OTHER, TEACHER_B, ASSISTANT, ROOM_B);
        rule(RULE_TUE, OFFERING_MAIN, ARRANGEMENT_MAIN, 2, 1, 2);
        rule(RULE_FRI_LATE, OFFERING_MAIN, ARRANGEMENT_MAIN, 5, 3, 4);
        rule(RULE_MON, OFFERING_MAIN, ARRANGEMENT_MAIN, 1, 1, 2);
        rule(RULE_WED, OFFERING_MAIN, ARRANGEMENT_MAIN, 3, 1, 2);
        rule(RULE_MON_LATE, OFFERING_MAIN, ARRANGEMENT_MAIN, 1, 3, 4);
        rule(RULE_OTHER_THU, OFFERING_OTHER, ARRANGEMENT_OTHER, 4, 1, 2);

        for (int week = 1; week <= 4; week++) {
            occur(970600L + week, RULE_TUE, OFFERING_MAIN, TEACHER, null, ROOM_A, week, 2, 1, 2);
            occur(970620L + week, RULE_FRI_LATE, OFFERING_MAIN, TEACHER, null, ROOM_A, week, 5, 3, 4);
            occur(970670L + week, RULE_MON, OFFERING_MAIN, TEACHER, null, ROOM_A, week, 1, 1, 2);
            occur(970680L + week, RULE_WED, OFFERING_MAIN, TEACHER, null, ROOM_A, week, 3, 1, 2);
            occur(970720L + week, RULE_OTHER_THU, OFFERING_OTHER, TEACHER_B, ASSISTANT, ROOM_B,
                    week, 4, 1, 2);
        }
        // The unrequested-week trap: the same offering already occupies the sparse proposal's slot
        // in week 2, so any check against week 2 would report a blocking overlap.
        occur(970691L, RULE_MON_LATE, OFFERING_MAIN, TEACHER, null, ROOM_A, 2, 1, 3, 4);

        execute("INSERT INTO schedule_resource(id,resource_type,business_id,conflict_mode) VALUES"
                + "(970411,'teacher','" + TEACHER + "','EXCLUSIVE'),"
                + "(970412,'classroom','" + ROOM_A + "','EXCLUSIVE')");
        execute("INSERT INTO resource_booking(id,plan_id,occurrence_id,resource_id,resource_role)"
                + " VALUES(970421," + PLAN + ",970601,970411,'TEACHER'),"
                + "(970422," + PLAN + ",970601,970412,'CLASSROOM'),"
                + "(970423," + PLAN + ",970621,970411,'TEACHER'),"
                + "(970424," + PLAN + ",970621,970412,'CLASSROOM')");

        // A: clean two-week approval.
        request(REQUEST_CLEAN, OFFERING_MAIN, 5, 1, 2, null, null, null, 1);
        target(970801L, REQUEST_CLEAN, 970601L, 1, 2, 1, 2, TEACHER, null, ROOM_A);
        target(970802L, REQUEST_CLEAN, 970602L, 2, 2, 1, 2, TEACHER, null, ROOM_A);
        // B: inherits the room but proposes the other offering's teacher and assistant.
        request(REQUEST_OVERRIDABLE, OFFERING_MAIN, 4, 1, 2, TEACHER_B, ASSISTANT, null, 2);
        target(970803L, REQUEST_OVERRIDABLE, 970603L, 3, 2, 1, 2, TEACHER, null, ROOM_A);
        target(970804L, REQUEST_OVERRIDABLE, 970604L, 4, 2, 1, 2, TEACHER, null, ROOM_A);
        // C: a small, occupied room produces capacity and overlap conflicts at once.
        request(REQUEST_ROOM, OFFERING_MAIN, 4, 1, 2, TEACHER_C, null, ROOM_B, 3);
        target(970805L, REQUEST_ROOM, 970672L, 2, 1, 1, 2, TEACHER, null, ROOM_A);
        // D: the proposed slot is already taken by the same offering in a later period pair.
        request(REQUEST_BLOCKING, OFFERING_MAIN, 5, 3, 4, null, null, null, 4);
        target(970806L, REQUEST_BLOCKING, 970671L, 1, 1, 1, 2, TEACHER, null, ROOM_A);
        // E: clean but rejected.
        request(REQUEST_REJECT, OFFERING_MAIN, 5, 1, 2, null, null, null, 5);
        target(970807L, REQUEST_REJECT, 970623L, 3, 5, 3, 4, TEACHER, null, ROOM_A);
        // F: clean and replayed under one operation identity.
        request(REQUEST_REPLAY, OFFERING_MAIN, 5, 1, 2, null, null, null, 6);
        target(970808L, REQUEST_REPLAY, 970624L, 4, 5, 3, 4, TEACHER, null, ROOM_A);
        // G: clean, raced by two administrators.
        request(REQUEST_RACE, OFFERING_MAIN, 1, 3, 4, null, null, null, 7);
        target(970809L, REQUEST_RACE, 970674L, 4, 1, 1, 2, TEACHER, null, ROOM_A);
        // H: non-contiguous weeks 1 and 3 with every proposed resource left null.
        request(REQUEST_SPARSE, OFFERING_MAIN, 1, 3, 4, null, null, null, 8);
        target(970810L, REQUEST_SPARSE, 970671L, 1, 1, 1, 2, TEACHER, null, ROOM_A);
        target(970811L, REQUEST_SPARSE, 970683L, 3, 3, 1, 2, TEACHER, null, ROOM_A);
        // I: clean, but the notice write is injected to fail.
        request(REQUEST_ROLLBACK, OFFERING_MAIN, 3, 3, 4, null, null, null, 9);
        target(970812L, REQUEST_ROLLBACK, 970683L, 3, 3, 1, 2, TEACHER, null, ROOM_A);
        // J: the proposed weekday has no teaching day in the calendar at all.
        request(REQUEST_SLOT_INVALID, OFFERING_MAIN, 6, 1, 2, null, null, null, 10);
        target(970813L, REQUEST_SLOT_INVALID, 970673L, 3, 1, 1, 2, TEACHER, null, ROOM_A);
        // K: the target was already adjusted by an earlier approved request.
        request(REQUEST_ADJUSTED_TARGET, OFFERING_MAIN, 3, 3, 4, null, null, null, 11);
        target(970814L, REQUEST_ADJUSTED_TARGET, 970601L, 1, 2, 1, 2, TEACHER, null, ROOM_A);
        // L: two targets share week 1, so one proposed slot cannot serve both.
        request(REQUEST_DUPLICATE_WEEK, OFFERING_MAIN, 3, 3, 4, null, null, null, 12);
        target(970815L, REQUEST_DUPLICATE_WEEK, 970621L, 1, 5, 3, 4, TEACHER, null, ROOM_A);
        target(970816L, REQUEST_DUPLICATE_WEEK, 970681L, 1, 3, 1, 2, TEACHER, null, ROOM_A);
        // M and N: two clean requests sharing one operation identity.
        request(REQUEST_RACE_FIRST, OFFERING_MAIN, 4, 3, 4, null, null, null, 13);
        target(970817L, REQUEST_RACE_FIRST, 970682L, 2, 3, 1, 2, TEACHER, null, ROOM_A);
        request(REQUEST_RACE_SECOND, OFFERING_MAIN, 4, 3, 4, null, null, null, 14);
        target(970818L, REQUEST_RACE_SECOND, 970673L, 3, 1, 1, 2, TEACHER, null, ROOM_A);
    }

    private static void arrangement(long arrangementId, long offeringId, String teacher,
                                    String assistant, long classroomId) throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,assistant_uid,classroom_id,status,version) VALUES(" + arrangementId + ","
                + PLAN + "," + offeringId + ",'" + teacher + "'," + sql(assistant) + ","
                + classroomId + ",'ACTIVE',1)");
    }

    private static void rule(long ruleId, long offeringId, long arrangementId, int weekday,
                             int startPeriod, int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + ruleId + "," + PLAN + ","
                + offeringId + "," + arrangementId + "," + weekday + "," + startPeriod + ","
                + endPeriod + ",'ACTIVE')");
    }

    private static void occur(long occurrenceId, long ruleId, long offeringId, String teacher,
                              String assistant, long classroomId, int week, int weekday,
                              int startPeriod, int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + ruleId + ","
                + week + ")");
        String date = localDate(week, weekday);
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + occurrenceId + "," + ruleId + "," + PLAN + ",'"
                + utcText(date, periodTime(startPeriod, true)) + "','"
                + utcText(date, periodTime(endPeriod, false)) + "'," + week + "," + weekday + ")");
    }

    private static void request(long requestId, long offeringId, int weekday, int startPeriod,
                                int endPeriod, String newTeacher, String newAssistant,
                                Long newClassroom, int hour) throws SQLException {
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,"
                + "reason,version,status,new_weekday,new_start_period,new_end_period,new_teacher_uid,"
                + "new_assistant_uid,new_classroom_id,submitted_at) VALUES(" + requestId + ","
                + offeringId + ",'" + APPLICANT + "','临时调课夹具',1,'PENDING'," + weekday + ","
                + startPeriod + "," + endPeriod + "," + sql(newTeacher) + "," + sql(newAssistant)
                + "," + (newClassroom == null ? "NULL" : newClassroom.toString())
                + ",'2026-09-10 " + String.format("%02d", hour) + ":00:00')");
    }

    private static void target(long targetId, long requestId, long occurrenceId, int week,
                               int weekday, int startPeriod, int endPeriod, String teacher,
                               String assistant, Long classroomId) throws SQLException {
        String date = localDate(week, weekday);
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES("
                + targetId + "," + requestId + "," + occurrenceId + "," + week + ",'"
                + utcText(date, periodTime(startPeriod, true)) + "','"
                + utcText(date, periodTime(endPeriod, false)) + "'," + sql(teacher) + ","
                + sql(assistant) + "," + (classroomId == null ? "NULL" : classroomId.toString())
                + ")");
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid IN ('" + ADMIN_A + "','"
                + ADMIN_B + "')");
        execute("DELETE FROM course_notice WHERE adjustment_request_id BETWEEN 970700 AND 970799");
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 970000"
                + " AND 979999");
        execute("DELETE FROM course_schedule_adjustment_target WHERE target_id BETWEEN 970000"
                + " AND 979999");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 970000"
                + " AND 979999");
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 970000 AND 970099");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 970500 AND 970599");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 970000 AND 970099");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 970000 AND 970099");
        execute("DELETE FROM course_offering_conflict WHERE plan_id BETWEEN 970000 AND 970099");
        execute("DELETE FROM resource_booking WHERE plan_id BETWEEN 970000 AND 970099");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=" + CALENDAR);
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 970000 AND 970099");
        execute("DELETE FROM schedule_resource WHERE id BETWEEN 970400 AND 970499");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 970300 AND 970399");
        execute("DELETE FROM course WHERE course_id BETWEEN 970100 AND 970199");
        execute("DELETE FROM classroom WHERE id BETWEEN 970200 AND 970299");
        execute("DELETE FROM calendar_date WHERE calendar_id BETWEEN 970000 AND 970099");
        execute("DELETE FROM period_definition WHERE id BETWEEN 970000 AND 970099");
        execute("DELETE FROM day_template WHERE id BETWEEN 970000 AND 970099");
        execute("DELETE FROM teaching_calendar WHERE id BETWEEN 970000 AND 970099");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'adj970-%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id"
                        + " BETWEEN 970000 AND 979999") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id"
                        + " BETWEEN 970000 AND 979999") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target WHERE target_id"
                        + " BETWEEN 970000 AND 979999") == 0
                        && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id BETWEEN 970000"
                        + " AND 970099") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_rule WHERE plan_id BETWEEN 970000"
                        + " AND 970099") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_arrangement WHERE plan_id"
                        + " BETWEEN 970000 AND 970099") == 0
                        && count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 970000"
                        + " AND 970099") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN 970300"
                        + " AND 970399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 970100"
                        + " AND 970199") == 0
                        && count("SELECT COUNT(*) FROM classroom WHERE id BETWEEN 970200"
                        + " AND 970299") == 0
                        && count("SELECT COUNT(*) FROM teaching_calendar WHERE id BETWEEN 970000"
                        + " AND 970099") == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id"
                        + " BETWEEN 970700 AND 970799") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid IN ('"
                        + ADMIN_A + "','" + ADMIN_B + "')") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'adj970-%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    /** Canonical text of the published base plan the approval must never mutate. */
    private static String scheduleSnapshot() throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append(text("SELECT CONCAT(id,'|',status,'|',revision) FROM schedule_plan WHERE id="
                + PLAN));
        snapshot.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',weekday,'|',start_period,'|',"
                + "end_period,'|',status) ORDER BY id SEPARATOR ',') FROM course_schedule_rule"
                + " WHERE plan_id=" + PLAN));
        snapshot.append(text("SELECT GROUP_CONCAT(CONCAT(rule_id,'|',week_no) ORDER BY rule_id,week_no"
                + " SEPARATOR ',') FROM course_schedule_rule_week WHERE rule_id BETWEEN 970500"
                + " AND 970599"));
        snapshot.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',rule_id,'|',start_at,'|',end_at,'|',"
                + "week_no,'|',teaching_weekday) ORDER BY id SEPARATOR ',') FROM course_occurrence"
                + " WHERE plan_id=" + PLAN));
        snapshot.append(text("SELECT GROUP_CONCAT(CONCAT(id,'|',occurrence_id,'|',resource_id,'|',"
                + "resource_role) ORDER BY id SEPARATOR ',') FROM resource_booking WHERE plan_id="
                + PLAN));
        return snapshot.toString();
    }

    private static String localDate(int week, int weekday) {
        return LocalDate.parse("2026-09-07").plusDays((week - 1) * 7L + weekday - 1).toString();
    }

    private static String periodTime(int periodNo, boolean start) {
        return switch (periodNo) {
            case 1 -> start ? "08:00:00" : "08:45:00";
            case 2 -> start ? "08:50:00" : "09:35:00";
            case 3 -> start ? "10:00:00" : "10:45:00";
            case 4 -> start ? "10:50:00" : "11:35:00";
            default -> throw new IllegalArgumentException("no fixture period " + periodNo);
        };
    }

    private static String utcText(String date, String time) {
        Instant instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE)
                .toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace('T', ' ');
    }

    /**
     * Mirrors the service's DATETIME(6) to {@code Instant} reading. The column holds a UTC wall
     * clock and the JVM default zone is not UTC, so the value must be re-anchored at UTC rather
     * than converted with {@code Timestamp.toInstant()}.
     */
    private static String instantText(String utcLiteral) {
        return DateTimeFormatter.ISO_INSTANT.format(
                Timestamp.valueOf(utcLiteral).toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String op(int value) {
        return String.format("70000000-0000-0000-0000-%012d", value);
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
                "Refusing adjustment approval test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing adjustment approval test outside the guarded schema");
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

    private static List<String> ids(AdjustmentRequestPageDTO page) {
        List<String> values = new ArrayList<>();
        for (AdjustmentRequestSummaryDTO summary : page.getItems()) {
            values.add(summary.getRequestId());
        }
        return values;
    }

    /** The 9707xx fixture identities of a result set, keeping the order it was returned in. */
    private static List<String> fixtures(List<String> ids) {
        List<String> mine = new ArrayList<>();
        for (String id : ids) {
            if (id.startsWith("9707")) mine.add(id);
        }
        return mine;
    }

    private static AdjustmentRequestSummaryDTO summaryOf(AdjustmentRequestPageDTO page,
                                                         long requestId) {
        for (AdjustmentRequestSummaryDTO summary : page.getItems()) {
            if (Long.toString(requestId).equals(summary.getRequestId())) return summary;
        }
        throw new AssertionError("request " + requestId + " is absent from " + ids(page));
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
}
