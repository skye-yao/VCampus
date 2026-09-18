package service;

import dao.TeacherApplicationDAO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
import dto.course.teacher.TeacherPageDTO;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Guarded MySQL coverage for the unified 「我的申请」 surface and the read-receipt model.
 *
 * <p>What it pins, in the order the design states it:
 *
 * <ul>
 *   <li><b>合并分页在 SQL 里。</b>两条事实表的行合起来排一次序、取一页：{@code totalCount} 是合并
 *       后的总数，第 1 页与第 2 页不重叠，并列的提交时间由 {@code (type, id DESC)} 决定。</li>
 *   <li><b>两张表的状态字母表不同。</b>同一个状态字符串在两条分支上的含义不同：成绩提交没有
 *       WITHDRAWN，所以「成绩提交 + 已撤销」必须是参数错误，而不是一条悄悄放过全部成绩行的查询。</li>
 *   <li><b>不同类型可以有同一个数字 ID。</b>回执主键是「教师+类型+ID」，标记一条绝不会波及另一条。</li>
 *   <li><b>读过的 PENDING 不会让随后的 APPROVED 变成已读。</b>状态键随结果变化而改变；
 *       用旧键标记会被拒绝（CAS），且什么都不写。</li>
 *   <li><b>归属只按申请人的字段。</b>别人的申请不可见；教学班成员关系解除之后，自己的历史仍然可读。</li>
 * </ul>
 *
 * <p>Fixtures live in the 946000-946999 band with the {@code tap946-} prefix (945xxx belongs to
 * TeacherAdjustmentApplicationMySqlTest, 947xxx/948xxx/949xxx to the grade draft, submission and
 * revision tests, 970xxx/974xxx to the administrator approvals), and every fixture is removed by
 * the same prefix again. Without a {@code mysql} argument the test prints SKIP and is never
 * reported as passing.
 */
public final class TeacherApplicationsMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tap946-";

    private static final String TEACHER_A = PREFIX + "teacher-a";
    private static final String TEACHER_B = PREFIX + "teacher-b";
    private static final String TEACHER_EMPTY = PREFIX + "teacher-empty";
    private static final String ADMIN = PREFIX + "admin";

    private static final long COURSE = 946201L;
    private static final long OFFERING_MAIN = 946301L;
    private static final long OFFERING_OTHER = 946302L;
    private static final long CLASSROOM = 946101L;

    /** A PENDING adjustment; the detail variant is pinned by its head fields, no targets needed. */
    private static final long ADJ_PENDING = 946501L;
    /** Two rows sharing one submitted_at, to pin the (type, id DESC) tiebreaker. */
    private static final long ADJ_TIE_HIGH = 946503L;
    private static final long ADJ_TIE_LOW = 946502L;
    /** Withdrawn: reviewed_at is NULL and only withdrawn_at marks it as handled. */
    private static final long ADJ_WITHDRAWN = 946504L;
    /** The numeric ID this row shares with SUB_SHARED, in the other fact table. */
    private static final long ADJ_SHARED_ID = 946601L;
    /** Requested by TEACHER_B: must never show up in TEACHER_A's list. */
    private static final long ADJ_FOREIGN = 946701L;

    private static final long SUB_TIE = 946401L;
    private static final long SUB_PENDING = 946402L;
    /** Same numeric ID as ADJ_SHARED_ID. */
    private static final long SUB_SHARED_ID = 946601L;
    private static final long SUB_FOREIGN = 946702L;

    private static final String TIE_AT = "2026-09-12 02:00:00";
    private static final String PENDING_AT = "2026-09-10 01:00:00";
    private static final String SHARED_ADJ_AT = "2026-09-11 03:00:00";
    private static final String SHARED_SUB_AT = "2026-09-11 04:00:00";
    private static final String PENDING_SUB_AT = "2026-09-09 01:00:00";
    private static final String WITHDRAWN_AT = "2026-09-08 01:00:00";
    private static final String WITHDRAWN_HANDLED_AT = "2026-09-08 05:00:00";
    private static final String APPROVED_AT = "2026-09-14 07:00:00";
    private static final String CLOCK_TEXT = "2026-09-14 06:30:00";

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher applications test "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            TeacherApplicationService service = service(new TeacherApplicationDAO());
            verifyCombinedPaging(service);
            verifyTypeAndStatusWhitelists(service);
            verifySameNumericIdAcrossTypes(service);
            verifyDetailVariants(service);
            verifyOwnership(service);
            verifyPagingIsStableAcrossPages(service);
            verifyUnreadModel(service);
            verifyStaleReadConfirmation(service);
            verifyHistorySurvivesLosingTheOffering(service);
            verifyNoRecords(service);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher applications MySQL test passed.");
    }

    // ------------------------------------------------------------- 合并分页

    private static void verifyCombinedPaging(TeacherApplicationService service) throws Exception {
        TeacherPageDTO<TeacherApplicationDTO> page = service.listMyApplications(TEACHER_A, null, null,
                1, 100);
        require(page.getTotalCount() == 8,
                "the total is the combined count of both fact tables, saw " + page.getTotalCount());
        require(keys(page).equals(List.of(
                        "GRADE_SUBMISSION|" + SUB_TIE,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_TIE_HIGH,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_TIE_LOW,
                        "GRADE_SUBMISSION|" + SUB_SHARED_ID,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_SHARED_ID,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_PENDING,
                        "GRADE_SUBMISSION|" + SUB_PENDING,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_WITHDRAWN)),
                "the merged order must be (submittedAt DESC, type, id DESC), saw " + keys(page));
        require(page.getItems().stream().allMatch(row ->
                        ("Applications 946 Course　TAP946-A".equals(row.getTitle())
                                || "Applications 946 Course　TAP946-B".equals(row.getTitle()))),
                "both branches produce the same title shape, saw "
                        + page.getItems().stream().map(TeacherApplicationDTO::getTitle).toList());

        // 并列的提交时间由 type 再按 id DESC 决定：先成绩分支，再调课分支里 ID 大的那一条。
        TeacherPageDTO<TeacherApplicationDTO> tied = filtered(service, null, null);
        require("GRADE_SUBMISSION".equals(tied.getItems().get(0).getType())
                        && Long.toString(SUB_TIE).equals(tied.getItems().get(0).getId())
                        && Long.toString(ADJ_TIE_HIGH).equals(tied.getItems().get(1).getId()),
                "records submitted at the same instant are ordered by type then id DESC, saw "
                        + keys(tied));

        require(filtered(service, TeacherApplicationDTO.GRADE_SUBMISSION, null).getTotalCount() == 3
                        && filtered(service, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, null)
                        .getTotalCount() == 5,
                "each type filter counts only its own branch");
    }

    // ------------------------------------------------- 类型与状态的白名单

    private static void verifyTypeAndStatusWhitelists(TeacherApplicationService service) {
        // 两张表的状态字母表确实不同：同一个字符串只对其中一张合法。
        require(TeacherApplicationDTO.isStatus(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "WITHDRAWN")
                        && !TeacherApplicationDTO.isStatus(TeacherApplicationDTO.GRADE_SUBMISSION,
                                "WITHDRAWN"),
                "WITHDRAWN exists only for schedule adjustments");

        expect(IllegalArgumentException.class,
                () -> service.listMyApplications(TEACHER_A, TeacherApplicationDTO.GRADE_SUBMISSION,
                        "WITHDRAWN", 1, 20),
                "a status outside the type's alphabet is rejected instead of being ignored");
        expect(IllegalArgumentException.class,
                () -> service.listMyApplications(TEACHER_A, "SOMETHING_ELSE", null, 1, 20),
                "an unknown application type is rejected");
        expect(IllegalArgumentException.class,
                () -> service.listMyApplications(TEACHER_A, null, "CANCELLED", 1, 20),
                "an unknown status is rejected even when the type is open");
        expect(IllegalArgumentException.class,
                () -> service.listMyApplications(TEACHER_A, null, null, 0, 20),
                "page 0 is rejected");
        expect(IllegalArgumentException.class,
                () -> service.listMyApplications(TEACHER_A, null, null, 1, 101),
                "size above 100 is rejected");

        TeacherPageDTO<TeacherApplicationDTO> withdrawn = service.listMyApplications(TEACHER_A, null,
                "WITHDRAWN", 1, 20);
        require(withdrawn.getTotalCount() == 1
                        && TeacherApplicationDTO.SCHEDULE_ADJUSTMENT
                        .equals(withdrawn.getItems().get(0).getType())
                        && Long.toString(ADJ_WITHDRAWN).equals(withdrawn.getItems().get(0).getId()),
                "an open-type WITHDRAWN filter matches only the adjustment branch, saw "
                        + keys(withdrawn));

        TeacherPageDTO<TeacherApplicationDTO> pending = service.listMyApplications(TEACHER_A, null,
                "PENDING", 1, 20);
        require(pending.getTotalCount() == 3
                        && keys(pending).contains("SCHEDULE_ADJUSTMENT|" + ADJ_PENDING)
                        && keys(pending).contains("GRADE_SUBMISSION|" + SUB_PENDING)
                        && keys(pending).contains("SCHEDULE_ADJUSTMENT|" + ADJ_SHARED_ID),
                "PENDING exists in both alphabets, so it matches both branches, saw "
                        + keys(pending));
    }

    // ---------------------------------------------- 不同类型相同数字 ID

    private static void verifySameNumericIdAcrossTypes(TeacherApplicationService service)
            throws Exception {
        TeacherPageDTO<TeacherApplicationDTO> adjustments =
                filtered(service, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, null);
        TeacherPageDTO<TeacherApplicationDTO> submissions =
                filtered(service, TeacherApplicationDTO.GRADE_SUBMISSION, null);
        require(keys(adjustments).contains("SCHEDULE_ADJUSTMENT|" + ADJ_SHARED_ID)
                        && keys(submissions).contains("GRADE_SUBMISSION|" + SUB_SHARED_ID)
                        && ADJ_SHARED_ID == SUB_SHARED_ID,
                "one adjustment and one submission carry the same numeric ID");

        TeacherApplicationDTO shared = null;
        for (TeacherApplicationDTO row : adjustments.getItems()) {
            if (Long.toString(ADJ_SHARED_ID).equals(row.getId())) shared = row;
        }
        require(shared != null && shared.isUnread(),
                "the shared ID starts unread on the adjustment side");
        TeacherApplicationDTO submission = null;
        for (TeacherApplicationDTO row : submissions.getItems()) {
            if (Long.toString(SUB_SHARED_ID).equals(row.getId())) submission = row;
        }
        require(submission != null && submission.isUnread(),
                "the shared ID starts unread on the submission side");

        TeacherApplicationDTO read = service.markApplicationRead(TEACHER_A,
                new MarkTeacherApplicationReadDTO(TeacherApplicationDTO.GRADE_SUBMISSION,
                        Long.toString(SUB_SHARED_ID), submission.getStateKey()));
        require(!read.isUnread(), "marking the submission read clears its own badge");

        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='" + TEACHER_A
                        + "' AND application_type='GRADE_SUBMISSION' AND application_id="
                        + SUB_SHARED_ID) == 1,
                "exactly one receipt row exists for the submission half");
        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='" + TEACHER_A
                        + "' AND application_type='SCHEDULE_ADJUSTMENT' AND application_id="
                        + ADJ_SHARED_ID) == 0,
                "the receipt of one type never leaks into the other type with the same ID");
        require(applicationById(service, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_SHARED_ID)
                        .isUnread(),
                "the adjustment with the same numeric ID stays unread");

        // 详情也各走各的类型化变体。
        TeacherApplicationDetailDTO adjustmentDetail = service.getMyApplication(TEACHER_A,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, Long.toString(ADJ_SHARED_ID));
        require(adjustmentDetail.getAdjustment() != null && adjustmentDetail.getGrade() == null,
                "the adjustment ID resolves to the adjustment variant");
        TeacherApplicationDetailDTO submissionDetail = service.getMyApplication(TEACHER_A,
                TeacherApplicationDTO.GRADE_SUBMISSION, Long.toString(SUB_SHARED_ID));
        require(submissionDetail.getGrade() != null && submissionDetail.getAdjustment() == null,
                "the submission ID resolves to the grade variant");
    }

    // ------------------------------------------------------------ 详情变体

    private static void verifyDetailVariants(TeacherApplicationService service) {
        TeacherApplicationDetailDTO pending = service.getMyApplication(TEACHER_A,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, Long.toString(ADJ_PENDING));
        require(pending.getAdjustment() != null && pending.getGrade() == null,
                "an adjustment detail carries exactly the adjustment variant");
        require(pending.getSummary().isCanWithdraw(),
                "a PENDING adjustment is the only row the page may offer to withdraw");
        require(Long.toString(ADJ_PENDING).equals(pending.getAdjustment().getRequestId())
                        && "待审批夹具".equals(pending.getAdjustment().getReason())
                        && pending.getAdjustment().getVersion() == 1,
                "the adjustment variant must be the real application, not a summary copy");
        require(pending.getAdjustment().getConflicts().isEmpty(),
                "an unverifiable conflict snapshot degrades to empty, never to an error");

        TeacherApplicationDetailDTO grade = service.getMyApplication(TEACHER_A,
                TeacherApplicationDTO.GRADE_SUBMISSION, Long.toString(SUB_TIE));
        require(grade.getGrade() != null && grade.getAdjustment() == null,
                "a grade detail carries exactly the snapshot variant");
        require(grade.getGrade().getSummary() != null
                        && Long.toString(SUB_TIE).equals(grade.getGrade().getSummary().getSubmissionId())
                        && grade.getSummary().getReviewComment() != null,
                "the grade snapshot is the immutable batch, review comment included");
        require(!grade.getSummary().isCanWithdraw(),
                "a grade submission has no withdrawal: the fact table has no withdrawn_at at all");
    }

    // -------------------------------------------------------------- 归属

    private static void verifyOwnership(TeacherApplicationService service) throws Exception {
        TeacherPageDTO<TeacherApplicationDTO> foreign =
                service.listMyApplications(TEACHER_B, null, null, 1, 100);
        require(keys(foreign).equals(List.of("GRADE_SUBMISSION|" + SUB_FOREIGN,
                        "SCHEDULE_ADJUSTMENT|" + ADJ_FOREIGN)),
                "another teacher sees exactly their own two rows, saw " + keys(foreign));
        require(!keys(foreign).contains("SCHEDULE_ADJUSTMENT|" + ADJ_PENDING),
                "another teacher's list never contains these requests");

        expect(TeacherApplicationService.NotFoundException.class,
                () -> service.getMyApplication(TEACHER_B, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        Long.toString(ADJ_PENDING)),
                "another teacher's adjustment detail is never visible");
        expect(TeacherApplicationService.NotFoundException.class,
                () -> service.getMyApplication(TEACHER_B, TeacherApplicationDTO.GRADE_SUBMISSION,
                        Long.toString(SUB_TIE)),
                "another teacher's submission detail is never visible");
        expect(TeacherApplicationService.NotFoundException.class,
                () -> service.getMyApplication(TEACHER_A, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        "946899"),
                "a missing application is not found");

        expect(TeacherApplicationService.NotFoundException.class,
                () -> service.markApplicationRead(TEACHER_B,
                        new MarkTeacherApplicationReadDTO(
                                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                                Long.toString(ADJ_PENDING), "PENDING:x")),
                "another teacher cannot mark somebody else's application read");
        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='" + TEACHER_B
                        + "'") == 0,
                "the refused mark writes no receipt");
        expect(IllegalArgumentException.class,
                () -> service.getMyApplication(TEACHER_A, null, Long.toString(ADJ_PENDING)),
                "the detail needs a concrete type");
        expect(IllegalArgumentException.class,
                () -> service.getMyApplication(TEACHER_A, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        "abc"),
                "a non-decimal id is rejected");
    }

    // ------------------------------------------------------- 稳定分页

    private static void verifyPagingIsStableAcrossPages(TeacherApplicationService service) {
        TeacherPageDTO<TeacherApplicationDTO> first =
                service.listMyApplications(TEACHER_A, null, null, 1, 2);
        TeacherPageDTO<TeacherApplicationDTO> second =
                service.listMyApplications(TEACHER_A, null, null, 2, 2);
        TeacherPageDTO<TeacherApplicationDTO> third =
                service.listMyApplications(TEACHER_A, null, null, 3, 2);
        TeacherPageDTO<TeacherApplicationDTO> fourth =
                service.listMyApplications(TEACHER_A, null, null, 4, 2);
        require(first.getTotalCount() == 8 && second.getTotalCount() == 8
                        && fourth.getTotalCount() == 8,
                "every page reports the combined total, not the page length");
        require(first.getItems().size() == 2 && second.getItems().size() == 2
                        && third.getItems().size() == 2 && fourth.getItems().size() == 2,
                "a page is exactly as large as asked while rows remain");

        List<String> walked = new ArrayList<>();
        walked.addAll(keys(first));
        walked.addAll(keys(second));
        walked.addAll(keys(third));
        walked.addAll(keys(fourth));
        require(walked.size() == 8 && walked.stream().distinct().count() == 8,
                "walking the pages yields every row exactly once, saw " + walked);
        require(walked.equals(keys(service.listMyApplications(TEACHER_A, null, null, 1, 100))),
                "the paged walk and the single large page agree on the order");

        TeacherPageDTO<TeacherApplicationDTO> beyond =
                service.listMyApplications(TEACHER_A, null, null, 9, 2);
        require(beyond.getItems().isEmpty() && beyond.getTotalCount() == 8,
                "a page beyond the end is empty but still reports the true total");
    }

    // ------------------------------------------------------------ 未读模型

    private static void verifyUnreadModel(TeacherApplicationService service) throws Exception {
        TeacherApplicationDTO pending = applicationById(service,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_PENDING);
        require(pending.isUnread(), "without a receipt the row is unread");
        require(pending.getStateKey().equals("PENDING:" + utc(PENDING_AT)),
                "the state key of an unhandled row falls back to the submission time, saw "
                        + pending.getStateKey());
        require(pending.getHandledAt() == null,
                "an unhandled row has no handledAt of its own");

        TeacherApplicationDTO read = service.markApplicationRead(TEACHER_A,
                new MarkTeacherApplicationReadDTO(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        Long.toString(ADJ_PENDING), pending.getStateKey()));
        require(!read.isUnread() && read.getStateKey().equals(pending.getStateKey()),
                "a matching confirmation clears the badge and keeps the key");
        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='" + TEACHER_A
                        + "' AND application_type='SCHEDULE_ADJUSTMENT' AND application_id="
                        + ADJ_PENDING + " AND seen_state_key='" + pending.getStateKey()
                        + "' AND read_at='" + CLOCK_TEXT + "'") == 1,
                "the receipt stores the seen key and the injected clock");

        // 同一个键重复标记是幂等的：还是一条回执。
        TeacherApplicationDTO again = service.markApplicationRead(TEACHER_A,
                new MarkTeacherApplicationReadDTO(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        Long.toString(ADJ_PENDING), pending.getStateKey()));
        require(!again.isUnread()
                        && count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='"
                        + TEACHER_A + "' AND application_type='SCHEDULE_ADJUSTMENT'"
                        + " AND application_id=" + ADJ_PENDING) == 1,
                "marking the same key twice keeps exactly one receipt");

        // 管理员随后通过：状态键变了，读过 PENDING 不代表读过 APPROVED。
        execute("UPDATE course_schedule_adjustment_request SET status='APPROVED',reviewed_by='"
                + ADMIN + "',reviewed_at='" + APPROVED_AT + "',review_comment='同意'"
                + " WHERE request_id=" + ADJ_PENDING);
        TeacherApplicationDTO approved = applicationById(service,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_PENDING);
        require(approved.isUnread(),
                "reading PENDING does not read the APPROVED result that arrives afterwards");
        require(approved.getStateKey().equals("APPROVED:" + utc(APPROVED_AT))
                        && utc(APPROVED_AT).equals(approved.getHandledAt()),
                "the new key is built from the review time, saw " + approved.getStateKey());
        require(!approved.isCanWithdraw(),
                "a handled adjustment is terminal and offers no withdrawal");

        TeacherApplicationDTO approvedRead = service.markApplicationRead(TEACHER_A,
                new MarkTeacherApplicationReadDTO(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        Long.toString(ADJ_PENDING), approved.getStateKey()));
        require(!approvedRead.isUnread(), "the new key can be read in turn");

        // 撤销也是「被处理」：它的键必须来自 withdrawn_at，而不是提交时间。
        TeacherApplicationDTO withdrawn = applicationById(service,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_WITHDRAWN);
        require(withdrawn.getStateKey().equals("WITHDRAWN:" + utc(WITHDRAWN_HANDLED_AT)),
                "a withdrawn request keys off withdrawn_at, saw " + withdrawn.getStateKey());
        require(utc(WITHDRAWN_HANDLED_AT).equals(withdrawn.getHandledAt())
                        && !withdrawn.getStateKey().contains(utc(WITHDRAWN_AT)),
                "the withdrawal time is the handled time, never the submission time");
    }

    // -------------------------------------------------- 过期读取确认

    private static void verifyStaleReadConfirmation(TeacherApplicationService service)
            throws Exception {
        TeacherApplicationDTO approved = applicationById(service,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_PENDING);
        String stale = "PENDING:" + utc(PENDING_AT);
        TeacherApplicationService.ConflictException refusal = expect(
                TeacherApplicationService.ConflictException.class,
                () -> service.markApplicationRead(TEACHER_A,
                        new MarkTeacherApplicationReadDTO(
                                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                                Long.toString(ADJ_PENDING), stale)),
                "a confirmation made against an older result is refused");
        require(refusal.getEntity() != null
                        && refusal.getEntity().getStateKey().equals(approved.getStateKey()),
                "the refusal carries the current row, not the stale one");
        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid='" + TEACHER_A
                        + "' AND application_type='SCHEDULE_ADJUSTMENT' AND application_id="
                        + ADJ_PENDING + " AND seen_state_key='" + stale + "'") == 0,
                "the stale confirmation writes nothing");
        require(!applicationById(service, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, ADJ_PENDING)
                        .isUnread(),
                "the already-read APPROVED result stays read after the refused stale write");
    }

    // ---------------------------------------- 失去教学班关系仍可读历史

    private static void verifyHistorySurvivesLosingTheOffering(TeacherApplicationService service)
            throws Exception {
        require(count("SELECT COUNT(*) FROM course_offering_teacher WHERE uid='" + TEACHER_A
                        + "' AND offering_id=" + OFFERING_MAIN + " AND role=0") == 1,
                "the fixture must start with the teaching-class relation in place, otherwise the"
                        + " assertion below would pass vacuously");
        long before = service.listMyApplications(TEACHER_A, null, null, 1, 100).getTotalCount();
        require(before == 8, "the history is visible while the relation still holds");

        execute("DELETE FROM course_offering_teacher WHERE uid='" + TEACHER_A
                + "' AND offering_id=" + OFFERING_MAIN);
        require(count("SELECT COUNT(*) FROM course_offering_teacher WHERE uid='" + TEACHER_A
                        + "' AND offering_id=" + OFFERING_MAIN) == 0,
                "the teaching-class relation really is gone");

        require(service.listMyApplications(TEACHER_A, null, null, 1, 100).getTotalCount() == 8,
                "losing the teaching class does not hide the teacher's own history");
        TeacherApplicationDetailDTO detail = service.getMyApplication(TEACHER_A,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, Long.toString(ADJ_TIE_HIGH));
        require(detail.getAdjustment() != null,
                "the history stays readable by detail after the relation is gone");
    }

    // ------------------------------------------------------------ 无记录

    private static void verifyNoRecords(TeacherApplicationService service) {
        TeacherPageDTO<TeacherApplicationDTO> empty =
                service.listMyApplications(TEACHER_EMPTY, null, null, 1, 20);
        require(empty.getItems().isEmpty() && empty.getTotalCount() == 0,
                "an account with no application gets an empty page with a zero total");
        require(service.listMyApplications(TEACHER_EMPTY, TeacherApplicationDTO.GRADE_SUBMISSION,
                        "APPROVED", 1, 20).getTotalCount() == 0,
                "a filtered empty page is empty too");
        expect(TeacherApplicationService.NotFoundException.class,
                () -> service.getMyApplication(TEACHER_EMPTY,
                        TeacherApplicationDTO.GRADE_SUBMISSION, "946899"),
                "no records means no detail either");
    }

    // ------------------------------------------------------------------ helpers

    private static TeacherApplicationService service(TeacherApplicationDAO dao) {
        Clock clock = Clock.fixed(Timestamp.valueOf(CLOCK_TEXT).toLocalDateTime()
                .toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new TeacherApplicationService(dao, new TeacherAdjustmentApplicationService(),
                new GradeApprovalService(), clock);
    }

    private static TeacherPageDTO<TeacherApplicationDTO> filtered(TeacherApplicationService service,
            String type, String status) {
        return service.listMyApplications(TEACHER_A, type, status, 1, 100);
    }

    private static TeacherApplicationDTO applicationById(TeacherApplicationService service,
            String type, long id) {
        TeacherPageDTO<TeacherApplicationDTO> page =
                service.listMyApplications(TEACHER_A, type, null, 1, 100);
        for (TeacherApplicationDTO row : page.getItems()) {
            if (Long.toString(id).equals(row.getId())) return row;
        }
        throw new AssertionError("no " + type + " " + id + " in " + keys(page));
    }

    private static List<String> keys(TeacherPageDTO<TeacherApplicationDTO> page) {
        List<String> values = new ArrayList<>();
        for (TeacherApplicationDTO row : page.getItems()) {
            values.add(row.getType() + "|" + row.getId());
        }
        return values;
    }

    /** 列里存的 UTC 墙钟字面量：ISO 时刻文本，就是服务端映射后 DTO 上的那一个。 */
    private static String utc(String utcLiteral) {
        return DateTimeFormatter.ISO_INSTANT.format(
                Timestamp.valueOf(utcLiteral).toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_A + "','Tap946 Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Tap946 Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_EMPTY + "','Tap946 Teacher Empty','x','x',1,'Engineering','Lect'),"
                + "('" + ADMIN + "','Tap946 Admin','x','x',0,'Administration','Registrar')");

        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE + ",'TAP946','Applications 946 Course',"
                + "3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'TAP946-A'," + COURSE
                + ",2026,3,30,2),(" + OFFERING_OTHER + ",'TAP946-B'," + COURSE + ",2026,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES("
                + OFFERING_MAIN + ",'" + TEACHER_A + "',0),(" + OFFERING_MAIN + ",'" + TEACHER_B
                + "',1),(" + OFFERING_OTHER + ",'" + TEACHER_B + "',0)");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(" + CLASSROOM
                + ",'Tap946 Room',60,1)");

        request(ADJ_PENDING, OFFERING_MAIN, TEACHER_A, "待审批夹具", "PENDING", PENDING_AT, null, null,
                null, null);
        request(ADJ_TIE_HIGH, OFFERING_MAIN, TEACHER_A, "并列夹具", "APPROVED", TIE_AT, ADMIN,
                "2026-09-13 01:00:00", "同意", null);
        request(ADJ_TIE_LOW, OFFERING_OTHER, TEACHER_A, "并列夹具", "REJECTED", TIE_AT, ADMIN,
                "2026-09-13 02:00:00", "材料不足", null);
        request(ADJ_WITHDRAWN, OFFERING_MAIN, TEACHER_A, "撤销夹具", "WITHDRAWN", WITHDRAWN_AT, null,
                null, null, WITHDRAWN_HANDLED_AT);
        request(ADJ_SHARED_ID, OFFERING_MAIN, TEACHER_A, "同号夹具", "PENDING", SHARED_ADJ_AT, null,
                null, null, null);
        request(ADJ_FOREIGN, OFFERING_MAIN, TEACHER_B, "别人的申请", "PENDING", PENDING_AT, null, null,
                null, null);

        submission(SUB_TIE, OFFERING_MAIN, TEACHER_A, 1, "APPROVED", TIE_AT,
                "2026-09-13 03:00:00", "同意，成绩已发布");
        submission(SUB_PENDING, OFFERING_MAIN, TEACHER_A, 2, "PENDING", PENDING_SUB_AT, null, null);
        submission(SUB_SHARED_ID, OFFERING_OTHER, TEACHER_A, 1, "REJECTED", SHARED_SUB_AT,
                "2026-09-13 04:00:00", "总分与平时分不一致");
        // 同一个教学班的批次版本唯一（uk_grade_submission_offering_version），所以给这一条下一版。
        submission(SUB_FOREIGN, OFFERING_OTHER, TEACHER_B, 2, "APPROVED", PENDING_AT,
                "2026-09-13 05:00:00", "同意");
    }

    private static void request(long requestId, long offeringId, String requester, String reason,
            String status, String submittedAt, String reviewedBy, String reviewedAt,
            String reviewComment, String withdrawnAt) throws SQLException {
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,requested_by,"
                + "reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_classroom_id,submitted_at,reviewed_by,reviewed_at,review_comment,"
                + "withdrawn_at) VALUES(" + requestId + "," + offeringId + ",'" + requester + "','"
                + reason + "',1,'" + status + "',5,5,6,NULL,'" + submittedAt + "',"
                + sql(reviewedBy) + "," + sql(reviewedAt) + "," + sql(reviewComment) + ","
                + sql(withdrawnAt) + ")");
    }

    private static void submission(long submissionId, long offeringId, String submittedBy,
            int version, String status, String submittedAt, String reviewedAt, String reviewComment)
            throws SQLException {
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,"
                + "submitted_at,status,reviewed_by,reviewed_at,review_comment,average_score,"
                + "max_score,min_score,failed_count,total_count) VALUES(" + submissionId + ","
                + offeringId + "," + version + ",'" + submittedBy + "','" + submittedAt + "','"
                + status + "'," + sql(reviewedAt == null ? null : ADMIN) + ","
                + sql(reviewedAt) + "," + sql(reviewComment) + ",NULL,NULL,NULL,0,0)");
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM teacher_application_read WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM grade_submission WHERE submitted_by LIKE '" + PREFIX + "%'");
        execute("DELETE FROM course_schedule_adjustment_target WHERE request_id IN (SELECT"
                + " request_id FROM course_schedule_adjustment_request WHERE requested_by LIKE '"
                + PREFIX + "%')");
        execute("DELETE FROM course_schedule_adjustment_request WHERE requested_by LIKE '"
                + PREFIX + "%'");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 946300 AND 946399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 946300 AND 946399");
        execute("DELETE FROM course WHERE course_id BETWEEN 946200 AND 946299");
        execute("DELETE FROM classroom WHERE id BETWEEN 946100 AND 946199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teacher_application_read WHERE teacher_uid LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submitted_by LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE"
                        + " requested_by LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target t JOIN"
                        + " course_schedule_adjustment_request r ON r.request_id=t.request_id"
                        + " WHERE r.requested_by LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 946300 AND 946399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 946200"
                        + " AND 946299") == 0
                        && count("SELECT COUNT(*) FROM classroom WHERE id BETWEEN 946100"
                        + " AND 946199") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'")
                        == 0,
                "cleanup must leave no fixture row behind");
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
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
                "Refusing the teacher applications test: the JDBC URL must target the guarded"
                        + " schema, saw " + database);
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing the teacher applications test outside the guarded schema");
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

    private TeacherApplicationsMySqlTest() {
    }
}
