package dto.course;

/** 教务模块的 CourseTermDTO 数据传输对象。 */
public final class CourseTermDTO {
    private final int academicYear;
    private final int semester;
    private final String displayName;

    public CourseTermDTO(int academicYear, int semester, String displayName) {
        this.academicYear = academicYear;
        this.semester = semester;
        this.displayName = displayName;
    }

    /** 获取 AcademicYear。 */
    public int getAcademicYear() {
        return academicYear;
    }

    /** 获取 Semester。 */
    public int getSemester() {
        return semester;
    }

    /** 获取 DisplayName。 */
    public String getDisplayName() {
        return displayName;
    }
}
