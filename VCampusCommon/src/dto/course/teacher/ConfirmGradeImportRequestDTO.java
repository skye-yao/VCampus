package dto.course.teacher;

/**
 * 确认导入（{@code data.request}，动作 {@code confirmGradeImport}）。
 *
 * <p>确认请求刻意**不带**任何成绩内容：候选就在服务端的预览状态里，客户端无法在确认这一步换一份
 * 内容进去。{@code expectedPreviewRevision} 锁定它确认的是哪一版预览，{@code expectedRevision}
 * 锁定它基于哪一版草稿；两者任一变化都要重新走一遍预览。
 *
 * <p>{@code operationId} 是幂等 UUID：令牌在提交成功之后才被消费，响应丢失时用同一个 operationId
 * 重试会先重放已提交的结果，而不是因为令牌已消费而报失败。
 */
public final class ConfirmGradeImportRequestDTO {
    private final String operationId;
    private final String importToken;
    private final int expectedPreviewRevision;
    private final long expectedRevision;

    public ConfirmGradeImportRequestDTO(String operationId, String importToken,
            int expectedPreviewRevision, long expectedRevision) {
        this.operationId = operationId;
        this.importToken = importToken;
        this.expectedPreviewRevision = expectedPreviewRevision;
        this.expectedRevision = expectedRevision;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 ImportToken。 */
    public String getImportToken() {
        return importToken;
    }

    /** 获取 ExpectedPreviewRevision。 */
    public int getExpectedPreviewRevision() {
        return expectedPreviewRevision;
    }

    /** 预览所基于的草稿版本（baseDraft.expectedRevision）。 */
    public long getExpectedRevision() {
        return expectedRevision;
    }
}
