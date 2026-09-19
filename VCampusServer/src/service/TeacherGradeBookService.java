package service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import course.grade.GradeCalculator;
import course.grade.GradePointScale;
import dao.AdminOfferingDAO;
import dao.TeacherCourseOperationDAO;
import dao.TeacherGradeAuditDAO;
import dao.TeacherGradeBookDAO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
* 教师成绩工作副本：成绩列表、成绩表读取与草稿保存。
*
* <p>权限（设计第 4 节）：读取走 {@link TeacherAccessPolicy#requireViewOffering}（任课教师、助教与
* 有效课表里的任课教师都能看，但助教的能力位是只读）；每次写入都在事务内重新调用
* {@link TeacherAccessPolicy#requireEditGrades}，只认该教学班 {@code role=0} 的任课教师。
* {@code uid} 必须是服务端校验过的 Session 身份，客户端传来的 teacherId/sender 一律不参与。
*
* <p>写事务的顺序（设计第 7、8 节）：规范化/校验 → 查同操作结果 → 锁 offering → 复查 role=0 →
* 再查同操作结果 → 锁工作副本 → 复核 rosterDigest 与学生归属 → 驳回惰性重开 → 方案/版本原子更新
* （影响行数不是 1 就中止，绝不再碰明细）→ 按 enrollment_id 升序写明细 → 写变更审计 → 写教师操作
* 日志 → commit。读取永远不写库：没有工作副本时返回 {@code revision=0} 的虚拟草稿（服务端默认
* 方案 + 当前正常名单全空分数），首次保存才在 offering 锁内创建。
*
* <p>幂等：相同 operationId 且规范化请求相同时重放 {@code teacher_course_operation_log} 里已提交的
* 响应（哪怕此时 expectedRevision 已经过期，重试不会变成冲突）；相同 operationId 但内容不同是
* CONFLICT。规范化请求按 enrollment_id 升序、方案按固定四项枚举顺序、分数按两位小数文本生成摘要，
* 所以行序或 {@code 88.5}/{@code 88.50} 的写法不会造成假冲突。
*
* <p>提交（设计第 8 节）在同一把 offering 锁内完成：先按同一套规则保存工作副本，再用 <em>刚写入的
* 草稿行</em>捕获整份正常名单（方案、名单摘要、身份、统计与每条明细）建成一个不可变批次，最后关闭
* 草稿并记下 last_submission_id，全程一个事务。提交版本取该班历史最大 version+1，不与草稿 revision
* 混用；“每班至多一个 PENDING 批次”在 offering 锁内检查，并发的第二次提交拿到 CONFLICT 而不是第二
* 个待审批次。提交快照里禁用组成写 NULL（草稿仍保留原值），等级不写（不编造等级编码）。
*
* <p>版本链（设计第 8、12 节）：{@link #reopenRejectedGradeBook} 从最后一次<b>被驳回</b>的批次复制
* 出可编辑副本（草稿类型 RESUBMISSION），{@link #beginGradeCorrection} 从最后一次<b>已通过</b>的
* 批次复制（草稿类型 CORRECTION + 基础批次 + 必填原因）。复制的一律是批次快照而不是当前草稿，并合并
* 当前正常名单：提交之后才入学的学生没有草稿行（四项 NULL），名单里已经消失的学生没有草稿行。两个
* 入口都不递增 revision（随后那次保存才递增），也不碰 {@code grade} 投影——学生的当前成绩只有在
* 新批次被管理员通过之后才切换，旧的批次快照永远可查。
*/
public class TeacherGradeBookService {
    /** 教师操作日志的目标类型：一份教学班成绩工作副本。 */
    public static final String TARGET_TYPE = "GRADE_BOOK";
    private static final String OK = "OK";
    private static final int DUPLICATE_KEY = 1062;
    private static final int MAX_PAGE_SIZE = 100;
    /** 界面状态：草稿可编辑。 */
    private static final String STATE_DRAFT = "DRAFT";
    /** 已提交待审核：与数据库和界面共用同一组字面量。 */
    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_APPROVED = "APPROVED";
    private static final String STATE_REJECTED = "REJECTED";
    /** 工作副本的草稿类型：与 V007 的 CHECK 取值一一对应。 */
    private static final String DRAFT_KIND_INITIAL = "INITIAL";
    private static final String DRAFT_KIND_RESUBMISSION = "RESUBMISSION";
    private static final String DRAFT_KIND_CORRECTION = "CORRECTION";
    private static final int SCORE_SCALE = 2;
    /**
    * 更正原因的字符上限，与 {@code teacher_grade_book.correction_reason}、
    * {@code grade_submission.correction_reason} 与 {@code teacher_grade_change_log.reason} 的
    * {@code VARCHAR(500)} 一致，也与同模块的调课原因同一个口径。超长必须在服务端按坏请求拒绝：
    * 交给 MySQL 严格模式就会变成 1406 / 服务不可用，非严格模式则把原因静默截断在审计里。
    */
    private static final int MAX_REASON = 500;
    /** 不及格线：总评严格小于 60 计入批次头的 failed_count。 */
    private static final BigDecimal FAIL_SCORE = new BigDecimal("60");
    private static final String SAVE_FAILURE = "保存成绩草稿事务执行失败";
    private static final String SUBMIT_FAILURE = "提交成绩批次事务执行失败";
    private static final String REVISION_FAILURE = "开始成绩版本变更事务执行失败";
    /** 驳回重开：来源必须是最后一次被驳回的批次，留下的草稿是普通重提（不要求原因）。 */
    private static final RevisionGoal REOPEN_GOAL = new RevisionGoal(
            TeacherCourseActions.REOPEN_REJECTED_GRADE_BOOK, STATE_REJECTED,
            DRAFT_KIND_RESUBMISSION, false, "已重开被驳回的成绩草稿");
    /** 发起更正：来源必须是最后一次已通过的批次，原因必填并留在草稿与后续批次上。 */
    private static final RevisionGoal CORRECTION_GOAL = new RevisionGoal(
            TeacherCourseActions.BEGIN_GRADE_CORRECTION, STATE_APPROVED, DRAFT_KIND_CORRECTION,
            true, "已开始更正草稿");
    /** 一行完全没有分数；与 DTO 的字段顺序一致。 */
    private static final GradeScoresDTO EMPTY_SCORES =
            new GradeScoresDTO(null, null, null, null);
    private static final Type RESULT_TYPE =
            new TypeToken<TeacherOperationResultDTO<TeacherGradeBookDTO>>() { }.getType();

    private final TeacherGradeBookDAO dao;
    private final TeacherGradeAuditDAO audit;
    private final TeacherCourseOperationDAO operations;
    private final TeacherAccessPolicy accessPolicy;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of TeacherGradeBookService.
    */
    public TeacherGradeBookService() {
        this(new TeacherGradeBookDAO(), new TeacherGradeAuditDAO(), new TeacherCourseOperationDAO(),
                new TeacherAccessPolicy(), Clock.systemUTC());
    }

    /** 供测试注入固定 {@link Clock} 与可覆写 DAO，从而可验证写入时间与整笔回滚。 */
    public TeacherGradeBookService(TeacherGradeBookDAO dao, TeacherGradeAuditDAO audit,
                                   TeacherCourseOperationDAO operations,
                                   TeacherAccessPolicy accessPolicy, Clock clock) {
        this.dao = dao;
        this.audit = audit;
        this.operations = operations;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
    * 新工作副本的默认方案：四项固定组成，全部启用，权重为 0。草稿允许权重未配齐，正式提交的
    * “启用项权重大于 0、合计 10000”由 Task 4 的提交校验把关；客户端永远不需要（也不许）自己
    * 发明一份方案。
    */
    public static GradeSchemeDTO defaultScheme() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            components.add(new GradeComponentDTO(code, true, 0));
        }
        return new GradeSchemeDTO(components);
    }

    // -------------------------------------------------------------------- 读

    /**
    * 一个教学班的成绩表。没有工作副本时返回 {@code revision=0} 的虚拟草稿且不写库；
    * 未提交的批次显示 DRAFT，已提交的批次显示 PENDING/APPROVED/REJECTED。
    */
    public TeacherGradeBookDTO getGradeBook(String uid, String offeringId) {
        String teacher = requireUid(uid);
        long offering = AdminOperationTransaction.parseId(offeringId, "offeringId");
        return read(connection -> {
            accessPolicy.requireViewOffering(connection, teacher, offering);
            return readBook(connection, offering,
                    accessPolicy.canEditGrades(connection, teacher, offering));
        });
    }

    /** 本人担任任课教师（role=0）的教学班成绩列表，按学期分页；没有工作副本的班也在这里。 */
    public TeacherPageDTO<TeacherGradeOfferingDTO> listGradeOfferings(String uid, int academicYear,
                                                                      int semester, int page,
                                                                      int size) {
        String teacher = requireUid(uid);
        requireTerm(academicYear, semester);
        int offset = offset(page, size);
        return read(connection -> {
            List<TeacherGradeBookDAO.ListedOffering> listed = dao.listOfferings(connection, teacher,
                    academicYear, semester, size, offset);
            long total = dao.countOfferings(connection, teacher, academicYear, semester);
            List<Long> offeringIds = new ArrayList<>();
            for (TeacherGradeBookDAO.ListedOffering offering : listed) {
                offeringIds.add(offering.offeringId());
            }
            Map<Long, List<TeacherGradeBookDAO.RosterScoreRow>> roster = new HashMap<>();
            for (TeacherGradeBookDAO.RosterScoreRow student
                    : dao.listRosterScores(connection, offeringIds)) {
                roster.computeIfAbsent(student.offeringId(), key -> new ArrayList<>()).add(student);
            }
            List<TeacherGradeOfferingDTO> items = new ArrayList<>();
            for (TeacherGradeBookDAO.ListedOffering row : listed) {
                List<TeacherGradeBookDAO.RosterScoreRow> students =
                        roster.getOrDefault(row.offeringId(), List.of());
                GradeSchemeDTO scheme = row.book() == null ? defaultScheme() : row.book().scheme();
                Counters counters = counters(scheme, students);
                // 列表只列出 role=0 的教学班，所以能力位就是工作副本状态本身。
                BookState state = bookState(row.book(), true);
                Long lastSubmissionId = row.book() == null ? null : row.book().lastSubmissionId();
                TeacherOfferingDTO offering = new TeacherOfferingDTO(
                        Long.toString(row.offeringId()), row.offeringCode(),
                        row.courseName() + " " + row.offeringCode(), Long.toString(row.courseId()),
                        row.courseCode(), row.courseName(), row.credit(), row.academicYear(),
                        row.semester(), students.size(), row.capacity(),
                        AdminOfferingDAO.statusLabel(row.status()), state.canEdit(), true);
                items.add(new TeacherGradeOfferingDTO(offering, state.state(), counters.entered(),
                        counters.missing(),
                        lastSubmissionId == null ? null : Long.toString(lastSubmissionId)));
            }
            return new TeacherPageDTO<>(items, total, page, size);
        });
    }

    // -------------------------------------------------------------------- 写

    /**
    * Persists saveDraft data.
    */
    public TeacherOperationResultDTO<TeacherGradeBookDTO> saveDraft(String uid,
            WriteGradeBookRequestDTO raw) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Normalized request = normalize(teacher, raw, operationId, false);
        String action = TeacherCourseActions.SAVE_GRADE_DRAFT;
        String digest = operations.digest(action, request.canonical());
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                        saveTransaction(connection, request, action, digest);
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
                restoreAutoCommit(connection, originalAutoCommit, inFlight, SAVE_FAILURE);
            }
        } catch (SQLException failure) {
            throw new DatabaseException(SAVE_FAILURE, failure);
        }
    }

    /**
    * 在**调用方持有**的连接与事务里执行一次草稿写入：不打开连接、不提交、不回滚、不恢复
    * autoCommit，连接的生命周期完全由调用方负责。
    *
    * <p>导入确认要用它：候选草稿、教师操作日志与（若重试）已提交结果的重放必须在同一个事务里
    * 完成，而 {@link #saveDraft} 自带一个独立事务，从另一个事务里调用它就会把「这一笔到底提交了
    * 没有」拆成两段。这里复用同一套 {@code validate → normalize → digest → saveTransaction} 流程，
    * 所以确认导入写的草稿与教师手工保存出来的草稿完全是同一种状态，唯一的差别是动作名：
    * {@code action} 会写进单元格变更审计，导入改写整班与手工改一格必须能分辨。
    *
    * @param action 触发本次写入的动作名（导入确认用
    *         {@link TeacherCourseActions#CONFIRM_GRADE_IMPORT}），参与请求摘要
    */
    TeacherOperationResultDTO<TeacherGradeBookDTO> saveDraftInTransaction(Connection connection,
            String uid, WriteGradeBookRequestDTO raw, String action) throws SQLException {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Normalized request = normalize(teacher, raw, operationId, false);
        String digest = operations.digest(action, request.canonical());
        return saveTransaction(connection, request, action, digest);
    }

    /**
    * 正式提交：请求就是当前完整编辑内容，事务内先按草稿规则保存工作副本，再把它整份捕获成一个
    * 不可变批次（方案/名单摘要/身份/统计/明细），关闭草稿并记录 last_submission_id。
    *
    * <p>与 {@link #saveDraft} 的差别：方案必须配齐权重（否则提交没有确定的总评），整份正常名单的
    * 每个启用组成都必须有分数（缺失不是 0 分，拒绝而不是补 0），并且该教学班不能已有 PENDING 批次。
    * 提交不额外写正式成绩：只有管理员审批成功才发布 {@code grade} 投影。
    */
    public TeacherOperationResultDTO<TeacherGradeBookDTO> submitGradeBook(String uid,
            WriteGradeBookRequestDTO raw) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Normalized request = normalize(teacher, raw, operationId, true);
        String action = TeacherCourseActions.SUBMIT_GRADE_BOOK;
        String digest = operations.digest(action, request.canonical());
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                        submitTransaction(connection, request, action, digest);
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
                restoreAutoCommit(connection, originalAutoCommit, inFlight, SUBMIT_FAILURE);
            }
        } catch (SQLException failure) {
            throw new DatabaseException(SUBMIT_FAILURE, failure);
        }
    }

    // ------------------------------------------------------------ 驳回重开与更正

    /**
    * 驳回重开：把本班最后一次<b>被驳回</b>的批次复制成一份新的可编辑草稿。
    *
    * <p>与「直接对被驳回的草稿再保存一次」（{@link #prepareBook} 里的惰性重开）是同一件事的两个
    * 入口：草稿类型都标 RESUBMISSION、基础批次都是那一批被驳回的批次、都不在这里递增 revision。
    * 差别只在时机——显式重开不要求教师先改一格成绩，也不要求原因。
    */
    public TeacherOperationResultDTO<TeacherGradeBookDTO> reopenRejectedGradeBook(String uid,
            StartGradeRevisionRequestDTO raw) {
        return startRevision(uid, raw, REOPEN_GOAL);
    }

    /**
    * 发起更正：把本班最后一次<b>已通过</b>的批次复制成一份新的可编辑草稿，更正原因必填。
    *
    * <p>学生的当前成绩在更正批次被管理员通过之前不会改变：这里只动工作副本与草稿，不碰
    * {@code grade} 投影。后续提交的批次带 CORRECTION 类型与 base_submission_id，由
    * {@link #submitTransaction} 从工作副本读出，不需要第二条写通路。
    */
    public TeacherOperationResultDTO<TeacherGradeBookDTO> beginGradeCorrection(String uid,
            StartGradeRevisionRequestDTO raw) {
        return startRevision(uid, raw, CORRECTION_GOAL);
    }

    private TeacherOperationResultDTO<TeacherGradeBookDTO> startRevision(String uid,
            StartGradeRevisionRequestDTO raw, RevisionGoal goal) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Revision request = normalizeRevision(teacher, raw, operationId, goal);
        String digest = operations.digest(goal.action(), request.canonical());
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                        startRevisionTransaction(connection, request, goal, digest);
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
                restoreAutoCommit(connection, originalAutoCommit, inFlight, REVISION_FAILURE);
            }
        } catch (SQLException failure) {
            throw new DatabaseException(REVISION_FAILURE, failure);
        }
    }

    /**
    * 版本变更事务：与保存/提交共用同一把 offering 锁与同一个锁顺序（offering → grade book →
    * 明细），守卫依次是「没有未审批批次」「没有已打开未处理的草稿」「来源就是最后一次批次且状态
    * 正好对上」「客户端看到的版本就是当前版本」。任何一条不满足都只给冲突与最新成绩表，绝不在
    * 一个不属于本次操作的批次上动手。
    */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> startRevisionTransaction(
            Connection connection, Revision request, RevisionGoal goal, String digest)
            throws SQLException {
        TeacherCourseOperationDAO.StoredOperation stored =
                operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        if (!dao.lockOffering(connection, request.offeringId())) {
            throw new TeacherAccessPolicy.AccessDeniedException("没有编辑该教学班成绩的权限");
        }
        accessPolicy.requireEditGrades(connection, request.uid(), request.offeringId());
        // A duplicate request may have committed while this one waited for the offering lock.
        stored = operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        TeacherGradeBookDAO.GradeBookRow book =
                dao.findBookForUpdate(connection, request.offeringId());
        if (book == null) {
            // 没有工作副本就没有任何批次可复制：虚拟草稿本来就可以直接编辑，不需要"重开"。
            throw new ConflictException("该教学班还没有成绩批次，不能重开或发起更正",
                    readBook(connection, request.offeringId(), true));
        }
        if (dao.findPendingSubmission(connection, request.offeringId()) != null) {
            // 待审批批次必须先由管理员审批或驳回，教师不能在审批途中替换它的基础版本。
            throw new ConflictException("该教学班已有待审批的成绩批次，请先等待审批结果",
                    readBook(connection, request.offeringId(), true));
        }
        if (book.draftOpen()) {
            // 已经打开的草稿就是"未处理完的副本"：再按一次按钮是冲突，不是把草稿重置一遍。
            throw new ConflictException("成绩草稿已经打开，请先提交或丢弃当前草稿",
                    readBook(connection, request.offeringId(), true));
        }
        requireRevisionSource(connection, request, goal, book);
        if (request.expectedRevision() != book.revision()) {
            throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }

        copySnapshotIntoDraft(connection, request);
        int affected = dao.reopenFromSubmission(connection, request.offeringId(), book.revision(),
                goal.draftKind(), request.sourceSubmissionId(),
                goal.correction() ? request.reason() : null, request.uid(), clock.instant());
        if (affected != 1) {
            throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }
        // 班级级审计：这一次"开始更正/重开"本身就是一次成绩版本变更，即使还没有任何分数被改，
        // 也要留下动作、原因、教师与时间。book_revision 是本次写入后仍在生效的版本（这里不递增，
        // 与 auditChanges 记录的"写入之后的版本"是同一个口径）。
        String schemeJson = TeacherGradeBookDAO.schemeJson(book.scheme());
        audit.insert(connection, request.uid(), request.offeringId(), null, request.operationId(),
                book.revision(), goal.action(), schemeJson, schemeJson,
                goal.correction() ? request.reason() : null);

        TeacherGradeBookDTO entity = readBook(connection, request.offeringId(), true);
        TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                new TeacherOperationResultDTO<>(request.operationId(), goal.message(), entity, false);
        return auditOrRecover(connection, request.uid(), request.operationId(),
                request.offeringId(), request.canonical(), goal.action(), digest, result);
    }

    /**
    * 来源批次必须是本班最后一次提交，且处于本次操作要求的状态（重开只认 REJECTED、更正只认
    * APPROVED）。批次一旦被审批就是终态，所以这里对 {@code grade_submission} 的非锁定读是安全的：
    * 最坏情况是"还没有看到刚刚提交的审批结论"，那只会多给一次可重试的冲突，不会放行不该放行的写入
    * （与 {@link #reopenDraft} 的判断同源）。
    */
    private void requireRevisionSource(Connection connection, Revision request, RevisionGoal goal,
                                       TeacherGradeBookDAO.GradeBookRow book) throws SQLException {
        if (book.lastSubmissionId() == null
                || book.lastSubmissionId() != request.sourceSubmissionId()) {
            throw new ConflictException("来源批次不是该教学班最后一次提交，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }
        TeacherGradeBookDAO.SubmissionRef source =
                dao.findSubmissionRef(connection, request.sourceSubmissionId());
        if (source == null || source.offeringId() != request.offeringId()
                || !goal.requiredStatus().equals(source.status())) {
            throw new ConflictException(STATE_REJECTED.equals(goal.requiredStatus())
                    ? "只有被驳回的成绩批次可以重新编辑"
                    : "只有已通过的成绩批次可以发起更正",
                    readBook(connection, request.offeringId(), true));
        }
    }

    /**
    * 从来源批次的冻结快照重建可编辑副本：先删掉当前名单里已经没有的草稿行，再把快照里<b>同时</b>
    * 属于当前正常名单的分数写回草稿。
    *
    * <p>来源是批次快照而不是当前草稿：草稿里可能留着从没进过任何批次的旧值（被禁用的组成、
    * 退课学生改之前的分数），把它当历史事实复制过来就是在编造依据。提交之后才入学的学生因此
    * 没有草稿行——四项 NULL，不是 0 分，也绝不从别人的成绩里抄一份。
    */
    private void copySnapshotIntoDraft(Connection connection, Revision request) throws SQLException {
        List<Long> roster = dao.normalEnrollmentIds(connection, request.offeringId());
        dao.deleteItemsOutsideRoster(connection, request.offeringId(), roster);
        Map<Long, GradeScoresDTO> snapshot = new HashMap<>();
        for (TeacherGradeBookDAO.ItemRow item
                : dao.listSubmissionItems(connection, request.sourceSubmissionId())) {
            snapshot.put(item.enrollmentId(), item.scores());
        }
        for (Long enrollmentId : roster) {
            GradeScoresDTO scores = snapshot.get(enrollmentId);
            if (scores == null) continue;
            dao.upsertItem(connection, request.offeringId(), enrollmentId, scores);
        }
    }

    /**
    * 版本变更请求的规范化：两个 BIGINT 标识只解析一次，原因按空白归一（空白等于没写），更正要求
    * 原因非空。规范化后的 JSON 是幂等摘要的输入，所以 {@code ""}/空格/缺省三种写法是同一次请求。
    */
    private static Revision normalizeRevision(String uid, StartGradeRevisionRequestDTO raw,
                                              String operationId, RevisionGoal goal) {
        if (raw == null) throw new IllegalArgumentException("请求体不能为空");
        long offeringId = AdminOperationTransaction.parseId(raw.getOfferingId(), "offeringId");
        long sourceSubmissionId =
                AdminOperationTransaction.parseId(raw.getSourceSubmissionId(), "sourceSubmissionId");
        long expectedRevision = raw.getExpectedRevision();
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负数");
        }
        String reason = AdminOperationTransaction.blankToNull(raw.getReason());
        if (goal.correction() && reason == null) {
            throw new IllegalArgumentException("更正原因不能为空");
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("更正原因不能超过 " + MAX_REASON + " 字符");
        }
        return new Revision(uid, operationId, offeringId, sourceSubmissionId, expectedRevision,
                reason, canonicalRevision(operationId, offeringId, sourceSubmissionId,
                expectedRevision, reason));
    }

    /** 版本变更的规范化请求体：字段顺序固定，缺失的原因为 JSON null。 */
    private static JsonObject canonicalRevision(String operationId, long offeringId,
                                                long sourceSubmissionId, long expectedRevision,
                                                String reason) {
        JsonObject canonical = new JsonObject();
        canonical.addProperty("operationId", operationId);
        canonical.addProperty("offeringId", Long.toString(offeringId));
        canonical.addProperty("sourceSubmissionId", Long.toString(sourceSubmissionId));
        canonical.addProperty("expectedRevision", expectedRevision);
        canonical.addProperty("reason", reason);
        return canonical;
    }

    private TeacherOperationResultDTO<TeacherGradeBookDTO> saveTransaction(Connection connection,
            Normalized request, String action, String digest) throws SQLException {
        TeacherCourseOperationDAO.StoredOperation stored =
                operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        // 锁顺序：offering → grade book → 按 enrollment_id 排序的明细。
        if (!dao.lockOffering(connection, request.offeringId())) {
            throw new TeacherAccessPolicy.AccessDeniedException("没有编辑该教学班成绩的权限");
        }
        accessPolicy.requireEditGrades(connection, request.uid(), request.offeringId());
        // A duplicate request may have committed while this one waited for the offering lock.
        stored = operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        TeacherGradeBookDAO.GradeBookRow book =
                dao.findBookForUpdate(connection, request.offeringId());
        if (book == null && request.expectedRevision() != 0) {
            // 还没有工作副本时唯一合法的期望版本是 0：客户端看到的就是 revision=0 的虚拟草稿，
            // 任何别的版本号都说明它读到的不是这份状态，只给冲突、不给静默创建。
            throw new ConflictException("该教学班还没有成绩草稿，expectedRevision 必须为 0",
                    readBook(connection, request.offeringId(), true));
        }
        List<Long> rosterIds = dao.normalEnrollmentIds(connection, request.offeringId());
        if (!TeacherGradeBookDAO.rosterDigest(rosterIds).equals(request.rosterDigest())) {
            throw new ConflictException("名单已变化，请重新加载成绩表并合并已输入的成绩",
                    readBook(connection, request.offeringId(), true));
        }
        Set<Long> roster = new HashSet<>(rosterIds);
        for (Row row : request.rows()) {
            if (!roster.contains(row.enrollmentId())) {
                throw new IllegalArgumentException(
                        "学生不在本教学班当前名单中: " + row.enrollmentId());
            }
        }

        writeDraft(connection, request, book, action);

        TeacherGradeBookDTO entity = readBook(connection, request.offeringId(), true);
        TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                new TeacherOperationResultDTO<>(request.operationId(), "成绩草稿已保存", entity,
                        false);
        return auditOrRecover(connection, request.uid(), request.operationId(),
                request.offeringId(), request.canonical(), action, digest, result);
    }

    /**
    * 保存草稿的公共写路径：方案/版本原子更新 → 按 enrollment_id 升序写合并后的明细 → 变更审计。
    * 保存与提交共用它，所以提交保存的草稿和单独保存草稿得到的是同一份状态。
    */
    private PreparedBook writeDraft(Connection connection, Normalized request,
                                    TeacherGradeBookDAO.GradeBookRow book, String action)
            throws SQLException {
        List<TeacherGradeBookDAO.ItemRow> previousItems = book == null
                ? List.of() : dao.listItems(connection, request.offeringId());
        Map<Long, GradeScoresDTO> previous = new HashMap<>();
        for (TeacherGradeBookDAO.ItemRow item : previousItems) {
            previous.put(item.enrollmentId(), item.scores());
        }
        PreparedBook prepared = prepareBook(connection, request, book);
        List<Row> storedRows = new ArrayList<>();
        for (Row row : request.rows()) {
            storedRows.add(new Row(row.enrollmentId(),
                    mergeDisabled(row.scores(), previous.get(row.enrollmentId()),
                            request.scheme())));
        }
        for (Row row : storedRows) {
            dao.upsertItem(connection, request.offeringId(), row.enrollmentId(), row.scores());
        }
        auditChanges(connection, request, action, book == null ? null : book.scheme(), previous,
                storedRows, prepared.revision(), prepared.correctionReason());
        return prepared;
    }

    /**
    * 工作副本的方案与版本原子更新。首次创建只认 {@code expectedRevision=0}（守卫在调用方）；
    * 被驳回的关闭草稿先惰性重开；其余关闭状态（PENDING/APPROVED）直接冲突。
    *
    * @return 本次写入后的 revision 与工作副本类型，供提交记录批次来源
    */
    private PreparedBook prepareBook(Connection connection, Normalized request,
                                     TeacherGradeBookDAO.GradeBookRow book) throws SQLException {
        String schemeJson = TeacherGradeBookDAO.schemeJson(request.scheme());
        if (book == null) {
            dao.insertBook(connection, request.offeringId(), schemeJson, request.uid(),
                    clock.instant());
            return new PreparedBook(1, DRAFT_KIND_INITIAL, null, null);
        }
        boolean reopened = !book.draftOpen();
        if (reopened) reopenDraft(connection, request, book);
        // 条件里的版本是客户端看到的 expectedRevision，不是刚读到的版本：乐观锁由这一行
        // 影响行数来判定，过期请求一个明细字节都写不出去。
        int affected = dao.updateScheme(connection, request.offeringId(),
                request.expectedRevision(), schemeJson, request.uid(), clock.instant());
        if (affected != 1) {
            // 版本过期或草稿已关闭：明细一个字节都不许写。
            throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }
        // 重开的草稿类型固定是 RESUBMISSION，基础批次就是被驳回的那一批，更正原因被清空
        // （两者都由 DAO 在重开时写入；这里同步反映到内存里的那份状态）。
        return new PreparedBook(book.revision() + 1,
                reopened ? DRAFT_KIND_RESUBMISSION : book.draftKind(),
                reopened ? book.lastSubmissionId() : book.baseSubmissionId(),
                reopened ? null : book.correctionReason());
    }

    /**
    * 驳回后的惰性重开：只把草稿状态翻回来，revision 留给随后那次方案更新递增。
    * 非 REJECTED 的关闭草稿（PENDING/APPROVED）保持只读，这里直接冲突。
    */
    private void reopenDraft(Connection connection, Normalized request,
                             TeacherGradeBookDAO.GradeBookRow book) throws SQLException {
        if (book.lastSubmissionId() == null
                || !STATE_REJECTED.equals(book.submissionStatus())) {
            throw new ConflictException("成绩草稿已关闭，只有被驳回的批次可以重新编辑",
                    readBook(connection, request.offeringId(), true));
        }
        int reopened = dao.reopenForResubmission(connection, request.offeringId(),
                request.expectedRevision(), book.lastSubmissionId());
        if (reopened != 1) {
            throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }
    }

    /**
    * 提交事务。锁顺序与保存一致：offering → grade book → 按 enrollment_id 排序的明细；
    * 新建的批次行排在最后（批次头 → 明细 → 关闭草稿），审批路径只锁批次、从不回锁工作副本，
    * 两边不存在环。
    *
    * <p>捕获的快照直接来自刚写入的草稿行：提交不引入第二条数据通路，事务内被拒绝时整笔回滚
    * （草稿、批次、统计、日志一起消失），所以“响应丢失后重试”只会重放一个已提交批次，
    * 不会留下半个批次。
    */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> submitTransaction(Connection connection,
            Normalized request, String action, String digest) throws SQLException {
        TeacherCourseOperationDAO.StoredOperation stored =
                operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        if (!dao.lockOffering(connection, request.offeringId())) {
            throw new TeacherAccessPolicy.AccessDeniedException("没有编辑该教学班成绩的权限");
        }
        accessPolicy.requireEditGrades(connection, request.uid(), request.offeringId());
        // A duplicate request may have committed while this one waited for the offering lock.
        stored = operations.find(connection, request.uid(), request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        // 每班至多一个 PENDING 批次：持 offering 锁时检查，随后插入的批次也在同一锁内完成。
        if (dao.findPendingSubmission(connection, request.offeringId()) != null) {
            throw new ConflictException("该教学班已有待审批的成绩批次，不能重复提交",
                    readBook(connection, request.offeringId(), true));
        }
        TeacherGradeBookDAO.GradeBookRow book =
                dao.findBookForUpdate(connection, request.offeringId());
        if (book == null && request.expectedRevision() != 0) {
            throw new ConflictException("该教学班还没有成绩草稿，expectedRevision 必须为 0",
                    readBook(connection, request.offeringId(), true));
        }
        List<Long> rosterIds = dao.normalEnrollmentIds(connection, request.offeringId());
        if (!TeacherGradeBookDAO.rosterDigest(rosterIds).equals(request.rosterDigest())) {
            throw new ConflictException("名单已变化，请重新加载成绩表并合并已输入的成绩",
                    readBook(connection, request.offeringId(), true));
        }
        Set<Long> roster = new HashSet<>(rosterIds);
        for (Row row : request.rows()) {
            if (!roster.contains(row.enrollmentId())) {
                throw new IllegalArgumentException(
                        "学生不在本教学班当前名单中: " + row.enrollmentId());
            }
        }

        PreparedBook prepared = writeDraft(connection, request, book, action);

        // 快照来自刚写入的草稿行：正常名单里的每个学生都按当前方案重算，缺启用项分数一律拒绝。
        Map<Long, GradeScoresDTO> storedScores = new HashMap<>();
        for (TeacherGradeBookDAO.ItemRow item : dao.listItems(connection, request.offeringId())) {
            storedScores.put(item.enrollmentId(), item.scores());
        }
        List<TeacherGradeBookDAO.SubmissionItemRow> items = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = null;
        BigDecimal min = null;
        int failed = 0;
        for (TeacherGradeBookDAO.RosterScoreRow student
                : dao.listRosterScores(connection, List.of(request.offeringId()))) {
            GradeScoresDTO snapshot = disabledAsNull(storedScores.get(student.enrollmentId()),
                    request.scheme());
            BigDecimal total;
            try {
                total = GradeCalculator.total(request.scheme(), snapshot);
            } catch (IllegalArgumentException broken) {
                throw new IllegalArgumentException("提交成绩失败: " + broken.getMessage());
            }
            if (total == null) {
                // 缺失不是 0 分：整份名单的启用组成都必须有分数才能提交。
                throw new IllegalArgumentException(
                        "提交成绩前必须补齐所有启用组成的成绩，缺少学生: " + student.studentUid());
            }
            BigDecimal point = GradePointScale.gradePointFor(total);
            sum = sum.add(total);
            max = max == null || total.compareTo(max) > 0 ? total : max;
            min = min == null || total.compareTo(min) < 0 ? total : min;
            if (total.compareTo(FAIL_SCORE) < 0) failed++;
            items.add(new TeacherGradeBookDAO.SubmissionItemRow(student.enrollmentId(), snapshot,
                    total, point, student.studentUid(), student.studentName()));
        }
        items.sort(Comparator.comparingLong(TeacherGradeBookDAO.SubmissionItemRow::enrollmentId));
        int totalCount = items.size();
        BigDecimal average = totalCount == 0 ? null
                : sum.divide(BigDecimal.valueOf(totalCount), SCORE_SCALE, RoundingMode.HALF_UP);

        Instant now = clock.instant();
        long submissionId = dao.insertSubmission(connection, new TeacherGradeBookDAO.SubmissionInsert(
                request.offeringId(), dao.nextSubmissionVersion(connection, request.offeringId()),
                request.uid(), now, TeacherGradeBookDAO.schemeJson(request.scheme()),
                request.rosterDigest(), prepared.baseSubmissionId(), prepared.correctionReason(),
                prepared.draftKind(), totalCount, failed, average, max, min));
        for (TeacherGradeBookDAO.SubmissionItemRow item : items) {
            dao.insertSubmissionItem(connection, submissionId, item);
        }
        if (dao.markSubmitted(connection, request.offeringId(), prepared.revision(), submissionId,
                request.uid(), now) != 1) {
            throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                    readBook(connection, request.offeringId(), true));
        }

        TeacherGradeBookDTO entity = readBook(connection, request.offeringId(), true);
        TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                new TeacherOperationResultDTO<>(request.operationId(), "成绩批次已提交", entity, false);
        return auditOrRecover(connection, request.uid(), request.operationId(),
                request.offeringId(), request.canonical(), action, digest, result);
    }

    /** 提交快照的组成值：禁用组成一律写 NULL，草稿里的旧值绝不进入正式批次。 */
    private static GradeScoresDTO disabledAsNull(GradeScoresDTO scores, GradeSchemeDTO scheme) {
        if (scores == null) return EMPTY_SCORES;
        return new GradeScoresDTO(
                enabledScore(GradeComponentCodeDTO.DAILY, scores.getDailyScore(), scheme),
                enabledScore(GradeComponentCodeDTO.MIDTERM, scores.getMidtermScore(), scheme),
                enabledScore(GradeComponentCodeDTO.EXPERIMENT, scores.getExperimentScore(), scheme),
                enabledScore(GradeComponentCodeDTO.FINALTERM, scores.getFinaltermScore(), scheme));
    }

    private static BigDecimal enabledScore(GradeComponentCodeDTO code, BigDecimal score,
                                           GradeSchemeDTO scheme) {
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (component.getCode() == code) return component.isEnabled() ? score : null;
        }
        return null;
    }

    // ----------------------------------------------------------------- 规范化

    private static Normalized normalize(String uid, WriteGradeBookRequestDTO raw,
                                        String operationId, boolean requireComplete) {
        if (raw == null || raw.getContent() == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        GradeBookContentDTO content = raw.getContent();
        long offeringId = AdminOperationTransaction.parseId(content.getOfferingId(), "offeringId");
        int expectedRevision = content.getExpectedRevision();
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负数");
        }
        String rosterDigest = requireDigest(content.getRosterDigest());
        GradeSchemeDTO scheme =
                content.getScheme() == null ? defaultScheme() : content.getScheme();
        // 提交（requireComplete）还要求权重配齐：没有确定权重就没有可核验的总评。
        GradeCalculator.validateScheme(scheme, requireComplete);
        scheme = canonicalScheme(scheme);

        List<Row> rows = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (GradeRowInputDTO row : content.getRows()) {
            if (row == null) throw new IllegalArgumentException("成绩行不能为空");
            long enrollmentId =
                    AdminOperationTransaction.parseId(row.getEnrollmentId(), "enrollmentId");
            if (!seen.add(enrollmentId)) {
                throw new IllegalArgumentException("同一学生不能在同一次保存里重复出现");
            }
            GradeScoresDTO scores = row.getScores() == null ? EMPTY_SCORES : row.getScores();
            GradeCalculator.validateScores(scores);
            rows.add(new Row(enrollmentId, scores));
        }
        rows.sort(Comparator.comparingLong(Row::enrollmentId));
        return new Normalized(uid, operationId, offeringId, expectedRevision, rosterDigest, scheme,
                List.copyOf(rows),
                canonical(operationId, offeringId, expectedRevision, rosterDigest, scheme, rows));
    }

    /** 方案按固定四项的枚举顺序存储，使 scheme_json 可比较、审计不因列顺序漂移。 */
    private static GradeSchemeDTO canonicalScheme(GradeSchemeDTO scheme) {
        List<GradeComponentDTO> components = new ArrayList<>(scheme.getComponents());
        components.sort(Comparator.comparingInt(component -> component.getCode().ordinal()));
        return new GradeSchemeDTO(components);
    }

    private static String requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("rosterDigest 必须是 64 位小写十六进制 SHA-256");
        }
        return digest;
    }

    /**
    * 规范化请求：字段顺序固定、行按 enrollment_id 升序、方案按枚举顺序、分数按两位小数文本。
    * 摘要与操作日志都建立在这份 JSON 上，所以行序或 {@code 88.5}/{@code 88.50} 的写法不会造成
    * 假冲突；内容真的不同时摘要必然不同。
    */
    private static JsonObject canonical(String operationId, long offeringId, int expectedRevision,
                                        String rosterDigest, GradeSchemeDTO scheme,
                                        List<Row> rows) {
        JsonObject canonical = new JsonObject();
        canonical.addProperty("operationId", operationId);
        canonical.addProperty("offeringId", Long.toString(offeringId));
        canonical.addProperty("expectedRevision", expectedRevision);
        canonical.addProperty("rosterDigest", rosterDigest);
        JsonObject schemeValues = new JsonObject();
        JsonArray components = new JsonArray();
        for (GradeComponentDTO component : scheme.getComponents()) {
            JsonObject item = new JsonObject();
            item.addProperty("code", component.getCode().name());
            item.addProperty("enabled", component.isEnabled());
            item.addProperty("weightBasisPoints", component.getWeightBasisPoints());
            components.add(item);
        }
        schemeValues.add("components", components);
        canonical.add("scheme", schemeValues);
        JsonArray rowValues = new JsonArray();
        for (Row row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("enrollmentId", Long.toString(row.enrollmentId()));
            JsonObject scores = new JsonObject();
            scores.addProperty("daily", scoreText(row.scores().getDailyScore()));
            scores.addProperty("midterm", scoreText(row.scores().getMidtermScore()));
            scores.addProperty("experiment", scoreText(row.scores().getExperimentScore()));
            scores.addProperty("finalterm", scoreText(row.scores().getFinaltermScore()));
            item.add("scores", scores);
            rowValues.add(item);
        }
        canonical.add("rows", rowValues);
        return canonical;
    }

    /** 分数已在规范化阶段校验过小数位，setScale(2) 只会补零，不会四舍五入。 */
    private static String scoreText(BigDecimal score) {
        return score == null ? null : score.setScale(SCORE_SCALE).toPlainString();
    }

    /**
    * 禁用项不是“清空”：客户端没有给值时保留草稿里已有的值，重新启用后原值还在。
    * 只有提交快照把禁用项置 NULL（{@link #disabledAsNull}），草稿阶段不做这个替换。
    */
    private static GradeScoresDTO mergeDisabled(GradeScoresDTO incoming, GradeScoresDTO stored,
                                                GradeSchemeDTO scheme) {
        if (stored == null) return incoming;
        return new GradeScoresDTO(
                keep(GradeComponentCodeDTO.DAILY, incoming.getDailyScore(),
                        stored.getDailyScore(), scheme),
                keep(GradeComponentCodeDTO.MIDTERM, incoming.getMidtermScore(),
                        stored.getMidtermScore(), scheme),
                keep(GradeComponentCodeDTO.EXPERIMENT, incoming.getExperimentScore(),
                        stored.getExperimentScore(), scheme),
                keep(GradeComponentCodeDTO.FINALTERM, incoming.getFinaltermScore(),
                        stored.getFinaltermScore(), scheme));
    }

    private static BigDecimal keep(GradeComponentCodeDTO code, BigDecimal incoming,
                                   BigDecimal stored, GradeSchemeDTO scheme) {
        if (incoming != null) return incoming;
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (component.getCode() == code && !component.isEnabled()) return stored;
        }
        return null;
    }

    // -------------------------------------------------------------------- 审计

    /**
    * 权重（方案）变化记班级级日志，分数变化记学生级日志；首次创建没有 before_json。
    * 学生级快照带服务器按当时方案算出的总评与绩点，便于追责时核对“当时是多少”。
    * {@code action} 是触发本次写入的动作：单独保存记 saveGradeDraft，提交连带保存记 submitGradeBook；
    * {@code reason} 是更正原因（普通草稿为 null），更正批次的学生级日志因此也带着原因。
    *
    * <p>权重变化会影响<b>整班</b>：四个组成分数一个字节都没动，重算后的总评和绩点却可能整片改变，
    * 所以方案变了的学生级日志不能只看「分数有没有改」，而要比「按变更前后方案分别重算的总评/绩点
    * 有没有改」，否则一次纯权重调整在审计里会看起来什么都没发生。分数确实改了的行照旧记录——
    * 禁用组成上的改动也是改动，不因为总评恰好没动就被吞掉。
    */
    private void auditChanges(Connection connection, Normalized request, String action,
                              GradeSchemeDTO beforeScheme, Map<Long, GradeScoresDTO> before,
                              List<Row> after, int revision, String reason) throws SQLException {
        String schemeJson = TeacherGradeBookDAO.schemeJson(request.scheme());
        boolean schemeChanged = beforeScheme == null
                || !TeacherGradeBookDAO.schemeJson(beforeScheme).equals(schemeJson);
        if (schemeChanged) {
            audit.insert(connection, request.uid(), request.offeringId(), null,
                    request.operationId(), revision, action,
                    beforeScheme == null ? null : TeacherGradeBookDAO.schemeJson(beforeScheme),
                    schemeJson, reason);
        }
        for (Row row : after) {
            GradeScoresDTO previous = before.get(row.enrollmentId());
            boolean scoresChanged = previous == null ? !blank(row.scores())
                    : !sameScores(previous, row.scores());
            if (!scoresChanged
                    && !(schemeChanged && moved(beforeScheme, previous, request.scheme(),
                            row.scores()))) {
                continue;
            }
            audit.insert(connection, request.uid(), request.offeringId(), row.enrollmentId(),
                    request.operationId(), revision, action,
                    previous == null ? null
                            : TeacherGradeAuditDAO.scoreSnapshot(row.enrollmentId(), previous,
                                    total(beforeScheme, previous),
                                    gradePoint(beforeScheme, previous)),
                    TeacherGradeAuditDAO.scoreSnapshot(row.enrollmentId(), row.scores(),
                            total(request.scheme(), row.scores()),
                            gradePoint(request.scheme(), row.scores())),
                    reason);
        }
    }

    /**
    * 这一行的总评或绩点是否真的变了：变更前方案配旧分数 vs 变更后方案配存下来的分数。
    * 绩点目前是总评的纯函数，两项仍然各比一次：判据是「总评或绩点变了」，不给将来留空子。
    */
    private static boolean moved(GradeSchemeDTO beforeScheme, GradeScoresDTO before,
                                 GradeSchemeDTO afterScheme, GradeScoresDTO after) {
        BigDecimal beforeTotal = safeTotal(beforeScheme, before);
        BigDecimal afterTotal = safeTotal(afterScheme, after);
        if (!same(beforeTotal, afterTotal)) return true;
        if (beforeTotal == null) return false;
        return !same(GradePointScale.gradePointFor(beforeTotal),
                GradePointScale.gradePointFor(afterTotal));
    }

    /**
    * 算不出总评时按 null 处理。库里可能存着手工改过的非法分数（读取路径也为同样的情况准备了行级
    * 错误），这一层是审计的取舍：不该把一次保存变成失败，也不该因为算不出来就编一个总评。
    */
    private static BigDecimal safeTotal(GradeSchemeDTO scheme, GradeScoresDTO scores) {
        try {
            return total(scheme, scores);
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    // -------------------------------------------------------------------- 组装

    /**
    * 一个教学班的成绩表：当前正常名单 + 草稿分数 + 服务端重算的总评/绩点。
    * 工作副本不存在时给默认方案，但绝不写库。
    */
    private TeacherGradeBookDTO readBook(Connection connection, long offeringId, boolean gradeEditor)
            throws SQLException {
        TeacherGradeBookDAO.GradeBookRow book = dao.findBook(connection, offeringId);
        GradeSchemeDTO scheme = book == null ? defaultScheme() : book.scheme();
        List<TeacherGradeRowDTO> rows = new ArrayList<>();
        for (TeacherGradeBookDAO.RosterScoreRow student
                : dao.listRosterScores(connection, List.of(offeringId))) {
            rows.add(gradeRow(scheme, student));
        }
        BookState state = bookState(book, gradeEditor);
        Long lastSubmissionId = book == null ? null : book.lastSubmissionId();
        boolean rosterChanged = lastSubmissionId != null
                && dao.rosterChangedSinceSubmission(connection, offeringId, lastSubmissionId);
        return new TeacherGradeBookDTO(Long.toString(offeringId),
                book == null ? 0 : book.revision(),
                TeacherGradeBookDAO.rosterDigest(dao.normalEnrollmentIds(connection, offeringId)),
                state.state(), scheme, rows,
                lastSubmissionId == null ? null : Long.toString(lastSubmissionId),
                book == null || book.baseSubmissionId() == null ? null
                        : Long.toString(book.baseSubmissionId()),
                state.canEdit(), book == null ? null : book.correctionReason(), rosterChanged,
                // 审核意见与被驳回/已通过的批次一起读出来：只读状态界面据此显示“为什么不能改”。
                book == null ? null : book.reviewComment());
    }

    /** 总评与绩点永远由服务端重算；草稿权重未配齐或缺启用项分数时两者都为 null。 */
    private static TeacherGradeRowDTO gradeRow(GradeSchemeDTO scheme,
                                               TeacherGradeBookDAO.RosterScoreRow student) {
        BigDecimal total = null;
        List<String> errors = new ArrayList<>();
        try {
            total = GradeCalculator.total(scheme, student.scores());
        } catch (IllegalArgumentException broken) {
            // 库里存着算不出来的分数（手工改过的行）：行内报错，不让整张成绩表变成不可读。
            errors.add(broken.getMessage());
        }
        BigDecimal point = total == null ? null : GradePointScale.gradePointFor(total);
        return new TeacherGradeRowDTO(Long.toString(student.enrollmentId()), student.studentUid(),
                student.studentName(), student.scores(), total, point, total != null, errors);
    }

    /**
    * 界面状态与可编辑位：草稿开放时是 DRAFT；否则按最后一次批次显示 PENDING/APPROVED/REJECTED。
    * 被驳回的批次虽然 draft_open=0，但下一次保存会惰性重开，所以对任课教师仍是可编辑的。
    */
    private static BookState bookState(TeacherGradeBookDAO.GradeBookRow book,
                                       boolean gradeEditor) {
        if (book == null || book.draftOpen()) return new BookState(STATE_DRAFT, gradeEditor);
        String status = book.submissionStatus();
        if (STATE_REJECTED.equals(status)) return new BookState(STATE_REJECTED, gradeEditor);
        if (STATE_PENDING.equals(status) || STATE_APPROVED.equals(status)) {
            return new BookState(status, false);
        }
        // 防御分支：草稿已关闭却查不到最后一次批次，只读对待，绝不猜成可编辑。
        return new BookState(STATE_DRAFT, false);
    }

    /**
    * 已录入 = 至少一项非 null 分数；缺失 = 至少一个启用项没有分数（全空自然也算缺失）。
    * 四项都被禁用时没人缺分：没有启用项就没有“缺了什么”可言，缺失数因此是 0。
    */
    private static Counters counters(GradeSchemeDTO scheme,
                                     List<TeacherGradeBookDAO.RosterScoreRow> roster) {
        int entered = 0;
        int missing = 0;
        for (TeacherGradeBookDAO.RosterScoreRow student : roster) {
            boolean any = false;
            boolean absent = false;
            for (GradeComponentDTO component : scheme.getComponents()) {
                BigDecimal score = scoreOf(component.getCode(), student.scores());
                if (score != null) {
                    any = true;
                } else if (component.isEnabled()) {
                    absent = true;
                }
            }
            if (any) entered++;
            if (absent) missing++;
        }
        return new Counters(entered, missing);
    }

    /** 与 GradeScoresDTO 的字段顺序一致的取值；DTO 的四项与固定枚举一一对应。 */
    private static BigDecimal scoreOf(GradeComponentCodeDTO code, GradeScoresDTO scores) {
        return switch (code) {
            case DAILY -> scores.getDailyScore();
            case MIDTERM -> scores.getMidtermScore();
            case EXPERIMENT -> scores.getExperimentScore();
            case FINALTERM -> scores.getFinaltermScore();
        };
    }

    private static boolean sameScores(GradeScoresDTO left, GradeScoresDTO right) {
        return same(left.getDailyScore(), right.getDailyScore())
                && same(left.getMidtermScore(), right.getMidtermScore())
                && same(left.getExperimentScore(), right.getExperimentScore())
                && same(left.getFinaltermScore(), right.getFinaltermScore());
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static boolean blank(GradeScoresDTO scores) {
        return scores == null || (scores.getDailyScore() == null && scores.getMidtermScore() == null
                && scores.getExperimentScore() == null && scores.getFinaltermScore() == null);
    }

    private static BigDecimal total(GradeSchemeDTO scheme, GradeScoresDTO scores) {
        return scheme == null || scores == null ? null : GradeCalculator.total(scheme, scores);
    }

    private static BigDecimal gradePoint(GradeSchemeDTO scheme, GradeScoresDTO scores) {
        BigDecimal total = total(scheme, scores);
        return total == null ? null : GradePointScale.gradePointFor(total);
    }

    // ---------------------------------------------------------------- 幂等与事务

    /**
    * 操作日志是同一 operationId 的两个并发请求唯一共享的行：插入撞上主键时回滚本地写入，
    * 读回已提交结果，按摘要重放或返回摘要冲突，而不是把驱动错误抛给客户端。
    *
    * <p>保存、提交与版本变更（重开/更正）共用这一个入口，所以三种写操作的幂等语义只有一份实现。
    */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> auditOrRecover(Connection connection,
            String uid, String operationId, long offeringId, JsonObject canonical, String action,
            String digest, TeacherOperationResultDTO<TeacherGradeBookDTO> result)
            throws SQLException {
        try {
            operations.insert(connection, uid, operationId, action, TARGET_TYPE,
                    Long.toString(offeringId), digest, operations.json(canonical),
                    operations.json(result), OK);
            return result;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != DUPLICATE_KEY) throw failure;
            rollback(connection, failure);
            TeacherCourseOperationDAO.StoredOperation winner =
                    operations.find(connection, uid, operationId);
            if (winner == null) throw failure;
            return replay(winner, digest, operationId);
        }
    }

    private TeacherOperationResultDTO<TeacherGradeBookDTO> replay(
            TeacherCourseOperationDAO.StoredOperation stored, String digest, String operationId) {
        if (!digest.equals(stored.requestDigest())) {
            // 保存与提交也共用这一份日志：同一个 operationId 换个动作或换个内容都是冲突。
            throw new ConflictException("operationId 已用于不同的成绩写入请求");
        }
        TeacherOperationResultDTO<TeacherGradeBookDTO> storedResult =
                operations.decode(stored.responseJson(), RESULT_TYPE);
        return new TeacherOperationResultDTO<>(operationId, storedResult.getMessage(),
                storedResult.getValue(), true);
    }

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight,
    *  and a failed rollback is swallowed rather than replacing an escaping {@link Error}. */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
        }
    }

    /** Never replaces an in-flight failure with a connection-cleanup failure. */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit,
                                          Throwable inFlight, String failureMessage) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoration) {
            if (inFlight != null) {
                inFlight.addSuppressed(restoration);
            } else {
                throw new DatabaseException(failureMessage, restoration);
            }
        }
    }

    private static <T> T read(SqlRead<T> operation) {
        try (Connection connection = DBUtil.getConnection()) {
            return operation.execute(connection);
        } catch (SQLException failure) {
            throw new DatabaseException("查询教师成绩表失败", failure);
        }
    }

    @FunctionalInterface
    /**
    * Internal course-management type SqlRead.
    */
    private interface SqlRead<T> {
        T execute(Connection connection) throws SQLException;
    }

    // -------------------------------------------------------------------- 校验

    private static String requireUid(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        return uid.trim();
    }

    private static void requireTerm(int academicYear, int semester) {
        if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
    }

    private static int offset(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("页码必须大于 0，每页条数必须为 1 至 100");
        }
        return (page - 1) * size;
    }

    // -------------------------------------------------------------------- records

    /**
    * Internal course-management type Row.
    */
    private record Row(long enrollmentId, GradeScoresDTO scores) {
    }

    /** 界面状态与可编辑位；只有 role=0 的任课教师才可能 canEdit。 */
    private record BookState(String state, boolean canEdit) {
    }

    /**
    * Internal course-management type Counters.
    */
    private record Counters(int entered, int missing) {
    }

    /**
    * 保存/提交写入后的工作副本状态：{@code revision} 是本次写入后的版本，类型与基础批次供提交
    * 记录批次来源（重开的草稿固定是 RESUBMISSION，基础批次是被驳回的那一批）。
    *
    * <p>{@code correctionReason} 是本次写入后工作副本上的更正原因：重开一律为 null（重提不是更正，
    * 被驳回的批次本身就是一次更正时也不能把上一轮的原因带进新批次与变更审计），其余情况沿用工作
    * 副本原来的值。它必须从这里取而不是从写事务开头读到的那一行取——惰性重开已经把原因清掉了，
    * 而那个 {@code GradeBookRow} 还是清之前的样子。
    */
    private record PreparedBook(int revision, String draftKind, Long baseSubmissionId,
                                String correctionReason) {
    }

    /**
    * Internal course-management type Normalized.
    */
    private record Normalized(String uid, String operationId, long offeringId, int expectedRevision,
                              String rosterDigest, GradeSchemeDTO scheme, List<Row> rows,
                              JsonObject canonical) {
    }

    /** 规范化后的版本变更请求：来源批次、期望版本与归一后的原因。 */
    private record Revision(String uid, String operationId, long offeringId, long sourceSubmissionId,
                            long expectedRevision, String reason, JsonObject canonical) {
    }

    /**
    * 一次版本变更的目标。
    *
    * @param action        写进操作日志与变更审计的动作名
    * @param requiredStatus 来源批次必须处于的状态（REJECTED 重提 / APPROVED 更正）
    * @param draftKind     写进工作副本的草稿类型
    * @param correction    true 表示更正草稿：原因必填并写入 correction_reason；false（重提）不要求
    *                      原因，correction_reason 一律 NULL，绝不沿用上一轮的更正原因
    * @param message       成功响应的消息
    */
    private record RevisionGoal(String action, String requiredStatus, String draftKind,
                                boolean correction, String message) {
    }

    /** 成绩写操作的冲突：携带可重新加载的最新工作副本，上层映射为 CONFLICT 并把名单交给界面合并。 */
    public static class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final TeacherGradeBookDTO entity;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message) {
            this(message, null);
        }

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, TeacherGradeBookDTO entity) {
            super(message);
            this.entity = entity;
        }

        /** 最新可见的成绩表；operationId 摘要冲突这类“没有新内容可给”的情况为 null。 */
        public TeacherGradeBookDTO getEntity() {
            return entity;
        }
    }
}
