package model.course;

import java.util.Objects;

/** 学年和学期构成的课程学期视图，可作为界面选择项。 */
public final class CourseTermView {
    private final int academicYear;
    private final int semester;
    private final String displayName;

    /** 创建课程学期视图。 */
    public CourseTermView(int academicYear, int semester, String displayName) {
        this.academicYear = academicYear;
        this.semester = semester;
        this.displayName = displayName;
    }

    public int getAcademicYear() { return academicYear; }
    public int getSemester() { return semester; }
    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof CourseTermView)) return false;
        CourseTermView other = (CourseTermView) value;
        return academicYear == other.academicYear && semester == other.semester;
    }

    @Override
    public int hashCode() {
        return Objects.hash(academicYear, semester);
    }
}
