package dao;

import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseTermDTO;
import dto.course.GradeRecordDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.SelectionStateDTO;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

public final class CourseQueryMappingTest {
    private static final String BIG_ID = "9007199254740993";

    private CourseQueryMappingTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyOfferingGroupingAndStatePriority();
        verifyScheduleAndNoticeIdsRemainExact();
        verifyAdjustedEntriesPairAndPlainEntriesStaySingle();
        verifyNullableGradeComponentsRemainNull();
        System.out.println("Course query mapping test passed.");
    }

    /**
     * An adjusted occurrence becomes a display-only original plus an effective target sharing one
     * adjustment identity; an occurrence without an ACTIVE adjustment stays exactly one NORMAL row.
     */
    private static void verifyAdjustedEntriesPairAndPlainEntriesStaySingle() throws Exception {
        CachedRowSet rows = rows(SCHEDULE_COLUMNS, SCHEDULE_TYPES,
                scheduleRow(BIG_ID, "CS101", "Programming", "Teacher A", "Room A",
                        2, 3, 4, 1, 16, null, null, null, null, null, null, null),
                scheduleRow("2002", "CS202", "Databases", "Teacher B", "Room B",
                        2, 3, 4, 1, 16, "9007199254740999", "教师出差", 5, 3, 4,
                        "Teacher C", "Room C"));
        List<ScheduleEntryDTO> entries = CourseScheduleDAO.mapScheduleRows(rows, "2026-2027 秋学期");
        require(entries.size() == 3, "an adjusted row must expand into a pair, observed "
                + entries.size());

        // The rowset fixture does not preserve insertion order, so the two shapes are selected by
        // display kind rather than by position.
        List<ScheduleEntryDTO> plainEntries = entries.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()).toList();
        List<ScheduleEntryDTO> pairs = entries.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind()).toList();
        require(plainEntries.size() == 1 && pairs.size() == 2,
                "exactly one plain entry and one pair are expected");
        ScheduleEntryDTO plain = plainEntries.get(0);
        require(plain.getAdjustmentId() == null && plain.getOriginalScheduleText() == null
                        && plain.getAdjustedScheduleText() == null
                        && plain.getAdjustmentReason() == null,
                "an unadjusted occurrence must stay one NORMAL entry with no adjustment data");

        ScheduleEntryDTO original = pairs.get(0);
        ScheduleEntryDTO target = pairs.get(1);
        require(ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == original.getDisplayKind()
                        && ScheduleDisplayKindDTO.ADJUSTED_TARGET == target.getDisplayKind(),
                "the pair must be ordered original then target");
        require("9007199254740999".equals(original.getAdjustmentId())
                        && original.getAdjustmentId().equals(target.getAdjustmentId()),
                "both halves must share one exact adjustment identity");
        require(original.getDayOfWeek() == 2 && original.getStartPeriod() == 3
                        && original.getPeriodCount() == 2 && "Room B".equals(original.getLocation())
                        && "Teacher B".equals(original.getTeacher()),
                "the original half must keep the base plan coordinates and resources");
        require(target.getDayOfWeek() == 5 && target.getStartPeriod() == 3
                        && target.getPeriodCount() == 2 && "Room C".equals(target.getLocation())
                        && "Teacher C".equals(target.getTeacher()),
                "the target half must carry the proposed coordinates and resources");
        require("周二 第3-4节 Room B".equals(original.getOriginalScheduleText())
                        && "周五 第3-4节 Room C".equals(original.getAdjustedScheduleText()),
                "both halves must describe the original and the adjusted arrangement");
        require(original.getOriginalScheduleText().equals(target.getOriginalScheduleText())
                        && original.getAdjustedScheduleText().equals(target.getAdjustedScheduleText())
                        && "教师出差".equals(target.getAdjustmentReason()),
                "either half must be able to render the full detail text");
        require(original.getOfferingId().equals(target.getOfferingId())
                        && original.getStartWeek() == 1 && target.getEndWeek() == 16,
                "both halves must keep the offering identity and the week range");
    }

    private static void verifyOfferingGroupingAndStatePriority() throws Exception {
        String[] columns = {
                "course_id", "course_code", "course_name", "course_type", "credit",
                "credit_hours", "description", "prerequisites", "offering_id",
                "enrolled_count", "capacity", "plan_status", "failure_reason",
                "waitlist_status", "offered_at", "expires_at", "enrollment_id",
                "teacher_uid", "teacher_name", "meeting_id", "day_of_week",
                "start_period", "end_period", "start_week", "end_week", "week_pattern",
                "location", "starts_at_utc", "ends_at_utc"
        };
        int[] types = {
                Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.DECIMAL,
                Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.BIGINT,
                Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR,
                Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP, Types.BIGINT,
                Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.INTEGER,
                Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR,
                Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP
        };
        Timestamp starts = Timestamp.from(Instant.parse("2026-09-10T02:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2026-09-10T03:35:00Z"));
        Object[] common = {BIG_ID, "CS-BIG", "Exact IDs", 1, 3.0, 48, null, null};

        CachedRowSet rows = rows(columns, types,
                offering(common, BIG_ID, "FULL", "capacity", "OFFERED", starts, ends,
                        7001L, "teacher-a", "Teacher A", 8101L, 2, 1, 2, 1, 16, "1-16",
                        "Room A", starts, ends),
                offering(common, BIG_ID, "FULL", "capacity", "OFFERED", starts, ends,
                        7001L, "teacher-b", "Teacher B", 8101L, 2, 1, 2, 1, 16, "1-16",
                        "Room A", starts, ends),
                offering(common, BIG_ID, "FULL", "capacity", "OFFERED", starts, ends,
                        7001L, "teacher-a", "Teacher A", 8102L, 4, 3, 4, 1, 16, "1-16",
                        "Room B", starts, ends),
                offering(common, BIG_ID, "FULL", "capacity", "OFFERED", starts, ends,
                        7001L, "teacher-b", "Teacher B", 8102L, 4, 3, 4, 1, 16, "1-16",
                        "Room B", starts, ends),
                offering(course(1002L), 2002L, "PLANNED", null, "OFFERED", starts, ends,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null),
                offering(course(1003L), 2003L, "PLANNED", null, "WAITING", null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null),
                offering(course(1004L), 2004L, "FULL", "full", null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null),
                offering(course(1005L), 2005L, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null));

        List<CourseOfferingDTO> offerings = CourseQueryDAO.mapOfferingRows(rows);
        CourseOfferingDTO exact = find(offerings, BIG_ID);
        require(exact.getCourseId().equals(BIG_ID), "course BIGINT must remain exact");
        require(exact.getTeachers().size() == 2, "two teachers must be grouped once");
        require(exact.getMeetings().size() == 2, "two meetings must be grouped once");
        require(exact.getSelectionState() == SelectionStateDTO.ENROLLED,
                "enrollment must outrank waitlist and plan state");
        require(find(offerings, "2002").getSelectionState() == SelectionStateDTO.WAITLIST_OFFERED,
                "offered waitlist must outrank plan state");
        require(find(offerings, "2003").getSelectionState() == SelectionStateDTO.WAITLISTED,
                "waiting waitlist must outrank plan state");
        require(find(offerings, "2004").getSelectionState() == SelectionStateDTO.FULL,
                "plan FULL state must survive");
        CourseOfferingDTO available = find(offerings, "2005");
        require(available.getSelectionState() == SelectionStateDTO.AVAILABLE,
                "missing state rows must map to AVAILABLE");
        require(available.getFailureReason() == null && available.getOfferedAt() == null
                        && available.getExpiresAt() == null,
                "SQL nulls must remain null");

        rows.beforeFirst();
        CoursePlanSnapshotDTO snapshot = CourseQueryDAO.mapSnapshotRows(
                rows, new CourseTermDTO(2026, 2, "2026-2027 秋学期"));
        require(snapshot.getEnrolledItems().size() == 1, "snapshot enrolled partition");
        require(snapshot.getWaitlistItems().size() == 2, "snapshot waitlist partition");
        require(snapshot.getPlanItems().size() == 1, "snapshot plan partition");
        require(snapshot.getEnrolledItems().get(0).getCourse().getDescription() == null,
                "nullable course text must remain null");
    }

    private static void verifyScheduleAndNoticeIdsRemainExact() throws Exception {
        CachedRowSet scheduleRows = rows(SCHEDULE_COLUMNS, SCHEDULE_TYPES,
                scheduleRow(BIG_ID, "CS101", "Programming", "Teacher A", "Room A",
                        2, 1, 2, 1, 16, null, null, null, null, null, null, null));
        List<ScheduleEntryDTO> schedule = CourseScheduleDAO.mapScheduleRows(
                scheduleRows, "2026-2027 秋学期");
        require(BIG_ID.equals(schedule.get(0).getOfferingId()),
                "schedule offering BIGINT must remain exact");

        CachedRowSet noticeRows = rows(
                new String[]{"notice_id", "offering_id", "week_no", "notice_type", "title", "content"},
                new int[]{Types.BIGINT, Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR},
                new Object[]{BIG_ID, BIG_ID, null, "GENERAL", "Title", "Body"});
        List<CourseNoticeDTO> notices = CourseScheduleDAO.mapNoticeRows(
                noticeRows, "2026-2027 秋学期");
        require(BIG_ID.equals(notices.get(0).getNoticeId())
                        && BIG_ID.equals(notices.get(0).getOfferingId()),
                "notice BIGINT values must remain exact");
        require(notices.get(0).getWeek() == 0, "nullable notice week must map to unspecified");
    }

    private static void verifyNullableGradeComponentsRemainNull() throws Exception {
        CachedRowSet gradeRows = rows(
                new String[]{"course_code", "course_name", "credit", "score", "grade_point",
                        "daily_score", "midterm_score", "experiment_score", "finalterm_score"},
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.DECIMAL, Types.DECIMAL, Types.DECIMAL,
                        Types.DECIMAL, Types.DECIMAL, Types.DECIMAL, Types.DECIMAL},
                new Object[]{"CS101", "Programming", 3.0, 90.0, 4.0, null, 88.0, null, 92.0});
        List<GradeRecordDTO> grades = CourseAcademicDAO.mapGradeRows(
                gradeRows, "2026-2027 秋学期");
        require(grades.get(0).getDailyScore() == null
                        && grades.get(0).getExperimentScore() == null,
                "nullable grade components must remain null");
    }

    private static final String[] SCHEDULE_COLUMNS = {"offering_id", "course_code", "course_name",
            "teacher", "location", "day_of_week", "start_period", "end_period", "start_week",
            "end_week", "adjustment_id", "adjustment_reason", "new_weekday", "new_start_period",
            "new_end_period", "adjusted_teacher", "adjusted_location"};

    private static final int[] SCHEDULE_TYPES = {Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER,
            Types.INTEGER, Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER,
            Types.VARCHAR, Types.VARCHAR};

    /** One shape for both the plain and the adjusted row, so a fixture cannot drift per case. */
    private static Object[] scheduleRow(Object offeringId, String code, String name, String teacher,
                                        String location, int day, int start, int end, int startWeek,
                                        int endWeek, Object adjustmentId, Object reason,
                                        Object newDay, Object newStart, Object newEnd,
                                        Object adjustedTeacher, Object adjustedLocation) {
        return new Object[]{offeringId, code, name, teacher, location, day, start, end, startWeek,
                endWeek, adjustmentId, reason, newDay, newStart, newEnd, adjustedTeacher,
                adjustedLocation};
    }

    private static Object[] course(long id) {
        return new Object[]{id, "CS" + id, "Course " + id, 1, 3.0, 48, null, null};
    }

    private static Object[] offering(Object[] course, Object offeringId, String planStatus,
                                     String failureReason, String waitlistStatus,
                                     Object offeredAt, Object expiresAt, Object enrollmentId,
                                     Object teacherUid, Object teacherName, Object meetingId,
                                     Object day, Object startPeriod, Object endPeriod,
                                     Object startWeek, Object endWeek, Object weekPattern,
                                     Object location, Object startsAt, Object endsAt) {
        Object[] row = new Object[29];
        System.arraycopy(course, 0, row, 0, course.length);
        Object[] rest = {offeringId, 1, 30, planStatus, failureReason, waitlistStatus,
                offeredAt, expiresAt, enrollmentId, teacherUid, teacherName, meetingId,
                day, startPeriod, endPeriod, startWeek, endWeek, weekPattern, location,
                startsAt, endsAt};
        System.arraycopy(rest, 0, row, course.length, rest.length);
        return row;
    }

    private static CachedRowSet rows(String[] names, int[] types, Object[]... values) throws Exception {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(names.length);
        for (int i = 0; i < names.length; i++) {
            metadata.setColumnName(i + 1, names[i]);
            metadata.setColumnLabel(i + 1, names[i]);
            metadata.setColumnType(i + 1, types[i]);
        }
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(metadata);
        for (Object[] row : values) {
            rows.moveToInsertRow();
            for (int i = 0; i < row.length; i++) {
                if (row[i] == null) rows.updateNull(i + 1);
                else rows.updateObject(i + 1, row[i]);
            }
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }

    private static CourseOfferingDTO find(List<CourseOfferingDTO> offerings, String id) {
        return offerings.stream().filter(item -> id.equals(item.getOfferingId())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing offering " + id));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
