package dto.course.teacher;

import java.util.List;

/**
 * 「我的申请」的统一列表行：把两类互不相同的事实表（调课申请与成绩提交批次）投影成同一种形状。
 *
 * <p>这是任务六设计 §10/§11 的载体，三个不能改的约定：
 *
 * <ul>
 *   <li>{@code status} 是 {@link String} 而不是枚举：两张事实表的状态字母表不一样——
 *       {@code course_schedule_adjustment_request.status} 是四态（含教师撤销 WITHDRAWN），
 *       而 {@code grade_submission.status} 只有三态、没有撤销状态。用一个枚举去装两种字母表，
 *       结果只能是把「已撤销」解析成「已驳回」这一类的静默降级。</li>
 *   <li>{@code stateKey = status + ':' + (handledAt != null ? handledAt : submittedAt)}。
 *       它是「结果是否已读」的唯一判据：读过 PENDING 不代表读过随后到达的 APPROVED，
 *       因为 APPROVED 的 stateKey 不同。</li>
 *   <li>{@code unread} 由服务端按已读回执算出（没有回执即未读），客户端不许自己猜。</li>
 * </ul>
 *
 * <p>{@code handledAt} 是「结果产生的时间」：调课申请取 {@code reviewed_at ?? withdrawn_at}
 * （撤销也是本人的终态，它有 withdrawn_at 而永远没有 reviewed_at），成绩提交取 {@code reviewed_at}；
 * 没被处理时为 null，{@code stateKey} 这时回落到 {@code submittedAt}。
 *
 * <p>{@code canWithdraw} 只对 PENDING 的调课申请为 true：成绩提交没有撤销状态，也不存在
 * {@code withdrawn_at} 列，界面不能给它一个没有定义的后端入口。
 */
public final class TeacherApplicationDTO {
    /** 调课申请：对应 {@code course_schedule_adjustment_request}。 */
    public static final String SCHEDULE_ADJUSTMENT = "SCHEDULE_ADJUSTMENT";
    /** 成绩提交/更正批次：对应 {@code grade_submission}。 */
    public static final String GRADE_SUBMISSION = "GRADE_SUBMISSION";

    /** 调课申请的四态，含教师撤销。 */
    private static final List<String> ADJUSTMENT_STATUSES =
            List.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN");
    /** 成绩提交的三态：没有 WITHDRAWN，也不要有。 */
    private static final List<String> SUBMISSION_STATUSES =
            List.of("PENDING", "APPROVED", "REJECTED");

    private final String type;
    private final String id;
    private final String offeringId;
    private final String title;
    private final String status;
    private final String submittedAt;
    private final String handledAt;
    private final String reviewComment;
    private final boolean canWithdraw;
    private final String stateKey;
    private final boolean unread;

    public TeacherApplicationDTO(String type, String id, String offeringId, String title,
            String status, String submittedAt, String handledAt, String reviewComment,
            boolean canWithdraw, String stateKey, boolean unread) {
        this.type = type;
        this.id = id;
        this.offeringId = offeringId;
        this.title = title;
        this.status = status;
        this.submittedAt = submittedAt;
        this.handledAt = handledAt;
        this.reviewComment = reviewComment;
        this.canWithdraw = canWithdraw;
        this.stateKey = stateKey;
        this.unread = unread;
    }

    /** 类型是否合法（{@code SCHEDULE_ADJUSTMENT} 或 {@code GRADE_SUBMISSION}）。 */
    public static boolean isType(String type) {
        return SCHEDULE_ADJUSTMENT.equals(type) || GRADE_SUBMISSION.equals(type);
    }

    /**
     * 状态是否属于该类型的状态白名单。{@code type} 为空（列表不限类型）时只要属于任一张表的
     * 字母表即可——服务端仍然会把同一个状态原样带到两条分支上，另一张表由自己的 CHECK 约束
     * 保证不会有这个值。
     */
    public static boolean isStatus(String type, String status) {
        if (status == null || status.isBlank()) return true;
        if (SCHEDULE_ADJUSTMENT.equals(type)) return ADJUSTMENT_STATUSES.contains(status);
        if (GRADE_SUBMISSION.equals(type)) return SUBMISSION_STATUSES.contains(status);
        return ADJUSTMENT_STATUSES.contains(status) || SUBMISSION_STATUSES.contains(status);
    }

    /** 该类型可筛选的状态白名单；{@code type} 为空时返回两者的并集。 */
    public static List<String> statusesFor(String type) {
        if (SCHEDULE_ADJUSTMENT.equals(type)) return ADJUSTMENT_STATUSES;
        if (GRADE_SUBMISSION.equals(type)) return SUBMISSION_STATUSES;
        return ADJUSTMENT_STATUSES;
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public String getOfferingId() {
        return offeringId;
    }

    /** 行标题：课程名与教学班代码；两类事实表的标题口径一致。 */
    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    /** 结果产生时间；未被处理时为 null（{@code stateKey} 这时回落到 {@code submittedAt}）。 */
    public String getHandledAt() {
        return handledAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public boolean isCanWithdraw() {
        return canWithdraw;
    }

    /** 当前结果的状态键；客户端在标记已读时原样带回，服务端据此做 compare-and-set。 */
    public String getStateKey() {
        return stateKey;
    }

    public boolean isUnread() {
        return unread;
    }
}
