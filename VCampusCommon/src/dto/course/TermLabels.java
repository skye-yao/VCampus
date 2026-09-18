package dto.course;

/**
 * 学期标签的唯一来源。管理员目录、选课、课表、教学班编辑器都必须走这里，
 * 否则同一个 (academicYear, semester) 会在不同页面上写出不同的中文。
 */
public final class TermLabels {

    private TermLabels() {
    }

    /** 形如 {@code 秋学期}。未知值退化为 {@code 第N学期}，不抛异常。 */
    public static String label(int semester) {
        return switch (semester) {
            case 1 -> "暑期学校";
            case 2 -> "秋学期";
            case 3 -> "春学期";
            default -> "第" + semester + "学期";
        };
    }

    /** 形如 {@code 2026-2027 秋学期}。学年区间是 {@code academicYear..academicYear+1}。 */
    public static String displayName(int academicYear, int semester) {
        return academicYear + "-" + (academicYear + 1) + " " + label(semester);
    }
}
