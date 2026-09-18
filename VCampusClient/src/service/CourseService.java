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

public interface CourseService {
    CompletableFuture<List<CourseTermView>> loadTerms();

    CompletableFuture<List<CourseView>> loadCourses(CourseTermView term);

    CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
            CourseTermView term, long courseId);

    CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(CourseTermView term);

    CompletableFuture<CourseMutationResultView> addToPlan(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<CourseMutationResultView> removeFromPlan(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<CourseMutationResultView> selectOffering(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<CourseMutationResultView> joinWaitlist(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<CourseMutationResultView> cancelWaitlist(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
            CourseTermView term, long offeringId, String operationId,
            WaitlistDecision decision);

    CompletableFuture<CourseMutationResultView> dropOffering(
            CourseTermView term, long offeringId, String operationId);

    CompletableFuture<Void> ackCourseEvent(String eventId);

    CourseSubscription subscribe(CoursePushListener listener);

    /**
     * 某教学周的课表：课次连同该周的日期与节次字典一起返回，网格几何因此由服务端教学日历决定。
     *
     * <p>{@code week} 为 null 表示“跟随当前周”：请求里不带周次，由服务端按教学日历与系统时钟决定；
     * 响应里的 {@code minWeek}/{@code maxWeek}/{@code currentWeek} 就是周次控件的范围与初值来源。
     */
    CompletableFuture<ScheduleWeekView> loadSchedule(CourseTermView term, Integer week);

    CompletableFuture<List<CourseNoticeView>> loadNotices(CourseTermView term, int week);

    CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term);

    CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
}
