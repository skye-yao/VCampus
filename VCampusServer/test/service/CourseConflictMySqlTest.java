package service;

import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import service.CourseConflictService.Candidate;
import util.DBUtil;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Conflict-engine fixtures for the guarded {@code virtual_campus_course_test} schema. Each
 * scheduled arrangement is inserted directly so the engine is judged against rows it did not
 * write; the candidate is always an in-memory {@link Candidate}.
 */
public final class CourseConflictMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final long CALENDAR = 900001L;
    private static final long TEMPLATE = 900002L;
    private static final long PLAN = 900003L;
    private static final long ROOM_A = 900004L;
    private static final long ROOM_B = 900005L;
    private static final long ROOM_C = 900006L;
    // A DST-observing calendar: 2026-03-08 turns US clocks forward, so week 1 and week 2 of the
    // same weekday resolve to different UTC offsets.
    private static final long DAYLIGHT_CALENDAR = 900100L;
    private static final long DAYLIGHT_PLAN = 900101L;
    // A plan holding one arrangement with no slots, used to prove the publication gate sees it.
    private static final long SLOTLESS_PLAN = 900103L;

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    // Seeded test offerings: 2001 -> course 1001 (ACTIVE, capacity 30), 2004 -> course 1002.
    private static final long OFFERING_SELF = 2001L;
    private static final long OFFERING_OTHER = 2004L;

    private CourseConflictMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            CourseConflictService conflicts =
                    new CourseConflictService(new dao.AdminScheduleDAO(),
                            new dao.AdminScheduleConflictDAO());

            verifyOfferingOverlap(conflicts);
            verifyTeacherOverlap(conflicts);
            verifyAssistantOverlap(conflicts);
            verifyClassroomOverlap(conflicts);
            verifyClassroomCapacity(conflicts);
            verifyWeekRangeMerge(conflicts);
            verifyBoundaryTouch(conflicts);
            verifyEditingExcludesOwnOccurrences(conflicts);
            verifyActiveAdjustmentReplacesOriginal(conflicts);
            verifyDisabledRowsAreInvisible(conflicts);
            verifyDaylightSavingOffset(conflicts);
            verifySlotlessArrangementBlocksPublication(conflicts);
            verifyArrangementListingSkipsDisabled();
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Course conflict MySQL test passed.");
    }

    private static void verifyOfferingOverlap(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-beta", null, ROOM_C,
                        slot(2, 1, 1), 1, 1, null));
        requireOnly(result, "OFFERING_OVERLAP", BLOCKING, "self overlap");
    }

    private static void verifyTeacherOverlap(CourseConflictService conflicts) throws Exception {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", null, ROOM_C,
                        slot(1, 1, 1), 1, 1, null));
        requireOnly(result, "TEACHER_OVERLAP", OVERRIDABLE, "teacher");
        ScheduleConflictDTO overlap = result.get(0);
        require(Long.toString(OFFERING_SELF).equals(overlap.getOfferingId())
                        && offeringCode(OFFERING_SELF).equals(overlap.getOfferingLabel()),
                "overlap conflicts must name the offering they belong to, got offering="
                        + overlap.getOfferingId() + " label=" + overlap.getOfferingLabel());
    }

    private static void verifyAssistantOverlap(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", "teacher-beta", ROOM_C,
                        slot(4, 1, 1), 1, 1, null));
        requireOnly(result, "ASSISTANT_OVERLAP", OVERRIDABLE, "assistant");
    }

    private static void verifyClassroomOverlap(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                        slot(2, 1, 1), 2, 2, null));
        requireOnly(result, "CLASSROOM_OVERLAP", OVERRIDABLE, "room");
    }

    private static void verifyClassroomCapacity(CourseConflictService conflicts) throws Exception {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", null, ROOM_B,
                        slot(3, 1, 1), 2, 2, null));
        requireOnly(result, "CLASSROOM_CAPACITY", OVERRIDABLE, "capacity");
        ScheduleConflictDTO capacity = result.get(0);
        require(Long.toString(OFFERING_SELF).equals(capacity.getRelatedOfferingId())
                        && Long.toString(OFFERING_SELF).equals(capacity.getOfferingId())
                        && offeringCode(OFFERING_SELF).equals(capacity.getOfferingLabel()),
                "the capacity conflict must name its own offering, got related="
                        + capacity.getRelatedOfferingId() + " offering="
                        + capacity.getOfferingId() + " label=" + capacity.getOfferingLabel());
    }

    /** 归属标签的期望值：不硬编码种子代码，直接读受保护测试库里的真实值。 */
    private static String offeringCode(long offeringId) throws SQLException {
        return text("SELECT offering_code FROM course_offering WHERE offering_id=" + offeringId);
    }

    /**
     * 甲4：跨 N 周的一条安排会把同一条冲突按周各报一次，mergeWeekRanges 把连续周次合并成区间；
     * 但「第 1 周和第 3 周」这种不相邻的周次必须保持两条——按 min..max 合并会凭空造出第 2 周的课。
     * 引擎本身（check）不改：合并只发生在消费点，所以这里显式调用助手。
     */
    private static void verifyWeekRangeMerge(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> weekly = conflicts.check(candidate(OFFERING_SELF,
                "teacher-delta", null, ROOM_A, slot(3, 2, 2), 1, 2, null));
        require(weekly.size() == 2 && weekly.get(0).getWeek() == 1 && weekly.get(1).getWeek() == 2,
                "the engine still reports one conflict per week, got " + positions(weekly));

        List<ScheduleConflictDTO> merged = CourseConflictService.mergeWeekRanges(weekly);
        require(merged.size() == 1,
                "two contiguous weekly conflicts must merge into one range, got "
                        + positions(merged));
        ScheduleConflictDTO range = merged.get(0);
        require("TEACHER_OVERLAP".equals(range.getType()) && range.getWeek() == 1
                        && range.getEndWeek() == 2,
                "the merged conflict must span weeks 1-2, got " + range.getType() + " "
                        + range.getWeek() + "-" + range.getEndWeek());
        require(CourseConflictService.mergeWeekRanges(merged).size() == 1,
                "merging an already merged range must not split it again");

        List<ScheduleConflictDTO> gapped = conflicts.check(candidate(OFFERING_SELF,
                "teacher-epsilon", null, ROOM_A, slot(5, 2, 2), 1, 3, null));
        require(gapped.size() == 2 && gapped.get(0).getWeek() == 1 && gapped.get(1).getWeek() == 3,
                "the candidate spans 1..3 but the other arrangement only occupies 1 and 3, got "
                        + positions(gapped));
        List<ScheduleConflictDTO> separate = CourseConflictService.mergeWeekRanges(gapped);
        require(separate.size() == 2,
                "non-contiguous weeks must stay two conflicts, got " + positions(separate));
        require(separate.get(0).getWeek() == 1 && separate.get(0).getEndWeek() == 1
                        && separate.get(1).getWeek() == 3 && separate.get(1).getEndWeek() == 3,
                "each non-contiguous week is its own single-week range, got "
                        + positions(separate));
    }

    private static String positions(List<ScheduleConflictDTO> conflicts) {
        List<String> parts = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            parts.add(conflict.getType() + "@" + conflict.getWeek() + "-" + conflict.getEndWeek());
        }
        return parts.toString();
    }

    private static void verifyBoundaryTouch(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-beta", null, ROOM_C,
                        slot(4, 2, 2), 2, 2, null));
        require(result.isEmpty(), "boundary-touching slots must not overlap");
    }

    private static void verifyEditingExcludesOwnOccurrences(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                        slot(4, 1, 1), 2, 2, 910005L));
        require(result.isEmpty(), "editing must exclude the arrangement's own occurrences");
    }

    private static void verifyActiveAdjustmentReplacesOriginal(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> original =
                conflicts.check(candidate(OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                        slot(2, 3, 3), 1, 1, null));
        require(original.isEmpty(),
                "an ACTIVE adjustment must remove its original occurrence from effective checks");

        List<ScheduleConflictDTO> replacement =
                conflicts.check(candidate(OFFERING_SELF, "teacher-gamma", null, ROOM_C,
                        slot(3, 1, 1), 1, 1, null));
        requireOnly(replacement, "OFFERING_OVERLAP", BLOCKING,
                "an ACTIVE adjustment must participate at its replacement time");
    }

    private static Candidate candidate(long offeringId, String teacher, String assistant,
                                       long classroomId, ScheduleSlotDTO slot, int startWeek,
                                       int endWeek, Long arrangementId) {
        return new Candidate(PLAN, arrangementId, offeringId, teacher, assistant, classroomId,
                List.of(slot), startWeek, endWeek);
    }

    private static ScheduleSlotDTO slot(int dayOfWeek, int startPeriod, int endPeriod) {
        return new ScheduleSlotDTO(dayOfWeek, startPeriod, endPeriod);
    }

    private static void verifyDisabledRowsAreInvisible(CourseConflictService conflicts) {
        List<ScheduleConflictDTO> result =
                conflicts.check(candidate(OFFERING_SELF, "teacher-beta", null, ROOM_C,
                        slot(5, 1, 1), 1, 1, null));
        require(result.isEmpty(),
                "a DISABLED rule and arrangement must not occupy resources, got " + types(result));
    }

    private static void verifyDaylightSavingOffset(CourseConflictService conflicts) {
        // The DST arrangement's occurrence is stored at 12:00 UTC, which is 08:00 EDT on
        // 2026-03-09. A fixed UTC-5 window would put the week-2 candidate at 13:00 UTC and miss it.
        List<ScheduleConflictDTO> switched = conflicts.check(daylightCandidate(2));
        requireOnly(switched, "OFFERING_OVERLAP", BLOCKING,
                "the week after the DST switch must resolve through the new offset");

        List<ScheduleConflictDTO> standard = conflicts.check(daylightCandidate(1));
        require(standard.isEmpty(),
                "the week before the DST switch must resolve through the standard offset, got "
                        + types(standard));
    }

    private static Candidate daylightCandidate(int week) {
        return new Candidate(DAYLIGHT_PLAN, null, OFFERING_SELF, "teacher-beta", null, ROOM_C,
                List.of(slot(1, 1, 1)), week, week);
    }

    private static void verifySlotlessArrangementBlocksPublication(CourseConflictService conflicts) {
        // 读路径容忍不完整的安排，发布门不容忍——两半语义各自钉一条。
        require(conflicts.checkPlan(SLOTLESS_PLAN).isEmpty(),
                "a slotless arrangement must be skipped, not fatal, on the read path");
        expect(IllegalArgumentException.class, () -> conflicts.requirePublishable(SLOTLESS_PLAN),
                "an arrangement with no slots must not be invisible to the publication gate");
    }

    private static void verifyArrangementListingSkipsDisabled() throws Exception {
        ScheduleManagementService service =
                new ScheduleManagementService(new dao.AdminScheduleDAO(),
                        new dao.AdminScheduleConflictDAO(),
                        Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC));
        List<String> listed = new ArrayList<>();
        for (ScheduleArrangementDTO arrangement
                : service.listArrangements(Long.toString(PLAN), null)) {
            listed.add(arrangement.getArrangementId());
        }
        require(listed.size() == 8 && !listed.contains("910008"),
                "listArrangements must report only ACTIVE arrangements, got " + listed);
    }

    private static void requireOnly(List<ScheduleConflictDTO> conflicts, String expectedType,
                                    ScheduleConflictSeverityDTO expectedSeverity, String message) {
        require(conflicts.size() == 1,
                message + ": expected exactly one conflict, got " + types(conflicts));
        ScheduleConflictDTO conflict = conflicts.get(0);
        require(expectedType.equals(conflict.getType())
                        && expectedSeverity == conflict.getSeverity(),
                message + ": expected " + expectedType + " but got " + types(conflicts));
        require(conflict.getWeek() > 0 && conflict.getDayOfWeek() > 0,
                message + ": the conflict must carry the position it was found at");
    }

    private static List<String> types(List<ScheduleConflictDTO> conflicts) {
        List<String> names = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) names.add(conflict.getType());
        return names;
    }

    private static <X extends Throwable> X expect(Class<X> type, ThrowingRun action,
                                                  String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('teacher-gamma','Course Test Teacher C','x','x',1,'Engineering','Lecturer'),"
                + "('teacher-delta','Course Test Teacher D','x','x',1,'Engineering','Lecturer'),"
                + "('teacher-epsilon','Course Test Teacher E','x','x',1,'Engineering','Lecturer')");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR + ",'Conflict test calendar',"
                + "2026,3,'2026-09-07','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO day_template(id,name,version) VALUES(" + TEMPLATE
                + ",'Conflict test template',1)");
        // Contiguous periods so two adjacent slots touch exactly at a boundary instant.
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(900010," + TEMPLATE + ",1,'08:00:00','08:45:00'),"
                + "(900011," + TEMPLATE + ",2,'08:45:00','09:30:00'),"
                + "(900012," + TEMPLATE + ",3,'09:30:00','10:15:00'),"
                + "(900013," + TEMPLATE + ",4,'10:15:00','11:00:00')");
        int dateId = 900020;
        // Week 3 exists for the gap fixture: a candidate spanning 1..3 with the other arrangement
        // occupying only weeks 1 and 3 proves the merge never fills week 2 in.
        for (int week = 1; week <= 3; week++) {
            for (int day = 1; day <= 5; day++) {
                String date = LocalDate.parse("2026-09-07")
                        .plusDays((week - 1) * 7L + day - 1).toString();
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                        + "teaching_weekday,day_template_id,is_teaching_day) VALUES(" + dateId++
                        + "," + CALENDAR + ",'" + date + "'," + week + "," + day + "," + TEMPLATE
                        + ",1)");
            }
        }
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN + ",'Conflict test plan'," + CALENDAR
                + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES"
                + "(" + ROOM_A + ",'Conflict Room A',60,1),"
                + "(" + ROOM_B + ",'Conflict Room B',5,1),"
                + "(" + ROOM_C + ",'Conflict Room C',60,1)");

        // OFFERING_OVERLAP: same offering, different teacher, different room.
        scheduled(910001L, 911001L, 912001L, OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                week(1, 2), slot(2, 1, 1));
        // TEACHER_OVERLAP: different offering, same teacher. Kept on day 1, which no other
        // fixture or adjustment uses, so this window yields exactly one conflict type.
        scheduled(910002L, 911002L, 912002L, OFFERING_OTHER, "teacher-alpha", null, ROOM_A,
                week(1, 1), slot(1, 1, 1));
        // ASSISTANT_OVERLAP: different offering and teacher, same assistant.
        scheduled(910003L, 911003L, 912003L, OFFERING_OTHER, "teacher-gamma", "teacher-beta",
                ROOM_A, week(1, 4), slot(4, 1, 1));
        // CLASSROOM_OVERLAP: different offering and teacher, same room.
        scheduled(910004L, 911004L, 912004L, OFFERING_OTHER, "teacher-gamma", null, ROOM_A,
                week(2, 2), slot(2, 1, 1));
        // Boundary touch: same offering, same day, the candidate starts exactly when this ends.
        scheduled(910005L, 911005L, 912005L, OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                week(2, 4), slot(4, 1, 1));
        // ACTIVE adjustment: this original occurrence moves to week 1 day 3 period 1.
        scheduled(910006L, 911006L, 912006L, OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                week(1, 2), slot(2, 3, 3));
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,"
                + "new_end_period,new_teacher_uid,new_assistant_uid,new_classroom_id,"
                + "reviewed_by,reviewed_at) VALUES(920001," + OFFERING_SELF
                + ",'teacher-alpha','conflict fixture',1,'APPROVED',3,1,1,'teacher-beta',NULL,"
                + ROOM_B + ",'admin-alpha',NOW(6))");
        String[] moved = utc("2026-09-09", "08:00:00", "08:45:00");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(920002,920001,912006,'" + moved[0] + "','"
                + moved[1] + "','teacher-beta',NULL," + ROOM_B + ",'ACTIVE')");

        // DISABLED rule and arrangement: the same window as the disabled-check candidate.
        scheduled(910008L, 911008L, 912008L, PLAN, OFFERING_SELF, "teacher-alpha", null, ROOM_C,
                DISABLED, week(1, 5), slot(5, 1, 1));

        // Week-range merge fixtures, both on windows no other fixture uses. 910010 spans weeks 1-2
        // contiguously; 910011 occupies weeks 1 and 3 only, leaving week 2 free.
        scheduledWeeks(910010L, 911010L, 912010L, OFFERING_OTHER, "teacher-delta", ROOM_C,
                slot(3, 2, 2), 1, 2);
        scheduledWeeks(910011L, 911011L, 912012L, OFFERING_OTHER, "teacher-epsilon", ROOM_C,
                slot(5, 2, 2), 1, 3);

        // An arrangement that occupies nothing because it has no slots at all.
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + SLOTLESS_PLAN + ",'Conflict slotless plan'," + CALENDAR
                + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        insertArrangement(910009L, SLOTLESS_PLAN, OFFERING_SELF, "teacher-alpha", null, ROOM_A,
                ACTIVE);

        // DST calendar: 2026-03-08 turns US clocks forward, so 08:00 local on 2026-03-09 is
        // 12:00 UTC while 08:00 local on 2026-03-02 is 13:00 UTC.
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + DAYLIGHT_CALENDAR
                + ",'Conflict DST calendar',2027,1,'2026-03-02','America/New_York',1,'PUBLISHED')");
        execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                + "day_template_id,is_teaching_day) VALUES(900120," + DAYLIGHT_CALENDAR
                + ",'2026-03-02',1,1," + TEMPLATE + ",1),(900121," + DAYLIGHT_CALENDAR
                + ",'2026-03-09',2,1," + TEMPLATE + ",1)");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + DAYLIGHT_PLAN + ",'Conflict DST plan',"
                + DAYLIGHT_CALENDAR + ",1,'DRAFT','2026-08-01 00:00:00','2026-08-01 00:00:00')");
        scheduledAtUtc(910007L, 911007L, 912007L, DAYLIGHT_PLAN, OFFERING_SELF, "teacher-alpha",
                ROOM_A, 2, 1, "2026-03-09 12:00:00", "2026-03-09 12:45:00");
    }

    /** Inserts one arrangement in {@link #PLAN} with a single slot and its generated occurrence. */
    private static void scheduled(long arrangementId, long ruleId, long occurrenceId,
                                  long offeringId, String teacher, String assistant,
                                  long classroomId, WeekDay at, ScheduleSlotDTO slot)
            throws SQLException {
        scheduled(arrangementId, ruleId, occurrenceId, PLAN, offeringId, teacher, assistant,
                classroomId, ACTIVE, at, slot);
    }

    private static void scheduled(long arrangementId, long ruleId, long occurrenceId, long planId,
                                  long offeringId, String teacher, String assistant,
                                  long classroomId, String status, WeekDay at, ScheduleSlotDTO slot)
            throws SQLException {
        insertArrangement(arrangementId, planId, offeringId, teacher, assistant, classroomId,
                status);
        insertRule(ruleId, planId, offeringId, arrangementId, slot, status);
        insertRuleWeek(ruleId, at.week());
        String[] range = utc(at.date(), period(slot.getStartPeriod(), true),
                period(slot.getEndPeriod(), false));
        insertOccurrence(occurrenceId, ruleId, planId, range[0], range[1], at.week(),
                slot.getDayOfWeek());
    }

    /**
     * One ACTIVE arrangement in {@link #PLAN} covering exactly the given weeks — one rule, one
     * rule_week row and one occurrence per week, with occurrence ids counting up from
     * {@code occurrenceId}. Same-week gaps stay gaps.
     */
    private static void scheduledWeeks(long arrangementId, long ruleId, long occurrenceId,
                                       long offeringId, String teacher, long classroomId,
                                       ScheduleSlotDTO slot, int... weeks) throws SQLException {
        insertArrangement(arrangementId, PLAN, offeringId, teacher, null, classroomId, ACTIVE);
        insertRule(ruleId, PLAN, offeringId, arrangementId, slot, ACTIVE);
        for (int index = 0; index < weeks.length; index++) {
            insertRuleWeek(ruleId, weeks[index]);
            String[] range = utc(week(weeks[index], slot.getDayOfWeek()).date(),
                    period(slot.getStartPeriod(), true), period(slot.getEndPeriod(), false));
            insertOccurrence(occurrenceId + index, ruleId, PLAN, range[0], range[1], weeks[index],
                    slot.getDayOfWeek());
        }
    }

    /** Inserts an arrangement in another calendar whose occurrence UTC window is written literally. */
    private static void scheduledAtUtc(long arrangementId, long ruleId, long occurrenceId,
                                       long planId, long offeringId, String teacher,
                                       long classroomId, int weekNo, int weekday, String startUtc,
                                       String endUtc) throws SQLException {
        insertArrangement(arrangementId, planId, offeringId, teacher, null, classroomId, ACTIVE);
        insertRule(ruleId, planId, offeringId, arrangementId,
                new ScheduleSlotDTO(weekday, 1, 1), ACTIVE);
        insertRuleWeek(ruleId, weekNo);
        insertOccurrence(occurrenceId, ruleId, planId, startUtc, endUtc, weekNo, weekday);
    }

    private static void insertArrangement(long arrangementId, long planId, long offeringId,
                                          String teacher, String assistant, Long classroomId,
                                          String status) throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,assistant_uid,classroom_id,status,version) VALUES(" + arrangementId
                + "," + planId + "," + offeringId + "," + sql(teacher) + "," + sql(assistant) + ","
                + (classroomId == null ? "NULL" : classroomId.toString()) + ",'" + status + "',1)");
    }

    private static void insertRule(long ruleId, long planId, long offeringId, long arrangementId,
                                   ScheduleSlotDTO slot, String status) throws SQLException {
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + ruleId + "," + planId + ","
                + offeringId + "," + arrangementId + "," + slot.getDayOfWeek() + ","
                + slot.getStartPeriod() + "," + slot.getEndPeriod() + ",'" + status + "')");
    }

    private static void insertRuleWeek(long ruleId, int weekNo) throws SQLException {
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + ruleId + ","
                + weekNo + ")");
    }

    private static void insertOccurrence(long occurrenceId, long ruleId, long planId, String start,
                                         String end, int weekNo, int weekday) throws SQLException {
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + occurrenceId + "," + ruleId + "," + planId + ",'"
                + start + "','" + end + "'," + weekNo + "," + weekday + ")");
    }

    private static WeekDay week(int weekNo, int dayOfWeek) {
        LocalDate date = LocalDate.parse("2026-09-07").plusDays((weekNo - 1) * 7L + dayOfWeek - 1);
        return new WeekDay(weekNo, date.toString());
    }

    private static String period(int periodNo, boolean start) {
        return switch (periodNo) {
            case 1 -> start ? "08:00:00" : "08:45:00";
            case 2 -> start ? "08:45:00" : "09:30:00";
            case 3 -> start ? "09:30:00" : "10:15:00";
            case 4 -> start ? "10:15:00" : "11:00:00";
            default -> throw new IllegalArgumentException("no fixture period " + periodNo);
        };
    }

    private static String[] utc(String date, String from, String to) {
        return new String[] { utcText(date, from), utcText(date, to) };
    }

    private static String utcText(String date, String time) {
        Instant instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE)
                .toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace('T', ' ');
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 920000 AND 920999");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 920000"
                + " AND 920999");
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 900000 AND 900999");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 911000 AND 911999");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 900000 AND 900999");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 900000 AND 900999");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 900000 AND 900999");
        execute("DELETE FROM classroom WHERE id BETWEEN 900000 AND 900999");
        execute("DELETE FROM calendar_date WHERE calendar_id BETWEEN 900000 AND 900999");
        execute("DELETE FROM period_definition WHERE id BETWEEN 900000 AND 900999");
        execute("DELETE FROM day_template WHERE id BETWEEN 900000 AND 900999");
        execute("DELETE FROM teaching_calendar WHERE id BETWEEN 900000 AND 900999");
        execute("DELETE FROM tbl_user WHERE UID IN"
                + " ('teacher-gamma','teacher-delta','teacher-epsilon')");
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = DBUtil.class.getClassLoader()
                .getResourceAsStream("resources/db.properties")) {
            require(stream != null, "db.properties is unavailable on the runtime classpath");
            properties.load(stream);
        }
        String url = properties.getProperty("db.url");
        require(url != null && !url.isBlank(), "db.url is not configured");
        String raw = url.startsWith("jdbc:") ? url.substring(5) : url;
        String path = URI.create(raw).getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(GUARDED_DATABASE.equals(database),
                "Refusing conflict test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing conflict test outside the guarded schema");
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(text(sql));
    }

    /** Proves {@code cleanup} leaves nothing behind, so a rerun starts from the same state. */
    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_arrangement"
                + " WHERE plan_id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_rule"
                + " WHERE plan_id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_rule_week"
                + " WHERE rule_id BETWEEN 911000 AND 911999") == 0
                && count("SELECT COUNT(*) FROM course_occurrence"
                + " WHERE plan_id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment"
                + " WHERE adjustment_id BETWEEN 920000 AND 920999") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                + " WHERE request_id BETWEEN 920000 AND 920999") == 0
                && count("SELECT COUNT(*) FROM classroom WHERE id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM calendar_date"
                + " WHERE calendar_id BETWEEN 900000 AND 900999") == 0
                && count("SELECT COUNT(*) FROM tbl_user WHERE UID IN"
                + " ('teacher-gamma','teacher-delta','teacher-epsilon')") == 0,
                "cleanup must leave no fixture row behind");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record WeekDay(int week, String date) {
    }
}
