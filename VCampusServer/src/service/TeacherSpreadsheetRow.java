package service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上传工作簿里的一个数据行：行号、学号、姓名、四项成绩的原始文本，以及该行的单元格结构错误。
 *
 * <p>这里是**原文**，不是解析结果：成绩列保存的是 {@code DataFormatter} 格式化后的文本，绝不在这里
 * 转成数字，也绝不把空白变成 0（缺项与零分是两件事）。把「读到的文本」与「业务判定」分开，是为了让
 * 上层预览能原样回显教师文件里的内容，而不是用服务端猜出来的值冒充导入成功。
 *
 * <p>字段的空值语义（上层必须区分）：
 * <ul>
 *   <li>{@code studentUid}、{@code studentName} 永远非空：没有该列时为空字符串，空单元格同样是空字符串。
 *       姓名是可选的，空字符串表示「文件没有提供姓名」。</li>
 *   <li>四项成绩为 {@code null} 表示**该列在文件里不存在**；空字符串表示列存在但单元格空白。
 *       两者都表示「没有值」，上层一律保留已有草稿值，不写成 0。</li>
 * </ul>
 *
 * <p>{@code cellErrors} 是该行的结构错误（学号为空、学号重复、学号不在名单里、姓名与名单不一致等），
 * 没有错误时是空列表。错误按 {@code field}/{@code rawValue}/{@code message} 三段表达，与
 * {@code GradeImportRowIssueDTO} 一一对应，预览据此定位到具体单元格而不是只给整行一句提示。
 */
public record TeacherSpreadsheetRow(int rowNumber, String studentUid, String studentName,
        String rawDailyScore, String rawMidtermScore, String rawExperimentScore,
        String rawFinaltermScore, List<CellError> cellErrors) {

    /**
     * 字段名与行字段同名：解析阶段只会给出这两个身份字段的错误（学号为空/重复、学号不在名单、
     * 姓名与名单不一致）；成绩字段的错误由上层按 dailyScore/midtermScore/experimentScore/
     * finaltermScore 的同一套命名补齐。
     */
    public static final String FIELD_STUDENT_UID = "studentUid";
    public static final String FIELD_STUDENT_NAME = "studentName";

    public TeacherSpreadsheetRow {
        studentUid = studentUid == null ? "" : studentUid;
        studentName = studentName == null ? "" : studentName;
        cellErrors = cellErrors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cellErrors));
    }

    /** 该行是否有结构错误；没有任何错误的行为有效行。 */
    public boolean hasCellErrors() {
        return !cellErrors.isEmpty();
    }

    /** 追加错误后的新行（记录本身不可变）：预览把名单校验与教师修正的结果叠加上来。 */
    public TeacherSpreadsheetRow withCellErrors(List<CellError> errors) {
        return new TeacherSpreadsheetRow(rowNumber, studentUid, studentName, rawDailyScore,
                rawMidtermScore, rawExperimentScore, rawFinaltermScore, errors);
    }

    /**
     * 一个单元格的结构错误：字段名、该单元格的原文与可直接显示的说明。
     *
     * @param field 字段名（见 {@link #FIELD_STUDENT_UID} 等常量）
     * @param rawValue 单元格原文，便于教师对照文件定位；可能是空字符串
     * @param message 面向教师的说明，不含英文堆栈或类名
     */
    public record CellError(String field, String rawValue, String message) {
    }
}
