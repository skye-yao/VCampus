package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
        return new Component(entries.get(0).startPeriod,
                componentEnd, laneEnds.size(), placedEntries);
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
