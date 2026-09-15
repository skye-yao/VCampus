package dto.course.teacher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

import dto.course.ScheduleDisplayKindDTO;

/**
 * 教师课表公共契约的 JSON 保真测试。
 *
 * <p>覆盖：BIGINT 标识保持精确十进制字符串；{@code localDate} 始终是 ISO 本地日期、不是 UTC 时刻；
 * {@code timezone} 保持 IANA 名称；跨周时原/目标课次靠各自的 {@code localDate}+{@code week} 区分；
 * {@code adjustmentId} 与 {@code currentWeek} 可空且往返不变；dates/periods/entries 在构造和反序列化
 * 两条路径上都不可变；以及四类 DTO 的线格式键集合。
 */
public final class TeacherScheduleDtoJsonTest {
    private static final Gson GSON = new Gson();

    private static final String CALENDAR_ID = "9007199254740991";
    private static final String OCCURRENCE_ID = "9007199254740993";
    private static final String ADJUSTMENT_ID = "9007199254740995";
    private static final String OFFERING_ID = "9007199254740997";

    public static void main(String[] args) {
        roundTripsCalendarDate();
        roundTripsPeriod();
        roundTripsScheduleEntryWithExactIds();
        keepsLocalDateLocalAndTimezoneIana();
        distinguishesCrossWeekAdjustmentMarkers();
        keepsNullableAdjustmentIdNullAndSet();
        keepsNullableCurrentWeekNullAndSet();
        copiesCallerListsDefensively();
        constructedAndDeserializedGettersRejectMutation();
        treatsNullListsAsEmptyImmutable();
        pinsExactJsonKeySets();
        System.out.println("TeacherScheduleDtoJsonTest passed (11 scenarios)");
    }

    private static void roundTripsCalendarDate() {
        TeacherCalendarDateDTO copy = GSON.fromJson(
                GSON.toJson(new TeacherCalendarDateDTO("2026-09-14", 3, 1, true)),
                TeacherCalendarDateDTO.class);

        require("2026-09-14".equals(copy.getDate()), "calendar date must round-trip");
        require(copy.getWeek() == 3, "calendar week must round-trip");
        require(copy.getTeachingWeekday() == 1, "teaching weekday must round-trip");
        require(copy.isTeachingDay(), "teaching day flag must round-trip");

        TeacherCalendarDateDTO holiday = GSON.fromJson(
                GSON.toJson(new TeacherCalendarDateDTO("2026-10-01", 5, 4, false)),
                TeacherCalendarDateDTO.class);
        require(!holiday.isTeachingDay(), "a non-teaching day must stay false");
        require(holiday.getTeachingWeekday() == 4, "weekend weekday index must round-trip");
    }

    private static void roundTripsPeriod() {
        TeacherPeriodDTO copy = GSON.fromJson(
                GSON.toJson(new TeacherPeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00")),
                TeacherPeriodDTO.class);

        require("2026-09-14".equals(copy.getDate()), "period date must round-trip");
        require(copy.getPeriod() == 1, "period number must round-trip");
        require("08:00:00".equals(copy.getStartTime()), "period start time must round-trip");
        require("08:45:00".equals(copy.getEndTime()), "period end time must round-trip");
    }

    private static void roundTripsScheduleEntryWithExactIds() {
        TeacherScheduleEntryDTO copy = GSON.fromJson(
                GSON.toJson(adjustedEntry()), TeacherScheduleEntryDTO.class);

        require(OCCURRENCE_ID.equals(copy.getOccurrenceId()),
                "occurrence ID must stay exact beyond the JavaScript safe integer");
        require(OFFERING_ID.equals(copy.getOfferingId()), "offering ID must stay exact");
        require(ADJUSTMENT_ID.equals(copy.getAdjustmentId()), "adjustment ID must stay exact");
        require("CS203".equals(copy.getCourseCode()), "course code must round-trip");
        require("数据结构".equals(copy.getCourseName()), "UTF-8 course name must survive JSON");
        require("陈老师".equals(copy.getTeacher()), "teacher name must survive JSON");
        require("B-203".equals(copy.getLocation()), "location must round-trip");
        require(copy.getWeek() == 3 && copy.getDayOfWeek() == 1,
                "week and calendar weekday must round-trip");
        require(copy.getStartPeriod() == 1 && copy.getEndPeriod() == 2,
                "period span must round-trip");
        require(copy.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET,
                "ADJUSTED_TARGET display kind must round-trip");
        require("第3周周一第1-2节".equals(copy.getOriginalScheduleText()),
                "original schedule text must survive JSON");
        require("第3周周二第5-6节".equals(copy.getAdjustedScheduleText()),
                "adjusted schedule text must survive JSON");
        require("教师出差".equals(copy.getAdjustmentReason()),
                "adjustment reason must survive JSON");
        require(copy.isCanRequestAdjustment(), "the adjustment capability flag must round-trip");

        TeacherScheduleEntryDTO plain = GSON.fromJson(
                GSON.toJson(normalEntry()), TeacherScheduleEntryDTO.class);
        require(plain.getDisplayKind() == ScheduleDisplayKindDTO.NORMAL,
                "NORMAL display kind must round-trip");
        require(plain.isCanRequestAdjustment(),
                "a NORMAL occurrence may still be requestable");

        TeacherScheduleEntryDTO readOnly = GSON.fromJson(
                GSON.toJson(new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203",
                        "数据结构", "陈老师", "A-101", "2026-09-14", 3, 1, 1, 2,
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET, ADJUSTMENT_ID, "原时间", "新时间",
                        "教师出差", false)),
                TeacherScheduleEntryDTO.class);
        require(!readOnly.isCanRequestAdjustment(),
                "a false adjustment capability flag must stay false");
    }

    private static void keepsLocalDateLocalAndTimezoneIana() {
        TeacherScheduleEntryDTO copy = GSON.fromJson(
                GSON.toJson(normalEntry()), TeacherScheduleEntryDTO.class);
        require("2026-09-14".equals(copy.getLocalDate()),
                "localDate must stay the ISO local date of the lesson");
        require(!copy.getLocalDate().contains("T") && !copy.getLocalDate().contains("Z"),
                "localDate must never be a UTC instant");

        TeacherScheduleWeekDTO week = GSON.fromJson(
                GSON.toJson(normalWeek(8, 8)), TeacherScheduleWeekDTO.class);
        require("Asia/Shanghai".equals(week.getTimezone()),
                "timezone must stay the teaching calendar's IANA name");
        require(week.getTimezone().contains("/"),
                "timezone must remain an IANA region name, not an offset");
    }

    private static void distinguishesCrossWeekAdjustmentMarkers() {
        TeacherScheduleEntryDTO original = GSON.fromJson(
                GSON.toJson(new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203",
                        "数据结构", "陈老师", "A-101", "2026-10-26", 8, 1, 1, 2,
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, ADJUSTMENT_ID, "第8周周一第1-2节",
                        "第9周周二第3-4节", "教师出差", false)),
                TeacherScheduleEntryDTO.class);
        TeacherScheduleEntryDTO target = GSON.fromJson(
                GSON.toJson(new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203",
                        "数据结构", "陈老师", "B-203", "2026-11-03", 9, 2, 3, 4,
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET, ADJUSTMENT_ID, "第8周周一第1-2节",
                        "第9周周二第3-4节", "教师出差", false)),
                TeacherScheduleEntryDTO.class);

        require(original.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL,
                "the source week must keep the ADJUSTED_ORIGINAL marker");
        require(target.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET,
                "the target week must keep the ADJUSTED_TARGET marker");
        require(original.getWeek() == 8 && target.getWeek() == 9,
                "the two markers must stay in different weeks");
        require("2026-10-26".equals(original.getLocalDate())
                        && "2026-11-03".equals(target.getLocalDate()),
                "each cross-week marker must keep its own local date");
        require(!original.getLocalDate().equals(target.getLocalDate()),
                "cross-week markers must not collapse onto one date");
    }

    private static void keepsNullableAdjustmentIdNullAndSet() {
        TeacherScheduleEntryDTO absent = GSON.fromJson(
                GSON.toJson(new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203",
                        "数据结构", "陈老师", "A-101", "2026-09-14", 3, 1, 1, 2,
                        ScheduleDisplayKindDTO.NORMAL, null, null, null, null, true)),
                TeacherScheduleEntryDTO.class);
        require(absent.getAdjustmentId() == null,
                "an unadjusted occurrence must keep a null adjustment ID");

        TeacherScheduleEntryDTO adjusted = GSON.fromJson(
                GSON.toJson(adjustedEntry()), TeacherScheduleEntryDTO.class);
        require(ADJUSTMENT_ID.equals(adjusted.getAdjustmentId()),
                "an adjusted occurrence must keep its exact adjustment ID");
    }

    private static void keepsNullableCurrentWeekNullAndSet() {
        TeacherScheduleWeekDTO outsideTerm = GSON.fromJson(
                GSON.toJson(normalWeek(8, null)), TeacherScheduleWeekDTO.class);
        require(outsideTerm.getCurrentWeek() == null,
                "currentWeek must stay null when today is outside the term");
        require(outsideTerm.getWeek() == 8,
                "the displayed week must still fall inside the term when currentWeek is null");

        TeacherScheduleWeekDTO insideTerm = GSON.fromJson(
                GSON.toJson(normalWeek(8, 8)), TeacherScheduleWeekDTO.class);
        require(Integer.valueOf(8).equals(insideTerm.getCurrentWeek()),
                "currentWeek must round-trip when the term contains today");
    }

    private static void copiesCallerListsDefensively() {
        List<TeacherCalendarDateDTO> dates =
                new ArrayList<>(List.of(new TeacherCalendarDateDTO("2026-09-14", 8, 1, true)));
        List<TeacherPeriodDTO> periods =
                new ArrayList<>(List.of(new TeacherPeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00")));
        List<TeacherScheduleEntryDTO> entries = new ArrayList<>(List.of(normalEntry()));

        TeacherScheduleWeekDTO week = new TeacherScheduleWeekDTO(CALENDAR_ID, "Asia/Shanghai",
                8, 1, 16, 8, dates, periods, entries);
        dates.clear();
        periods.clear();
        entries.clear();

        require(week.getDates().size() == 1, "week must copy the caller's date list");
        require(week.getPeriods().size() == 1, "week must copy the caller's period list");
        require(week.getEntries().size() == 1, "week must copy the caller's entry list");
    }

    private static void constructedAndDeserializedGettersRejectMutation() {
        TeacherScheduleWeekDTO week = normalWeek(8, 8);
        requireUnmodifiable(week.getDates(), "constructed week dates");
        requireUnmodifiable(week.getPeriods(), "constructed week periods");
        requireUnmodifiable(week.getEntries(), "constructed week entries");

        TeacherScheduleWeekDTO copy = GSON.fromJson(
                GSON.toJson(normalWeek(8, 8)), TeacherScheduleWeekDTO.class);
        require(copy.getEntries().size() == 1, "deserialized week must keep its entry");
        requireUnmodifiable(copy.getDates(), "deserialized week dates");
        requireUnmodifiable(copy.getPeriods(), "deserialized week periods");
        requireUnmodifiable(copy.getEntries(), "deserialized week entries");
    }

    private static void treatsNullListsAsEmptyImmutable() {
        TeacherScheduleWeekDTO week = new TeacherScheduleWeekDTO(
                CALENDAR_ID, "Asia/Shanghai", 8, 1, 16, null, null, null, null);

        require(week.getDates() != null && week.getDates().isEmpty(),
                "a null date list must become empty, not null");
        require(week.getPeriods() != null && week.getPeriods().isEmpty(),
                "a null period list must become empty, not null");
        require(week.getEntries() != null && week.getEntries().isEmpty(),
                "a null entry list must become empty, not null");
        requireUnmodifiable(week.getDates(), "null-list week dates");
        requireUnmodifiable(week.getPeriods(), "null-list week periods");
        requireUnmodifiable(week.getEntries(), "null-list week entries");

        TeacherScheduleWeekDTO wire = GSON.fromJson(
                "{\"calendarId\":\"" + CALENDAR_ID + "\",\"timezone\":\"Asia/Shanghai\","
                        + "\"week\":8,\"minWeek\":1,\"maxWeek\":16,\"currentWeek\":null,"
                        + "\"dates\":null,\"periods\":null,\"entries\":null}",
                TeacherScheduleWeekDTO.class);
        require(wire.getEntries().isEmpty(), "an explicit JSON null entry list must become empty");
        requireUnmodifiable(wire.getEntries(), "explicit null-list week entries");
    }

    private static void pinsExactJsonKeySets() {
        requireKeySet(new TeacherCalendarDateDTO("2026-09-14", 8, 1, true), Arrays.asList(
                "date", "week", "teachingWeekday", "teachingDay"));

        requireKeySet(new TeacherPeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00"), Arrays.asList(
                "date", "period", "startTime", "endTime"));

        requireKeySet(adjustedEntry(), Arrays.asList(
                "occurrenceId", "offeringId", "courseCode", "courseName", "teacher", "location",
                "localDate", "week", "dayOfWeek", "startPeriod", "endPeriod", "displayKind",
                "adjustmentId", "originalScheduleText", "adjustedScheduleText", "adjustmentReason",
                "canRequestAdjustment"));

        requireKeySet(normalWeek(8, 8), Arrays.asList(
                "calendarId", "timezone", "week", "minWeek", "maxWeek", "currentWeek",
                "dates", "periods", "entries"));
    }

    private static TeacherScheduleEntryDTO normalEntry() {
        return new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203", "数据结构",
                "陈老师", "A-101", "2026-09-14", 3, 1, 1, 2, ScheduleDisplayKindDTO.NORMAL,
                null, null, null, null, true);
    }

    private static TeacherScheduleEntryDTO adjustedEntry() {
        return new TeacherScheduleEntryDTO(OCCURRENCE_ID, OFFERING_ID, "CS203", "数据结构",
                "陈老师", "B-203", "2026-09-14", 3, 1, 1, 2,
                ScheduleDisplayKindDTO.ADJUSTED_TARGET, ADJUSTMENT_ID,
                "第3周周一第1-2节", "第3周周二第5-6节", "教师出差", true);
    }

    private static TeacherScheduleWeekDTO normalWeek(int week, Integer currentWeek) {
        return new TeacherScheduleWeekDTO(CALENDAR_ID, "Asia/Shanghai", week, 1, 16, currentWeek,
                List.of(new TeacherCalendarDateDTO("2026-09-14", week, 1, true)),
                List.of(new TeacherPeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00")),
                List.of(normalEntry()));
    }

    private static void requireKeySet(Object sample, List<String> expected) {
        Set<String> actual =
                new LinkedHashSet<>(GSON.toJsonTree(sample).getAsJsonObject().keySet());
        Set<String> expectedSet = new LinkedHashSet<>(expected);

        require(actual.equals(expectedSet),
                "wire keys for " + sample.getClass().getSimpleName() + " must be "
                        + expectedSet + " but were " + actual);
    }

    private static <T> void requireUnmodifiable(List<T> values, String label) {
        try {
            values.add(null);
            throw new AssertionError(label + " allowed append");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
        if (!values.isEmpty()) {
            try {
                values.set(0, values.get(0));
                throw new AssertionError(label + " allowed replacement");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable contract.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
