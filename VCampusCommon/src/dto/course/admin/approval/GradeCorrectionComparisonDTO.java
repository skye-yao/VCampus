package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个更正/重提批次与它的基础批次之间的比较，由服务端从两个批次自己的
 * {@code grade_submission_item} 行算出——绝不从当前可变草稿取“历史值”。
 *
 * <p>管理员审批时据此看到：基础批次的版本号与<b>它自己的审批状态</b>、本次提交版本（在 summary 里）、
 * 这次更正的原因，以及本次真的改变了的学生与他们的旧/新值。基础状态是给界面的措辞用的：
 * 一次驳回重提的基础批次是被<b>驳回</b>的那一批，把它写成「原批准版本」就是一句假话。
 *
 * <p>普通批次（{@code base_submission_id IS NULL}）没有比较对象，字段整块为 {@code null}；基础批次
 * 已被清理而读不出来时同样为 {@code null}——不伪造比较。
 *
 * <p>{@code changes} 为空表示本次提交没有改变任何学生的分数（例如只改了权重方案）。
 */
public final class GradeCorrectionComparisonDTO {
    private final int baseVersion;
    private final ApprovalStatusDTO baseStatus;
    private final String reason;
    private final List<GradeCorrectionChangeDTO> changes;

    /**
     * 旧构造方法保留：没有基础状态时界面按中性措辞渲染，新字段不破坏既有调用方。
     */
    public GradeCorrectionComparisonDTO(int baseVersion, String reason,
            List<GradeCorrectionChangeDTO> changes) {
        this(baseVersion, null, reason, changes);
    }

    /**
     * @param baseStatus 基础批次自己的审批状态（该批次可能是被驳回的那一批，也可能因数据清理
     *                   而读不出来）；{@code null} 表示调用方没有提供，界面用中性措辞
     */
    public GradeCorrectionComparisonDTO(int baseVersion, ApprovalStatusDTO baseStatus,
            String reason, List<GradeCorrectionChangeDTO> changes) {
        this.baseVersion = baseVersion;
        this.baseStatus = baseStatus;
        this.reason = reason;
        this.changes = changes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(changes));
    }

    /** 基础批次的版本号；本次提交版本在 {@code summary.getVersion()}。 */
    public int getBaseVersion() {
        return baseVersion;
    }

    /**
     * 基础批次自己的审批状态（APPROVED / REJECTED / PENDING）；没有时为 {@code null}。
     * 界面据此选择措辞——只有 APPROVED 的基础批次才配叫「原批准版本」。
     */
    public ApprovalStatusDTO getBaseStatus() {
        return baseStatus;
    }

    /** 本次更正的更正原因；重提批次与历史批次为 {@code null}。 */
    public String getReason() {
        return reason;
    }

    /**
     * 本次真的改变了的学生（分数、总评或绩点任一项不同）。顺序是<b>本次批次的学号顺序，其后是
     * 基础批次独有的学生</b>（各自都按学号排），不是全局按学号排序。
     */
    public List<GradeCorrectionChangeDTO> getChanges() {
        return changes == null ? Collections.emptyList() : Collections.unmodifiableList(changes);
    }
}
