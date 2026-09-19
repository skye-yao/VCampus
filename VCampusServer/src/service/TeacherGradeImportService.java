package service;

import com.google.gson.reflect.TypeToken;
import course.grade.GradeCalculator;
import dao.TeacherCourseOperationDAO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import exception.DatabaseException;
import session.UserSession;
import util.DBUtil;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
* 教师成绩 Excel 的导入预览与原子确认（设计第 9 节的后半段）。
*
* <p>分工：文件票据与短连接负责把字节搬到服务端（T1），表格服务负责解析成原样行（T2），本类负责
* 「预览 → 修订 → 确认」这三步业务。预览解析完**立即删除**上传的临时文件：候选已经在内存里
* （最多 5000 行 × 6 个短列），确认阶段不需要文件，因此确认时的「删除临时文件」是一个已经完成的
* 动作，而不是要跨两个请求维持的资源。
*
* <p>预览不写任何库表；确认只写**草稿**，不是提交审批（{@code requireComplete=false}）。提交成绩
* 仍然要求所有启用项完整，这条规则没有被导入绕过。
*
* <p>归属、名单、方案在预览和确认都校验：预览走 {@link TeacherGradeBookService#getGradeBook}
* （它内部就是 {@link TeacherAccessPolicy#requireViewOffering} 加 {@code canEditGrades}），并复核
* 票据版本、草稿版本与名单摘要；确认则在事务内由 {@code saveTransaction} 重新锁 offering、
* 复查 {@code role=0}、复核 rosterDigest。两个阶段用**同一次**成绩表读取得到名单与摘要
* （{@code listRosterScores} 的行 + {@code rosterDigest(normalEnrollmentIds)} 的摘要），因此预览
* 认下的名单就是确认要校验的名单，不会出现「预览放行、确认说不认识」的错位。
*
* <p>候选合法内容与非法原文严格分开：{@code candidate} 里只有服务端认得的分数，非法单元格的原文
* 只出现在 issues 里，界面据此显示错误而不是拿旧值冒充导入成功。缺列与空白单元格**不是**错误，
* 它们保留 baseDraft 的值（尚无值就保持 NULL），绝不写成 0。
*
* <p>幂等与重放：确认的令牌在 {@code commit} 成功之后才消费。若响应丢失，客户端用同一个
* operationId 重试时令牌已经不在了，也就无法重建候选——这时按 operationId 查教师操作日志，
* 有已提交结果就重放（{@code replayed=true}），没有才报「导入预览已过期」。令牌还活着时走正常
* 路径，由 {@code saveTransaction} 自己的 {@code operations.find} 做带摘要校验的完整重放，
* 「同 operationId、不同内容 ⇒ 冲突」这条性质因此不受影响。
* </p>
*/
public class TeacherGradeImportService {

    /** 令牌不存在、已过期或属于别的教师时的统一文案（三种情况不区分）。 */
    public static final String EXPIRED_PREVIEW = "导入预览已过期，请重新导入";

    private static final String CONFIRM_FAILURE = "确认导入事务执行失败";
    private static final String CONFLICT_REVISION = "成绩草稿版本已变化，请重新导入";
    private static final String CONFLICT_PREVIEW = "导入预览已更新，请重新加载预览后重试";
    private static final String UNRESOLVED = "仍有未解决的错误行，请修正或明确排除后再确认导入";
    private static final String UNKNOWN_FIELD = "不支持的修正字段";
    private static final Type RESULT_TYPE =
            new TypeToken<TeacherOperationResultDTO<TeacherGradeBookDTO>>() { }.getType();
    /** 一行完全没有分数；与 {@link GradeScoresDTO} 的字段顺序一致。 */
    private static final GradeScoresDTO EMPTY_SCORES =
            new GradeScoresDTO(null, null, null, null);

    private final TeacherFileTicketService tickets;
    private final TeacherGradeBookService grades;
    private final TeacherGradeImportStore store;
    private final TeacherCourseOperationDAO operations;
    /** 只做文件与文本，不碰数据库：与 Handler 里的表格能力是同一个实现类。 */
    private final TeacherSpreadsheetService spreadsheets = new TeacherSpreadsheetService();

    /**
    * Handles the course-management responsibility of TeacherGradeImportService.
    */
    public TeacherGradeImportService(TeacherFileTicketService tickets, TeacherGradeBookService grades,
                                     TeacherGradeImportStore store) {
        this(tickets, grades, store, new TeacherCourseOperationDAO());
    }

    /** 供测试注入可覆写的操作日志 DAO。 */
    public TeacherGradeImportService(TeacherFileTicketService tickets, TeacherGradeBookService grades,
                                     TeacherGradeImportStore store, TeacherCourseOperationDAO operations) {
        this.tickets = tickets;
        this.grades = grades;
        this.store = store;
        this.operations = operations;
    }

    // ------------------------------------------------------------------ 预览

    /**
    * 用上传成功的文件生成预览：兑换并消费上传票据 → 核对归属/版本/方案/名单 → 解析 → 删除临时文件。
    *
    * <p>解析失败时临时文件同样删除：一次失败的导入不该在服务端留下任何半成品，教师重新上传即可。
    */
    public GradeImportPreviewDTO preview(String uid, UserSession session,
            PreviewGradeImportRequestDTO raw) {
        String teacher = requireTeacher(uid, session);
        if (raw == null || raw.getBaseDraft() == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        GradeBookContentDTO base = raw.getBaseDraft();
        long offering = AdminOperationTransaction.parseId(base.getOfferingId(), "offeringId");
        if (base.getExpectedRevision() < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负数");
        }
        TeacherFileTicketService.Ticket ticket = tickets.claimUploaded(raw.getUploadTicket(), session);
        Path workbook = ticket.path();
        try {
            if (!offeringIdOf(ticket).equals(Long.toString(offering))) {
                throw new IllegalArgumentException("上传票据与导入的教学班不一致");
            }
            if (ticket.expectedRevision() != base.getExpectedRevision()) {
                // 教师在上传与预览之间保存过草稿：宁可让他重新导入，也不做静默 rebase。
                throw new TeacherGradeBookService.ConflictException(CONFLICT_REVISION, null);
            }
            TeacherGradeBookDTO book = grades.getGradeBook(teacher, Long.toString(offering));
            if (!book.isCanEdit()) {
                throw new TeacherAccessPolicy.AccessDeniedException("只有任课教师可以导入成绩");
            }
            GradeSchemeDTO scheme = schemeOf(base, book);
            GradeCalculator.validateScheme(scheme, false);
            if (base.getRosterDigest() == null
                    || !book.getRosterDigest().equals(base.getRosterDigest())) {
                throw new TeacherGradeBookService.ConflictException(
                        "名单已变化，请重新加载成绩表并合并已输入的成绩", book);
            }
            List<TeacherRosterRowDTO> roster = rosterOf(book);
            List<TeacherSpreadsheetRow> rows = spreadsheets.parse(workbook, roster);
            Map<Long, GradeScoresDTO> baseline = baselineOf(book, base);
            return snapshot(store.open(teacher, Long.toString(offering),
                    base.getExpectedRevision(), book.getRosterDigest(), scheme, rows, roster,
                    baseline));
        } finally {
            TeacherFileTicketService.deleteQuietly(workbook);
        }
    }

    // ------------------------------------------------------------------ 修订

    /** 修订预览：修正异常行的单元格文本，或明确排除整行；返回递增版本的新预览。 */
    public GradeImportPreviewDTO revise(String uid, ReviseGradeImportRequestDTO raw) {
        String teacher = requireUid(uid);
        if (raw == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        TeacherGradeImportStore.Preview current = store.require(teacher, raw.getImportToken());
        if (current.previewRevision() != raw.getExpectedPreviewRevision()) {
            throw new TeacherGradeBookService.ConflictException(CONFLICT_PREVIEW, null);
        }
        requireKnownRows(current, raw);
        return snapshot(store.replace(current, raw.getCorrections(), raw.getExcludedRows()));
    }

    // ------------------------------------------------------------------ 确认

    /**
    * 确认导入：一个事务里写候选草稿 + 审计 + 教师操作日志，提交成功之后才消费令牌。
    *
    * <p>不写提交批次：确认导入不是提交审批，整份启用项是否完整仍由提交通道把关。
    */
    public TeacherOperationResultDTO<TeacherGradeBookDTO> confirm(String uid,
            ConfirmGradeImportRequestDTO raw) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        String token = raw.getImportToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("importToken 不能为空");
        }
        TeacherGradeImportStore.Preview preview = store.find(teacher, token);
        if (preview == null) {
            // 令牌已消费（或过期）：候选无法重建，只能按 operationId 重放已提交的结果。
            return replay(teacher, operationId);
        }
        if (preview.previewRevision() != raw.getExpectedPreviewRevision()) {
            throw new TeacherGradeBookService.ConflictException(CONFLICT_PREVIEW, null);
        }
        if (preview.baseRevision() != raw.getExpectedRevision()) {
            throw new TeacherGradeBookService.ConflictException(CONFLICT_REVISION, null);
        }
        Merged merged = merge(preview);
        if (merged.blocked()) {
            throw new IllegalArgumentException(UNRESOLVED);
        }
        TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                writeDraft(teacher, new WriteGradeBookRequestDTO(operationId, merged.candidate()));
        // 只有提交成功之后才消费令牌：回滚的那一次留下的令牌仍然可以重试。
        store.discard(teacher, token);
        return result;
    }

    /** 取消导入：丢弃令牌。客户端的编辑副本由客户端自己恢复，服务端本来就没有写过任何东西。 */
    public void cancel(String uid, String importToken) {
        store.discard(requireUid(uid), importToken);
    }

    // ------------------------------------------------------- 候选与问题的合并

    /**
    * 从原始解析行与修正记录重新算出候选与问题：每一轮都从零重算，所以修订不会累积中间状态。
    *
    * <p>行级判定是「整行要么导入、要么不导入」：有任何未解决的问题（或已被教师排除）的行都不进入
    * 候选，教师必须先修正或明确排除它。这样界面上不会出现「一半导入、一半是旧值」的行，也不会
    * 把候选里的旧值显示成导入成功。
    */
    private Merged merge(TeacherGradeImportStore.Preview preview) {
        Map<Integer, GradeImportCorrectionDTO> corrections = preview.correctionsByRow();
        Set<Integer> excluded = new HashSet<>(preview.excludedRows());
        List<TeacherSpreadsheetRow> corrected = new ArrayList<>();
        for (TeacherSpreadsheetRow row : preview.rows()) {
            corrected.add(correctedRow(row, corrections.get(row.rowNumber())));
        }
        // 名单匹配复用 T2 的规则（学号不在名单、姓名与名单不一致），修正过的学号重新参与匹配。
        List<TeacherSpreadsheetRow> matched = spreadsheets.matchRoster(corrected, preview.roster());
        Map<String, Integer> uidCounts = uidCounts(corrected, excluded);
        Map<String, Long> enrollmentByUid = enrollmentByUid(preview.roster());

        Map<Long, GradeScoresDTO> candidate = new LinkedHashMap<>(preview.baseline());
        List<GradeImportRowIssueDTO> issues = new ArrayList<>();
        Map<Integer, List<GradeImportRowIssueDTO>> issuesByRow = new LinkedHashMap<>();
        for (int index = 0; index < corrected.size(); index++) {
            TeacherSpreadsheetRow row = corrected.get(index);
            boolean excludedRow = excluded.contains(row.rowNumber());
            List<GradeImportRowIssueDTO> rowIssues = new ArrayList<>();
            for (TeacherSpreadsheetRow.CellError error : matched.get(index).cellErrors()) {
                rowIssues.add(issue(row, error.field(), error.rawValue(), error.message(),
                        excludedRow));
            }
            if (row.studentUid().isEmpty()) {
                rowIssues.add(issue(row, TeacherSpreadsheetRow.FIELD_STUDENT_UID, "",
                        "学号不能为空", excludedRow));
            } else {
                Integer count = uidCounts.get(row.studentUid());
                if (count != null && count > 1) {
                    rowIssues.add(issue(row, TeacherSpreadsheetRow.FIELD_STUDENT_UID,
                            row.studentUid(), "学号在文件中重复出现 " + count + " 次", excludedRow));
                }
            }
            for (ScoreIssue scoreIssue : scoreIssues(row)) {
                rowIssues.add(issue(row, scoreIssue.field(), scoreIssue.rawValue(),
                        scoreIssue.message(), excludedRow));
            }
            issuesByRow.put(row.rowNumber(), rowIssues);
            issues.addAll(rowIssues);
        }

        int valid = 0;
        int error = 0;
        for (TeacherSpreadsheetRow row : corrected) {
            List<GradeImportRowIssueDTO> rowIssues = issuesByRow.get(row.rowNumber());
            if (excluded.contains(row.rowNumber()) || !rowIssues.isEmpty()) {
                error++;
                continue;
            }
            Long enrollmentId = enrollmentByUid.get(row.studentUid());
            GradeScoresDTO current = candidate.get(enrollmentId);
            candidate.put(enrollmentId, mergeScores(current == null ? EMPTY_SCORES : current, row));
            valid++;
        }

        List<Map.Entry<Long, GradeScoresDTO>> sorted = new ArrayList<>(candidate.entrySet());
        sorted.sort(Comparator.comparingLong(Map.Entry::getKey));
        List<GradeRowInputDTO> rows = new ArrayList<>();
        for (Map.Entry<Long, GradeScoresDTO> entry : sorted) {
            rows.add(new GradeRowInputDTO(Long.toString(entry.getKey()), entry.getValue()));
        }
        GradeBookContentDTO content = new GradeBookContentDTO(preview.offeringId(),
                preview.baseRevision(), preview.rosterDigest(), preview.scheme(), rows);
        return new Merged(content, List.copyOf(issues), valid, error);
    }

    /** 把教师对一行给出的修正文本叠加上去；界面上没有改动的行保持解析结果原样。 */
    private static TeacherSpreadsheetRow correctedRow(TeacherSpreadsheetRow row,
            GradeImportCorrectionDTO correction) {
        Map<String, String> cells = correction == null ? Map.of() : correction.getCorrectedCells();
        // 解析阶段的行错误在这里丢掉：预览统一从**修正后**的原文重算，避免旧错误与修正后的事实并存。
        return new TeacherSpreadsheetRow(row.rowNumber(),
                text(cells, TeacherSpreadsheetRow.FIELD_STUDENT_UID, row.studentUid()),
                text(cells, TeacherSpreadsheetRow.FIELD_STUDENT_NAME, row.studentName()),
                score(cells, GradeImportRowIssueDTO.FIELD_DAILY_SCORE, row.rawDailyScore()),
                score(cells, GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, row.rawMidtermScore()),
                score(cells, GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE, row.rawExperimentScore()),
                score(cells, GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE, row.rawFinaltermScore()),
                List.of());
    }

    private static String text(Map<String, String> cells, String field, String fallback) {
        return cells.containsKey(field) ? cells.get(field).strip() : fallback;
    }

    /** 成绩单元格：修正后的空串与文件里的空白同义（没有值），不是 0 分；缺列仍然是 null。 */
    private static String score(Map<String, String> cells, String field, String fallback) {
        return cells.containsKey(field) ? cells.get(field).strip() : fallback;
    }

    /** 每个非空学号（排除行不参与计数）在文件里出现的次数：排除掉重复行里的一条即可解决冲突。 */
    private static Map<String, Integer> uidCounts(List<TeacherSpreadsheetRow> rows,
            Set<Integer> excluded) {
        Map<String, Integer> counts = new HashMap<>();
        for (TeacherSpreadsheetRow row : rows) {
            if (excluded.contains(row.rowNumber()) || row.studentUid().isEmpty()) {
                continue;
            }
            counts.merge(row.studentUid(), 1, Integer::sum);
        }
        return counts;
    }

    /**
    * 成绩单元格的数值判定：缺列（null）与空白（""）都不是错误；其余文本必须是合法分数。
    *
    * <p>合法性只有 {@link GradeCalculator#validateScores} 一个出口（0..100、最多两位小数），这里
    * 只是把它换成面向教师的说明；{@code rawValue} 始终是文件里的原文。
    */
    private static List<ScoreIssue> scoreIssues(TeacherSpreadsheetRow row) {
        List<ScoreIssue> issues = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            String raw = rawScoreOf(code, row);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String field = fieldOf(code);
            BigDecimal value;
            try {
                value = new BigDecimal(raw.strip());
            } catch (NumberFormatException notANumber) {
                issues.add(new ScoreIssue(field, raw,
                        "「" + label(code) + "」不是有效的数字"));
                continue;
            }
            try {
                GradeCalculator.validateScores(singleScore(code, value));
            } catch (IllegalArgumentException rejected) {
                issues.add(new ScoreIssue(field, raw,
                        "「" + label(code) + "」必须在 0 到 100 之间，且最多两位小数"));
            }
        }
        return issues;
    }

    /** 合并一行合法导入：缺列/空白保留原草稿值，有值的组成用文件里的值，两者都不写 0。 */
    private static GradeScoresDTO mergeScores(GradeScoresDTO base, TeacherSpreadsheetRow row) {
        return new GradeScoresDTO(
                value(row.rawDailyScore(), base.getDailyScore()),
                value(row.rawMidtermScore(), base.getMidtermScore()),
                value(row.rawExperimentScore(), base.getExperimentScore()),
                value(row.rawFinaltermScore(), base.getFinaltermScore()));
    }

    private static BigDecimal value(String raw, BigDecimal fallback) {
        return raw == null || raw.isBlank() ? fallback : new BigDecimal(raw.strip());
    }

    // ------------------------------------------------------------------ 读表

    /**
    * 预览用的名单与原草稿值，全部来自**同一个**成绩表读取：名单是它列出的正常学生
    * （{@code listRosterScores}），摘要是它给出的 {@code rosterDigest(normalEnrollmentIds)}，
    * 确认时 {@code saveTransaction} 复核的是同一个摘要。
    */
    private static List<TeacherRosterRowDTO> rosterOf(TeacherGradeBookDTO book) {
        List<TeacherRosterRowDTO> roster = new ArrayList<>();
        for (TeacherGradeRowDTO row : book.getRows()) {
            roster.add(new TeacherRosterRowDTO(row.getEnrollmentId(), row.getStudentUid(),
                    row.getStudentName(), null, "ENROLLED", null, null));
        }
        return List.copyOf(roster);
    }

    /**
    * 每位正常名单学生的「原草稿值」：先取服务端当前草稿，再用客户端送来的编辑副本覆盖
    * （编辑器里清空的值就是清空，不能被服务端的旧值顶回来）。客户端没提到的学生保持服务端的值，
    * 因此一次导入不会把编辑副本里根本没有的学生悄悄写成空。
    */
    private static Map<Long, GradeScoresDTO> baselineOf(TeacherGradeBookDTO book,
            GradeBookContentDTO base) {
        Map<Long, GradeScoresDTO> baseline = new LinkedHashMap<>();
        for (TeacherGradeRowDTO row : book.getRows()) {
            long enrollmentId = Long.parseLong(row.getEnrollmentId());
            baseline.put(enrollmentId, row.getScores() == null ? EMPTY_SCORES : row.getScores());
        }
        for (GradeRowInputDTO row : base.getRows()) {
            if (row == null) {
                continue;
            }
            long enrollmentId =
                    AdminOperationTransaction.parseId(row.getEnrollmentId(), "enrollmentId");
            if (baseline.containsKey(enrollmentId)) {
                baseline.put(enrollmentId,
                        row.getScores() == null ? EMPTY_SCORES : row.getScores());
            }
        }
        return baseline;
    }

    private static Map<String, Long> enrollmentByUid(List<TeacherRosterRowDTO> roster) {
        Map<String, Long> byUid = new HashMap<>();
        for (TeacherRosterRowDTO student : roster) {
            if (student.getStudentUid() != null && !student.getStudentUid().isBlank()) {
                byUid.putIfAbsent(student.getStudentUid(),
                        Long.parseLong(student.getEnrollmentId()));
            }
        }
        return byUid;
    }

    /** 方案由服务端定：客户端带了就用它（教师可能正在编辑权重），没带就沿用服务端当前方案。 */
    private static GradeSchemeDTO schemeOf(GradeBookContentDTO base, TeacherGradeBookDTO book) {
        return base.getScheme() == null ? book.getScheme() : base.getScheme();
    }

    private GradeImportPreviewDTO snapshot(TeacherGradeImportStore.Preview preview) {
        Merged merged = merge(preview);
        return new GradeImportPreviewDTO(preview.importToken(), preview.previewRevision(),
                merged.candidate(), preview.rows().size(), merged.validRows(), merged.errorRows(),
                merged.issues(), preview.expiresAt().toString());
    }

    private static GradeImportRowIssueDTO issue(TeacherSpreadsheetRow row, String field,
            String rawValue, String message, boolean excluded) {
        return new GradeImportRowIssueDTO(row.rowNumber(), row.studentUid(), row.studentName(),
                field, rawValue, message, excluded);
    }

    // ------------------------------------------------------------------ 确认写库

    /**
    * 确认导入的写事务：连接与生命周期都在这里，草稿写入本身复用
    * {@link TeacherGradeBookService#saveDraftInTransaction} —— 同一个事务里完成版本复核、候选写入、
    * 变更审计与教师操作日志，绝不从另一个事务里调用。
    */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> writeDraft(String uid,
            WriteGradeBookRequestDTO request) {
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                        grades.saveDraftInTransaction(connection, uid, request,
                                TeacherCourseActions.CONFIRM_GRADE_IMPORT);
                connection.commit();
                committed = true;
                return result;
            } catch (RuntimeException | SQLException failure) {
                inFlight = failure;
                rollback(connection, failure);
                throw failure;
            } finally {
                if (!committed) {
                    rollback(connection, inFlight);
                }
                restoreAutoCommit(connection, originalAutoCommit, inFlight);
            }
        } catch (SQLException failure) {
            throw new DatabaseException(CONFIRM_FAILURE, failure);
        }
    }

    /**
    * 令牌已消费时的重放：按 operationId 查教师操作日志。
    *
    * <p>查询与写入在同一个连接上进行（这里没有写入，读的正是并发那一笔提交的事务结果），
    * 因此并发的第二次确认要么在这里看到已提交结果，要么走正常路径撞上审计表的重复键，
    * 与该路径已有的恢复逻辑是同一套。没有已保存的结果才报令牌过期。
    */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> replay(String uid, String operationId) {
        try (Connection connection = DBUtil.getConnection()) {
            TeacherCourseOperationDAO.StoredOperation stored =
                    operations.find(connection, uid, operationId);
            if (stored == null || stored.responseJson() == null) {
                throw new NotFoundException(EXPIRED_PREVIEW);
            }
            TeacherOperationResultDTO<TeacherGradeBookDTO> value =
                    operations.decode(stored.responseJson(), RESULT_TYPE);
            if (value == null) {
                throw new NotFoundException(EXPIRED_PREVIEW);
            }
            return new TeacherOperationResultDTO<>(operationId, value.getMessage(), value.getValue(),
                    true);
        } catch (SQLException failure) {
            throw new DatabaseException("确认导入结果查询失败", failure);
        }
    }

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight. */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            if (failure != null) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    /** Never replaces an in-flight failure with a connection-cleanup failure. */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit,
                                          Throwable inFlight) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoration) {
            if (inFlight != null) {
                inFlight.addSuppressed(restoration);
            } else {
                throw new DatabaseException(CONFIRM_FAILURE, restoration);
            }
        }
    }

    // ------------------------------------------------------------------ 校验

    /** 修订只允许指向文件里真实存在的行与真实存在的字段：伪造的行号不会变成静默忽略。 */
    private static void requireKnownRows(TeacherGradeImportStore.Preview preview,
            ReviseGradeImportRequestDTO raw) {
        Set<Integer> known = new HashSet<>();
        for (TeacherSpreadsheetRow row : preview.rows()) {
            known.add(row.rowNumber());
        }
        for (GradeImportCorrectionDTO correction : raw.getCorrections()) {
            if (correction == null || !known.contains(correction.getRowNumber())) {
                throw new IllegalArgumentException("修正指向的行不在本次导入的文件里");
            }
            for (String field : correction.getCorrectedCells().keySet()) {
                if (!isKnownField(field)) {
                    throw new IllegalArgumentException(UNKNOWN_FIELD + "：" + field);
                }
            }
        }
        for (Integer rowNumber : raw.getExcludedRows()) {
            if (rowNumber == null || !known.contains(rowNumber)) {
                throw new IllegalArgumentException("排除的行不在本次导入的文件里");
            }
        }
    }

    private static boolean isKnownField(String field) {
        return TeacherSpreadsheetRow.FIELD_STUDENT_UID.equals(field)
                || TeacherSpreadsheetRow.FIELD_STUDENT_NAME.equals(field)
                || GradeImportRowIssueDTO.FIELD_DAILY_SCORE.equals(field)
                || GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE.equals(field)
                || GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE.equals(field)
                || GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE.equals(field);
    }

    private static String requireUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("UID 不能为空");
        }
        return uid.trim();
    }

    /** 身份同时来自会话与参数：两者不一致说明调用方把身份搞错了，宁可拒绝也不猜。 */
    private static String requireTeacher(String uid, UserSession session) {
        String teacher = requireUid(uid);
        if (session == null || !teacher.equals(session.getUsername())) {
            throw new TeacherAccessPolicy.AccessDeniedException("登录会话与请求身份不一致，请重新登录");
        }
        return teacher;
    }

    private static String offeringIdOf(TeacherFileTicketService.Ticket ticket) {
        return Long.toString(AdminOperationTransaction.parseId(ticket.offeringId(), "offeringId"));
    }

    private static String rawScoreOf(GradeComponentCodeDTO code, TeacherSpreadsheetRow row) {
        return switch (code) {
            case DAILY -> row.rawDailyScore();
            case MIDTERM -> row.rawMidtermScore();
            case EXPERIMENT -> row.rawExperimentScore();
            case FINALTERM -> row.rawFinaltermScore();
        };
    }

    private static String fieldOf(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> GradeImportRowIssueDTO.FIELD_DAILY_SCORE;
            case MIDTERM -> GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE;
            case EXPERIMENT -> GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE;
            case FINALTERM -> GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE;
        };
    }

    private static String label(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> "平时成绩";
            case MIDTERM -> "期中成绩";
            case EXPERIMENT -> "实验成绩";
            case FINALTERM -> "期末成绩";
        };
    }

    /** 把一位分数放进它在 {@link GradeScoresDTO} 里的位置，复用同一个分数校验规则。 */
    private static GradeScoresDTO singleScore(GradeComponentCodeDTO code, BigDecimal value) {
        return switch (code) {
            case DAILY -> new GradeScoresDTO(value, null, null, null);
            case MIDTERM -> new GradeScoresDTO(null, value, null, null);
            case EXPERIMENT -> new GradeScoresDTO(null, null, value, null);
            case FINALTERM -> new GradeScoresDTO(null, null, null, value);
        };
    }

    // -------------------------------------------------------------------- records

    /** 一个成绩单元格的问题：字段名、原文与面向教师的说明（原文不会被改写成数字）。 */
    private record ScoreIssue(String field, String rawValue, String message) {
    }

    /** 一次合并的结果：候选内容、问题列表与计数口径（valid + error == 文件数据行数）。 */
    private record Merged(GradeBookContentDTO candidate, List<GradeImportRowIssueDTO> issues,
            int validRows, int errorRows) {

        /** 有未解决的错误行时确认必须被拒绝；已排除的行不算未解决。 */
        boolean blocked() {
            for (GradeImportRowIssueDTO issue : issues) {
                if (!issue.isExcluded()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 令牌不存在、已过期或属于别的教师：上层映射为 NOT_FOUND，客户端据此回到上传步骤。 */
    public static final class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) {
            super(message);
        }
    }
}
