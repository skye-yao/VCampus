package service;

import dao.ScheduleAdjustmentDAO;
import dao.TeacherApplicationDAO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
* 「我的申请与结果通知」（设计 §10/§11）：把调课申请与成绩提交批次合成一条本人可见的时间线，
* 并维护结果未读的已读回执。
*
* <p>三件事各有一个「不许偷懒」的约束：
*
* <ul>
*   <li><b>合并分页在 SQL 里做。</b>两张表的状态字母表不同（调课四态含撤销、成绩只有三态），
*       所以类型与状态都按**白名单**解析，再由 {@link TeacherApplicationDAO} 用 {@code UNION ALL}
*       排一次序、取一页——不在 Java 里先各取一页再合并，那样 totalCount 与页码都不成立。</li>
*   <li><b>已读是 compare-and-set。</b>{@code stateKey = status + ':' + (handledAt ?: submittedAt)}，
*       {@code unread = 已存回执 != 当前键}。标记已读先验证本人，再比对客户端带来的
*       {@code expectedStateKey}：不一致就**什么都不写**并返回冲突（冲突里带上当前 DTO，页面据此
*       刷新后重判）；一致才 upsert 回执。没有 {@code operationId}——回执按「教师+类型+申请」主键
*       upsert，本身幂等，需要防的是「拿旧结果的确认去标新结果」，那是 CAS 的事。</li>
*   <li><b>详情恰好一个变体。</b>{@link TeacherApplicationDetailDTO} 只有两个类型化字段，按
*       {@code summary.type} 取其一；成绩详情复用既有的不可变批次快照（只读，没有任何编辑入口）。</li>
* </ul>
*
* <p>归属只按 {@code requested_by} / {@code submitted_by} 判定，**不**要求现在还任课该教学班：
* 教学班成员关系解除之后，历史申请仍然是这位教师自己的事实。
*/
public class TeacherApplicationService {
    private static final String PENDING = "PENDING";
    private static final int MAX_PAGE_SIZE = 100;

    private final TeacherApplicationDAO dao;
    private final TeacherAdjustmentApplicationService adjustments;
    private final GradeApprovalService grades;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of TeacherApplicationService.
    */
    public TeacherApplicationService() {
        this(new TeacherApplicationDAO(), new TeacherAdjustmentApplicationService(),
                new GradeApprovalService(), Clock.systemUTC());
    }

    /** 供测试注入固定 {@link Clock} 与可覆写的兄弟服务。 */
    public TeacherApplicationService(TeacherApplicationDAO dao,
            TeacherAdjustmentApplicationService adjustments, GradeApprovalService grades,
            Clock clock) {
        this.dao = dao;
        this.adjustments = adjustments;
        this.grades = grades;
        this.clock = clock;
    }

    // -------------------------------------------------------------------- 列表

    /** 本人两类申请的统一分页；{@code type}/{@code status} 为空表示不按该条件筛选。 */
    public TeacherPageDTO<TeacherApplicationDTO> listMyApplications(String uid, String type,
            String status, int page, int size) {
        String teacher = requireUid(uid);
        if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页条数必须为 1 至 100");
        }
        String wantedType = optionalType(type);
        String wantedStatus = optionalStatus(wantedType, status);
        try (Connection connection = DBUtil.getConnection()) {
            long total = dao.count(connection, teacher, wantedType, wantedStatus);
            List<TeacherApplicationDTO> items = new ArrayList<>();
            for (TeacherApplicationDAO.Row row : dao.list(connection, teacher, wantedType,
                    wantedStatus, (page - 1) * size, size)) {
                items.add(dto(row));
            }
            return new TeacherPageDTO<>(items, total, page, size);
        } catch (SQLException failure) {
            throw new DatabaseException("查询我的申请失败", failure);
        }
    }

    // -------------------------------------------------------------------- 详情

    /**
    * 一条本人申请的详情，恰好一个类型化变体非空。别人的申请与不存在的申请对外不可区分，
    * 一律 {@link NotFoundException}。
    *
    * <p>成绩详情交给 {@link GradeApprovalService#getGradeSubmission(String)} 映射：那份映射是审批侧
    * 已经验证过的批次快照（含名单、分布与更正比较），在这里重写一份只会多出一套漂移的口径；
    * 归属判定仍由本方法先做完，所以不存在「不校验本人就读到别人批次」的旁路。
    */
    public TeacherApplicationDetailDTO getMyApplication(String uid, String type, String id) {
        String teacher = requireUid(uid);
        String wantedType = requireType(type);
        long applicationId = parseId(id);
        try (Connection connection = DBUtil.getConnection()) {
            TeacherApplicationDAO.Row row = dao.find(connection, teacher, wantedType, applicationId);
            if (row == null) throw notFound();
            TeacherApplicationDTO summary = dto(row);
            if (TeacherApplicationDTO.SCHEDULE_ADJUSTMENT.equals(wantedType)) {
                AdjustmentRequestDetailDTO adjustment =
                        adjustments.get(teacher, Long.toString(applicationId));
                return new TeacherApplicationDetailDTO(summary, adjustment, null);
            }
            GradeSubmissionDetailDTO grade =
                    grades.getGradeSubmission(Long.toString(applicationId));
            return new TeacherApplicationDetailDTO(summary, null, grade);
        } catch (SQLException failure) {
            throw new DatabaseException("查询申请详情失败", failure);
        }
    }

    // ---------------------------------------------------------------- 标记已读

    /**
    * 标记一条本人的申请结果为已读，返回最新的一行。
    *
    * <p>顺序固定为：校验本人 → 比对 {@code expectedStateKey} 与当前状态键 → 只在一致时 upsert 回执。
    * 过期确认返回 {@link ConflictException} 并带上**当前** DTO（含它自己的 {@code unread}），让页面
    * 刷新后重新决定，绝不把更新过的结果悄悄标成已读，也不静默成功。
    */
    public TeacherApplicationDTO markApplicationRead(String uid,
            MarkTeacherApplicationReadDTO raw) {
        String teacher = requireUid(uid);
        if (raw == null) throw new IllegalArgumentException("请求体不能为空");
        String type = requireType(raw.getType());
        long applicationId = parseId(raw.getId());
        String expected = raw.getExpectedStateKey();
        if (expected == null || expected.isBlank()) {
            throw new IllegalArgumentException("expectedStateKey 不能为空");
        }
        String wanted = expected.trim();
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherApplicationDAO.Row row =
                        dao.find(connection, teacher, type, applicationId);
                if (row == null) throw notFound();
                String current = stateKey(row);
                if (!current.equals(wanted)) {
                    throw new ConflictException("申请结果已更新，请刷新后重试", dto(row));
                }
                dao.upsertSeen(connection, teacher, type, applicationId, current,
                        clock.instant());
                TeacherApplicationDTO updated = dto(row, current);
                connection.commit();
                committed = true;
                return updated;
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
            throw new DatabaseException("标记申请已读事务执行失败", failure);
        }
    }

    // -------------------------------------------------------------- 映射与校验

    /** 当前状态键：{@code status + ':' + (handledAt != null ? handledAt : submittedAt)}，ISO 时刻文本。 */
    private static String stateKey(TeacherApplicationDAO.Row row) {
        Timestamp handled = row.handledAt() != null ? row.handledAt() : row.submittedAt();
        String moment = ScheduleAdjustmentDAO.instantText(handled);
        return row.status() + ":" + (moment == null ? "" : moment);
    }

    /** 一行事实 → 列表行；未读由回执与当前键比对得出（没有回执即未读）。 */
    private static TeacherApplicationDTO dto(TeacherApplicationDAO.Row row) {
        return dto(row, row.seenStateKey());
    }

    private static TeacherApplicationDTO dto(TeacherApplicationDAO.Row row, String seenStateKey) {
        String key = stateKey(row);
        return new TeacherApplicationDTO(row.type(), Long.toString(row.id()),
                Long.toString(row.offeringId()),
                orEmpty(row.courseName()) + "　" + orEmpty(row.offeringCode()), row.status(),
                ScheduleAdjustmentDAO.instantText(row.submittedAt()),
                ScheduleAdjustmentDAO.instantText(row.handledAt()), row.reviewComment(),
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT.equals(row.type())
                        && PENDING.equals(row.status()),
                key, !key.equals(seenStateKey));
    }

    /** 列表允许不限类型（null/空白）；给了值就必须是白名单里的两种之一。 */
    private static String optionalType(String type) {
        if (type == null || type.isBlank()) return null;
        String wanted = type.trim();
        if (!TeacherApplicationDTO.isType(wanted)) {
            throw new IllegalArgumentException(
                    "type 必须为 SCHEDULE_ADJUSTMENT 或 GRADE_SUBMISSION");
        }
        return wanted;
    }

    /** 详情与已读必须指定类型：这两条路径都要落在一张具体的事实表上。 */
    private static String requireType(String type) {
        String wanted = optionalType(type);
        if (wanted == null) throw new IllegalArgumentException("type 不能为空");
        return wanted;
    }

    /**
    * 状态按类型白名单解析：给定类型时只接受该类型的状态；不限类型时接受两者的并集。
    * 两张表的状态字母表不同（成绩提交没有 WITHDRAWN），一个「全局合法」的枚举会在这里静默错配。
    */
    private static String optionalStatus(String type, String status) {
        if (status == null || status.isBlank()) return null;
        String wanted = status.trim();
        if (!TeacherApplicationDTO.isStatus(type, wanted)) {
            throw new IllegalArgumentException("status 对该类型无效: " + wanted);
        }
        return wanted;
    }

    private static long parseId(String id) {
        return AdminOperationTransaction.parseId(id, "id");
    }

    private static String requireUid(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        return uid.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("申请不存在或不属于本人");
    }

    // ------------------------------------------------------------ transaction

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight. */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
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
                throw new DatabaseException("标记申请已读事务执行失败", restoration);
            }
        }
    }

    /** 不属于本人（或不存在）的申请；对外与「不存在」不可区分，绝不返回 FORBIDDEN。 */
    public static class NotFoundException extends RuntimeException {
        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
    * 标记已读的过期确认：携带**当前**的申请行。用独立的异常类型而不是复用调课/成绩的冲突类型，
    * 是因为随附实体的响应键不同——调课冲突带 {@code latest}、成绩冲突带 {@code gradeBook}，
    * 这里带的是统一的 {@code application}。
    */
    public static class ConflictException extends RuntimeException {
        private final TeacherApplicationDTO entity;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, TeacherApplicationDTO entity) {
            super(message);
            this.entity = entity;
        }

        /**
        * Obtains Entity data.
        */
        public TeacherApplicationDTO getEntity() {
            return entity;
        }
    }
}
