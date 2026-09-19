package dto.course;

/** 教务模块操作名称的常量集合。 */
public final class CourseActions {
    public static final String LIST_TERMS = "listTerms";
    public static final String LIST_COURSES = "listCourses";
    public static final String LIST_COURSE_OFFERINGS = "listCourseOfferings";
    public static final String LOAD_SELECTION_SNAPSHOT = "loadSelectionSnapshot";
    public static final String ADD_TO_PLAN = "addToPlan";
    public static final String REMOVE_FROM_PLAN = "removeFromPlan";
    public static final String SELECT_OFFERING = "selectOffering";
    public static final String JOIN_WAITLIST = "joinWaitlist";
    public static final String CANCEL_WAITLIST = "cancelWaitlist";
    public static final String RESOLVE_WAITLIST_OFFER = "resolveWaitlistOffer";
    public static final String DROP_OFFERING = "dropOffering";
    public static final String ACK_COURSE_EVENT = "ackCourseEvent";
    public static final String LOAD_SCHEDULE = "loadSchedule";
    public static final String LOAD_NOTICES = "loadNotices";
    public static final String LOAD_GRADES = "loadGrades";
    public static final String LOAD_TRAINING_PLAN = "loadTrainingPlan";
    public static final String SELECTION_EVENT = "selectionEvent";

    private CourseActions() {
    }
}
