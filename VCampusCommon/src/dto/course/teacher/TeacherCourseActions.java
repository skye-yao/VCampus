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
    /** 用上传成功的文件生成一份可编辑的导入预览（不写库）；响应键 {@code preview}。 */
    public static final String PREVIEW_GRADE_IMPORT = "previewGradeImport";
    /** 修订预览：修正异常行或明确排除它们；响应键同为 {@code preview}。 */
    public static final String REVISE_GRADE_IMPORT = "reviseGradeImport";
    /**
     * 确认导入：在一个事务里把候选写成成绩草稿，响应键 {@code result}。
     *
     * <p>这个字面量同时是写库时的动作名：它会随 {@code writeDraft} 落进每条单元格变更审计，
     * 因此「导入改写整班成绩」与「教师手工改一格」在 {@code teacher_grade_change_log.action} 里
     * 可以区分，事后追责不必靠猜。
     */
    public static final String CONFIRM_GRADE_IMPORT = "confirmGradeImport";
    /** 取消导入：丢弃预览令牌，客户端恢复导入前的编辑副本。 */
    public static final String CANCEL_GRADE_IMPORT = "cancelGradeImport";

    private TeacherCourseActions() {
    }
}
