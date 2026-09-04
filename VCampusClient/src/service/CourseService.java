package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.TrainingPlanGroupView;

public interface CourseService {
    CompletableFuture<List<CourseOfferingView>> loadOfferings();

    CompletableFuture<CourseOfferingView> addToPlan(long offeringId);

    CompletableFuture<CourseOfferingView> removeFromPlan(long offeringId);

    CompletableFuture<List<CourseOfferingView>> confirmPlan();

    CompletableFuture<CourseOfferingView> joinWaitlist(long offeringId);

    CompletableFuture<CourseOfferingView> leaveWaitlist(long offeringId);

    CompletableFuture<CourseOfferingView> dropCourse(long offeringId);

    CompletableFuture<List<ScheduleEntryView>> loadSchedule(String term, int week);

    CompletableFuture<List<CourseNoticeView>> loadNotices(String term, int week);

    CompletableFuture<GradeSummaryView> loadGrades(String term);

    CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
}
