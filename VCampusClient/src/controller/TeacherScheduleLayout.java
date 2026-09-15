package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;

/**
 * 教师周课表某一天的冲突布局，是学生端 {@link ScheduleLayout} 的教师版。
 *
 * <p>刻意不复用学生端的实现：学生端消费 {@code ScheduleEntryView}（起止节次由 start+count 推出），
 * 教师端拿到的是带具体日期的 {@link TeacherScheduleEntryDTO}（起止节次已成对给出），两者的输入契约
 * 不同；本类也不依赖 {@code model.course} 下的任何视图模型。
 *
 * <p>规则与学生端保持一致：先把区间按 {@code (startPeriod, endPeriod, offeringId, occurrenceId)}
 * 排序，时间区间传递性相连的归为一个 {@link Component}，断开处另起一个；组件内为每个区间分配横向
 * lane——{@code NORMAL} 与 {@code ADJUSTED_TARGET} 是真实占用，{@code ADJUSTED_ORIGINAL} 只是画在
 * 被替换掉的旧位置上的提示层，落在第 0 lane 的最底层，既不申请新 lane，也不能把真实课程挤到额外列。
 */
final class TeacherScheduleLayout {
    private TeacherScheduleLayout() {
    }

    /** 把某一天（按教学日历的 {@code teachingWeekday}）的课次切成若干互不相连的冲突组件。 */
    static List<Component> layoutDay(List<TeacherScheduleEntryDTO> entries, int dayOfWeek,
            int firstPeriod, int lastPeriod) {
        if (firstPeriod > lastPeriod) {
            return Collections.emptyList();
        }

        List<VisibleEntry> visibleEntries = new ArrayList<>();
        for (TeacherScheduleEntryDTO entry : entries) {
            if (entry == null || entry.getDayOfWeek() != dayOfWeek) {
                continue;
            }
            int startPeriod = Math.max(firstPeriod, entry.getStartPeriod());
            int rawEndPeriod = Math.max(entry.getStartPeriod(), entry.getEndPeriod());
            int endPeriod = Math.min(lastPeriod, rawEndPeriod);
            if (startPeriod <= endPeriod) {
                visibleEntries.add(new VisibleEntry(entry, startPeriod, endPeriod));
            }
        }

        visibleEntries.sort(Comparator.comparingInt(VisibleEntry::getStartPeriod)
                .thenComparingInt(VisibleEntry::getEndPeriod)
                .thenComparing(value -> value.entry.getOfferingId(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(value -> value.entry.getOccurrenceId(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));

        // 时间段相连（含传递相连）的课次并进同一个组件，断开处另起一个。
        List<Component> components = new ArrayList<>();
        List<VisibleEntry> connectedEntries = new ArrayList<>();
        int componentEnd = Integer.MIN_VALUE;
        for (VisibleEntry entry : visibleEntries) {
            if (!connectedEntries.isEmpty() && entry.startPeriod > componentEnd) {
                components.add(createComponent(connectedEntries));
                connectedEntries = new ArrayList<>();
                componentEnd = Integer.MIN_VALUE;
            }
            connectedEntries.add(entry);
            componentEnd = Math.max(componentEnd, entry.endPeriod);
        }
        if (!connectedEntries.isEmpty()) {
            components.add(createComponent(connectedEntries));
        }
        return Collections.unmodifiableList(components);
    }

    /** 把一个组件内的课次排序并分配横向 lane，同时记录组件的节次范围与列数。 */
    private static Component createComponent(List<VisibleEntry> entries) {
        List<Integer> laneEnds = new ArrayList<>();
        List<PlacedEntry> placedEntries = new ArrayList<>();
        int componentEnd = entries.get(0).endPeriod;

        for (VisibleEntry entry : entries) {
            if (isDisplayOnlyOriginal(entry.entry)) {
                placedEntries.add(new PlacedEntry(
                        entry.entry, entry.startPeriod, entry.endPeriod, 0));
                componentEnd = Math.max(componentEnd, entry.endPeriod);
                continue;
            }
            int lane = firstAvailableLane(laneEnds, entry.startPeriod);
            if (lane == laneEnds.size()) {
                laneEnds.add(entry.endPeriod);
            } else {
                laneEnds.set(lane, entry.endPeriod);
            }
            placedEntries.add(new PlacedEntry(
                    entry.entry, entry.startPeriod, entry.endPeriod, lane));
            componentEnd = Math.max(componentEnd, entry.endPeriod);
        }
        placedEntries.sort(Comparator.comparingInt(PlacedEntry::getLane)
                .thenComparingInt(entry -> isDisplayOnlyOriginal(entry.getEntry()) ? 0 : 1)
                .thenComparing(value -> value.getEntry().getOfferingId(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(value -> value.getEntry().getOccurrenceId(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return new Component(entries.get(0).startPeriod,
                componentEnd, Math.max(1, laneEnds.size()), placedEntries);
    }

    /** {@code ADJUSTED_ORIGINAL} 只做提示，不占用任何横向列。 */
    private static boolean isDisplayOnlyOriginal(TeacherScheduleEntryDTO entry) {
        return ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == entry.getDisplayKind();
    }

    private static int firstAvailableLane(List<Integer> laneEnds, int startPeriod) {
        for (int lane = 0; lane < laneEnds.size(); lane++) {
            if (laneEnds.get(lane) < startPeriod) {
                return lane;
            }
        }
        return laneEnds.size();
    }

    /** 若干时间上相连的课次，附带节次范围、横向列数与已定位的条目。 */
    static final class Component {
        private final int startPeriod;
        private final int endPeriod;
        private final int laneCount;
        private final List<PlacedEntry> entries;

        private Component(int startPeriod, int endPeriod, int laneCount,
                List<PlacedEntry> entries) {
            this.startPeriod = startPeriod;
            this.endPeriod = endPeriod;
            this.laneCount = laneCount;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        int getStartPeriod() {
            return startPeriod;
        }

        int getEndPeriod() {
            return endPeriod;
        }

        int getLaneCount() {
            return laneCount;
        }

        List<PlacedEntry> getEntries() {
            return entries;
        }
    }

    /** 已分配 lane 的课次，节次范围已按当天的行列边界裁剪。 */
    static final class PlacedEntry {
        private final TeacherScheduleEntryDTO entry;
        private final int startPeriod;
        private final int endPeriod;
        private final int lane;

        private PlacedEntry(TeacherScheduleEntryDTO entry, int startPeriod, int endPeriod,
                int lane) {
            this.entry = entry;
            this.startPeriod = startPeriod;
            this.endPeriod = endPeriod;
            this.lane = lane;
        }

        TeacherScheduleEntryDTO getEntry() {
            return entry;
        }

        int getStartPeriod() {
            return startPeriod;
        }

        int getEndPeriod() {
            return endPeriod;
        }

        int getLane() {
            return lane;
        }
    }

    private static final class VisibleEntry {
        private final TeacherScheduleEntryDTO entry;
        private final int startPeriod;
        private final int endPeriod;

        private VisibleEntry(TeacherScheduleEntryDTO entry, int startPeriod, int endPeriod) {
            this.entry = entry;
            this.startPeriod = startPeriod;
            this.endPeriod = endPeriod;
        }

        private int getStartPeriod() {
            return startPeriod;
        }

        private int getEndPeriod() {
            return endPeriod;
        }
    }
}
