package model.course.admin;

/** 管理端向教学班添加学生时使用的学生搜索结果视图。 */
public final class StudentSearchResultView {
    private final String uid;
    private final String name;
    private final String major;
    private final int cohortYear;
    private final String academicStatus;

    /** 创建学生搜索结果视图。 */
    public StudentSearchResultView(String uid, String name, String major,
            int cohortYear, String academicStatus) {
        this.uid = uid;
        this.name = name;
        this.major = major;
        this.cohortYear = cohortYear;
        this.academicStatus = academicStatus;
    }

    public String getUid() { return uid; }
    public String getName() { return name; }
    public String getMajor() { return major; }
    public int getCohortYear() { return cohortYear; }
    public String getAcademicStatus() { return academicStatus; }
}
