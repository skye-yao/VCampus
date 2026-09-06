package controller;

import java.util.Arrays;
import java.util.List;
import model.course.ScheduleEntryView;

public final class ScheduleLayoutTest {
    public static void main(String[] args) {
        groupsTransitivelyConnectedIntervals();
        keepsTheFullRangeOfAContainingInterval();
        assignsAndReusesLanesForPartialOverlaps();
        System.out.println("ScheduleLayoutTest: PASS");
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
