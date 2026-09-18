package dto.course.teacher;

/**
 * 导入预览请求（{@code data.request}，动作 {@code previewGradeImport}）。
 *
 * <p>{@code uploadTicket} 是上传成功之后那一张短连接票据：文件字节已经落在服务端临时目录，
 * 票据在这里被兑换并消费，一个上传只能换来一次预览。
 *
 * <p>{@code baseDraft} 是教师**当前编辑副本**的完整内容，不是把它先写库：文件里缺列或空白的
 * 单元格一律保留这份副本里的值，绝不转成 0；服务端还会用它复核草稿版本与名单摘要，副本与服务器
 * 看到的状态不一致时直接冲突，不做静默 rebase。
 */
public final class PreviewGradeImportRequestDTO {
    private final String uploadTicket;
    private final GradeBookContentDTO baseDraft;

    public PreviewGradeImportRequestDTO(String uploadTicket, GradeBookContentDTO baseDraft) {
        this.uploadTicket = uploadTicket;
        this.baseDraft = baseDraft;
    }

    public String getUploadTicket() {
        return uploadTicket;
    }

    public GradeBookContentDTO getBaseDraft() {
        return baseDraft;
    }
}
