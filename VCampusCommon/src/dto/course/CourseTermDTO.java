package dto.course;

public final class CourseTermDTO {
    private final String academicYear;
    private final int semester;
    private final String displayName;

    public CourseTermDTO(String academicYear, int semester, String displayName) {
        this.academicYear = academicYear;
        this.semester = semester;
        this.displayName = displayName;
    }

    public String getAcademicYear() {
        return academicYear;
    }

    public int getSemester() {
        return semester;
    }

    public String getDisplayName() {
        return displayName;
    }
}
