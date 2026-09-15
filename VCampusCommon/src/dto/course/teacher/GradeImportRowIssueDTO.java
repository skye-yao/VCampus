package dto.course.teacher;

/**
 * 一次导入预览里的一条行/单元格问题（响应 {@code preview} 的 {@code issues} 元素）。
 *
 * <p>这是「候选合法 DTO 与非法 raw issues 分开」的落点：候选（{@link GradeBookContentDTO}）里只有
 * 服务端认得的合法分数，非法单元格的原文只出现在这里，界面据此显示错误而不是拿候选里的旧值冒充
 * 导入成功。{@code rawValue} 永远是教师文件里的原始文本（学号、姓名或成绩单元格的原文），
 * 服务端不会把它改写成数字。
 *
 * <p>{@code field} 取值为 {@link #FIELD_STUDENT_UID}、{@link #FIELD_STUDENT_NAME} 或四项成绩字段之一，
 * 与 {@link GradeImportCorrectionDTO#getCorrectedCells()} 的键完全同名：界面拿一个 field 既能显示错误，
 * 也能直接拼出修正请求，两侧不会各用一套命名。
 *
 * <p>{@code excluded} 表示教师已经明确排除这一行：问题仍然保留给界面显示，但不再阻止确认导入。
 */
public final class GradeImportRowIssueDTO {

    /** 学号字段：与文件行的学号列同名。 */
    public static final String FIELD_STUDENT_UID = "studentUid";
    /** 姓名字段：文件里姓名是可选的，为空不是错误。 */
    public static final String FIELD_STUDENT_NAME = "studentName";
    public static final String FIELD_DAILY_SCORE = "dailyScore";
    public static final String FIELD_MIDTERM_SCORE = "midtermScore";
    public static final String FIELD_EXPERIMENT_SCORE = "experimentScore";
    public static final String FIELD_FINALTERM_SCORE = "finaltermScore";

    private final int rowNumber;
    private final String studentUid;
    private final String studentName;
    private final String field;
    private final String rawValue;
    private final String message;
    private final boolean excluded;

    public GradeImportRowIssueDTO(int rowNumber, String studentUid, String studentName, String field,
            String rawValue, String message, boolean excluded) {
        this.rowNumber = rowNumber;
        this.studentUid = studentUid == null ? "" : studentUid;
        this.studentName = studentName == null ? "" : studentName;
        this.field = field;
        this.rawValue = rawValue == null ? "" : rawValue;
        this.message = message;
        this.excluded = excluded;
    }

    /** Excel 里的数据行号（第 1 行是表头），与文件行一一对应，就是教师看到的行号。 */
    public int getRowNumber() {
        return rowNumber;
    }

    public String getStudentUid() {
        return studentUid;
    }

    public String getStudentName() {
        return studentName;
    }

    public String getField() {
        return field;
    }

    /** 该单元格的原文，便于教师对照文件定位；可能是空字符串，但不会是 null。 */
    public String getRawValue() {
        return rawValue;
    }

    /** 面向教师的说明，不含英文堆栈或类名。 */
    public String getMessage() {
        return message;
    }

    /** 教师已明确排除这一行：问题仍显示，但不再阻止确认导入。 */
    public boolean isExcluded() {
        return excluded;
    }
}
