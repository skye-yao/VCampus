package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleConflictDTO;

/**
 * 教师调课预检查结果（响应键 {@code conflicts}）。
 *
 * <p>冲突是服务端按有效课表算出的类型化结果；教师没有 force 权限，只要存在冲突
 * {@code canSubmit} 就是 false。客户端预检查只是体验优化，提交时服务端会重新检查。
 */
public final class TeacherAdjustmentPreviewDTO {
    private final List<ScheduleConflictDTO> conflicts;
    private final boolean canSubmit;

    public TeacherAdjustmentPreviewDTO(List<ScheduleConflictDTO> conflicts, boolean canSubmit) {
        this.conflicts = conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
        this.canSubmit = canSubmit;
    }

    /** null 视为空列表；构造与反序列化两条路径都返回不可修改视图。 */
    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }

    /** 判断 CanSubmit 是否成立。 */
    public boolean isCanSubmit() {
        return canSubmit;
    }
}
