package service;

import dto.course.CoursePeriodDTO;
import java.util.List;
import model.course.CourseNoticeView;
import model.course.CourseTermView;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;
import model.course.ScheduleWeekView;

public final class MockCourseScheduleTest {
    private static final CourseTermView TERM = new CourseTermView(2026, 1, "2026-2027 秋学期");
    private static final CourseTermView OTHER_TERM =
            new CourseTermView(2025, 1, "2025-2026 秋学期");

    public static void main(String[] args) throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = service.loadTerms().get().get(0);
        ScheduleWeekView initialWeek = service.loadSchedule(TERM, 3).get();
        List<ScheduleEntryView> initial = initialWeek.getEntries();
        require(hasOffering(initial, 1005L) && hasOffering(initial, 1006L),
                "initial enrolled courses must appear");
        require(initialWeek.getWeek() == 3
                        && initialWeek.getDates().size() == 5
                        && initialWeek.getDates().get(0).getTeachingWeekday() == 1
                        && initialWeek.getPeriods().size() == 40
                        && initialWeek.getPeriods().get(0).getPeriod() == 1
                        && initialWeek.getPeriods().get(7).getPeriod() == 8,
                "the week object must carry the calendar fixture: 1..8 节 × 周一至周五，实际 "
                        + initialWeek.getDates().size() + " 天 / "
                        + initialWeek.getPeriods().size() + " 条节次");
        require(periodRows(initialWeek).equals(List.of(1, 2, 3, 4, 5, 6, 7, 8)),
                "网格的节次行必须来自夹具字典而不是常量，实际 " + periodRows(initialWeek));
        // 离线界面也要拿到服务端形状的周范围与当前周（"回到本周"与周次控件的来源）。
        require(initialWeek.getMinWeek() == 1 && initialWeek.getMaxWeek() == 16
                        && Integer.valueOf(3).equals(initialWeek.getCurrentWeek()),
                "假体必须给出确定的周范围与当前周，实际 " + initialWeek.getMinWeek() + ".."
                        + initialWeek.getMaxWeek() + " 当前 " + initialWeek.getCurrentWeek());
        require(service.loadSchedule(TERM, null).get().getWeek() == 3
                        && hasOffering(service.loadSchedule(TERM, null).get().getEntries(), 1005L),
                "缺省周次必须回退到夹具的当前周，与真服务\"缺省即当前周\"同义");
        require(service.loadSchedule(OTHER_TERM, 3).get().getEntries().isEmpty(),
                "schedule from another term must not appear");
        require(service.loadSchedule(TERM, 17).get().getEntries().isEmpty(),
                "schedule outside the active week range must not appear");
        requireImmutable(initial, "schedule results must be immutable");
        requireImmutable(initialWeek.getPeriods(), "the period dictionary must be immutable");
        service.addToPlan(term, 1001L, "schedule-plan-1001").get();
        service.selectOffering(term, 1001L, "schedule-select-1001").get();
        require(hasOffering(service.loadSchedule(TERM, 3).get().getEntries(), 1001L),
                "new enrollment must appear");
        service.dropOffering(term, 1005L, "schedule-drop-1005").get();
        require(!hasOffering(service.loadSchedule(TERM, 3).get().getEntries(), 1005L),
                "dropped course must disappear");
        List<CourseNoticeView> notices = service.loadNotices(TERM, 13).get();
        require(notices.stream().anyMatch(n -> n.getTitle().contains("数据结构")),
                "week 13 adjustment notice expected");
        require(service.loadNotices(OTHER_TERM, 13).get().isEmpty(),
                "notices from another term must not appear");
        require(service.loadNotices(TERM, 12).get().isEmpty(),
                "notices from another week must not appear");
        requireImmutable(notices, "notice results must be immutable");

        List<ScheduleEntryView> adjusted = service.loadSchedule(TERM, 13).get().getEntries();
        List<ScheduleEntryView> pair = adjusted.stream()
                .filter(entry -> entry.getOfferingId() == 1001L).toList();
        require(pair.size() == 2
                        && pair.get(0).getDisplayKind() == ScheduleDisplayKind.ADJUSTED_ORIGINAL
                        && pair.get(1).getDisplayKind() == ScheduleDisplayKind.ADJUSTED_TARGET
                        && "ADJ-1001-13".equals(pair.get(0).getAdjustmentId())
                        && pair.get(0).getAdjustmentId().equals(pair.get(1).getAdjustmentId()),
                "an adjusted week must pair one display-only original with one effective target");
        require(pair.get(0).getDayOfWeek() == 2 && pair.get(0).getStartPeriod() == 3
                        && pair.get(1).getDayOfWeek() == 5 && pair.get(1).getStartPeriod() == 3,
                "the pair must sit at the original and the adjusted coordinates, observed "
                        + pair.get(0).getDayOfWeek() + "/" + pair.get(0).getStartPeriod() + " and "
                        + pair.get(1).getDayOfWeek() + "/" + pair.get(1).getStartPeriod());
        require(pair.get(0).isActiveInWeek(13) && pair.get(1).isActiveInWeek(13)
                        && "周二 第3-4节 教四-201".equals(pair.get(0).getOriginalScheduleText())
                        && "周五 第3-4节 教四-201".equals(pair.get(0).getAdjustedScheduleText())
                        && pair.get(0).getOriginalScheduleText().equals(
                        pair.get(1).getOriginalScheduleText())
                        && pair.get(0).getAdjustedScheduleText().equals(
                        pair.get(1).getAdjustedScheduleText()),
                "both halves must be active in the adjusted week and share the detail text");

        List<ScheduleEntryView> neighbouring = service.loadSchedule(TERM, 12).get().getEntries()
                .stream().filter(entry -> entry.getOfferingId() == 1001L).toList();
        require(neighbouring.size() == 1
                        && neighbouring.get(0).getDisplayKind() == ScheduleDisplayKind.NORMAL
                        && neighbouring.get(0).getAdjustmentId() == null
                        && neighbouring.get(0).getDayOfWeek() == 2,
                "a neighbouring week must keep the single plain entry");
        System.out.println("MockCourseScheduleTest: PASS");
    }

    private static boolean hasOffering(List<ScheduleEntryView> entries, long id) {
        return entries.stream().anyMatch(entry -> entry.getOfferingId() == id);
    }

    /** 独立地重算一遍行号：节次字典里出现过哪些节次（升序去重），断言夹具本身覆盖 1..8。 */
    private static List<Integer> periodRows(ScheduleWeekView week) {
        return week.getPeriods().stream()
                .map(CoursePeriodDTO::getPeriod)
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireImmutable(List<?> values, String message) {
        try {
            values.clear();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected immutable service snapshot.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
