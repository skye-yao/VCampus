package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 教师提交调课申请（courseTeacher 模块写请求体，位于 {@code data.request}）。
 *
 * <p>标识沿用数据库 BIGINT 的十进制字符串；{@code operationId} 是幂等 UUID。
 * {@code targets} 是目标课次列表：服务端保持列表形态以兼容管理员的多周申请，教师首版每次只提交一个。
 * 构造时防御性复制并对外只读，保证提交过程中的目标集合不可被调用方改写。
 */
public final class TeacherAdjustmentWriteDTO {
    private final String operationId;
    private final String offeringId;
    private final List<TeacherAdjustmentTargetInputDTO> targets;
    private final int newStartPeriod;
    private final int newEndPeriod;
    private final String newClassroomId;
    private final String reason;

    public TeacherAdjustmentWriteDTO(String operationId, String offeringId,
            List<TeacherAdjustmentTargetInputDTO> targets, int newStartPeriod, int newEndPeriod,
            String newClassroomId, String reason) {
        this.operationId = operationId;
        this.offeringId = offeringId;
        this.targets = immutableCopy(targets);
        this.newStartPeriod = newStartPeriod;
        this.newEndPeriod = newEndPeriod;
        this.newClassroomId = newClassroomId;
        this.reason = reason;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 构造时复制、反序列化后也返回不可修改视图，避免提交过程中目标集合被改写。 */
    public List<TeacherAdjustmentTargetInputDTO> getTargets() {
        return unmodifiable(targets);
    }

    /** 获取 NewStartPeriod。 */
    public int getNewStartPeriod() {
        return newStartPeriod;
    }

    /** 获取 NewEndPeriod。 */
    public int getNewEndPeriod() {
        return newEndPeriod;
    }

    /** 获取 NewClassroomId。 */
    public String getNewClassroomId() {
        return newClassroomId;
    }

    /** 获取 Reason。 */
    public String getReason() {
        return reason;
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
