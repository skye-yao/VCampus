package dto.course.teacher;

/**
 * 保存草稿或提交成绩的写请求（courseTeacher 模块，位于 {@code data.request}）。
 *
 * <p>{@code operationId} 是客户端生成的幂等 UUID：相同 ID 且规范化内容相同时服务端重放已提交结果。
 * {@code content} 是当前完整编辑内容，提交不要求先单独保存一次草稿，
 * 避免“保存”和“提交”两个请求之间的版本间隙。
 */
public final class WriteGradeBookRequestDTO {
    private final String operationId;
    private final GradeBookContentDTO content;

    public WriteGradeBookRequestDTO(String operationId, GradeBookContentDTO content) {
        this.operationId = operationId;
        this.content = content;
    }

    public String getOperationId() {
        return operationId;
    }

    public GradeBookContentDTO getContent() {
        return content;
    }
}
