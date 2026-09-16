package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个更正/重提批次与它的基础批次之间的比较，由服务端从两个批次自己的
 * {@code grade_submission_item} 行算出——绝不从当前可变草稿取“历史值”。
 *
 * <p>管理员审批时据此看到：原批准批次的版本号、本次提交版本（在 summary 里）、这次更正的原因，
 * 以及本次真的改变了的学生与他们的旧/新值。普通批次（{@code base_submission_id IS NULL}）没有
 * 比较对象，字段整块为 {@code null}；基础批次已被清理而读不出来时同样为 {@code null}——不伪造比较。
 *
 * <p>{@code changes} 为空表示这次更正没有改变任何学生的分数（例如只改了权重方案）。
 */
public final class GradeCorrectionComparisonDTO {
    private final int baseVersion;
    private final String reason;
    private final List<GradeCorrectionChangeDTO> changes;

    public GradeCorrectionComparisonDTO(int baseVersion, String reason,
            List<GradeCorrectionChangeDTO> changes) {
        this.baseVersion = baseVersion;
        this.reason = reason;
        this.changes = changes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(changes));
    }

    /** 基础批次（原批准批次）的版本号；本次提交版本在 {@code summary.getVersion()}。 */
    public int getBaseVersion() {
        return baseVersion;
    }

    /** 本次更正的更正原因；重提批次与历史批次为 {@code null}。 */
    public String getReason() {
        return reason;
    }

    /** 本次真的改变了的学生（分数、总评或绩点任一项不同），按学号排序。 */
    public List<GradeCorrectionChangeDTO> getChanges() {
        return changes == null ? Collections.emptyList() : Collections.unmodifiableList(changes);
    }
}
