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
    /**
     * 驳回重开：把本班最后一次被驳回的批次复制成新的可编辑草稿（草稿类型 RESUBMISSION）。
     *
     * <p>这个字面量同时是写库时的动作名：它随版本变更落进 {@code teacher_grade_change_log.action}
     * 与 {@code teacher_course_operation_log.action}，所以「教师按下重开」与随后那次「保存草稿」
     * 在事后追责时是两件事。
     */
    public static final String REOPEN_REJECTED_GRADE_BOOK = "reopenRejectedGradeBook";
    /** 发起更正：把本班最后一次已通过的批次复制成新的可编辑草稿（草稿类型 CORRECTION，原因必填）。 */
    public static final String BEGIN_GRADE_CORRECTION = "beginGradeCorrection";
    /** 申请一张上传票据：Excel 文件本身走独立文件端口，业务 JSON 只带回票据。 */
    public static final String BEGIN_GRADE_UPLOAD = "beginGradeUpload";
    /** 申请一张空白成绩模板的下载票据（与名单导出同形，方向为 DOWNLOAD）。 */
    public static final String REQUEST_GRADE_TEMPLATE = "requestGradeTemplate";
    /** 申请一张完整名单导出的下载票据：按与列表相同的过滤取全部结果，不是当前页。 */
    public static final String REQUEST_ROSTER_EXPORT = "requestRosterExport";
    /**
     * 申请一张成绩导出的下载票据：名单（学号/姓名/专业）加已保存草稿的四项成绩、总评与绩点。
     *
     * <p>与 {@link #REQUEST_ROSTER_EXPORT} 是两条独立的路径：名单导出写的是教学班的学生名单
     * （含退课行与状态），成绩导出写的是成绩表当前这一版草稿，两者共用的只有文件票据通道。
     */
    public static final String REQUEST_GRADE_EXPORT = "requestGradeExport";
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
    /**
     * 统一的「我的申请」列表：把调课申请与成绩提交批次合并成一条按 (submittedAt DESC, type, id DESC)
     * 稳定排序的分页流，可按类型与状态筛选。响应键沿用 {@code applications}（列表仍叫这个名字）。
     *
     * <p>它与 {@link #LIST_MY_ADJUSTMENT_REQUESTS} 是两个动作而不是同一个：后者的页元素是
     * {@code AdjustmentRequestSummaryDTO}（四态枚举），前者的页元素是
     * {@link dto.course.teacher.TeacherApplicationDTO}（字符串状态，两张表共用）。两者的响应键相同
     * 但 TypeToken 不同，客户端按各自的泛型实参解析，因此不能互相复用。
     */
    public static final String LIST_MY_APPLICATIONS = "listMyApplications";
    /** 单条申请详情：响应键 {@code application}（单数），详情里恰好一个类型化变体非空。 */
    public static final String GET_MY_APPLICATION = "getMyApplication";
    /**
     * 标记一条申请结果为已读：写请求体位于 {@code data.request}，响应走 {@code result} 信封。
     *
     * <p>它没有 {@code operationId}：已读回执按「教师 + 类型 + 申请」主键 upsert，本身就幂等，
     * 而真正需要防的是「用看到旧结果时的确认去标新的结果」——那件事由 {@code expectedStateKey}
     * 的 compare-and-set 负责，不是幂等键。
     */
    public static final String MARK_APPLICATION_READ = "markApplicationRead";

    private TeacherCourseActions() {
    }
}
