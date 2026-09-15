package controller;

import java.util.Arrays;
import java.util.List;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;

/**
 * 教师端课表布局的四个场景，逐条对应学生端 {@link ScheduleLayoutTest}。
 *
 * <p>教师版消费的是带日期的 {@link TeacherScheduleEntryDTO}（起止节次已成对给出，不再是
 * start+count），但冲突列规则与学生端一致：{@code NORMAL}/{@code ADJUSTED_TARGET} 才是真实占用，
 * {@code ADJUSTED_ORIGINAL} 只是画在旧位置上的提示层，既不申请新列也不能把真实课程挤到额外列。
 * 全程不启动 JavaFX 工具包。
 */
public final class TeacherScheduleLayoutTest {
    private static final int WEEK = 8;
    private static final String MONDAY = "2026-09-07";

    private TeacherScheduleLayoutTest() {
    }

    public static void main(String[] args) {
        groupsTransitivelyConnectedIntervals();
        keepsTheFullRangeOfAContainingInterval();
        assignsAndReusesLanesForPartialOverlaps();
        displayOnlyOriginalsDoNotForceACollisionLane();
        System.out.println("TeacherScheduleLayoutTest: PASS");
    }

    private static void groupsTransitivelyConnectedIntervals() {
        List<TeacherScheduleEntryDTO> entries = Arrays.asList(
                entry("9101", 1, 1, 2),
                entry("9102", 1, 2, 3),
                entry("9103", 1, 3, 4),
                entry("9104", 1, 5, 6),
                entry("9199", 2, 1, 6));

        List<TeacherScheduleLayout.Component> components =
                TeacherScheduleLayout.layoutDay(entries, 1, 1, 13);

        require(components.size() == 2,
                "transitive overlaps must form one component and leave gaps separate, saw "
                        + components.size());
        require(components.get(0).getStartPeriod() == 1
                        && components.get(0).getEndPeriod() == 4,
                "first connected component must span periods 1-4, saw "
                        + components.get(0).getStartPeriod() + "-"
                        + components.get(0).getEndPeriod());
        require(components.get(0).getEntries().size() == 3,
                "first connected component must contain all transitive overlaps, saw "
                        + components.get(0).getEntries().size());
        require(components.get(1).getStartPeriod() == 5
                        && components.get(1).getEndPeriod() == 6,
                "second component must span periods 5-6");
    }

    private static void keepsTheFullRangeOfAContainingInterval() {
        List<TeacherScheduleEntryDTO> entries = Arrays.asList(
                entry("9205", 2, 1, 6),
                entry("9206", 2, 2, 2));

        TeacherScheduleLayout.Component component =
                TeacherScheduleLayout.layoutDay(entries, 2, 1, 13).get(0);

        require(component.getStartPeriod() == 1 && component.getEndPeriod() == 6,
                "component range must include an earlier interval that ends last, saw "
                        + component.getStartPeriod() + "-" + component.getEndPeriod());
    }

    private static void assignsAndReusesLanesForPartialOverlaps() {
        List<TeacherScheduleEntryDTO> entries = Arrays.asList(
                entry("9210", 3, 1, 4),
                entry("9211", 3, 2, 2),
                entry("9212", 3, 3, 5),
                entry("9213", 3, 5, 6));

        TeacherScheduleLayout.Component component =
                TeacherScheduleLayout.layoutDay(entries, 3, 1, 13).get(0);

        require(component.getLaneCount() == 2,
                "partial overlaps must use two visible lanes, saw " + component.getLaneCount());
        require(laneOf(component, "9210") == 0, "first interval must use lane 0");
        require(laneOf(component, "9211") == 1, "overlapping interval must use lane 1");
        require(laneOf(component, "9212") == 1,
                "lane 1 must be reused after its prior interval ends");
        require(laneOf(component, "9213") == 0,
                "lane 0 must be reused while lane 1 remains occupied");
    }

    private static void displayOnlyOriginalsDoNotForceACollisionLane() {
        // 一格真实课程，以及同一时间段上它自己留下的“原安排”提示块。
        List<TeacherScheduleEntryDTO> overlaid = Arrays.asList(
                entry("9221", 1, 1, 2),
                adjusted("9222", 1, 1, 2, ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL));

        TeacherScheduleLayout.Component component =
                TeacherScheduleLayout.layoutDay(overlaid, 1, 1, 13).get(0);

        require(component.getLaneCount() == 1,
                "a display-only original must not add a collision lane, observed "
                        + component.getLaneCount());
        require(laneOf(component, "9221") == 0 && laneOf(component, "9222") == 0,
                "the original overlay must share the real lesson's lane");
        require("9222".equals(component.getEntries().get(0).getEntry().getOccurrenceId()),
                "the overlay must be placed first so real lessons draw above it, saw "
                        + component.getEntries().get(0).getEntry().getOccurrenceId());

        // 新位置是真实占用，与已有课程冲突时仍然需要额外一列。
        List<TeacherScheduleEntryDTO> targeted = Arrays.asList(
                entry("9231", 2, 2, 3),
                adjusted("9232", 2, 2, 3, ScheduleDisplayKindDTO.ADJUSTED_TARGET));

        require(TeacherScheduleLayout.layoutDay(targeted, 2, 1, 13).get(0).getLaneCount() == 2,
                "an adjusted target occupies its slot and must take its own lane");

        // 只由提示块构成的组也必须能渲染（至少一列，避免除零）。
        List<TeacherScheduleEntryDTO> onlyOriginal = List.of(
                adjusted("9241", 3, 5, 6, ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL));
        TeacherScheduleLayout.Component alone =
                TeacherScheduleLayout.layoutDay(onlyOriginal, 3, 1, 13).get(0);
        require(alone.getLaneCount() == 1 && alone.getEntries().size() == 1,
                "an overlay-only component must still render in one lane");
    }

    private static int laneOf(TeacherScheduleLayout.Component component, String occurrenceId) {
        for (TeacherScheduleLayout.PlacedEntry placed : component.getEntries()) {
            if (occurrenceId.equals(placed.getEntry().getOccurrenceId())) {
                return placed.getLane();
            }
        }
        throw new AssertionError("missing occurrence in layout: " + occurrenceId);
    }

    private static TeacherScheduleEntryDTO entry(String occurrenceId, int weekday,
            int startPeriod, int endPeriod) {
        return new TeacherScheduleEntryDTO(occurrenceId, "offering-" + occurrenceId,
                "C" + occurrenceId, "Course " + occurrenceId, "陈老师", "A-101", MONDAY, WEEK,
                weekday, startPeriod, endPeriod, ScheduleDisplayKindDTO.NORMAL, null, null, null,
                null, true);
    }

    private static TeacherScheduleEntryDTO adjusted(String occurrenceId, int weekday,
            int startPeriod, int endPeriod, ScheduleDisplayKindDTO kind) {
        return new TeacherScheduleEntryDTO(occurrenceId, "offering-" + occurrenceId,
                "C" + occurrenceId, "Course " + occurrenceId, "陈老师", "A-101", MONDAY, WEEK,
                weekday, startPeriod, endPeriod, kind, "ADJ-" + occurrenceId,
                "周一 第1-2节 A-101", "周三 第3-4节 B-203", "教师出差", false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
