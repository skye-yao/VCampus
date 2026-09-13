package model.course.admin;

public final class OfferingStudentView {
    private final String enrollmentId;
    private final String uid;
    private final String name;
    private final String major;
    private final int cohortYear;
    private final String enrollmentStatus;
    private final boolean removable;
    private final String blockedReason;

    public OfferingStudentView(String enrollmentId, String uid, String name,
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

    public String getEnrollmentId() { return enrollmentId; }
    public String getUid() { return uid; }
    public String getName() { return name; }
    public String getMajor() { return major; }
    public int getCohortYear() { return cohortYear; }
    public String getEnrollmentStatus() { return enrollmentStatus; }
    public boolean isRemovable() { return removable; }
    public String getBlockedReason() { return blockedReason; }
}
