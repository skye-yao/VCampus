package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 修订导入预览的请求（{@code data.request}，动作 {@code reviseGradeImport}）：修改异常行或明确排除它们。
 *
 * <p>{@code expectedPreviewRevision} 是客户端看到的预览版本；与服务端当前版本不符时整个修订被拒绝，
 * 避免一次迟到的修订覆盖掉更新的状态。
 *
 * <p>{@code corrections} 与 {@code excludedRows} 是**本次修订后的完整状态**而不是增量：教师取消一处
 * 修正或取消排除同样要表达出来，只有整份替换才能如实反映。每次都从原始解析结果重新计算，
 * 所以修订不会把上一轮的中间状态叠加上去。两者都做防御性不可变复制。
 */
public final class ReviseGradeImportRequestDTO {
    private final String importToken;
    private final int expectedPreviewRevision;
    private final List<GradeImportCorrectionDTO> corrections;
    private final List<Integer> excludedRows;

    public ReviseGradeImportRequestDTO(String importToken, int expectedPreviewRevision,
            List<GradeImportCorrectionDTO> corrections, List<Integer> excludedRows) {
        this.importToken = importToken;
        this.expectedPreviewRevision = expectedPreviewRevision;
        this.corrections = immutableCopy(corrections);
        this.excludedRows = excludedRows == null
                ? List.of() : List.copyOf(excludedRows);
    }

    public String getImportToken() {
        return importToken;
    }

    public int getExpectedPreviewRevision() {
        return expectedPreviewRevision;
    }

    /** 本次修订后仍然生效的全部单元格修正。 */
    public List<GradeImportCorrectionDTO> getCorrections() {
        return corrections;
    }

    /** 本次修订后仍然被排除的行号（Excel 数据行号）。 */
    public List<Integer> getExcludedRows() {
        return excludedRows;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
