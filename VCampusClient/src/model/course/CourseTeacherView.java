package model.course;

/** 教学班任课教师的客户端展示信息。 */
public final class CourseTeacherView {
    private final String uid;
    private final String displayName;

    /** 创建教师展示信息。 */
    public CourseTeacherView(String uid, String displayName) {
        this.uid = uid;
        this.displayName = displayName;
    }

    public String getUid() { return uid; }
    public String getDisplayName() { return displayName; }
}
