package model.course;

public final class CourseTeacherView {
    private final String uid;
    private final String displayName;

    public CourseTeacherView(String uid, String displayName) {
        this.uid = uid;
        this.displayName = displayName;
    }

    public String getUid() { return uid; }
    public String getDisplayName() { return displayName; }
}
