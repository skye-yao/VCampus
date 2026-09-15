package dto.course;

public final class CourseTeacherDTO {
    private final String uid;
    private final String displayName;

    public CourseTeacherDTO(String uid, String displayName) {
        this.uid = uid;
        this.displayName = displayName;
    }

    public String getUid() {
        return uid;
    }

    public String getDisplayName() {
        return displayName;
    }
}
