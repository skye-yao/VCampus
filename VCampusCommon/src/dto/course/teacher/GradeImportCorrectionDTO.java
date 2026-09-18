package dto.course.teacher;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教师对文件里某一行的一处修正（{@link ReviseGradeImportRequestDTO} 的元素）。
 *
 * <p>键是 {@link GradeImportRowIssueDTO} 里的字段名（{@code studentUid}/{@code studentName}/四项成绩），
 * 值是教师改后的单元格文本；服务端把修正后的文本当成「这一行在文件里本来写的就是这个」重新校验，
 * 因此修正学号会重新做名单匹配，修正成绩单元格会重新做数值与范围判定。
 *
 * <p>值允许是空串：空串表示「这一格没有值」，与文件里空白单元格同义（保留原草稿值），不是 0 分。
 * 校验规则与文件里的原文完全相同——修正不是绕过校验的后门。
 */
public final class GradeImportCorrectionDTO {
    private final int rowNumber;
    private final Map<String, String> correctedCells;

    public GradeImportCorrectionDTO(int rowNumber, Map<String, String> correctedCells) {
        this.rowNumber = rowNumber;
        this.correctedCells = immutableCopy(correctedCells);
    }

    public int getRowNumber() {
        return rowNumber;
    }

    /** 本次修正的单元格（字段名 → 新文本），构造时复制并对外只读。 */
    public Map<String, String> getCorrectedCells() {
        return Collections.unmodifiableMap(correctedCells);
    }

    private static Map<String, String> immutableCopy(Map<String, String> values) {
        if (values == null) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return copy;
    }
}
