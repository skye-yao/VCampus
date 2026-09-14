package service;

import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.GradeSummaryDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.TrainingPlanGroupDTO;
import util.DBUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class CourseQueryMySqlTest {
    private static final String STUDENT = "query-adjust-student";

    private CourseQueryMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            require(result.next() && "virtual_campus_course_test".equals(result.getString(1)),
                    "Refusing query test outside virtual_campus_course_test");
        }

        CourseQueryService service = new CourseQueryService();
        require(service.listTerms("student-alpha").stream()
                        .anyMatch(term -> term.getAcademicYear() == 2026 && term.getSemester() == 2),
                "student must see seeded term");
        require(service.listCourses("student-alpha", 2026, 2).size() == 1,
                "catalog must enforce seeded major and cohort visibility");

        java.util.List<CourseOfferingDTO> offerings = service.listCourseOfferings(
                "student-alpha", 2026, 2, 1001L);
        require(offerings.size() == 2, "one course must expose two offerings");
        require(offerings.stream().allMatch(item -> "1001".equals(item.getCourseId())),
                "offering course IDs must be decimal strings");
        require(offerings.stream().filter(item -> "2001".equals(item.getOfferingId()))
                        .findFirst().orElseThrow().getMeetings().size() == 2,
                "published plan must expose both meetings");

        CoursePlanSnapshotDTO snapshot = service.loadSelectionSnapshot("student-alpha", 2026, 2);
        require(snapshot.getEnrolledItems().size() == 1
                        && snapshot.getPlanItems().size() == 1
                        && snapshot.getWaitlistItems().isEmpty(),
                "snapshot must partition seeded student state");

        // The guarded schema may already carry adjustments created outside this suite, so the
        // seeded meetings are asserted as plain-or-original entries rather than by a bare count.
        List<ScheduleEntryDTO> seededWeek = service.loadSchedule("student-alpha", 2026, 2, 1);
        long pairedHalves = seededWeek.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind()).count();
        long plainMeetings = seededWeek.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()).count();
        require(seededWeek.stream().allMatch(entry -> "2001".equals(entry.getOfferingId()))
                        && plainMeetings + pairedHalves / 2 == 2 && pairedHalves % 2 == 0,
                "schedule must use the selected published plan, observed " + seededWeek.size()
                        + " entries with " + pairedHalves + " paired halves");
        require(originalPositions(seededWeek).equals(List.of("2/1-2", "4/3-4")),
                "both published meetings must be present as a plain or original entry, observed "
                        + originalPositions(seededWeek));

        require(service.loadNotices("student-alpha", 2026, 2, 1).size() == 1,
                "notices must be published and enrollment-scoped");

        verifyAdjustedWeekOverlaysTheEffectiveSchedule(service);

        GradeSummaryDTO grades = service.loadGrades("student-beta", 2026, 2);
        require(grades.getRecords().size() == 1 && grades.getTermGpa() == 4.0,
                "published seeded grade must be summarized");
        java.util.List<TrainingPlanGroupDTO> plan = service.loadTrainingPlan("student-beta");
        require(plan.size() == 1 && plan.get(0).getCourses().size() == 2
                        && plan.get(0).getEarnedCredits() == 3.0,
                "training plan must use profile and published passing grades");

        boolean notFound = false;
        try {
            service.listCourseOfferings("student-alpha", 2026, 2, 999999L);
        } catch (CourseQueryService.NotFoundException expected) {
            notFound = true;
        }
        require(notFound, "unknown course must be distinguished from an empty offering list");
        System.out.println("Course query MySQL test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * A fully isolated term: two weekly meetings, one of which carries an ACTIVE adjustment in week
     * 1 only. Week 1 must show that meeting as an original/target pair beside the untouched plain
     * entry, and week 3 must show it as a single NORMAL entry. No seed row takes part, so stray
     * adjustments in the guarded schema cannot influence the result.
     */
    private static void verifyAdjustedWeekOverlaysTheEffectiveSchedule(CourseQueryService service)
            throws Exception {
        try {
            insertAdjustmentFixtures();
            List<ScheduleEntryDTO> adjusted = service.loadSchedule(STUDENT, 2027, 3, 1);
            require(adjusted.size() == 3,
                    "the adjusted meeting must expand into a pair next to the untouched one, observed "
                            + adjusted.size());

            List<ScheduleEntryDTO> pair = adjusted.stream()
                    .filter(entry -> ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind())
                    .toList();
            List<ScheduleEntryDTO> plain = adjusted.stream()
                    .filter(entry -> ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind())
                    .toList();
            require(pair.size() == 2 && plain.size() == 1,
                    "exactly one meeting must pair and one must stay single");

            ScheduleEntryDTO original = pair.get(0);
            ScheduleEntryDTO target = pair.get(1);
            require(ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == original.getDisplayKind()
                            && ScheduleDisplayKindDTO.ADJUSTED_TARGET == target.getDisplayKind(),
                    "the pair must be ordered original then target");
            require("972971".equals(original.getAdjustmentId())
                            && "972971".equals(target.getAdjustmentId()),
                    "both halves must carry the exact adjustment identity");
            require(original.getDayOfWeek() == 2 && original.getStartPeriod() == 1
                            && original.getPeriodCount() == 2
                            && "Query Room A".equals(original.getLocation())
                            && "Query Teacher A".equals(original.getTeacher()),
                    "the original half must keep the published plan coordinates and resources, observed "
                            + original.getDayOfWeek() + "/" + original.getStartPeriod() + "/"
                            + original.getLocation() + "/" + original.getTeacher());
            require(target.getDayOfWeek() == 5 && target.getStartPeriod() == 3
                            && target.getPeriodCount() == 2
                            && "Query Room B".equals(target.getLocation())
                            && "Query Teacher B".equals(target.getTeacher()),
                    "the target half must carry the approved coordinates and resources, observed "
                            + target.getDayOfWeek() + "/" + target.getStartPeriod() + "/"
                            + target.getLocation() + "/" + target.getTeacher());
            require("周二 第1-2节 Query Room A".equals(original.getOriginalScheduleText())
                            && "周五 第3-4节 Query Room B".equals(original.getAdjustedScheduleText())
                            && "教师出差".equals(original.getAdjustmentReason())
                            && original.getOriginalScheduleText().equals(
                            target.getOriginalScheduleText())
                            && target.getAdjustedScheduleText().equals(
                            original.getAdjustedScheduleText()),
                    "either half must describe the original and the adjusted arrangement, observed "
                            + original.getOriginalScheduleText() + " -> "
                            + original.getAdjustedScheduleText());
            require(original.getStartWeek() == 1 && target.getEndWeek() == 1,
                    "the pair must cover only the adjusted week");

            ScheduleEntryDTO untouched = plain.get(0);
            require(untouched.getAdjustmentId() == null
                            && ScheduleDisplayKindDTO.NORMAL == untouched.getDisplayKind()
                            && untouched.getDayOfWeek() == 4 && untouched.getStartPeriod() == 3
                            && "Query Room A".equals(untouched.getLocation()),
                    "the unadjusted meeting must stay one NORMAL entry");

            List<ScheduleEntryDTO> neighbouring = service.loadSchedule(STUDENT, 2027, 3, 3);
            require(neighbouring.size() == 1
                            && ScheduleDisplayKindDTO.NORMAL == neighbouring.get(0).getDisplayKind()
                            && neighbouring.get(0).getAdjustmentId() == null
                            && neighbouring.get(0).getDayOfWeek() == 2,
                    "an unadjusted week must return the single plain entry");
            require(service.loadSchedule(STUDENT, 2027, 3, 1).size() == 3,
                    "the overlay must be stable across repeated reads");
        } finally {
            cleanAdjustmentFixtures();
        }
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id=972971") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE request_id=972951") == 0
                        && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=972301") == 0
                        && count("SELECT COUNT(*) FROM schedule_plan WHERE id=972301") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE enrollment_id=972901") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'query-adjust-%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    /** The published coordinates of every meeting, whether plain or represented by its original. */
    private static List<String> originalPositions(List<ScheduleEntryDTO> entries) {
        List<String> positions = new ArrayList<>();
        for (ScheduleEntryDTO entry : entries) {
            if (ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()
                    || ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == entry.getDisplayKind()) {
                positions.add(entry.getDayOfWeek() + "/" + entry.getStartPeriod() + "-"
                        + (entry.getStartPeriod() + entry.getPeriodCount() - 1));
            }
        }
        positions.sort(null);
        return positions;
    }

    private static void insertAdjustmentFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('query-adjust-teacher-a','Query Teacher A','x','x',1,'Engineering','Professor'),"
                + "('query-adjust-teacher-b','Query Teacher B','x','x',1,'Engineering','Professor'),"
                + "('query-adjust-student','Query Student','x','x',2,'Engineering','CS')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(972101,'QRY-ADJ','Query Adjustment Course',3.00,48,1,"
                + "'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(972201,'QRY-ADJ-A',972101,2027,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES"
                + "(972201,'query-adjust-teacher-a',0)");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(972401,'Query adjustment calendar',2027,3,"
                + "'2027-09-06','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(972301,'Query adjustment plan',972401,1,'PUBLISHED',"
                + "'2027-08-01 00:00:00','2027-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=972301 WHERE id=972401");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES(972501,'Query Room A',60,1),"
                + "(972502,'Query Room B',60,1)");
        execute("INSERT INTO schedule_resource(id,resource_type,business_id,conflict_mode) VALUES"
                + "(972601,'classroom','972501','EXCLUSIVE'),"
                + "(972602,'classroom','972502','EXCLUSIVE')");
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,classroom_id,status,version) VALUES(972751,972301,972201,"
                + "'query-adjust-teacher-a',972501,'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(972711,972301,972201,972751,2,1,2,"
                + "'ACTIVE'),(972712,972301,972201,972751,4,3,4,'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(972711,1),(972711,3),"
                + "(972712,1)");
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(972801,972711,972301,'2027-09-07 00:00:00',"
                + "'2027-09-07 01:35:00',1,2),(972802,972712,972301,'2027-09-09 02:00:00',"
                + "'2027-09-09 03:35:00',1,4),(972803,972711,972301,'2027-09-21 00:00:00',"
                + "'2027-09-21 01:35:00',3,2)");
        execute("INSERT INTO resource_booking(plan_id,occurrence_id,resource_id,resource_role) VALUES"
                + "(972301,972801,972601,'CLASSROOM'),(972301,972802,972601,'CLASSROOM'),"
                + "(972301,972803,972601,'CLASSROOM')");
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(972901,972201,972101,2027,3,"
                + "'query-adjust-student',2,'2027-09-01 00:00:00')");
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,submitted_at,reviewed_by,"
                + "reviewed_at,review_comment) VALUES(972951,972201,'query-adjust-teacher-a',"
                + "'教师出差',2,'APPROVED',5,3,4,'query-adjust-teacher-b',NULL,972502,"
                + "'2027-09-01 01:00:00','admin-alpha','2027-09-01 02:00:00','同意')");
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id) VALUES"
                + "(972961,972951,972801,1,'2027-09-07 00:00:00','2027-09-07 01:35:00',"
                + "'query-adjust-teacher-a',NULL,972501)");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(972971,972951,972801,'2027-09-10 02:00:00',"
                + "'2027-09-10 03:35:00','query-adjust-teacher-b',NULL,972502,'ACTIVE')");
    }

    private static void cleanAdjustmentFixtures() throws Exception {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id=972971");
        execute("DELETE FROM course_schedule_adjustment_target WHERE target_id=972961");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id=972951");
        execute("DELETE FROM resource_booking WHERE plan_id=972301");
        execute("DELETE FROM enrollment WHERE enrollment_id=972901");
        execute("DELETE FROM course_occurrence WHERE plan_id=972301");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 972711 AND 972712");
        execute("DELETE FROM course_schedule_rule WHERE plan_id=972301");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id=972301");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=972401");
        execute("DELETE FROM schedule_plan WHERE id=972301");
        execute("DELETE FROM schedule_resource WHERE id BETWEEN 972601 AND 972602");
        execute("DELETE FROM course_offering_teacher WHERE offering_id=972201");
        execute("DELETE FROM course_offering WHERE offering_id=972201");
        execute("DELETE FROM course WHERE course_id=972101");
        execute("DELETE FROM classroom WHERE id BETWEEN 972501 AND 972502");
        execute("DELETE FROM teaching_calendar WHERE id=972401");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'query-adjust-%'");
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }
}
