package service;

import dao.CourseAcademicDAO;
import dao.CourseQueryDAO;
import dao.CourseScheduleDAO;
import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseScheduleWeekDTO;
import dto.course.GradeSummaryDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.TrainingPlanGroupDTO;
import util.DBUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class CourseQueryMySqlTest {
    private static final String STUDENT = "query-adjust-student";
    /** 第 1 教学周周一；每个日历日期都由 (week, weekday) 从它推导。 */
    private static final String WEEK_ONE_START = "2027-09-06";
    private static final long DAY_TEMPLATE = 972451L;
    /** 第 1 周周五 2027-09-10 与第 4 周周五 2027-10-01 的日历日期行。 */
    private static final long SAME_WEEK_TARGET_DATE = 972425L;
    private static final long CROSS_WEEK_TARGET_DATE = 972446L;

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
        CourseScheduleWeekDTO seededWeek = service.loadSchedule("student-alpha", 2026, 2, 1);
        require(!seededWeek.getPeriods().isEmpty(),
                "a teaching week must publish its period dictionary");
        require(!seededWeek.getDates().isEmpty(),
                "a teaching week must publish its teaching days");
        require(seededWeek.getPeriods().stream().anyMatch(period -> period.getPeriod() == 1),
                "period 1 must be defined");
        List<ScheduleEntryDTO> seededEntries = seededWeek.getEntries();
        long pairedHalves = seededEntries.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind()).count();
        long plainMeetings = seededEntries.stream()
                .filter(entry -> ScheduleDisplayKindDTO.NORMAL == entry.getDisplayKind()).count();
        require(seededEntries.stream().allMatch(entry -> "2001".equals(entry.getOfferingId()))
                        && plainMeetings + pairedHalves / 2 == 2 && pairedHalves % 2 == 0,
                "schedule must use the selected published plan, observed " + seededEntries.size()
                        + " entries with " + pairedHalves + " paired halves");
        require(originalPositions(seededEntries).equals(List.of("2/1-2", "4/3-4")),
                "both published meetings must be present as a plain or original entry, observed "
                        + originalPositions(seededEntries));

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
     * A fully isolated term: two weekly meetings, one of which carries an ACTIVE same-week
     * adjustment in week 1. No seed row takes part, so stray adjustments in the guarded schema
     * cannot influence the result.
     *
     * <p>T6 extends the same fixture with a cross-week adjustment（第 3 周调到第 4 周）与三条通知：
     * 查询必须覆盖原周和目标周两个日期范围——原周只返回原标记、目标周只返回目标标记、中间的周次
     * 什么都不返回；关联调课申请的通知只在原周与目标周各出现一次（去重），普通通知仍只按自己的
     * week_no 出现。
     *
     * <p>本批再复用同一份日历夹具验证学生端的周导航（范围、当前周、缺省即当前周），见
     * {@link #verifyWeekNavigationFollowsTheInjectedClock()}。
     */
    private static void verifyAdjustedWeekOverlaysTheEffectiveSchedule(CourseQueryService service)
            throws Exception {
        try {
            insertAdjustmentFixtures();
            List<ScheduleEntryDTO> adjusted = service.loadSchedule(STUDENT, 2027, 3, 1)
                    .getEntries();
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

            // 跨周（972972）：原周 3 只留原标记，目标周 4 只返回目标标记，中间的周 2 什么都没有。
            List<ScheduleEntryDTO> originWeek = service.loadSchedule(STUDENT, 2027, 3, 3)
                    .getEntries();
            require(originWeek.size() == 1
                            && ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL
                            == originWeek.get(0).getDisplayKind()
                            && "972972".equals(originWeek.get(0).getAdjustmentId())
                            && originWeek.get(0).getDayOfWeek() == 2
                            && originWeek.get(0).getStartPeriod() == 1
                            && "Query Room A".equals(originWeek.get(0).getLocation()),
                    "the origin week of a cross-week move must keep only the original marker, observed "
                            + describe(originWeek));
            List<ScheduleEntryDTO> targetWeek = service.loadSchedule(STUDENT, 2027, 3, 4)
                    .getEntries();
            require(targetWeek.size() == 1
                            && ScheduleDisplayKindDTO.ADJUSTED_TARGET == targetWeek.get(0)
                            .getDisplayKind()
                            && "972972".equals(targetWeek.get(0).getAdjustmentId())
                            && targetWeek.get(0).getDayOfWeek() == 5
                            && targetWeek.get(0).getStartPeriod() == 3
                            && "Query Room A".equals(targetWeek.get(0).getLocation())
                            && "Query Teacher A".equals(targetWeek.get(0).getTeacher())
                            && "周二 第1-2节 Query Room A".equals(
                            targetWeek.get(0).getOriginalScheduleText())
                            && "周五 第3-4节 Query Room A".equals(
                            targetWeek.get(0).getAdjustedScheduleText()),
                    "the target week of a cross-week move must hold only the target marker, observed "
                            + describe(targetWeek));
            require(service.loadSchedule(STUDENT, 2027, 3, 2).getEntries().isEmpty(),
                    "the week between origin and target must stay empty, observed "
                            + describe(service.loadSchedule(STUDENT, 2027, 3, 2).getEntries()));

            // 通知：普通通知只按 week_no；调课通知按关联申请的原周/目标周去重后每周至多一条。
            require(noticeIds(service, 1).equals(List.of("972031", "972032")),
                    "week 1 must hold the plain week-1 notice and the same-week notice once, observed "
                            + noticeIds(service, 1));
            require(service.loadNotices(STUDENT, 2027, 3, 2).isEmpty(),
                    "week 2 must hold no notice, observed " + noticeIds(service, 2));
            require(noticeIds(service, 3).equals(List.of("972033"))
                            && noticeIds(service, 4).equals(List.of("972033")),
                    "the cross-week notice must appear once in its origin and target weeks, observed "
                            + noticeIds(service, 3) + " / " + noticeIds(service, 4));

            require(service.loadSchedule(STUDENT, 2027, 3, 1).getEntries().size() == 3
                            && service.loadSchedule(STUDENT, 2027, 3, 4).getEntries().size() == 1,
                    "the overlay must be stable across repeated reads");

            verifyWeekNavigationFollowsTheInjectedClock();
        } finally {
            cleanAdjustmentFixtures();
        }
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id"
                        + " BETWEEN 972971 AND 972972") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE request_id BETWEEN 972951 AND 972952") == 0
                        && count("SELECT COUNT(*) FROM course_schedule_adjustment_target"
                        + " WHERE target_id BETWEEN 972961 AND 972962") == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE notice_id BETWEEN 972031"
                        + " AND 972033") == 0
                        && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id=972301") == 0
                        && count("SELECT COUNT(*) FROM calendar_date WHERE calendar_id=972401") == 0
                        && count("SELECT COUNT(*) FROM day_template WHERE id=972451") == 0
                        && count("SELECT COUNT(*) FROM schedule_plan WHERE id=972301") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE enrollment_id=972901") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'query-adjust-%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    /**
     * 学生端的周导航（{@code minWeek}/{@code maxWeek}/{@code currentWeek} 与"缺省即当前周"）必须由
     * 教学日历与注入的时钟决定，而不是客户端常量——教师端 {@code TeacherScheduleMySqlTest} 有同形的
     * 三组断言，这里是学生端的那一份。复用上面已经建好的 2027/3 夹具：教学周 1..4、第 1 周周一
     * 2027-09-06、时区 Asia/Shanghai，因此 2027-09-15 落在第 2 周，2027-12-01 在学期之外。
     */
    private static void verifyWeekNavigationFollowsTheInjectedClock() {
        CourseQueryService inTerm = serviceWithClock("2027-09-15T02:00:00Z");
        CourseScheduleWeekDTO current = inTerm.loadSchedule(STUDENT, 2027, 3, null);
        require(current.getCurrentWeek() != null && current.getCurrentWeek() == 2,
                "2027-09-15 必须落在第 2 教学周，实际 " + current.getCurrentWeek());
        require(current.getWeek() == current.getCurrentWeek(),
                "缺省 week 必须取服务端当前周，实际 " + current.getWeek());
        require(current.getMinWeek() == 1 && current.getMaxWeek() == 4,
                "教学周范围必须来自教学日历，实际 "
                        + current.getMinWeek() + ".." + current.getMaxWeek());
        require(current.getWeek() >= current.getMinWeek()
                        && current.getWeek() <= current.getMaxWeek(),
                "解析出的周必须落在教学周范围内，实际 " + current.getWeek());
        // 非正周次与缺省同义：handler 只允许"缺省或整数"，0 不该被当成第 0 周。
        require(inTerm.loadSchedule(STUDENT, 2027, 3, 0).getWeek() == current.getWeek(),
                "week=0 必须与缺省同义");
        require(inTerm.loadSchedule(STUDENT, 2027, 3, 4).getWeek() == 4,
                "明确的周次必须原样返回，与当前周无关");

        // 学期之外：没有当前周（界面据此禁用"回到本周"），周次退回最小教学周。
        CourseQueryService outOfTerm = serviceWithClock("2027-12-01T02:00:00Z");
        CourseScheduleWeekDTO outside = outOfTerm.loadSchedule(STUDENT, 2027, 3, null);
        require(outside.getCurrentWeek() == null,
                "学期之外不得编造当前周，实际 " + outside.getCurrentWeek());
        require(outside.getWeek() == outside.getMinWeek() && outside.getWeek() == 1,
                "没有当前周时必须退回最小教学周，实际 " + outside.getWeek());
        require(outOfTerm.loadSchedule(STUDENT, 2027, 3, 4).getWeek() == 4,
                "明确的周次不受时钟影响");
    }

    /** 固定时刻的查询服务：Clock 注入让学生端的周解析不再依赖跑测试的当天日期。 */
    private static CourseQueryService serviceWithClock(String instant) {
        return new CourseQueryService(new CourseQueryDAO(), new CourseScheduleDAO(),
                new CourseAcademicDAO(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static List<String> noticeIds(CourseQueryService service, int week) {
        List<String> ids = new ArrayList<>();
        for (CourseNoticeDTO notice : service.loadNotices(STUDENT, 2027, 3, week)) {
            ids.add(notice.getNoticeId());
        }
        ids.sort(null);
        return List.copyOf(ids);
    }

    private static String describe(List<ScheduleEntryDTO> entries) {
        List<String> described = new ArrayList<>();
        for (ScheduleEntryDTO entry : entries) {
            described.add(entry.getAdjustmentId() + ":" + entry.getDisplayKind() + "@"
                    + entry.getDayOfWeek() + "/" + entry.getStartPeriod());
        }
        return described.toString();
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
        execute("INSERT INTO day_template(id,name,version) VALUES(972451,'Query adjustment day',1)");
        insertCalendarDates();
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
                + "reviewed_at,review_comment) VALUES"
                + "(972951,972201,'query-adjust-teacher-a','教师出差',2,'APPROVED',5,3,4,"
                + "'query-adjust-teacher-b',NULL,972502,'2027-09-01 01:00:00','admin-alpha',"
                + "'2027-09-01 02:00:00','同意'),"
                + "(972952,972201,'query-adjust-teacher-a','教师出差',2,'APPROVED',5,3,4,"
                + "NULL,NULL,NULL,'2027-09-01 01:00:00','admin-alpha','2027-09-01 02:00:00','同意')");
        execute("INSERT INTO course_schedule_adjustment_target(target_id,request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id,"
                + "target_calendar_date_id) VALUES"
                + "(972961,972951,972801,1,'2027-09-07 00:00:00','2027-09-07 01:35:00',"
                + "'query-adjust-teacher-a',NULL,972501," + SAME_WEEK_TARGET_DATE + "),"
                + "(972962,972952,972803,3,'2027-09-21 00:00:00','2027-09-21 01:35:00',"
                + "'query-adjust-teacher-a',NULL,972501," + CROSS_WEEK_TARGET_DATE + ")");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES"
                + "(972971,972951,972801,'2027-09-10 02:00:00','2027-09-10 03:35:00',"
                + "'query-adjust-teacher-b',NULL,972502,'ACTIVE'),"
                + "(972972,972952,972803,'2027-10-01 02:00:00','2027-10-01 03:35:00',"
                + "'query-adjust-teacher-a',NULL,972501,'ACTIVE')");
        execute("INSERT INTO course_notice(notice_id,offering_id,title,content,notice_type,week_no,"
                + "status,created_by,published_at,adjustment_request_id) VALUES"
                + "(972031,972201,'第 1 周通知','只按 week_no 出现的普通通知。','GENERAL',1,"
                + "'PUBLISHED','query-adjust-teacher-a','2027-09-01 00:00:00',NULL),"
                + "(972032,972201,'同周调课已生效','同周调课通知。','RESCHEDULED',NULL,'PUBLISHED',"
                + "'admin-alpha','2027-09-01 03:00:00',972951),"
                + "(972033,972201,'跨周调课已生效','跨周调课通知。','RESCHEDULED',NULL,'PUBLISHED',"
                + "'admin-alpha','2027-09-01 03:00:00',972952)");
    }

    /** 第 1..4 教学周、每周 7 天的日历日期；周日不是教学日。 */
    private static void insertCalendarDates() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int week = 1; week <= 4; week++) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                long id = 972420L + (week - 1) * 7L + weekday;
                String date = java.time.LocalDate.parse(WEEK_ONE_START)
                        .plusDays((week - 1) * 7L + weekday - 1L).toString();
                rows.append(rows.isEmpty() ? "" : ",").append('(').append(id).append(",972401,'")
                        .append(date).append("',").append(week).append(',').append(weekday)
                        .append(",972451,").append(weekday == 7 ? 0 : 1).append(')');
            }
        }
        execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                + "day_template_id,is_teaching_day) VALUES" + rows);
    }

    private static void cleanAdjustmentFixtures() throws Exception {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 972971 AND 972972");
        execute("DELETE FROM course_schedule_adjustment_target WHERE target_id BETWEEN 972961"
                + " AND 972962");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 972951"
                + " AND 972952");
        execute("DELETE FROM course_notice WHERE notice_id BETWEEN 972031 AND 972033");
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
        execute("DELETE FROM calendar_date WHERE calendar_id=972401");
        execute("DELETE FROM day_template WHERE id=972451");
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
