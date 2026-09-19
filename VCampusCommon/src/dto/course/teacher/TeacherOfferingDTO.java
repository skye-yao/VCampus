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

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 OfferingCode。 */
    public String getOfferingCode() {
        return offeringCode;
    }

    /** 获取 OfferingName。 */
    public String getOfferingName() {
        return offeringName;
    }

    /** 获取 CourseId。 */
    public String getCourseId() {
        return courseId;
    }

    /** 获取 CourseCode。 */
    public String getCourseCode() {
        return courseCode;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 Credit。 */
    public double getCredit() {
        return credit;
    }

    /** 获取 AcademicYear。 */
    public int getAcademicYear() {
        return academicYear;
    }

    /** 获取 Semester。 */
    public int getSemester() {
        return semester;
    }

    /** 获取 EnrolledCount。 */
    public int getEnrolledCount() {
        return enrolledCount;
    }

    /** 获取 Capacity。 */
    public int getCapacity() {
        return capacity;
    }

    /** 获取 Status。 */
    public String getStatus() {
        return status;
    }

    /** 判断 CanEditGrades 是否成立。 */
    public boolean isCanEditGrades() {
        return canEditGrades;
    }

    /** 判断 CanRequestAdjustment 是否成立。 */
    public boolean isCanRequestAdjustment() {
        return canRequestAdjustment;
    }
}
