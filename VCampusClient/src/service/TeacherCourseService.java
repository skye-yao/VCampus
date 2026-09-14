package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;

/**
 * 教师端只读课程查询与调课申请的客户端契约。
 *
 * <p>与 {@code TeacherCourseQueryService}/{@code TeacherAdjustmentApplicationService} 同名，但去掉
 * uid 参数：教师身份只由服务端从有效 Session token 解析，客户端不携带也不再伪造归属信息。方法名
 * 与响应键一一对应。
 *
 * <p>调课方法声明为 default：既有测试替身只有在真正需要调课能力时才覆写，避免一次新增接口方法
 * 让所有旧实现编译中断（与 {@code AdminCourseService} 的兼容约定一致）。
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
}
