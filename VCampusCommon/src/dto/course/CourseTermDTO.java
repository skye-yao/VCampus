package dto.course;

public final class CourseTermDTO {
    private final int academicYear;
    private final int semester;
    private final String displayName;

    public CourseTermDTO(int academicYear, int semester, String displayName) {
        this.academicYear = academicYear;
        this.semester = semester;
        this.displayName = displayName;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public int getSemester() {
        return semester;
    }

    public String getDisplayName() {
        return displayName;
    }
}
