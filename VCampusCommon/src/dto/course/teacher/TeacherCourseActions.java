package dto.course.teacher;

/**
 * 教师端 courseTeacher 模块的动作名。
 *
 * <p>与 {@link dto.course.admin.AdminCourseActions} 保持一致：动作名是 TCP 业务报文里的
 * 字符串常量，服务端 Handler 与客户端 Service 共用，避免两侧各自硬编码。
 */
public final class TeacherCourseActions {
    public static final String LIST_TERMS = "listTerms";
    public static final String LIST_OFFERINGS = "listOfferings";
    public static final String GET_OFFERING = "getOffering";
    public static final String LIST_OFFERING_STUDENTS = "listOfferingStudents";
    public static final String LIST_OFFERING_SCHEDULES = "listOfferingSchedules";
    public static final String LOAD_TEACHING_SCHEDULE = "loadTeachingSchedule";
    public static final String GET_ADJUSTMENT_OPTIONS = "getAdjustmentOptions";
    public static final String PREVIEW_ADJUSTMENT = "previewAdjustment";
    public static final String SUBMIT_ADJUSTMENT = "submitAdjustment";
    public static final String WITHDRAW_ADJUSTMENT = "withdrawAdjustment";
    public static final String GET_ADJUSTMENT_REQUEST = "getAdjustmentRequest";
    public static final String LIST_MY_ADJUSTMENT_REQUESTS = "listMyAdjustmentRequests";
    public static final String LIST_GRADE_OFFERINGS = "listGradeOfferings";
    public static final String GET_GRADE_BOOK = "getGradeBook";
    public static final String SAVE_GRADE_DRAFT = "saveGradeDraft";
    public static final String SUBMIT_GRADE_BOOK = "submitGradeBook";

    private TeacherCourseActions() {
    }
}
