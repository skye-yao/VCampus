package service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.SelectionStatus;
import model.course.TrainingPlanGroupView;

public final class MockCourseService implements CourseService {
    private final Map<Long, CourseOfferingView> offerings = new LinkedHashMap<>();

    public MockCourseService() {
        addOffering(new CourseOfferingView(
                1001L, "CS203", "数据结构", "必修", 4.0, 64,
                "张老师", "周二 3-4节", "教四-201",
                "线性表、树和图", "程序设计基础",
                96, 120, SelectionStatus.AVAILABLE));
        addOffering(new CourseOfferingView(
                1002L, "CS301", "操作系统", "必修", 3.5, 56,
                "李老师", "周一 5-6节", "教二-305",
                "进程、内存与文件系统", "数据结构",
                100, 100, SelectionStatus.PLANNED));
        addOffering(new CourseOfferingView(
                1003L, "CS352", "人机交互", "专业选修", 2.0, 32,
                "王老师", "周四 7-8节", "教一-408",
                "交互设计与可用性评估", "无",
                60, 60, SelectionStatus.WAITLISTED));
        addOffering(new CourseOfferingView(
                1004L, "AR101", "音乐鉴赏", "通识选修", 2.0, 32,
                "陈老师", "周五 9-10节", "艺术楼-101",
                "中外经典音乐作品赏析", "无",
                47, 80, SelectionStatus.AVAILABLE));
        addOffering(new CourseOfferingView(
                1005L, "MA202", "离散数学", "必修", 3.0, 48,
                "赵老师", "周三 1-2节", "教三-202",
                "集合、图论与数理逻辑", "高等数学",
                86, 120, SelectionStatus.ENROLLED));
        addOffering(new CourseOfferingView(
                1006L, "CS305", "计算机网络", "必修", 3.5, 56,
                "刘老师", "周四 3-4节", "教四-305",
                "网络体系结构与协议", "操作系统",
                79, 100, SelectionStatus.ENROLLED));
    }

    @Override
    public synchronized CompletableFuture<List<CourseOfferingView>> loadOfferings() {
        return CompletableFuture.completedFuture(offeringSnapshot());
    }

    @Override
    public synchronized CompletableFuture<CourseOfferingView> addToPlan(long offeringId) {
        return transition(offeringId, SelectionStatus.AVAILABLE, SelectionStatus.PLANNED, 0);
    }

    @Override
    public synchronized CompletableFuture<CourseOfferingView> removeFromPlan(long offeringId) {
        return transition(offeringId, SelectionStatus.PLANNED, SelectionStatus.AVAILABLE, 0);
    }

    @Override
    public synchronized CompletableFuture<List<CourseOfferingView>> confirmPlan() {
        for (Map.Entry<Long, CourseOfferingView> entry : offerings.entrySet()) {
            CourseOfferingView offering = entry.getValue();
            if (offering.getSelectionStatus() != SelectionStatus.PLANNED) {
                continue;
            }

            if (offering.getEnrolledCount() < offering.getCapacity()) {
                entry.setValue(copyWith(offering, SelectionStatus.ENROLLED,
                        offering.getEnrolledCount() + 1));
            } else {
                entry.setValue(copyWith(offering, SelectionStatus.WAITLISTED,
                        offering.getEnrolledCount()));
            }
        }
        return CompletableFuture.completedFuture(offeringSnapshot());
    }

    @Override
    public synchronized CompletableFuture<CourseOfferingView> joinWaitlist(long offeringId) {
        CourseOfferingView offering = offerings.get(offeringId);
        if (offering == null) {
            return failed("Unknown offering: " + offeringId);
        }
        if (offering.getSelectionStatus() != SelectionStatus.AVAILABLE
                || offering.getEnrolledCount() < offering.getCapacity()) {
            return failed("Only a full available offering can be waitlisted: " + offeringId);
        }
        return replace(offering, SelectionStatus.WAITLISTED, offering.getEnrolledCount());
    }

    @Override
    public synchronized CompletableFuture<CourseOfferingView> leaveWaitlist(long offeringId) {
        return transition(offeringId, SelectionStatus.WAITLISTED, SelectionStatus.AVAILABLE, 0);
    }

    @Override
    public synchronized CompletableFuture<CourseOfferingView> dropCourse(long offeringId) {
        CourseOfferingView offering = offerings.get(offeringId);
        if (offering == null) {
            return failed("Unknown offering: " + offeringId);
        }
        if (offering.getSelectionStatus() != SelectionStatus.ENROLLED) {
            return failed("Expected ENROLLED but was " + offering.getSelectionStatus()
                    + " for offering: " + offeringId);
        }
        return replace(offering, SelectionStatus.AVAILABLE,
                Math.max(0, offering.getEnrolledCount() - 1));
    }

    @Override
    public CompletableFuture<List<ScheduleEntryView>> loadSchedule(String term, int week) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletableFuture<List<CourseNoticeView>> loadNotices(String term, int week) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletableFuture<GradeSummaryView> loadGrades(String term) {
        return CompletableFuture.completedFuture(
                new GradeSummaryView(term, 0.0, 0.0, 0.0, 0.0, Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private void addOffering(CourseOfferingView offering) {
        offerings.put(offering.getOfferingId(), offering);
    }

    private CompletableFuture<CourseOfferingView> transition(long offeringId,
            SelectionStatus expected, SelectionStatus target, int enrolledCountChange) {
        CourseOfferingView offering = offerings.get(offeringId);
        if (offering == null) {
            return failed("Unknown offering: " + offeringId);
        }
        if (offering.getSelectionStatus() != expected) {
            return failed("Expected " + expected + " but was " + offering.getSelectionStatus()
                    + " for offering: " + offeringId);
        }
        int enrolledCount = Math.max(0, offering.getEnrolledCount() + enrolledCountChange);
        return replace(offering, target, enrolledCount);
    }

    private CompletableFuture<CourseOfferingView> replace(CourseOfferingView offering,
            SelectionStatus status, int enrolledCount) {
        CourseOfferingView replacement = copyWith(offering, status, enrolledCount);
        offerings.put(replacement.getOfferingId(), replacement);
        return CompletableFuture.completedFuture(replacement);
    }

    private static CourseOfferingView copyWith(CourseOfferingView offering,
            SelectionStatus status, int enrolledCount) {
        return new CourseOfferingView(
                offering.getOfferingId(), offering.getCourseCode(), offering.getCourseName(),
                offering.getCourseType(), offering.getCredit(), offering.getCreditHours(),
                offering.getTeacher(), offering.getSchedule(), offering.getLocation(),
                offering.getDescription(), offering.getPrerequisites(), enrolledCount,
                offering.getCapacity(), status);
    }

    private List<CourseOfferingView> offeringSnapshot() {
        return immutableList(new ArrayList<>(offerings.values()));
    }

    private static <T> CompletableFuture<T> failed(String message) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
