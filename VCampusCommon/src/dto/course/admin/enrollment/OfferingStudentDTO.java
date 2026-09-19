package dto.course.admin.enrollment;

/** 教务模块的 OfferingStudentDTO 数据传输对象。 */
public final class OfferingStudentDTO {
    private final String enrollmentId;
    private final String uid;
    private final String name;
    private final String major;
    private final int cohortYear;
    private final String enrollmentStatus;
    private final boolean removable;
    private final String blockedReason;

    public OfferingStudentDTO(String enrollmentId, String uid, String name,
            String major, int cohortYear, String enrollmentStatus,
            boolean removable, String blockedReason) {
        this.enrollmentId = enrollmentId;
        this.uid = uid;
        this.name = name;
        this.major = major;
        this.cohortYear = cohortYear;
        this.enrollmentStatus = enrollmentStatus;
        this.removable = removable;
        this.blockedReason = blockedReason;
    }

    /** 获取 EnrollmentId。 */
    public String getEnrollmentId() {
        return enrollmentId;
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

    /** 获取 EnrollmentStatus。 */
    public String getEnrollmentStatus() {
        return enrollmentStatus;
    }

    /** 判断 Removable 是否成立。 */
    public boolean isRemovable() {
        return removable;
    }

    /** 获取 BlockedReason。 */
    public String getBlockedReason() {
        return blockedReason;
    }
}
