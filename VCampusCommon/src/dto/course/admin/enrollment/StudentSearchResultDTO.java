package dto.course.admin.enrollment;

/** 教务模块的 StudentSearchResultDTO 数据传输对象。 */
public final class StudentSearchResultDTO {
    private final String uid;
    private final String name;
    private final String major;
    private final int cohortYear;
    private final String academicStatus;

    public StudentSearchResultDTO(String uid, String name, String major,
            int cohortYear, String academicStatus) {
        this.uid = uid;
        this.name = name;
        this.major = major;
        this.cohortYear = cohortYear;
        this.academicStatus = academicStatus;
    }

    /** 获取 Uid。 */
    public String getUid() {
        return uid;
    }

    /** 获取 Name。 */
    public String getName() {
        return name;
    }

    /** 获取 Major。 */
    public String getMajor() {
        return major;
    }

    /** 获取 CohortYear。 */
    public int getCohortYear() {
        return cohortYear;
    }

    /** 获取 AcademicStatus。 */
    public String getAcademicStatus() {
        return academicStatus;
    }
}
