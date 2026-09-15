package controller;

import java.util.Arrays;
import java.util.List;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;

public final class ScheduleLayoutTest {
    public static void main(String[] args) {
        groupsTransitivelyConnectedIntervals();
        keepsTheFullRangeOfAContainingInterval();
        assignsAndReusesLanesForPartialOverlaps();
        displayOnlyOriginalsDoNotForceACollisionLane();
        System.out.println("ScheduleLayoutTest: PASS");
    }

    private static void displayOnlyOriginalsDoNotForceACollisionLane() {
        // 一格真实课程，以及同一时间段上它自己留下的“原安排”占位块。
        List<ScheduleEntryView> overlaid = Arrays.asList(
                entry(21L, 1, 1, 2),
                adjusted(22L, 1, 1, 2, ScheduleDisplayKind.ADJUSTED_ORIGINAL));

        ScheduleLayout.Component component =
                ScheduleLayout.layoutDay(overlaid, 1, 1, 10).get(0);

        require(component.getLaneCount() == 1,
                "a display-only original must not add a collision lane, observed "
                        + component.getLaneCount());
        require(laneOf(component, 21L) == 0 && laneOf(component, 22L) == 0,
                "the original overlay must share the real lesson's lane");
        require(component.getEntries().get(0).getEntry().getOfferingId() == 22L,
                "the overlay must be placed first so real lessons draw above it");

        // 新位置是真实占用，与已有课程冲突时仍然需要额外一列。
        List<ScheduleEntryView> targeted = Arrays.asList(
                entry(31L, 2, 3, 4),
                adjusted(32L, 2, 3, 4, ScheduleDisplayKind.ADJUSTED_TARGET));

        require(ScheduleLayout.layoutDay(targeted, 2, 1, 10).get(0).getLaneCount() == 2,
                "an adjusted target occupies its slot and must take its own lane");

        // 只由占位块构成的组也必须能渲染（至少一列，避免除零）。
        List<ScheduleEntryView> onlyOriginal = List.of(
                adjusted(41L, 3, 5, 2, ScheduleDisplayKind.ADJUSTED_ORIGINAL));
        ScheduleLayout.Component alone =
                ScheduleLayout.layoutDay(onlyOriginal, 3, 1, 10).get(0);
        require(alone.getLaneCount() == 1 && alone.getEntries().size() == 1,
                "an overlay-only component must still render in one lane");
    }

    private static ScheduleEntryView adjusted(long offeringId, int day, int startPeriod,
            int periodCount, ScheduleDisplayKind kind) {
        return new ScheduleEntryView(
                offeringId, "2026-2027 秋学期", "C" + offeringId,
                "Course " + offeringId, "Teacher", "Room", day,
                startPeriod, periodCount, 1, 16, kind, "ADJ-" + offeringId,
                "周二 第1-2节 Room", "周五 第3-4节 Room B", "教师出差");
    }

    private static void groupsTransitivelyConnectedIntervals() {
        List<ScheduleEntryView> entries = Arrays.asList(
                entry(1L, 1, 1, 2),
                entry(2L, 1, 2, 2),
                entry(3L, 1, 3, 2),
                entry(4L, 1, 5, 2),
                entry(99L, 2, 1, 6));

        List<ScheduleLayout.Component> components =
                ScheduleLayout.layoutDay(entries, 1, 1, 10);

        require(components.size() == 2,
                "transitive overlaps must form one component and leave gaps separate");
        require(components.get(0).getStartPeriod() == 1
                        && components.get(0).getEndPeriod() == 4,
                "first connected component must span periods 1-4");
        require(components.get(0).getEntries().size() == 3,
                "first connected component must contain all transitive overlaps");
        require(components.get(1).getStartPeriod() == 5
                        && components.get(1).getEndPeriod() == 6,
                "second component must span periods 5-6");
    }

    private static void keepsTheFullRangeOfAContainingInterval() {
        List<ScheduleEntryView> entries = Arrays.asList(
                entry(5L, 2, 1, 6),
                entry(6L, 2, 2, 1));

        ScheduleLayout.Component component =
                ScheduleLayout.layoutDay(entries, 2, 1, 10).get(0);

        require(component.getStartPeriod() == 1 && component.getEndPeriod() == 6,
                "component range must include an earlier interval that ends last");
    }

    private static void assignsAndReusesLanesForPartialOverlaps() {
        List<ScheduleEntryView> entries = Arrays.asList(
                entry(10L, 3, 1, 4),
                entry(11L, 3, 2, 1),
                entry(12L, 3, 3, 3),
                entry(13L, 3, 5, 2));

        ScheduleLayout.Component component =
                ScheduleLayout.layoutDay(entries, 3, 1, 10).get(0);

        require(component.getLaneCount() == 2,
                "partial overlaps must use two visible lanes");
        require(laneOf(component, 10L) == 0, "first interval must use lane 0");
        require(laneOf(component, 11L) == 1, "overlapping interval must use lane 1");
        require(laneOf(component, 12L) == 1,
                "lane 1 must be reused after its prior interval ends");
        require(laneOf(component, 13L) == 0,
                "lane 0 must be reused while lane 1 remains occupied");
    }

    private static int laneOf(ScheduleLayout.Component component, long offeringId) {
        for (ScheduleLayout.PlacedEntry placed : component.getEntries()) {
            if (placed.getEntry().getOfferingId() == offeringId) {
                return placed.getLane();
            }
        }
        throw new AssertionError("missing offering in layout: " + offeringId);
    }

    private static ScheduleEntryView entry(long offeringId, int day,
            int startPeriod, int periodCount) {
        return new ScheduleEntryView(
                offeringId, "2026-2027 秋学期", "C" + offeringId,
                "Course " + offeringId, "Teacher", "Room", day,
                startPeriod, periodCount, 1, 16);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
