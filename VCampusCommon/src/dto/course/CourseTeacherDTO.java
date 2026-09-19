package dto.course;

/** 教务模块的 CourseTeacherDTO 数据传输对象。 */
public final class CourseTeacherDTO {
    private final String uid;
    private final String displayName;

    public CourseTeacherDTO(String uid, String displayName) {
        this.uid = uid;
        this.displayName = displayName;
    }

    /** 获取 Uid。 */
    public String getUid() {
        return uid;
    }

    /** 获取 DisplayName。 */
    public String getDisplayName() {
        return displayName;
    }
}
