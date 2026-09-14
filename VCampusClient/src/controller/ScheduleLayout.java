package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;

final class ScheduleLayout {
    private ScheduleLayout() {
    }

    static List<Component> layoutDay(List<ScheduleEntryView> entries, int dayOfWeek,
            int firstPeriod, int lastPeriod) {
        if (firstPeriod > lastPeriod) {
            return Collections.emptyList();
        }

        List<VisibleEntry> visibleEntries = new ArrayList<>();
        for (ScheduleEntryView entry : entries) {
            if (entry == null || entry.getDayOfWeek() != dayOfWeek) {
                continue;
            }
            int startPeriod = Math.max(firstPeriod, entry.getStartPeriod());
            long rawEndPeriod = (long) entry.getStartPeriod()
                    + Math.max(1, entry.getPeriodCount()) - 1L;
            int endPeriod = (int) Math.min(lastPeriod, rawEndPeriod);
            if (startPeriod <= endPeriod) {
                visibleEntries.add(new VisibleEntry(entry, startPeriod, endPeriod));
            }
        }
        visibleEntries.sort(Comparator.comparingInt(VisibleEntry::getStartPeriod)
                .thenComparingInt(VisibleEntry::getEndPeriod)
                .thenComparingLong(value -> value.entry.getOfferingId()));

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
                .thenComparingLong(value -> value.getEntry().getOfferingId()));
        return new Component(entries.get(0).startPeriod,
                componentEnd, Math.max(1, laneEnds.size()), placedEntries);
    }

    /**
     * 仅展示用的“原安排”占位块落在其被替换掉的旧位置上，不占用资源：它既不申请新列，也不能把
     * 真实课程挤到额外的列里，只画在第 0 列的最底层，因此必须先加入以便真实课程覆盖其上。
     */
    private static boolean isDisplayOnlyOriginal(ScheduleEntryView entry) {
        return ScheduleDisplayKind.ADJUSTED_ORIGINAL == entry.getDisplayKind();
    }

    private static int firstAvailableLane(List<Integer> laneEnds, int startPeriod) {
        for (int lane = 0; lane < laneEnds.size(); lane++) {
            if (laneEnds.get(lane) < startPeriod) {
                return lane;
            }
        }
        return laneEnds.size();
    }

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

    static final class PlacedEntry {
        private final ScheduleEntryView entry;
        private final int startPeriod;
        private final int endPeriod;
        private final int lane;

        private PlacedEntry(ScheduleEntryView entry, int startPeriod,
                int endPeriod, int lane) {
            this.entry = entry;
            this.startPeriod = startPeriod;
            this.endPeriod = endPeriod;
            this.lane = lane;
        }

        ScheduleEntryView getEntry() {
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
        private final ScheduleEntryView entry;
        private final int startPeriod;
        private final int endPeriod;

        private VisibleEntry(ScheduleEntryView entry, int startPeriod, int endPeriod) {
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
