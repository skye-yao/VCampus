package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleWeekView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;

/**
 * 学生教务查询与选课操作的异步门面。
 *
 * <p>实现层负责把协议 DTO 转为本包的只读 View；JavaFX 控制器只能通过返回的
 * {@link CompletableFuture} 在完成回调中更新界面，不能把 View 当作可写的网络 DTO。
 */
public interface CourseService {
    /** 异步加载可选学期。 */
    CompletableFuture<List<CourseTermView>> loadTerms();

    /** 异步加载指定学期的课程目录。 */
    CompletableFuture<List<CourseView>> loadCourses(CourseTermView term);

    /** 异步加载课程在指定学期的教学班。 */
    CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
            CourseTermView term, long courseId);

    /** 异步加载学生在指定学期的选课、候补和已选快照。 */
    CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(CourseTermView term);

    /** 将教学班加入选课计划，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> addToPlan(
            CourseTermView term, long offeringId, String operationId);

    /** 从选课计划移除教学班，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> removeFromPlan(
            CourseTermView term, long offeringId, String operationId);

    /** 提交教学班选课请求，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> selectOffering(
            CourseTermView term, long offeringId, String operationId);

    /** 加入教学班候补队列，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> joinWaitlist(
            CourseTermView term, long offeringId, String operationId);

    /** 取消教学班候补，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> cancelWaitlist(
            CourseTermView term, long offeringId, String operationId);

    /** 对候补录取作出确认或放弃决定。 */
    CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
            CourseTermView term, long offeringId, String operationId,
            WaitlistDecision decision);

    /** 退选已选教学班，并以 operationId 支持请求幂等。 */
    CompletableFuture<CourseMutationResultView> dropOffering(
            CourseTermView term, long offeringId, String operationId);

    /** 确认已处理的课程推送事件，避免服务端重复投递。 */
    CompletableFuture<Void> ackCourseEvent(String eventId);

    /** 注册课程推送监听器；调用返回订阅的 close 可解除监听。 */
    CourseSubscription subscribe(CoursePushListener listener);

    /**
     * 某教学周的课表：课次连同该周的日期与节次字典一起返回，网格几何因此由服务端教学日历决定。
     *
     * <p>{@code week} 为 null 表示“跟随当前周”：请求里不带周次，由服务端按教学日历与系统时钟决定；
     * 响应里的 {@code minWeek}/{@code maxWeek}/{@code currentWeek} 就是周次控件的范围与初值来源。
     */
    CompletableFuture<ScheduleWeekView> loadSchedule(CourseTermView term, Integer week);

    /** 异步加载指定教学周的课程通知。 */
    CompletableFuture<List<CourseNoticeView>> loadNotices(CourseTermView term, int week);

    /** 异步加载指定学期的成绩汇总及明细。 */
    CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term);

    /** 异步加载当前学生的培养方案分组与完成情况。 */
    CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
}
