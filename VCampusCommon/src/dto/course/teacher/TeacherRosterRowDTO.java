package dto.course.teacher;

/**
 * 教学班学生名单中的一行。
 *
 * <p>{@code enrollmentStatus} 为 ENROLLED（已选中）或 DROPPED（已退课）。退课行只读保留为历史，
 * 不参与成绩编辑。{@code selectedAt}、{@code droppedAt} 是 UTC ISO-8601 字符串；
 * 未退课的 {@code droppedAt} 保持 null，不能用空字符串或当前时间代替。
 */
public final class TeacherRosterRowDTO {
    private final String enrollmentId;
    private final String studentUid;
    private final String studentName;
    private final String major;
    private final String enrollmentStatus;
    private final String selectedAt;
    private final String droppedAt;

    public TeacherRosterRowDTO(String enrollmentId, String studentUid, String studentName,
            String major, String enrollmentStatus, String selectedAt, String droppedAt) {
        this.enrollmentId = enrollmentId;
        this.studentUid = studentUid;
        this.studentName = studentName;
        this.major = major;
        this.enrollmentStatus = enrollmentStatus;
        this.selectedAt = selectedAt;
        this.droppedAt = droppedAt;
    }

    /** 获取 EnrollmentId。 */
    public String getEnrollmentId() {
        return enrollmentId;
    }

    /** 获取 StudentUid。 */
    public String getStudentUid() {
        return studentUid;
    }

    /** 获取 StudentName。 */
    public String getStudentName() {
        return studentName;
    }

    /** 获取 Major。 */
    public String getMajor() {
        return major;
    }

    /** 获取 EnrollmentStatus。 */
    public String getEnrollmentStatus() {
        return enrollmentStatus;
    }

    /** 获取 SelectedAt。 */
    public String getSelectedAt() {
        return selectedAt;
    }

    /** 获取 DroppedAt。 */
    public String getDroppedAt() {
        return droppedAt;
    }
}
