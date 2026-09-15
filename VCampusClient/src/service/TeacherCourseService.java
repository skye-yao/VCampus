package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;

/**
 * 教师端只读课程查询与调课申请的客户端契约。
 *
 * <p>与 {@code TeacherCourseQueryService}/{@code TeacherAdjustmentApplicationService} 同名，但去掉
 * uid 参数：教师身份只由服务端从有效 Session token 解析，客户端不携带也不再伪造归属信息。方法名
 * 与响应键一一对应。
 *
 * <p>调课与成绩方法声明为 default：既有测试替身只有在真正需要该项能力时才覆写，避免一次新增接口
 * 方法让所有旧实现编译中断（与 {@code AdminCourseService} 的兼容约定一致）。default 实现一律
 * 抛 {@link UnsupportedOperationException}，绝不返回空结果——未实现的页面应当明确报错，而不是
 * 显示一张空成绩表。两个真实实现（{@code SocketTeacherCourseService}、
 * {@code MockTeacherCourseService}）都已覆写这些方法。
 */
public interface TeacherCourseService {
    CompletableFuture<List<CourseTermDTO>> listTerms();

    CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
            int academicYear, int semester, String query, int page, int size);

    CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId);

    CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
            String offeringId, String query, Integer enrollmentStatus, int page, int size);

    CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(String offeringId);

    /**
     * 按教学日历经周查看教师本人的课表。
     *
     * <p>{@code week} 为 null 时由服务端依据注入的 {@code Clock} 与教学日历选定当前/最近有效教学周；
     * 返回的 {@code currentWeek} 为 null 表示今天不在此学期。
     */
    CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
            int academicYear, int semester, Integer week);

    /** 某个原课次的可选目标域（该教学日历的教学日、节次与全部教室）。 */
    default CompletableFuture<TeacherAdjustmentOptionsDTO> getAdjustmentOptions(
            String offeringId, String originalOccurrenceId) {
        throw new UnsupportedOperationException("getAdjustmentOptions");
    }

    /** 纯预检查：不做写操作，忽略 operationId；提交时服务端会重新检查。 */
    default CompletableFuture<TeacherAdjustmentPreviewDTO> previewAdjustment(
            TeacherAdjustmentWriteDTO request) {
        throw new UnsupportedOperationException("previewAdjustment");
    }

    /** 提交调课申请；operationId 必须是 UUID，相同 operationId 与内容由服务端重放。 */
    default CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>> submitAdjustment(
            TeacherAdjustmentWriteDTO request) {
        throw new UnsupportedOperationException("submitAdjustment");
    }

    /** 撤销本人 PENDING 申请；expectedVersion 取自详情，成功后返回最新详情。 */
    default CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
            withdrawAdjustment(WithdrawTeacherAdjustmentRequestDTO request) {
        throw new UnsupportedOperationException("withdrawAdjustment");
    }

    /** 本人申请详情；别人的申请与不存在的申请同样以 NOT_FOUND 结束。 */
    default CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
        throw new UnsupportedOperationException("getAdjustmentRequest");
    }

    /** 本人申请的泛型页；status 缺省时服务端按 PENDING 处理，四态含 WITHDRAWN。 */
    default CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>> listMyAdjustmentRequests(
            AdjustmentRequestStatusDTO status, int page, int size) {
        throw new UnsupportedOperationException("listMyAdjustmentRequests");
    }

    // ------------------------------------------------------------------ 成绩工作副本

    /**
     * 本人任课（role=0）教学班的成绩列表，按学期分页；尚无工作副本的班也在列表里
     * （此时 {@code state} 是 DRAFT、revision 由随后的 {@link #getGradeBook(String)} 读出）。
     */
    default CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
            int academicYear, int semester, int page, int size) {
        throw new UnsupportedOperationException("listGradeOfferings");
    }

    /**
     * 一个教学班的成绩表。尚无工作副本时服务端返回 {@code revision=0} 的虚拟草稿且不写库；
     * 行里的总评/绩点全部来自服务端，客户端只用同一份纯计算规则做预览。
     */
    default CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
        throw new UnsupportedOperationException("getGradeBook");
    }

    /**
     * 保存草稿：请求就是当前完整编辑内容，允许权重未配齐与缺分，但非法分数不会被接受。
     * {@code expectedRevision}/{@code rosterDigest} 带的是客户端看到的版本与名单摘要。
     */
    default CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
            WriteGradeBookRequestDTO request) {
        throw new UnsupportedOperationException("saveGradeDraft");
    }

    /** 正式提交：服务端在事务内重新校验权重配齐、启用项分数完整，并生成不可变批次。 */
    default CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
            WriteGradeBookRequestDTO request) {
        throw new UnsupportedOperationException("submitGradeBook");
    }

    // ------------------------------------------------------------------ Excel 模板、导入与名单导出

    /**
     * 申请一张空白成绩模板的下载票据（方向 DOWNLOAD）。文件本身走独立文件端口，业务响应里只有票据。
     */
    default CompletableFuture<TeacherFileTicketDTO> requestGradeTemplate(String offeringId) {
        throw new UnsupportedOperationException("requestGradeTemplate");
    }

    /** 申请一张完整名单导出的下载票据：过滤条件与名单列表相同，但取全部结果而不是当前页。 */
    default CompletableFuture<TeacherFileTicketDTO> requestRosterExport(
            String offeringId, String query, Integer enrollmentStatus) {
        throw new UnsupportedOperationException("requestRosterExport");
    }

    /** 申请一张上传票据：客户端只声明教学班、草稿版本、文件名、字节数与摘要，不发送文件内容。 */
    default CompletableFuture<TeacherFileTicketDTO> beginGradeUpload(
            TeacherFileUploadRequestDTO request) {
        throw new UnsupportedOperationException("beginGradeUpload");
    }

    /** 上传成功之后把文件兑换成一份可编辑的导入预览；预览不写草稿。 */
    default CompletableFuture<GradeImportPreviewDTO> previewGradeImport(
            PreviewGradeImportRequestDTO request) {
        throw new UnsupportedOperationException("previewGradeImport");
    }

    /** 修订预览（修正异常行或明确排除它们），返回递增了 previewRevision 的新预览。 */
    default CompletableFuture<GradeImportPreviewDTO> reviseGradeImport(
            ReviseGradeImportRequestDTO request) {
        throw new UnsupportedOperationException("reviseGradeImport");
    }

    /** 确认导入：把候选写成成绩草稿。它不是提交审批，也不包含任何成绩内容。 */
    default CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> confirmGradeImport(
            ConfirmGradeImportRequestDTO request) {
        throw new UnsupportedOperationException("confirmGradeImport");
    }

    /** 取消导入：丢弃预览令牌；服务端本来就没写过任何东西，编辑副本由客户端自己恢复。 */
    default CompletableFuture<Void> cancelGradeImport(String importToken) {
        throw new UnsupportedOperationException("cancelGradeImport");
    }
}
