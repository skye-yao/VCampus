package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次导入预览（响应键 {@code preview}，预览、修订两个动作共用）。
 *
 * <p>预览不写库：这里只是一份可继续编辑的候选，教师确认之后才会写草稿。{@code importToken} 是这次
 * 预览的令牌，与上传票据是两张不同的票（上传票据一次性，令牌 10 分钟、可多次修订）；{@code
 * previewRevision} 每次修订递增，确认请求必须带上最新的值，过期修订不能覆盖新状态。
 *
 * <p>{@code candidate} 与 {@code issues} 刻意分开：候选里只有服务端认得、可以原样保存的合法内容
 * （缺列/空白的单元格保留 baseDraft 的值，非法单元格不进入候选），问题列表里是教师文件里的原文与
 * 说明。界面必须按问题显示错误文本，而不是把候选里的旧值显示成「导入成功」。
 *
 * <p>计数口径：{@code totalRows} 是文件里的数据行数；{@code validRows} 是已经合并进候选的行；
 * {@code errorRows} 是没有合并进候选的行（仍有未解决问题或被教师排除），三者满足
 * {@code validRows + errorRows == totalRows}。「缺少某项成绩」是合法的不完整草稿，不算错误行。
 * 列表在构造时防御性复制并对外只读。
 */
public final class GradeImportPreviewDTO {
    private final String importToken;
    private final int previewRevision;
    private final GradeBookContentDTO candidate;
    private final int totalRows;
    private final int validRows;
    private final int errorRows;
    private final List<GradeImportRowIssueDTO> issues;
    private final String expiresAt;

    public GradeImportPreviewDTO(String importToken, int previewRevision,
            GradeBookContentDTO candidate, int totalRows, int validRows, int errorRows,
            List<GradeImportRowIssueDTO> issues, String expiresAt) {
        this.importToken = importToken;
        this.previewRevision = previewRevision;
        this.candidate = candidate;
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.errorRows = errorRows;
        this.issues = immutableCopy(issues);
        this.expiresAt = expiresAt;
    }

    public String getImportToken() {
        return importToken;
    }

    /** 从 1 开始，每次修订递增；确认请求带的是这个值。 */
    public int getPreviewRevision() {
        return previewRevision;
    }

    /** 合并后的完整编辑内容（含未变动的学生行），确认时原样作为草稿写入。 */
    public GradeBookContentDTO getCandidate() {
        return candidate;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public int getErrorRows() {
        return errorRows;
    }

    /** 行/单元格问题；已排除的行也在这里，只是 {@code excluded} 为 true。 */
    public List<GradeImportRowIssueDTO> getIssues() {
        return issues;
    }

    /** ISO-8601 瞬时字符串：令牌 10 分钟有效，过期后必须重新上传导入。 */
    public String getExpiresAt() {
        return expiresAt;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
