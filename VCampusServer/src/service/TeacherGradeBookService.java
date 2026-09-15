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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
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
    private static final int SCORE_SCALE = 2;
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

    public TeacherOperationResultDTO<TeacherGradeBookDTO> saveDraft(String uid,
            WriteGradeBookRequestDTO raw) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher, raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Normalized request = normalize(teacher, raw, operationId);
        String action = TeacherCourseActions.SAVE_GRADE_DRAFT;
        String digest = operations.digest(action, request.canonical());
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            try {
                TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                        saveTransaction(connection, request, action, digest);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                inFlight = failure;
                rollback(connection, failure);
                throw failure;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit, inFlight);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("保存成绩草稿事务执行失败", failure);
        }
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

        List<TeacherGradeBookDAO.ItemRow> previousItems = book == null
                ? List.of() : dao.listItems(connection, request.offeringId());
        String schemeJson = TeacherGradeBookDAO.schemeJson(request.scheme());
        int revision;
        if (book == null) {
            // expectedRevision 已在上面的守卫里要求为 0：首次创建只认“客户端也没有草稿”。
            dao.insertBook(connection, request.offeringId(), schemeJson, request.uid(),
                    clock.instant());
            revision = 1;
        } else {
            if (!book.draftOpen()) reopenDraft(connection, request, book);
            // 条件里的版本是客户端看到的 expectedRevision，不是刚读到的版本：乐观锁由这一行
            // 影响行数来判定，过期请求一个明细字节都写不出去。
            int affected = dao.updateScheme(connection, request.offeringId(),
                    request.expectedRevision(), schemeJson, request.uid(), clock.instant());
            if (affected != 1) {
                // 版本过期或草稿已关闭：明细一个字节都不许写。
                throw new ConflictException("成绩草稿版本已变化，请重新加载后重试",
                        readBook(connection, request.offeringId(), true));
            }
            revision = book.revision() + 1;
        }

        Map<Long, GradeScoresDTO> previous = new HashMap<>();
        for (TeacherGradeBookDAO.ItemRow item : previousItems) {
            previous.put(item.enrollmentId(), item.scores());
        }
        List<Row> storedRows = new ArrayList<>();
        for (Row row : request.rows()) {
            storedRows.add(new Row(row.enrollmentId(),
                    mergeDisabled(row.scores(), previous.get(row.enrollmentId()),
                            request.scheme())));
        }
        for (Row row : storedRows) {
            dao.upsertItem(connection, request.offeringId(), row.enrollmentId(), row.scores());
        }
        auditChanges(connection, request, book == null ? null : book.scheme(), previous, storedRows,
                revision);

        TeacherGradeBookDTO entity = readBook(connection, request.offeringId(), true);
        TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                new TeacherOperationResultDTO<>(request.operationId(), "成绩草稿已保存", entity,
                        false);
        return auditOrRecover(connection, request, action, digest, result);
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

    // ----------------------------------------------------------------- 规范化

    private static Normalized normalize(String uid, WriteGradeBookRequestDTO raw,
                                        String operationId) {
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
        GradeCalculator.validateScheme(scheme, false);
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
     * 提交时的“禁用项写 NULL”发生在 Task 4，草稿阶段不做这个替换。
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
     */
    private void auditChanges(Connection connection, Normalized request,
                              GradeSchemeDTO beforeScheme, Map<Long, GradeScoresDTO> before,
                              List<Row> after, int revision) throws SQLException {
        String schemeJson = TeacherGradeBookDAO.schemeJson(request.scheme());
        if (beforeScheme == null
                || !TeacherGradeBookDAO.schemeJson(beforeScheme).equals(schemeJson)) {
            audit.insert(connection, request.uid(), request.offeringId(), null,
                    request.operationId(), revision, TeacherCourseActions.SAVE_GRADE_DRAFT,
                    beforeScheme == null ? null : TeacherGradeBookDAO.schemeJson(beforeScheme),
                    schemeJson, null);
        }
        for (Row row : after) {
            GradeScoresDTO previous = before.get(row.enrollmentId());
            if (previous == null ? blank(row.scores()) : sameScores(previous, row.scores())) {
                continue;
            }
            audit.insert(connection, request.uid(), request.offeringId(), row.enrollmentId(),
                    request.operationId(), revision, TeacherCourseActions.SAVE_GRADE_DRAFT,
                    previous == null ? null
                            : TeacherGradeAuditDAO.scoreSnapshot(row.enrollmentId(), previous,
                                    total(beforeScheme, previous),
                                    gradePoint(beforeScheme, previous)),
                    TeacherGradeAuditDAO.scoreSnapshot(row.enrollmentId(), row.scores(),
                            total(request.scheme(), row.scores()),
                            gradePoint(request.scheme(), row.scores())),
                    null);
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
                state.canEdit(), book == null ? null : book.correctionReason(), rosterChanged);
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
     */
    private TeacherOperationResultDTO<TeacherGradeBookDTO> auditOrRecover(Connection connection,
            Normalized request, String action, String digest,
            TeacherOperationResultDTO<TeacherGradeBookDTO> result) throws SQLException {
        try {
            operations.insert(connection, request.uid(), request.operationId(), action,
                    TARGET_TYPE, Long.toString(request.offeringId()), digest,
                    operations.json(request.canonical()), operations.json(result), OK);
            return result;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != DUPLICATE_KEY) throw failure;
            rollback(connection, failure);
            TeacherCourseOperationDAO.StoredOperation winner =
                    operations.find(connection, request.uid(), request.operationId());
            if (winner == null) throw failure;
            return replay(winner, digest, request.operationId());
        }
    }

    private TeacherOperationResultDTO<TeacherGradeBookDTO> replay(
            TeacherCourseOperationDAO.StoredOperation stored, String digest, String operationId) {
        if (!digest.equals(stored.requestDigest())) {
            throw new ConflictException("operationId 已用于不同的成绩保存请求");
        }
        TeacherOperationResultDTO<TeacherGradeBookDTO> storedResult =
                operations.decode(stored.responseJson(), RESULT_TYPE);
        return new TeacherOperationResultDTO<>(operationId, storedResult.getMessage(),
                storedResult.getValue(), true);
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
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
                throw new DatabaseException("保存成绩草稿事务执行失败", restoration);
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

    private record Row(long enrollmentId, GradeScoresDTO scores) {
    }

    /** 界面状态与可编辑位；只有 role=0 的任课教师才可能 canEdit。 */
    private record BookState(String state, boolean canEdit) {
    }

    private record Counters(int entered, int missing) {
    }

    private record Normalized(String uid, String operationId, long offeringId, int expectedRevision,
                              String rosterDigest, GradeSchemeDTO scheme, List<Row> rows,
                              JsonObject canonical) {
    }

    /** 成绩写操作的冲突：携带可重新加载的最新工作副本，上层映射为 CONFLICT 并把名单交给界面合并。 */
    public static class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final TeacherGradeBookDTO entity;

        public ConflictException(String message) {
            this(message, null);
        }

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
