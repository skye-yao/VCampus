package service;

import dao.TeacherCourseOperationDAO;
import dao.TeacherGradeAuditDAO;
import dao.TeacherGradeBookDAO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import session.SessionManager;
import session.UserSession;
import util.DBUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Guarded MySQL coverage for the teacher grade import preview and its atomic confirm: a preview that
 * writes nothing, a partial import that keeps the base draft wherever the file has no value, an
 * illegal cell whose original text survives as an issue, unknown and duplicate student uids that are
 * never silently skipped, corrections and explicit exclusions with an incrementing preview revision,
 * roster/draft changes caught on both sides of the preview, expired and cross-teacher tokens, and
 * the confirm replay that survives a lost response.
 *
 * <p>Fixtures live in the 948xxx band (CourseConflictMySqlTest uses 9000xx-9002xx,
 * TeacherAdjustmentConflictMySqlTest 9003xx-9008xx, ScheduleManagementMySqlTest 930xxx/940001,
 * TeacherAdjustmentApplicationMySqlTest 945000-945899, TeacherGradeDraftMySqlTest 947000-947999,
 * ScheduleAdjustmentApprovalMySqlTest 970xxx and GradeApprovalMySqlTest 974xxx) with the
 * {@code tgi948-} requester prefix, and every fixture row is deleted again by {@link #cleanup()}.
 * Without a {@code mysql} argument the test prints SKIP and is never reported as passing.
 *
 * <p>The upload is simulated at the ticket-service seam rather than over a socket, using the same
 * call order the file connection uses (claim the transfer, land the bytes on the server-named path,
 * hand the ticket over as uploaded) so the preview resolves exactly the file this upload produced.
 */
public final class TeacherGradeImportMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tgi948-";

    private static final String TEACHER_A = PREFIX + "teacher-a";
    private static final String TEACHER_B = PREFIX + "teacher-b";

    private static final long COURSE_MAIN = 948201L;
    private static final long OFFERING_MAIN = 948301L;

    private static final long S1 = 948401L;
    private static final long S2 = 948402L;
    private static final long S3 = 948403L;
    private static final long S4 = 948404L;
    /** A normal enrollment added after a preview: the roster digest must change under the token. */
    private static final long S5 = 948405L;

    /** sha256 of the canonical normal roster "948401\n948402\n948403\n948404\n". */
    private static final String ROSTER_DIGEST =
            "6bcc679a46801a5f84a32615e222e56c9422b7acfae42e74735b9a2ab2140958";
    /** sha256 of "948401\n": 另一份名单的摘要，用来证明预览会核对编辑副本里的名单。 */
    private static final String OTHER_ROSTER_DIGEST =
            "681cb500d2d51728becb82cac2fc97dd0bdcdd91d8a00636b1017b1453f0a32c";

    private static final String OFFERING_ID = Long.toString(OFFERING_MAIN);
    private static final List<String> HEADERS =
            List.of("学号", "姓名", "平时成绩", "期中成绩", "实验成绩");

    private static TeacherFileTicketService tickets;
    private static MutableClock clock;
    private static Path tempDirectory;
    private static UserSession sessionA;
    private static UserSession sessionB;

    private TeacherGradeImportMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher grade import test was not "
                    + "run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        sessionA = SessionManager.getInstance().createSession(TEACHER_A, "教师");
        sessionB = SessionManager.getInstance().createSession(TEACHER_B, "教师");
        tempDirectory = Files.createTempDirectory("teacher-grade-import-test-");
        clock = new MutableClock();
        tickets = new TeacherFileTicketService(0, clock);
        TeacherGradeImportStore store = new TeacherGradeImportStore(clock);
        TeacherGradeBookService grades = gradeService();
        TeacherGradeImportService service = new TeacherGradeImportService(tickets, grades, store);
        try {
            verifyPreviewKeepsCandidateAndIssuesApart(service, grades, store);
            verifyReviseCorrectsExcludesAndBlocks(service, grades, store);
            verifyConfirmReplaysAfterLostResponse(service, grades, store);
            verifyRosterAndDraftChangesConflict(service, grades, store);
            verifyExpiredAndForeignTokens(service, grades, store);
            verifyUploadTicketsStaySingleUse(grades);
        } finally {
            tickets.close();
            deleteRecursively(tempDirectory);
            SessionManager.getInstance().removeSession(sessionA.getToken());
            SessionManager.getInstance().removeSession(sessionB.getToken());
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher grade import MySQL test passed.");
    }

    // ------------------------------------------------- preview: candidate vs issues

    private static void verifyPreviewKeepsCandidateAndIssuesApart(TeacherGradeImportService service,
            TeacherGradeBookService grades, TeacherGradeImportStore store) throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        require(book.getRevision() == 0 && book.isCanEdit(),
                "the fixture offering starts without a working copy");
        require(ROSTER_DIGEST.equals(book.getRosterDigest()),
                "the fixture digest is the sorted normal-enrollment-id SHA-256 (observed "
                        + book.getRosterDigest() + ")");
        GradeBookContentDTO base = baseDraft(book, baseScores());

        String ticket = upload(TEACHER_A, 0, importWorkbook("first.xlsx"));
        GradeImportPreviewDTO preview = service.preview(TEACHER_A, sessionA,
                new PreviewGradeImportRequestDTO(ticket, base));

        require(preview.getPreviewRevision() == 1, "a fresh preview starts at revision 1 (observed "
                + preview.getPreviewRevision() + ")");
        require(preview.getTotalRows() == 6 && preview.getValidRows() == 3
                        && preview.getErrorRows() == 3,
                "rows are counted as total/valid/error 6/3/3 (observed " + preview.getTotalRows()
                        + "/" + preview.getValidRows() + "/" + preview.getErrorRows() + ")");
        require(preview.getExpiresAt() != null && !preview.getExpiresAt().isBlank(),
                "the preview must carry its expiry instant");

        // 预览不写库：工作副本、草稿明细、变更审计与教师操作日志都不许出现新行。
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN)
                        == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == 0
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid"
                        + "='" + TEACHER_A + "'") == 0,
                "a preview writes no working copy, item, audit or operation-log row");
        require(tempFiles(tickets.getTempDirectory()).isEmpty(),
                "a successful preview deletes the uploaded workbook right away, saw "
                        + tempFiles(tickets.getTempDirectory()));

        Map<Integer, List<GradeImportRowIssueDTO>> byRow = issuesByRow(preview);
        GradeImportRowIssueDTO badScore = issueWithField(byRow, 3,
                GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE);
        require("abc".equals(badScore.getRawValue()) && badScore.getMessage().contains("期中成绩")
                        && !badScore.isExcluded(),
                "an illegal cell keeps the teacher's original text (observed "
                        + badScore.getField() + "/" + badScore.getRawValue() + ")");
        GradeImportRowIssueDTO unknownUid =
                issueWithField(byRow, 4, GradeImportRowIssueDTO.FIELD_STUDENT_UID);
        require("0000".equals(unknownUid.getRawValue()),
                "an unknown student uid is reported instead of being skipped silently (observed "
                        + unknownUid.getRawValue() + ")");
        GradeImportRowIssueDTO duplicateUid =
                issueWithField(byRow, 5, GradeImportRowIssueDTO.FIELD_STUDENT_UID);
        require("tgi948-s2".equals(duplicateUid.getRawValue())
                        && duplicateUid.getMessage().contains("重复"),
                "a duplicated student uid is reported on every row that carries it (observed "
                        + duplicateUid.getRawValue() + ")");
        require(byRow.getOrDefault(6, List.of()).isEmpty()
                        && byRow.getOrDefault(7, List.of()).isEmpty()
                        && byRow.getOrDefault(2, List.of()).isEmpty(),
                "a missing column, a blank cell and a partial row are not errors, saw " + byRow);

        Map<Long, GradeScoresDTO> candidate = candidateScores(preview);
        require(same(scores(candidate, S1), scores("88.5", "61", "77", "63")),
                "the imported cell wins while the blank and the absent column keep the base draft "
                        + "(observed " + text(scores(candidate, S1)) + ")");
        require(same(scores(candidate, S3), scores("100", "100", "100", "83")),
                "a column absent from the file keeps the base value for every row (observed "
                        + text(scores(candidate, S3)) + ")");
        require(same(scores(candidate, S4), scores("90", "91", "80", "93")),
                "blank cells keep the base values and never become 0 (observed "
                        + text(scores(candidate, S4)) + ")");
        require(same(scores(candidate, S2), baseScores().get(S2)),
                "a row with an unresolved error is not merged into the candidate at all");
        require(preview.getCandidate().getRosterDigest().equals(book.getRosterDigest())
                        && preview.getCandidate().getExpectedRevision() == 0
                        && OFFERING_ID.equals(preview.getCandidate().getOfferingId()),
                "the candidate carries the server's roster digest and base revision");

        // 一个上传只能换一次预览：票据在预览阶段被消费。
        expect(IllegalArgumentException.class, () -> service.preview(TEACHER_A, sessionA,
                        new PreviewGradeImportRequestDTO(ticket, base)),
                "a second preview must not reuse the same upload ticket");
        require(store.find(TEACHER_A, preview.getImportToken()) != null,
                "a rejected second preview must not burn the live preview token");

        // 解析失败的导入同样不留半成品文件。
        List<Object[]> namelessRow = new ArrayList<>();
        namelessRow.add(new Object[]{"Tgi948 Student 1", "90"});
        String broken = upload(TEACHER_A, 0,
                workbook("broken.xlsx", List.of("姓名", "平时成绩"), namelessRow));
        expect(IllegalArgumentException.class, () -> service.preview(TEACHER_A, sessionA,
                        new PreviewGradeImportRequestDTO(broken, base)),
                "a workbook without the required 学号 column stops the preview");
        require(tempFiles(tickets.getTempDirectory()).isEmpty(),
                "a failed parse deletes the uploaded workbook too, saw "
                        + tempFiles(tickets.getTempDirectory()));
    }

    // ------------------------------- revise: correction, exclusion, version guard

    private static void verifyReviseCorrectsExcludesAndBlocks(TeacherGradeImportService service,
            TeacherGradeBookService grades, TeacherGradeImportStore store) throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        String ticket = upload(TEACHER_A, 0, importWorkbook("second.xlsx"));
        GradeImportPreviewDTO preview = service.preview(TEACHER_A, sessionA,
                new PreviewGradeImportRequestDTO(ticket, baseDraft(book, baseScores())));
        String token = preview.getImportToken();

        // 修正非法单元格 + 排除重复行：两件事都做完了这一行才算解决。
        GradeImportPreviewDTO revised = service.revise(TEACHER_A, new ReviseGradeImportRequestDTO(
                token, 1,
                List.of(new GradeImportCorrectionDTO(3,
                        Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91"))),
                List.of(5)));
        require(revised.getPreviewRevision() == 2 && revised.getTotalRows() == 6
                        && revised.getValidRows() == 4 && revised.getErrorRows() == 2,
                "revise returns an incremented revision and re-counts the rows (observed "
                        + revised.getPreviewRevision() + "/" + revised.getValidRows() + "/"
                        + revised.getErrorRows() + ")");
        require(issuesByRow(revised).getOrDefault(3, List.of()).isEmpty()
                        && same(scores(candidateScores(revised), S2).getMidtermScore(),
                        decimal("91")),
                "a corrected cell stops being an issue and imports the corrected value");
        Map<Integer, List<GradeImportRowIssueDTO>> afterExclude = issuesByRow(revised);
        require(afterExclude.getOrDefault(5, List.of()).stream()
                        .allMatch(GradeImportRowIssueDTO::isExcluded)
                        && afterExclude.getOrDefault(4, List.of()).stream()
                        .noneMatch(GradeImportRowIssueDTO::isExcluded),
                "an excluded row keeps its issue marked excluded, saw " + afterExclude);
        require(revised.getIssues().size() == afterExclude.values().stream()
                        .mapToInt(List::size).sum()
                        && revised.getValidRows() + revised.getErrorRows()
                        == revised.getTotalRows(),
                "every reported issue belongs to a row of the file");

        expect(TeacherGradeBookService.ConflictException.class, () -> service.revise(TEACHER_A,
                        new ReviseGradeImportRequestDTO(token, 1, List.of(), List.of())),
                "a stale expectedPreviewRevision must not overwrite the newer state");
        expect(IllegalArgumentException.class, () -> service.revise(TEACHER_A,
                        new ReviseGradeImportRequestDTO(token, 2, List.of(
                                new GradeImportCorrectionDTO(99, Map.of(
                                        GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "1"))),
                                List.of())),
                "a correction aimed at a row outside the file is rejected");
        expect(IllegalArgumentException.class, () -> service.revise(TEACHER_A,
                        new ReviseGradeImportRequestDTO(token, 2, List.of(
                                new GradeImportCorrectionDTO(3, Map.of("noSuchField", "1"))),
                                List.of())),
                "a correction using an unknown field name is rejected");

        // 仍有未解决的错误行（第 4 行的未知学号）时确认必须被拒绝，且令牌仍然活着。
        expect(IllegalArgumentException.class, () -> service.confirm(TEACHER_A,
                        new ConfirmGradeImportRequestDTO(op(1), token, 2, 0)),
                "an unresolved error row blocks the confirm");
        require(store.find(TEACHER_A, token) != null,
                "a refused confirm leaves the preview token alive");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN)
                        == 0,
                "a refused confirm writes nothing");

        GradeImportPreviewDTO resolved = service.revise(TEACHER_A, new ReviseGradeImportRequestDTO(
                token, 2,
                List.of(new GradeImportCorrectionDTO(3,
                        Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91"))),
                List.of(4, 5)));
        require(resolved.getPreviewRevision() == 3 && resolved.getValidRows() == 4
                        && resolved.getErrorRows() == 2,
                "excluding the remaining bad row clears the unresolved errors (observed "
                        + resolved.getPreviewRevision() + "/" + resolved.getValidRows() + "/"
                        + resolved.getErrorRows() + ")");

        TeacherOperationResultDTO<TeacherGradeBookDTO> result = service.confirm(TEACHER_A,
                new ConfirmGradeImportRequestDTO(op(1), token, 3, 0));
        require(!result.isReplayed() && "成绩草稿已保存".equals(result.getMessage())
                        && result.getValue().getRevision() == 1 && result.getValue().isCanEdit(),
                "the confirm writes draft revision 1 and stays editable (observed "
                        + result.getMessage() + "/" + result.getValue().getRevision() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=1 AND draft_open=1 AND updated_by='" + TEACHER_A + "'") == 1,
                "the confirm writes a draft, not a submission");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_MAIN)
                        == 0,
                "confirming an import never creates a submission batch");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND action='confirmGradeImport'") > 0
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER_A + "' AND action='confirmGradeImport'"
                        + " AND target_type='GRADE_BOOK' AND result_code='OK'") == 1,
                "the import is audited under its own action name, distinct from hand edits");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1
                        + " AND daily_score=88.50 AND midterm_score=61.00"
                        + " AND experiment_score=77.00 AND finalterm_score=63.00") == 1,
                "the draft stores the merged candidate exactly (imported and kept values)");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S2
                        + " AND daily_score=95.00 AND midterm_score=91.00"
                        + " AND experiment_score=72.00") == 1,
                "the corrected cell is what gets saved and the blank one keeps the base value");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S4
                        + " AND daily_score=90.00 AND experiment_score=80.00") == 1,
                "blank cells keep the base draft values in the saved draft");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN (" + S1 + "," + S2 + ","
                        + S3 + "," + S4 + ")") == 0,
                "an import never publishes a formal grade");
        require(store.find(TEACHER_A, token) == null,
                "the token is consumed only after the commit succeeded");
    }

    // ------------------------------------------- confirm replay after a lost response

    private static void verifyConfirmReplaysAfterLostResponse(TeacherGradeImportService service,
            TeacherGradeBookService grades, TeacherGradeImportStore store) throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        require(book.getRevision() == 1, "the previous confirm left draft revision 1 behind");
        GradeImportPreviewDTO preview = confirmablePreview(service, book, "third.xlsx");
        String token = preview.getImportToken();

        TeacherOperationResultDTO<TeacherGradeBookDTO> first = service.confirm(TEACHER_A,
                new ConfirmGradeImportRequestDTO(op(2), token, preview.getPreviewRevision(),
                        book.getRevision()));
        require(!first.isReplayed() && first.getValue().getRevision() == 2,
                "the first confirm writes revision 2");
        int auditRows = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid='"
                + TEACHER_A + "'");

        // 响应丢失后的重试：令牌已经消费，只能按 operationId 重放已提交结果。
        TeacherOperationResultDTO<TeacherGradeBookDTO> replay = service.confirm(TEACHER_A,
                new ConfirmGradeImportRequestDTO(op(2), token, preview.getPreviewRevision(),
                        book.getRevision()));
        require(replay.isReplayed() && first.getMessage().equals(replay.getMessage())
                        && replay.getValue().getRevision() == 2,
                "the retry replays the stored result instead of failing on the consumed token "
                        + "(observed replayed=" + replay.isReplayed() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid='"
                        + TEACHER_A + "'") == auditRows
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER_A + "' AND operation_id='" + op(2) + "'") == 1,
                "a replay writes neither a new audit row nor a second operation-log row");

        // 未知 operationId + 已消费令牌 = 预览过期，而不是编造一个成功。
        TeacherGradeImportService.NotFoundException expired =
                expect(TeacherGradeImportService.NotFoundException.class,
                        () -> service.confirm(TEACHER_A, new ConfirmGradeImportRequestDTO(op(3),
                                token, preview.getPreviewRevision(), book.getRevision())),
                        "an unknown operationId with a consumed token reports the expired preview");
        require(TeacherGradeImportService.EXPIRED_PREVIEW.equals(expired.getMessage()),
                "the expired-preview message is the documented one (observed " + expired.getMessage()
                        + ")");

        // 同一个 operationId 换一份内容：令牌还活着，摘要校验必须拦下它。
        TeacherGradeBookDTO current = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        GradeImportPreviewDTO fresh = confirmablePreview(service, current, "fourth.xlsx");
        GradeImportPreviewDTO other = service.revise(TEACHER_A, new ReviseGradeImportRequestDTO(
                fresh.getImportToken(), fresh.getPreviewRevision(), List.of(
                        new GradeImportCorrectionDTO(3,
                                Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91")),
                        new GradeImportCorrectionDTO(2,
                                Map.of(GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "77"))),
                List.of(4, 5)));
        expect(TeacherGradeBookService.ConflictException.class, () -> service.confirm(TEACHER_A,
                        new ConfirmGradeImportRequestDTO(op(2), other.getImportToken(),
                                other.getPreviewRevision(), current.getRevision())),
                "the same operationId with different content is a conflict, not a replay");
        service.cancel(TEACHER_A, fresh.getImportToken());
        require(store.find(TEACHER_A, fresh.getImportToken()) == null,
                "cancel drops the preview token");
    }

    // -------------------------------------------------- roster and draft changes

    private static void verifyRosterAndDraftChangesConflict(TeacherGradeImportService service,
            TeacherGradeBookService grades, TeacherGradeImportStore store) throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);

        // 编辑副本所基于的名单与服务器不符：预览阶段就要冲突。
        String ticket = upload(TEACHER_A, book.getRevision(), importWorkbook("fifth.xlsx"));
        expect(TeacherGradeBookService.ConflictException.class, () -> service.preview(TEACHER_A,
                        sessionA, new PreviewGradeImportRequestDTO(ticket,
                                baseDraft(book, storedScores(book), OTHER_ROSTER_DIGEST))),
                "a base draft taken from another roster is refused at preview");

        // 版本对不上也要在预览阶段拒绝（教师在上传与预览之间保存过草稿）。
        String staleTicket = upload(TEACHER_A, book.getRevision() + 3,
                importWorkbook("sixth.xlsx"));
        expect(TeacherGradeBookService.ConflictException.class, () -> service.preview(TEACHER_A,
                        sessionA, new PreviewGradeImportRequestDTO(staleTicket,
                                baseDraft(book, storedScores(book)))),
                "a base draft whose revision disagrees with the upload ticket is refused");

        // 教师在上传与确认之间自己保存了一次草稿：版本前进，确认必须冲突。
        GradeImportPreviewDTO preview = confirmablePreview(service, book, "seventh.xlsx");
        TeacherOperationResultDTO<TeacherGradeBookDTO> saved = grades.saveDraft(TEACHER_A,
                new WriteGradeBookRequestDTO(op(4), baseDraft(book, storedScores(book))));
        require(saved.getValue().getRevision() == book.getRevision() + 1,
                "the interleaved save moves the draft revision forward");
        expect(TeacherGradeBookService.ConflictException.class, () -> service.confirm(TEACHER_A,
                        new ConfirmGradeImportRequestDTO(op(5), preview.getImportToken(),
                                preview.getPreviewRevision(), book.getRevision())),
                "a draft saved after the preview makes the confirm conflict");
        require(store.find(TEACHER_A, preview.getImportToken()) != null,
                "a conflicted confirm leaves the token alive for a retry");
        service.cancel(TEACHER_A, preview.getImportToken());

        // 名单在确认之前变化：名单摘要复核必须拦下整笔写入。
        TeacherGradeBookDTO moved = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        GradeImportPreviewDTO rosterPreview = confirmablePreview(service, moved, "eighth.xlsx");
        enroll(S5, PREFIX + "s5", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 02:00:00");
        expect(TeacherGradeBookService.ConflictException.class, () -> service.confirm(TEACHER_A,
                        new ConfirmGradeImportRequestDTO(op(6), rosterPreview.getImportToken(),
                                rosterPreview.getPreviewRevision(), moved.getRevision())),
                "a roster change after the preview is refused by the confirm");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + (moved.getRevision() + 1)) == 0,
                "a refused confirm does not bump the draft revision");
        service.cancel(TEACHER_A, rosterPreview.getImportToken());
        execute("DELETE FROM enrollment WHERE enrollment_id=" + S5);
    }

    // -------------------------------------------- expired and cross-teacher tokens

    private static void verifyExpiredAndForeignTokens(TeacherGradeImportService service,
            TeacherGradeBookService grades, TeacherGradeImportStore store) throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        String ticket = upload(TEACHER_A, book.getRevision(), importWorkbook("ninth.xlsx"));
        GradeImportPreviewDTO preview = service.preview(TEACHER_A, sessionA,
                new PreviewGradeImportRequestDTO(ticket, baseDraft(book, storedScores(book))));
        String token = preview.getImportToken();

        expect(TeacherGradeImportService.NotFoundException.class, () -> service.revise(TEACHER_B,
                        new ReviseGradeImportRequestDTO(token, preview.getPreviewRevision(),
                                List.of(), List.of())),
                "another teacher must not revise a preview that is not theirs");
        expect(TeacherGradeImportService.NotFoundException.class, () -> service.confirm(TEACHER_B,
                        new ConfirmGradeImportRequestDTO(op(7), token,
                                preview.getPreviewRevision(), book.getRevision())),
                "another teacher must not confirm a preview that is not theirs");
        require(store.find(TEACHER_A, token) != null,
                "a rejected cross-teacher attempt leaves the owner's token alive");

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        expect(TeacherGradeImportService.NotFoundException.class, () -> service.revise(TEACHER_A,
                        new ReviseGradeImportRequestDTO(token, preview.getPreviewRevision(),
                                List.of(), List.of())),
                "an expired preview cannot be revised");
        TeacherGradeImportService.NotFoundException expired =
                expect(TeacherGradeImportService.NotFoundException.class,
                        () -> service.confirm(TEACHER_A, new ConfirmGradeImportRequestDTO(op(8),
                                token, preview.getPreviewRevision(), book.getRevision())),
                        "an expired preview with an unused operationId reports the expiry");
        require(TeacherGradeImportService.EXPIRED_PREVIEW.equals(expired.getMessage()),
                "the expiry message is the documented one");
        require(store.size() == 0, "an expired preview is swept out of the store");
    }

    // ----------------------------------------------- upload tickets stay single use

    private static void verifyUploadTicketsStaySingleUse(TeacherGradeBookService grades)
            throws Exception {
        TeacherGradeBookDTO book = grades.getGradeBook(TEACHER_A, OFFERING_ID);
        String ticket = upload(TEACHER_A, book.getRevision(), importWorkbook("tenth.xlsx"));
        IllegalArgumentException reused = expect(IllegalArgumentException.class,
                () -> tickets.claim(ticket, sessionA, TeacherFileTicketDTO.DIRECTION_UPLOAD),
                "a second upload with the same ticket must still be refused");
        require("文件票据无效或已被使用".equals(reused.getMessage()),
                "the second transfer attempt keeps the existing message (observed "
                        + reused.getMessage() + ")");
        // 预览把「已落地」这一阶段消费掉：同一个票号不会再被兑换第二次。
        tickets.claimUploaded(ticket, sessionA);
        expect(IllegalArgumentException.class, () -> tickets.claimUploaded(ticket, sessionA),
                "the uploaded phase is single use as well");
    }

    // ------------------------------------------------------------ test workbooks

    /**
     * 一次完整的导入文件：缺列、空白、非法值、未知学号与重复学号各一处。
     *
     * <p>重复学号落在学生 2 身上：他本来就有非法单元格，因此重复标记只会与已经出错的行重合，
     * 不会把「有效部分缺项」的用例一起拖下水。
     */
    private static Path importWorkbook(String fileName) {
        return workbook(fileName, HEADERS, List.of(
                new Object[]{"tgi948-s1", "Tgi948 Student 1", "88.5", null, "77"},
                new Object[]{"tgi948-s2", null, "95", "abc", null},
                new Object[]{"0000", "无名氏", "60", "60", "60"},
                new Object[]{"tgi948-s2", null, "70", null, null},
                new Object[]{"tgi948-s4", "Tgi948 Student 4", null, null, "80"},
                new Object[]{"tgi948-s3", "Tgi948 Student 3", "100", "100", "100"}));
    }

    private static Path workbook(String fileName, List<String> headers, List<Object[]> rows) {
        Path target = tempDirectory.resolve(fileName);
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("成绩录入");
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                Object[] values = rows.get(index);
                for (int column = 0; column < values.length; column++) {
                    // 全部写成文本单元格：学号的前导零必须保留，空白就是「没有值」。
                    if (values[column] != null) {
                        row.createCell(column).setCellValue(values[column].toString());
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                book.write(out);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法生成测试工作簿", failure);
        }
        return target;
    }

    // -------------------------------------------------- ticket and draft helpers

    /**
     * 模拟一次上传：与文件连接同样的调用顺序——兑换传输票据、把字节放到服务端自建路径上、
     * 交接成「已落地」——因此预览认的正是这次上传实际落盘的文件。
     */
    private static String upload(String uid, long expectedRevision, Path workbook) throws Exception {
        UserSession session = session(uid);
        TeacherFileTicketDTO issued = tickets.issueUpload(session, OFFERING_ID, expectedRevision,
                workbook.getFileName().toString(), Files.size(workbook),
                TeacherFileTicketService.sha256(workbook));
        TeacherFileTicketService.Ticket ticket = tickets.claim(issued.getTicket(), session,
                TeacherFileTicketDTO.DIRECTION_UPLOAD);
        Files.copy(workbook, ticket.path(), StandardCopyOption.REPLACE_EXISTING);
        tickets.markUploaded(issued.getTicket());
        return issued.getTicket();
    }

    /**
     * 一份可以走到确认的预览：修正第 3 行的非法单元格、排除未知学号与重复学号以外的行。
     *
     * <p>每份预览都从原始解析结果重算，所以新一轮修订必须重发仍然生效的修正。
     */
    private static GradeImportPreviewDTO confirmablePreview(TeacherGradeImportService service,
            TeacherGradeBookDTO book, String workbookName) throws Exception {
        String ticket = upload(TEACHER_A, book.getRevision(), importWorkbook(workbookName));
        GradeImportPreviewDTO preview = service.preview(TEACHER_A, sessionA,
                new PreviewGradeImportRequestDTO(ticket, baseDraft(book, storedScores(book))));
        return service.revise(TEACHER_A, new ReviseGradeImportRequestDTO(preview.getImportToken(),
                preview.getPreviewRevision(),
                List.of(new GradeImportCorrectionDTO(3,
                        Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91"))),
                List.of(4, 5)));
    }

    private static UserSession session(String uid) {
        if (TEACHER_A.equals(uid)) return sessionA;
        if (TEACHER_B.equals(uid)) return sessionB;
        throw new IllegalArgumentException("unknown fixture teacher: " + uid);
    }

    private static GradeBookContentDTO baseDraft(TeacherGradeBookDTO book,
            Map<Long, GradeScoresDTO> scores) {
        return baseDraft(book, scores, book.getRosterDigest());
    }

    /** 教师当前的编辑副本：客户端读到什么就发回什么，服务端再与自己的状态核对。 */
    private static GradeBookContentDTO baseDraft(TeacherGradeBookDTO book,
            Map<Long, GradeScoresDTO> scores, String rosterDigest) {
        List<GradeRowInputDTO> rows = new ArrayList<>();
        for (TeacherGradeRowDTO row : book.getRows()) {
            long enrollmentId = Long.parseLong(row.getEnrollmentId());
            GradeScoresDTO value = scores.get(enrollmentId);
            rows.add(new GradeRowInputDTO(row.getEnrollmentId(),
                    value == null ? row.getScores() : value));
        }
        return new GradeBookContentDTO(OFFERING_ID, book.getRevision(), rosterDigest,
                book.getScheme(), rows);
    }

    private static Map<Long, GradeScoresDTO> storedScores(TeacherGradeBookDTO book) {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        for (TeacherGradeRowDTO row : book.getRows()) {
            scores.put(Long.parseLong(row.getEnrollmentId()),
                    row.getScores() == null ? scores(null, null, null, null) : row.getScores());
        }
        return scores;
    }

    /** 夹具里每位学生各不相同的初始草稿值：合并时把「保留」与「导入」区分开。 */
    private static Map<Long, GradeScoresDTO> baseScores() {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        scores.put(S1, scores("60", "61", "62", "63"));
        scores.put(S2, scores("70", "71", "72", "73"));
        scores.put(S3, scores("80", "81", "82", "83"));
        scores.put(S4, scores("90", "91", "92", "93"));
        return scores;
    }

    private static GradeScoresDTO scores(String daily, String midterm, String experiment,
                                         String finalterm) {
        return new GradeScoresDTO(decimal(daily), decimal(midterm), decimal(experiment),
                decimal(finalterm));
    }

    private static Map<Long, GradeScoresDTO> candidateScores(GradeImportPreviewDTO preview) {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        for (GradeRowInputDTO row : preview.getCandidate().getRows()) {
            scores.put(Long.parseLong(row.getEnrollmentId()), row.getScores());
        }
        return scores;
    }

    private static GradeScoresDTO scores(Map<Long, GradeScoresDTO> scores, long enrollmentId) {
        GradeScoresDTO value = scores.get(enrollmentId);
        require(value != null, "the candidate must contain student " + enrollmentId);
        return value;
    }

    /** 逐项比较（{@link GradeScoresDTO} 没有 equals）：88.5 与 88.50 是同一个分数。 */
    private static boolean same(GradeScoresDTO left, GradeScoresDTO right) {
        return same(left.getDailyScore(), right.getDailyScore())
                && same(left.getMidtermScore(), right.getMidtermScore())
                && same(left.getExperimentScore(), right.getExperimentScore())
                && same(left.getFinaltermScore(), right.getFinaltermScore());
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    /** "88.50/61/77/63"：断言失败时把整行的四个值一次显示出来。 */
    private static String text(GradeScoresDTO scores) {
        return scores.getDailyScore() + "/" + scores.getMidtermScore() + "/"
                + scores.getExperimentScore() + "/" + scores.getFinaltermScore();
    }

    private static Map<Integer, List<GradeImportRowIssueDTO>> issuesByRow(
            GradeImportPreviewDTO preview) {
        Map<Integer, List<GradeImportRowIssueDTO>> byRow = new LinkedHashMap<>();
        for (GradeImportRowIssueDTO issue : preview.getIssues()) {
            byRow.computeIfAbsent(issue.getRowNumber(), key -> new ArrayList<>()).add(issue);
        }
        return byRow;
    }

    private static GradeImportRowIssueDTO issueWithField(
            Map<Integer, List<GradeImportRowIssueDTO>> byRow, int rowNumber, String field) {
        for (GradeImportRowIssueDTO issue : byRow.getOrDefault(rowNumber, List.of())) {
            if (field.equals(issue.getField())) {
                return issue;
            }
        }
        throw new AssertionError("row " + rowNumber + " must carry a " + field + " issue, saw "
                + byRow.getOrDefault(rowNumber, List.of()));
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static String op(int value) {
        return UUID.fromString(String.format("94800000-0000-0000-0000-%012d", value)).toString();
    }

    private static List<Path> tempFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private static TeacherGradeBookService gradeService() {
        return new TeacherGradeBookService(new TeacherGradeBookDAO(), new TeacherGradeAuditDAO(),
                new TeacherCourseOperationDAO(), new TeacherAccessPolicy(), clock);
    }

    // ------------------------------------------------------------------ fixtures

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_A + "','Tgi948 Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Tgi948 Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + PREFIX + "s1','Tgi948 Student 1','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s2','Tgi948 Student 2','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s3','Tgi948 Student 3','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s4','Tgi948 Student 4','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s5','Tgi948 Student 5','x','x',2,'Engineering','Student')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_MAIN
                + ",'TGI948A','Grade Import Course A',3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'TGI948-A'," + COURSE_MAIN
                + ",2026,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_MAIN
                + ",'" + TEACHER_A + "',0),(" + OFFERING_MAIN + ",'" + TEACHER_B + "',0)");

        // 插入顺序刻意打乱：名单摘要是对排序后的选课记录 ID 集合求的。
        enroll(S3, PREFIX + "s3", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S1, PREFIX + "s1", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S4, PREFIX + "s4", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S2, PREFIX + "s2", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
    }

    private static void enroll(long enrollmentId, String uid, long offeringId, long courseId,
                               int status, String selectTime) throws SQLException {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + enrollmentId + "," + offeringId + ","
                + courseId + ",2026,3,'" + uid + "'," + status + ",'" + selectTime + "')");
    }

    /**
     * 清场按「自己的教学班范围 + 自己的 UID 前缀」删除，不按 enrollment_id 区间：这个受保护测试库
     * 被同一台机器上的多个串行测试共用，别的测试的自增 ID 会落进同一段数字。
     */
    private static void cleanup() throws SQLException {
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 948300 AND 948399");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 948300 AND 948399");
        execute("DELETE FROM grade WHERE enrollment_id IN (SELECT enrollment_id FROM enrollment"
                + " WHERE offering_id BETWEEN 948300 AND 948399)");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 948300 AND 948399");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 948300 AND 948399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 948300 AND 948399");
        execute("DELETE FROM course WHERE course_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid"
                        + " LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id"
                        + " BETWEEN 948300 AND 948399") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id BETWEEN"
                        + " 948300 AND 948399") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE offering_id BETWEEN 948300"
                        + " AND 948399") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 948300 AND 948399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 948200 AND"
                        + " 948299") == 0
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
                "Refusing teacher grade import test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade import test outside the guarded schema");
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

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 可推进的时钟：10 分钟有效期不可能靠真实等待来验证。 */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-16T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
