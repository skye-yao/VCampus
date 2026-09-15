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
    /** 申请一张上传票据：Excel 文件本身走独立文件端口，业务 JSON 只带回票据。 */
    public static final String BEGIN_GRADE_UPLOAD = "beginGradeUpload";
    /** 申请一张空白成绩模板的下载票据（与名单导出同形，方向为 DOWNLOAD）。 */
    public static final String REQUEST_GRADE_TEMPLATE = "requestGradeTemplate";
    /** 申请一张完整名单导出的下载票据：按与列表相同的过滤取全部结果，不是当前页。 */
    public static final String REQUEST_ROSTER_EXPORT = "requestRosterExport";

    private TeacherCourseActions() {
    }
}
