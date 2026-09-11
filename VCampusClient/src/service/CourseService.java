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
import model.course.ScheduleEntryView;
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

    CompletableFuture<List<ScheduleEntryView>> loadSchedule(String term, int week);

    CompletableFuture<List<CourseNoticeView>> loadNotices(String term, int week);

    CompletableFuture<GradeSummaryView> loadGrades(String term);

    CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
}
