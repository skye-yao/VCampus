package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个教学班的成绩表（响应键 {@code gradeBook}）。
 *
 * <p>尚无工作副本时服务端返回 {@code revision=0} 的虚拟草稿且不写库：此时 revision 只表示“还没有草稿”，
 * 不会与真实工作副本（从 1 起）混淆。{@code state} 是界面状态：DRAFT（草稿可编辑）、
 * PENDING（已提交待审核）、APPROVED、REJECTED；有工作副本但未提交时显示 DRAFT。
 * {@code canEdit} 直接来自工作副本的 draft_open，PENDING 期间不可编辑。
 *
 * <p>{@code baseSubmissionId} 是更正草稿的基础批次，普通草稿为 null；
 * {@code correctionReason} 是更正原因，非更正草稿为 null。
 * {@code rosterChangedSinceSubmission} 表示提交之后名单发生了变化：
 * 已提交批次里的学生集合不会因此被篡改，新学生显示为“尚未纳入已提交批次”。
 * 行列表在构造时防御性复制并对外只读。
 *
 * <p>{@code reviewComment} 是最后一次批次的审核意见（管理员在审批/驳回时填写），没有批次或
 * 批次还没有意见时为 null。只读状态界面据此显示“为什么不能改”，它与 {@code correctionReason}
 * （教师自己写的更正原因）是两件事，不能互相冒充。
 */
public final class TeacherGradeBookDTO {
    private final String offeringId;
    private final int revision;
    private final String rosterDigest;
    private final String state;
    private final GradeSchemeDTO scheme;
    private final List<TeacherGradeRowDTO> rows;
    private final String lastSubmissionId;
    private final String baseSubmissionId;
    private final boolean canEdit;
    private final String correctionReason;
    private final boolean rosterChangedSinceSubmission;
    private final String reviewComment;

    public TeacherGradeBookDTO(String offeringId, int revision, String rosterDigest, String state,
            GradeSchemeDTO scheme, List<TeacherGradeRowDTO> rows, String lastSubmissionId,
            String baseSubmissionId, boolean canEdit, String correctionReason,
            boolean rosterChangedSinceSubmission) {
        this(offeringId, revision, rosterDigest, state, scheme, rows, lastSubmissionId,
                baseSubmissionId, canEdit, correctionReason, rosterChangedSinceSubmission, null);
    }

    public TeacherGradeBookDTO(String offeringId, int revision, String rosterDigest, String state,
            GradeSchemeDTO scheme, List<TeacherGradeRowDTO> rows, String lastSubmissionId,
            String baseSubmissionId, boolean canEdit, String correctionReason,
            boolean rosterChangedSinceSubmission, String reviewComment) {
        this.offeringId = offeringId;
        this.revision = revision;
        this.rosterDigest = rosterDigest;
        this.state = state;
        this.scheme = scheme;
        this.rows = immutableCopy(rows);
        this.lastSubmissionId = lastSubmissionId;
        this.baseSubmissionId = baseSubmissionId;
        this.canEdit = canEdit;
        this.correctionReason = correctionReason;
        this.rosterChangedSinceSubmission = rosterChangedSinceSubmission;
        this.reviewComment = reviewComment;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public int getRevision() {
        return revision;
    }

    public String getRosterDigest() {
        return rosterDigest;
    }

    public String getState() {
        return state;
    }

    public GradeSchemeDTO getScheme() {
        return scheme;
    }

    /** 构造时复制、反序列化后也返回不可修改视图，避免界面侧改写成绩行。 */
    public List<TeacherGradeRowDTO> getRows() {
        return unmodifiable(rows);
    }

    public String getLastSubmissionId() {
        return lastSubmissionId;
    }

    public String getBaseSubmissionId() {
        return baseSubmissionId;
    }

    public boolean isCanEdit() {
        return canEdit;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public boolean isRosterChangedSinceSubmission() {
        return rosterChangedSinceSubmission;
    }

    /** 最后一次批次的审核意见；没有批次或批次没有意见时为 null。 */
    public String getReviewComment() {
        return reviewComment;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
