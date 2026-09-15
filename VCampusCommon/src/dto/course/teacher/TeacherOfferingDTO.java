package dto.course.teacher;

/**
 * 教师教学班列表与详情共用的教学班摘要。
 *
 * <p>所有 BIGINT 标识在网络上都以十进制字符串传输，避免 JavaScript 安全整数以外的精度丢失。
 * {@code offeringName} 是“课程名 + 教学班代码”组成的展示名，不是数据库里的独立字段。
 * {@code canEditGrades}、{@code canRequestAdjustment} 只用于界面显隐，服务端每次写操作仍独立校验。
 */
public final class TeacherOfferingDTO {
    private final String offeringId;
    private final String offeringCode;
    private final String offeringName;
    private final String courseId;
    private final String courseCode;
    private final String courseName;
    private final double credit;
    private final int academicYear;
    private final int semester;
    private final int enrolledCount;
    private final int capacity;
    private final String status;
    private final boolean canEditGrades;
    private final boolean canRequestAdjustment;

    public TeacherOfferingDTO(String offeringId, String offeringCode, String offeringName,
            String courseId, String courseCode, String courseName, double credit,
            int academicYear, int semester, int enrolledCount, int capacity, String status,
            boolean canEditGrades, boolean canRequestAdjustment) {
        this.offeringId = offeringId;
        this.offeringCode = offeringCode;
        this.offeringName = offeringName;
        this.courseId = courseId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.credit = credit;
        this.academicYear = academicYear;
        this.semester = semester;
        this.enrolledCount = enrolledCount;
        this.capacity = capacity;
        this.status = status;
        this.canEditGrades = canEditGrades;
        this.canRequestAdjustment = canRequestAdjustment;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getOfferingCode() {
        return offeringCode;
    }

    public String getOfferingName() {
        return offeringName;
    }

    public String getCourseId() {
        return courseId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public double getCredit() {
        return credit;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public int getSemester() {
        return semester;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getStatus() {
        return status;
    }

    public boolean isCanEditGrades() {
        return canEditGrades;
    }

    public boolean isCanRequestAdjustment() {
        return canRequestAdjustment;
    }
}
