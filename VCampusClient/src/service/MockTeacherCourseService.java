package service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import course.grade.GradeCalculator;
import course.grade.GradePointScale;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;

/**
 * 无服务器预览用的确定性教师课程实现。
 *
 * <p>数据固定可复现：两个学期、一个超过一页的名单、超长姓名、一个空班和若干退课行，并返回与
 * 真实服务相同的能力字段（{@code canEditGrades}/{@code canRequestAdjustment}），因此界面可以在
 * 没有服务端与数据库的情况下被完整驱动。失败与真实 Socket 实现同形：{@code CompletableFuture}
 * 以 {@link TeacherCourseServiceException} 异常完成，而不是同步抛出。
 *
 * <p>仅供单一线程（预览界面线程）调用，内部状态不做并发保护。
 */
public final class MockTeacherCourseService implements TeacherCourseService {
    /** 排课方案状态：`course_schedule_arrangement.status` 的真实取值。 */
    private static final String ACTIVE = "ACTIVE";
    /**
     * 教学班状态：`course_offering.status` 的真实取值，由 {@code AdminOfferingDAO.statusLabel}
     * 把 TINYINT 1..4 映射而来。mock 只能用这四个之一，否则预览会显示服务端不可能发出的英文原值。
     */
    private static final String OFFERING_STATUS_OPEN = "OPEN";
    private static final String OFFERING_STATUS_NOT_OPEN = "NOT_OPEN";
    private static final String OFFERING_STATUS_STOPPED = "STOPPED";
    private static final String ENROLLED = "ENROLLED";
    private static final String DROPPED = "DROPPED";
    private static final int ENROLLED_CODE = 2;
    private static final int DROPPED_CODE = 3;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String FULL_ROSTER_OFFERING = "9007199254740993";
    /** 超过一页（size=20）的名单长度。 */
    private static final int ROSTER_LENGTH = 27;

    // 教师周课表的固定教学日历：mock 不连数据库、不读系统时钟，每个学期一份可复现日历。
    // 2025/2 的 currentWeek 为 null，用来驱动界面“今天不在该学期”的分支。
    private static final String SPRING_CALENDAR_ID = "9007199254740001";
    private static final String AUTUMN_CALENDAR_ID = "9007199254740002";
    private static final String SCHEDULE_TIMEZONE = "Asia/Shanghai";
    private static final int MIN_TEACHING_WEEK = 1;
    private static final int MAX_TEACHING_WEEK = 16;
    private static final Integer SPRING_CURRENT_WEEK = 8;
    /** “今天”不在 2025/2 学期内，因此该学期没有当前周。 */
    private static final Integer AUTUMN_CURRENT_WEEK = null;
    /** 第 1 周周一；每个 ISO 本地日期都由它按 (week, teachingWeekday) 推导。 */
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2026, 9, 7);
    private static final int CALENDAR_DAYS_PER_WEEK = 7;
    /** 日历序号 1..6 为教学日，7 不是；周末课因此仍然可见，界面不能只画五天。 */
    private static final int LAST_TEACHING_WEEKDAY = 6;
    /** 节次模板覆盖到第 13 节，不硬编码十节。 */
    private static final int PERIOD_COUNT = 13;
    private static final LocalTime FIRST_PERIOD_START = LocalTime.of(8, 0);
    private static final int PERIOD_LENGTH_MINUTES = 45;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final DateTimeFormatter PERIOD_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 课表与成绩 fixture 用到的教学班；与 {@code seedOfferings}/{@code seedEmptyOffering} 一致。 */
    private static final String INTERACTION_OFFERING = "9007199254740997";
    private static final String OPERATING_SYSTEM_OFFERING = "9007199254740995";
    private static final String AUTUMN_OFFERING = "9007199254740999";
    private static final String CROSS_WEEK_ADJUSTMENT_ID = "9301";
    private static final String SAME_WEEK_ADJUSTMENT_ID = "9302";
    private static final String TEACHER_WITH_ASSISTANT = "陈老师, 王助教";
    /**
     * 调整文案必须与服务端 {@code CourseScheduleDAO.scheduleText} 逐字符一致：
     * {@code 周X 第a-b节 地点}（地点为空时省略）。界面原样渲染，Task 5 按此断言。
     */
    private static final String CROSS_WEEK_ORIGINAL_TEXT = "周一 第1-2节 A-101";
    private static final String CROSS_WEEK_ADJUSTED_TEXT = "周三 第3-4节 B-203";
    private static final String SAME_WEEK_ORIGINAL_TEXT = "周二 第1-2节 A-101";
    private static final String SAME_WEEK_ADJUSTED_TEXT = "周五 第5-6节 B-203";
    private static final String CROSS_WEEK_REASON = "教师出差";
    private static final String SAME_WEEK_REASON = "临时调课";

    // 调课 mock 的确定性约定：申请人、时间戳、教室资源与四个状态夹具。T5 的“我的调课申请”
    // 页面与已撤销显示路径直接依赖这些值；提交/撤销会真实改变查询快照（版本递增）。
    private static final String ADJUSTMENT_APPLICANT = "00001234";
    private static final String ADJUSTMENT_TEACHER = "陈老师";
    private static final String ADJUSTMENT_ASSISTANT = "王助教";
    private static final String ADJUSTMENT_REVIEWER = "admin-alpha";
    private static final String ADJUSTMENT_SUBMITTED_AT = "2026-09-14T08:00:00Z";
    private static final String ADJUSTMENT_REVIEWED_AT = "2026-09-14T09:00:00Z";
    private static final int ADJUSTMENT_MAX_REASON = 500;
    private static final String CLASSROOM_A_101 = "8101";
    private static final String CLASSROOM_B_203 = "8103";
    private static final String CLASSROOM_C_301 = "8105";

    // 成绩 mock 的确定性约定：三个状态夹具（草稿含缺分、待审核、已驳回含更正原因）与一份配齐的
    // 权重方案；保存/提交会真实改变快照（revision 递增、状态迁移），因此界面在没有服务端时也能走完
    // “编辑 → 保存 → 提交 → 只读”的完整路径。提交的分数完整性与权重规则复用 Common 的纯计算。
    private static final String GRADE_STATE_DRAFT = "DRAFT";
    private static final String GRADE_STATE_PENDING = "PENDING";
    private static final String GRADE_STATE_REJECTED = "REJECTED";
    private static final String GRADE_SAVED_MESSAGE = "成绩草稿已保存";
    private static final String GRADE_SUBMITTED_MESSAGE = "成绩批次已提交";
    /** 被驳回批次的审核意见（管理员填写）；待审核批次还没有意见，保持 null。 */
    private static final String GRADE_REVIEW_COMMENT = "总分与平时分不一致，请核对后重新提交";
    private static final String GRADE_SUBMISSION_ID = "9601";
    /** 配齐的权重：30/20/20/30，合计 10000 万分比。 */
    private static final int[] GRADE_WEIGHTS = {3000, 2000, 2000, 3000};

    private final List<CourseTermDTO> terms = List.of(
            new CourseTermDTO(2025, 3, "2025-2026 春学期"),
            new CourseTermDTO(2025, 2, "2025-2026 秋学期"));

    private final Map<String, TeacherOfferingDTO> offerings = new LinkedHashMap<>();
    private final Map<String, TeacherOfferingDetailDTO> details = new LinkedHashMap<>();
    private final Map<String, List<TeacherRosterRowDTO>> rosters = new LinkedHashMap<>();
    private final Map<String, List<ScheduleArrangementDTO>> schedules = new LinkedHashMap<>();
    private final Map<String, AdjustmentRequestDetailDTO> adjustmentRequests = new LinkedHashMap<>();
    private final Map<String, RecordedAdjustmentOperation> adjustmentOperations = new LinkedHashMap<>();
    private final Map<String, MockGradeBook> gradeBooks = new LinkedHashMap<>();
    private final Map<String, RecordedGradeOperation> gradeOperations = new LinkedHashMap<>();
    private long nextAdjustmentRequestId = 9406;

    public MockTeacherCourseService() {
        seedOfferings();
        seedFullRoster();
        seedEmptyOffering();
        seedAutumnOffering();
        seedSchedules();
        seedAdjustmentRequests();
        seedGradeBooks();
    }

    @Override
    public CompletableFuture<List<CourseTermDTO>> listTerms() {
        return CompletableFuture.completedFuture(terms);
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
            int academicYear, int semester, String query, int page, int size) {
        try {
            String keyword = blankToNull(query);
            List<TeacherOfferingDTO> matched = new ArrayList<>();
            for (TeacherOfferingDTO offering : offerings.values()) {
                if (offering.getAcademicYear() != academicYear
                        || offering.getSemester() != semester) {
                    continue;
                }
                if (keyword != null && !matches(offering.getOfferingName(), keyword)
                        && !matches(offering.getOfferingCode(), keyword)
                        && !matches(offering.getCourseCode(), keyword)
                        && !matches(offering.getCourseName(), keyword)) {
                    continue;
                }
                matched.add(offering);
            }
            matched.sort(Comparator.comparing(TeacherOfferingDTO::getOfferingCode)
                    .thenComparing(TeacherOfferingDTO::getOfferingId));
            return CompletableFuture.completedFuture(page(matched, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
        try {
            TeacherOfferingDetailDTO detail = offeringId == null ? null : details.get(offeringId);
            if (detail == null) throw notFound("教学班不存在");
            return CompletableFuture.completedFuture(detail);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
            String offeringId, String query, Integer enrollmentStatus, int page, int size) {
        try {
            if (enrollmentStatus != null && enrollmentStatus != ENROLLED_CODE
                    && enrollmentStatus != DROPPED_CODE) {
                throw badRequest("enrollmentStatus 只接受 2（正常）或 3（退课）");
            }
            if (offeringId == null || !offerings.containsKey(offeringId)) {
                throw notFound("教学班不存在");
            }
            List<TeacherRosterRowDTO> roster = rosters.getOrDefault(offeringId, List.of());
            String keyword = blankToNull(query);
            String wanted = enrollmentStatus == null ? null
                    : enrollmentStatus == DROPPED_CODE ? DROPPED : ENROLLED;
            List<TeacherRosterRowDTO> matched = new ArrayList<>();
            for (TeacherRosterRowDTO row : roster) {
                if (wanted != null && !wanted.equals(row.getEnrollmentStatus())) {
                    continue;
                }
                if (keyword != null && !matches(row.getStudentName(), keyword)
                        && !matches(row.getStudentUid(), keyword)) {
                    continue;
                }
                matched.add(row);
            }
            matched.sort(Comparator.comparing(TeacherRosterRowDTO::getStudentUid)
                    .thenComparing(TeacherRosterRowDTO::getEnrollmentId));
            return CompletableFuture.completedFuture(page(matched, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(String offeringId) {
        try {
            if (offeringId == null || !offerings.containsKey(offeringId)) {
                throw notFound("教学班不存在");
            }
            // 没有正式排课方案的教学班返回空列表，与真实服务“无 PUBLISHED 方案即空”一致。
            List<ScheduleArrangementDTO> arrangements = schedules.get(offeringId);
            return CompletableFuture.completedFuture(
                    arrangements == null ? List.of() : List.copyOf(arrangements));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /**
     * 教师在某个教学日历周的课表。
     *
     * <p>{@code week} 缺省时按 mock 的固定“当前周”决定：在学期内用 currentWeek，不在学期内用
     * minWeek。未知学期与越界 week 分别以 NOT_FOUND / BAD_REQUEST 表达，与真实服务的映射一致。
     */
    @Override
    public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
            int academicYear, int semester, Integer week) {
        try {
            CalendarSpec calendar = calendarFor(academicYear, semester);
            if (calendar == null) {
                throw notFound("该学期暂无已发布的教学日历");
            }
            Integer selected = week != null ? week
                    : calendar.currentWeek != null ? calendar.currentWeek : MIN_TEACHING_WEEK;
            if (selected < MIN_TEACHING_WEEK || selected > MAX_TEACHING_WEEK) {
                throw badRequest("week 必须在 " + MIN_TEACHING_WEEK + ".." + MAX_TEACHING_WEEK
                        + " 之间");
            }
            return CompletableFuture.completedFuture(buildWeek(calendar, selected));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    // ------------------------------------------------------------------ 调课申请

    /**
     * 原课次的可选目标域：mock 与教师周课表共用同一教学日历，只返回教学日（周一到周六）的日期与
     * 这些日期的节次模板，教室为固定三间。日期不过滤“已过去”，与真实服务一致（由界面置灰）。
     */
    @Override
    public CompletableFuture<TeacherAdjustmentOptionsDTO> getAdjustmentOptions(
            String offeringId, String originalOccurrenceId) {
        try {
            TeacherOfferingDTO offering = offeringId == null ? null : offerings.get(offeringId);
            if (offering == null) throw forbidden("没有该教学班的调课权限");
            requireOccurrence(offeringId, originalOccurrenceId);
            CalendarSpec calendar = calendarFor(offering.getAcademicYear(), offering.getSemester());
            if (calendar == null) throw notFound("该学期暂无已发布的教学日历");
            List<TeacherCalendarDateDTO> dates = new ArrayList<>();
            List<TeacherPeriodDTO> periods = new ArrayList<>();
            for (int week = MIN_TEACHING_WEEK; week <= MAX_TEACHING_WEEK; week++) {
                for (int weekday = 1; weekday <= LAST_TEACHING_WEEKDAY; weekday++) {
                    String date = localDate(week, weekday);
                    dates.add(new TeacherCalendarDateDTO(date, week, weekday, true));
                    for (int period = 1; period <= PERIOD_COUNT; period++) {
                        LocalTime start = FIRST_PERIOD_START
                                .plusMinutes((long) (period - 1) * PERIOD_INTERVAL_MINUTES);
                        periods.add(new TeacherPeriodDTO(date, period,
                                start.format(PERIOD_TIME),
                                start.plusMinutes(PERIOD_LENGTH_MINUTES).format(PERIOD_TIME)));
                    }
                }
            }
            return CompletableFuture.completedFuture(new TeacherAdjustmentOptionsDTO(
                    calendar.calendarId(), SCHEDULE_TIMEZONE, dates, periods,
                    List.of(room(CLASSROOM_A_101), room(CLASSROOM_B_203), room(CLASSROOM_C_301))));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /**
     * 纯预检查：不做写操作、忽略 operationId、不要求原因。结果区分同周与跨周——目标落在教师已有
     * 课的时间段（且不是被调课次自己）报告 BLOCKING TEACHER_OVERLAP，非教学日报告 SLOT_INVALID，
     * 其余可提交。
     */
    @Override
    public CompletableFuture<TeacherAdjustmentPreviewDTO> previewAdjustment(
            TeacherAdjustmentWriteDTO write) {
        try {
            Assessment assessment = assessAdjustment(write, false);
            return CompletableFuture.completedFuture(new TeacherAdjustmentPreviewDTO(
                    assessment.conflicts(), assessment.conflicts().isEmpty()));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /**
     * 提交：与预览同一套校验，但要求 UUID operationId 与非空原因；任何冲突都拒绝（教师没有 force）。
     * 成功后写入一条 PENDING（version=1）申请，查询快照立即改变；同 operationId 同内容重放。
     */
    @Override
    public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
            submitAdjustment(TeacherAdjustmentWriteDTO write) {
        try {
            String operationId = requireOperationId(write == null ? null : write.getOperationId());
            String digest = adjustmentDigest("submit", write);
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> stored =
                    replayAdjustment(operationId, digest);
            if (stored != null) return CompletableFuture.completedFuture(stored);
            Assessment assessment = assessAdjustment(write, true);
            if (!assessment.conflicts().isEmpty()) {
                throw conflict("存在冲突，无法提交调课申请", null, assessment.conflicts());
            }
            AdjustmentRequestDetailDTO created = new AdjustmentRequestDetailDTO(
                    Long.toString(nextAdjustmentRequestId++),
                    requiredDecimal(write.getOfferingId(), "offeringId"),
                    ADJUSTMENT_APPLICANT, blankToNull(write.getReason()),
                    AdjustmentRequestStatusDTO.PENDING, 1, assessment.newWeekday(),
                    write.getNewStartPeriod(), write.getNewEndPeriod(), null, null,
                    classroomResource(blankToNull(write.getNewClassroomId())),
                    assessment.targets(), List.of(), ADJUSTMENT_SUBMITTED_AT, null, null, null);
            adjustmentRequests.put(created.getRequestId(), created);
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                    new TeacherOperationResultDTO<>(operationId, "调课申请已提交", created, false);
            adjustmentOperations.put(operationId, new RecordedAdjustmentOperation(digest, result));
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /**
     * 撤销：只允许本人 PENDING 且版本匹配；成功把状态改为 WITHDRAWN、version 递增并刷新查询快照。
     * 终态或版本过期抛 CONFLICT（携带最新详情），未知申请抛 NOT_FOUND，同 operationId 重放。
     */
    @Override
    public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
            withdrawAdjustment(WithdrawTeacherAdjustmentRequestDTO withdrawal) {
        try {
            String operationId = requireOperationId(withdrawal == null ? null
                    : withdrawal.getOperationId());
            String requestId = withdrawal == null ? null
                    : requiredDecimal(withdrawal.getRequestId(), "requestId");
            int expectedVersion = withdrawal == null ? 0 : withdrawal.getExpectedVersion();
            if (expectedVersion <= 0) throw badRequest("expectedVersion 必须为正整数");
            String digest = "withdraw|" + requestId + "|" + expectedVersion;
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> stored =
                    replayAdjustment(operationId, digest);
            if (stored != null) return CompletableFuture.completedFuture(stored);

            AdjustmentRequestDetailDTO current = adjustmentRequests.get(requestId);
            if (current == null) throw notFound("调课申请不存在");
            if (current.getStatus() != AdjustmentRequestStatusDTO.PENDING) {
                throw conflict("调课申请已被处理，请刷新后重试", current, List.of());
            }
            if (current.getVersion() != expectedVersion) {
                throw conflict("调课申请版本已变化，请刷新后重试", current, List.of());
            }
            AdjustmentRequestDetailDTO withdrawn = new AdjustmentRequestDetailDTO(
                    current.getRequestId(), current.getOfferingId(), current.getApplicantUid(),
                    current.getReason(), AdjustmentRequestStatusDTO.WITHDRAWN,
                    current.getVersion() + 1, current.getNewDayOfWeek(), current.getNewStartPeriod(),
                    current.getNewEndPeriod(), current.getNewTeacher(), current.getNewAssistant(),
                    current.getNewClassroom(), current.getTargets(), List.of(),
                    current.getSubmittedAt(), null, null, null);
            adjustmentRequests.put(withdrawn.getRequestId(), withdrawn);
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                    new TeacherOperationResultDTO<>(operationId, "调课申请已撤销", withdrawn, false);
            adjustmentOperations.put(operationId, new RecordedAdjustmentOperation(digest, result));
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
        try {
            String id = requiredDecimal(requestId, "requestId");
            AdjustmentRequestDetailDTO request = adjustmentRequests.get(id);
            if (request == null) throw notFound("调课申请不存在");
            return CompletableFuture.completedFuture(request);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /** 我的申请：只含 mock 自己的申请，按提交时间倒序；status 缺省与真实服务一样按 PENDING。 */
    @Override
    public CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>> listMyAdjustmentRequests(
            AdjustmentRequestStatusDTO status, int page, int size) {
        try {
            AdjustmentRequestStatusDTO filter = status == null
                    ? AdjustmentRequestStatusDTO.PENDING : status;
            List<AdjustmentRequestSummaryDTO> matching = new ArrayList<>();
            for (AdjustmentRequestDetailDTO request : adjustmentRequests.values()) {
                if (request.getStatus() == filter) matching.add(adjustmentSummary(request));
            }
            matching.sort(Comparator.comparing(AdjustmentRequestSummaryDTO::getSubmittedAt)
                    .reversed()
                    .thenComparing(AdjustmentRequestSummaryDTO::getRequestId,
                            Comparator.reverseOrder()));
            return CompletableFuture.completedFuture(page(matching, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    // ------------------------------------------------------------------ 成绩工作副本

    /** 本人任课教学班的成绩列表：只含 role=0 的教学班，未建草稿的班以虚拟草稿（revision=0）出现。 */
    @Override
    public CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
            int academicYear, int semester, int page, int size) {
        try {
            List<TeacherGradeOfferingDTO> matched = new ArrayList<>();
            for (TeacherOfferingDTO offering : offerings.values()) {
                if (offering.getAcademicYear() != academicYear
                        || offering.getSemester() != semester) {
                    continue;
                }
                MockGradeBook book = gradeBooks.get(offering.getOfferingId());
                List<GradeScoresDTO> scores = book == null
                        ? List.of() : book.scores(rosterOf(offering.getOfferingId()));
                GradeSchemeDTO scheme = book == null ? defaultGradeScheme() : book.scheme();
                int entered = 0;
                int missing = 0;
                for (GradeScoresDTO rowScores : scores) {
                    if (!blankScores(rowScores)) entered++;
                    if (missingEnabledScores(scheme, rowScores)) missing++;
                }
                matched.add(new TeacherGradeOfferingDTO(offering, stateOf(book), entered, missing,
                        book == null ? null : book.lastSubmissionId()));
            }
            matched.sort(Comparator.comparing(item -> item.getOffering().getOfferingCode()));
            return CompletableFuture.completedFuture(page(matched, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
        try {
            String id = requireGradeOffering(offeringId);
            return CompletableFuture.completedFuture(snapshotOf(id));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
            WriteGradeBookRequestDTO write) {
        return gradeWrite(TeacherCourseActions.SAVE_GRADE_DRAFT, write, false);
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
            WriteGradeBookRequestDTO write) {
        return gradeWrite(TeacherCourseActions.SUBMIT_GRADE_BOOK, write, true);
    }

    /**
     * 保存/提交共用一条写路径：校验 operationId → 幂等重放 → 版本与名单摘要 → 内容 → 落库到内存快照。
     * 版本、名单摘要与内容校验的顺序与真实服务一致，客户端因此能在本地看到同一批冲突文案。
     */
    private CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> gradeWrite(String action,
            WriteGradeBookRequestDTO write, boolean submit) {
        try {
            if (write == null || write.getContent() == null) {
                throw badRequest("请求体不能为空");
            }
            String operationId = requireOperationId(write.getOperationId());
            String digest = gradeDigest(action, write.getContent());
            RecordedGradeOperation stored = gradeOperations.get(operationId);
            if (stored != null) {
                if (!stored.digest().equals(digest)) {
                    throw gradeConflict("operationId 已用于不同的成绩写入请求", null);
                }
                TeacherOperationResultDTO<TeacherGradeBookDTO> first = stored.result();
                return CompletableFuture.completedFuture(new TeacherOperationResultDTO<>(
                        first.getOperationId(), first.getMessage(), first.getValue(), true));
            }

            GradeBookContentDTO content = write.getContent();
            String offeringId = requireGradeOffering(content.getOfferingId());
            MockGradeBook book = requireBookForWrite(offeringId);
            if (!book.rosterDigest().equals(content.getRosterDigest())) {
                throw gradeConflict("名单已变化，请重新加载成绩表并合并已输入的成绩",
                        snapshotOf(offeringId));
            }
            int expectedRevision = content.getExpectedRevision();
            if (book.revision() != expectedRevision) {
                throw gradeConflict("成绩草稿版本已变化，请重新加载后重试", snapshotOf(offeringId));
            }
            GradeSchemeDTO scheme = content.getScheme() == null
                    ? defaultGradeScheme() : content.getScheme();
            try {
                GradeCalculator.validateScheme(scheme, submit);
            } catch (IllegalArgumentException invalid) {
                throw badRequest(invalid.getMessage());
            }
            List<String> enrolled = normalEnrollmentIds(offeringId);
            Map<String, GradeScoresDTO> previous = book.scoresByEnrollment();
            Map<String, GradeScoresDTO> updated = new LinkedHashMap<>();
            for (GradeRowInputDTO row : content.getRows()) {
                String enrollmentId = requiredDecimal(row.getEnrollmentId(), "enrollmentId");
                if (!enrolled.contains(enrollmentId)) {
                    throw badRequest("学生不在本教学班当前名单中: " + enrollmentId);
                }
                GradeScoresDTO scores = row.getScores() == null
                        ? blankScoresDto() : row.getScores();
                try {
                    GradeCalculator.validateScores(scores);
                } catch (IllegalArgumentException invalid) {
                    throw badRequest(invalid.getMessage());
                }
                // 禁用组成不是“清空”：请求里没有值时保留草稿里的旧值，重新启用即可恢复。
                updated.put(enrollmentId, mergeDisabled(scores, previous.get(enrollmentId), scheme));
            }
            if (submit) {
                for (String enrollmentId : enrolled) {
                    GradeScoresDTO scores = updated.get(enrollmentId);
                    if (missingEnabledScores(scheme, scores)) {
                        throw badRequest("提交成绩前必须补齐所有启用组成的成绩，缺少学生: "
                                + studentUidOf(offeringId, enrollmentId));
                    }
                }
                book.submit(scheme, updated);
            } else {
                book.save(scheme, updated);
            }

            TeacherOperationResultDTO<TeacherGradeBookDTO> result =
                    new TeacherOperationResultDTO<>(operationId,
                            submit ? GRADE_SUBMITTED_MESSAGE : GRADE_SAVED_MESSAGE,
                            snapshotOf(offeringId), false);
            gradeOperations.put(operationId, new RecordedGradeOperation(digest, result));
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /** 教学班必须属于 mock 的任课范围，且未建草稿时按 virtual 草稿处理。 */
    private String requireGradeOffering(String offeringId) {
        String id = requiredDecimal(offeringId, "offeringId");
        if (!offerings.containsKey(id)) throw forbidden("没有该教学班的成绩录入权限");
        return id;
    }

    /** 没有工作副本时按服务端的“虚拟草稿”语义现建一份：revision=0、不落库、名单摘要当场计算。 */
    private MockGradeBook requireBookForWrite(String offeringId) {
        MockGradeBook book = gradeBooks.get(offeringId);
        if (book == null) {
            book = new MockGradeBook(offeringId, defaultGradeScheme(), 0);
            book.rosterDigest(digestOf(normalEnrollmentIds(offeringId)));
            gradeBooks.put(offeringId, book);
        }
        return book;
    }

    /** 当前快照：名单摘要、行（含服务端重算的总评/绩点）与状态位，全部按当前内存状态生成。 */
    private TeacherGradeBookDTO snapshotOf(String offeringId) {
        MockGradeBook book = gradeBooks.get(offeringId);
        GradeSchemeDTO scheme = book == null ? defaultGradeScheme() : book.scheme();
        List<TeacherRosterRowDTO> roster = rosterOf(offeringId);
        String rosterDigest = digestOf(enrollmentIdsOf(roster));
        // 名单摘要随每次读取刷新：写请求比对的是“客户端看到的名单”，与真实服务一致。
        if (book != null) book.rosterDigest(rosterDigest);
        List<TeacherGradeRowDTO> rows = new ArrayList<>();
        for (TeacherRosterRowDTO student : roster) {
            GradeScoresDTO scores = book == null ? blankScoresDto()
                    : book.scores().get(student.getEnrollmentId());
            rows.add(gradeRow(student, scheme, scores == null ? blankScoresDto() : scores));
        }
        return new TeacherGradeBookDTO(offeringId, book == null ? 0 : book.revision(),
                rosterDigest, stateOf(book), scheme, rows,
                book == null ? null : book.lastSubmissionId(), null,
                book == null || book.canEdit(),
                book == null ? null : book.correctionReason(), false,
                book == null ? null : book.reviewComment());
    }

    /** 总评与绩点用与服务端同一份纯计算；缺分或权重未配齐时保持 null，不编造总评。 */
    private static TeacherGradeRowDTO gradeRow(TeacherRosterRowDTO student, GradeSchemeDTO scheme,
            GradeScoresDTO scores) {
        BigDecimal total = null;
        List<String> errors = new ArrayList<>();
        try {
            total = GradeCalculator.total(scheme, scores);
        } catch (IllegalArgumentException broken) {
            errors.add(broken.getMessage());
        }
        BigDecimal point = total == null ? null : GradePointScale.gradePointFor(total);
        return new TeacherGradeRowDTO(student.getEnrollmentId(), student.getStudentUid(),
                student.getStudentName(), scores, total, point, total != null, errors);
    }

    private static String stateOf(MockGradeBook book) {
        return book == null ? GRADE_STATE_DRAFT : book.state();
    }

    /** 正常修读名单：退课历史只读保留，不参与成绩编辑。 */
    private List<TeacherRosterRowDTO> rosterOf(String offeringId) {
        List<TeacherRosterRowDTO> enrolled = new ArrayList<>();
        for (TeacherRosterRowDTO row : rosters.getOrDefault(offeringId, List.of())) {
            if (ENROLLED.equals(row.getEnrollmentStatus())) enrolled.add(row);
        }
        return enrolled;
    }

    private List<String> normalEnrollmentIds(String offeringId) {
        return enrollmentIdsOf(rosterOf(offeringId));
    }

    private static List<String> enrollmentIdsOf(List<TeacherRosterRowDTO> roster) {
        List<String> ids = new ArrayList<>();
        for (TeacherRosterRowDTO row : roster) ids.add(row.getEnrollmentId());
        return ids;
    }

    private String studentUidOf(String offeringId, String enrollmentId) {
        for (TeacherRosterRowDTO row : rosters.getOrDefault(offeringId, List.of())) {
            if (enrollmentId.equals(row.getEnrollmentId())) return row.getStudentUid();
        }
        return enrollmentId;
    }

    private static GradeSchemeDTO defaultGradeScheme() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            components.add(new GradeComponentDTO(code, true, 0));
        }
        return new GradeSchemeDTO(components);
    }

    private static GradeSchemeDTO weightedScheme(int[] weights) {
        List<GradeComponentDTO> components = new ArrayList<>();
        GradeComponentCodeDTO[] codes = GradeComponentCodeDTO.values();
        for (int index = 0; index < codes.length; index++) {
            components.add(new GradeComponentDTO(codes[index], true, weights[index]));
        }
        return new GradeSchemeDTO(components);
    }

    /**
     * 禁用项保持草稿里的旧值：请求里给 null 时沿用已存值，给新值时才覆盖。
     * 与 {@code TeacherGradeBookService.mergeDisabled} 同一语义，草稿保存不会误清禁用列。
     */
    private static GradeScoresDTO mergeDisabled(GradeScoresDTO incoming, GradeScoresDTO stored,
            GradeSchemeDTO scheme) {
        if (stored == null) return incoming;
        return new GradeScoresDTO(
                keepScore(GradeComponentCodeDTO.DAILY, incoming.getDailyScore(),
                        stored.getDailyScore(), scheme),
                keepScore(GradeComponentCodeDTO.MIDTERM, incoming.getMidtermScore(),
                        stored.getMidtermScore(), scheme),
                keepScore(GradeComponentCodeDTO.EXPERIMENT, incoming.getExperimentScore(),
                        stored.getExperimentScore(), scheme),
                keepScore(GradeComponentCodeDTO.FINALTERM, incoming.getFinaltermScore(),
                        stored.getFinaltermScore(), scheme));
    }

    private static BigDecimal keepScore(GradeComponentCodeDTO code, BigDecimal incoming,
            BigDecimal stored, GradeSchemeDTO scheme) {
        if (incoming != null) return incoming;
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (component.getCode() == code && !component.isEnabled()) return stored;
        }
        return null;
    }

    private static boolean missingEnabledScores(GradeSchemeDTO scheme, GradeScoresDTO scores) {
        if (scores == null) return false;
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (!component.isEnabled()) continue;
            BigDecimal score = switch (component.getCode()) {
                case DAILY -> scores.getDailyScore();
                case MIDTERM -> scores.getMidtermScore();
                case EXPERIMENT -> scores.getExperimentScore();
                case FINALTERM -> scores.getFinaltermScore();
            };
            if (score == null) return true;
        }
        return false;
    }

    private static boolean blankScores(GradeScoresDTO scores) {
        return scores == null || (scores.getDailyScore() == null && scores.getMidtermScore() == null
                && scores.getExperimentScore() == null && scores.getFinaltermScore() == null);
    }

    private static GradeScoresDTO blankScoresDto() {
        return new GradeScoresDTO(null, null, null, null);
    }

    /** 名单摘要：与服务端同形的 64 位小写十六进制 SHA-256（排序后逗号拼接）。 */
    private static String digestOf(List<String> enrollmentIds) {
        List<String> sorted = new ArrayList<>(enrollmentIds);
        sorted.sort(Comparator.naturalOrder());
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(
                    String.join(",", sorted).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    /** 写请求摘要：动作 + 规范化内容，用于“同一 operationId 换了内容”的冲突判定。 */
    private static String gradeDigest(String action, GradeBookContentDTO content) {
        List<String> rows = new ArrayList<>();
        for (GradeRowInputDTO row : content.getRows()) {
            rows.add(row.getEnrollmentId() + "@" + scoresText(row.getScores()));
        }
        rows.sort(Comparator.naturalOrder());
        return action + "|" + content.getOfferingId() + "|" + content.getExpectedRevision() + "|"
                + content.getRosterDigest() + "|" + schemeText(content.getScheme()) + "|" + rows;
    }

    private static String schemeText(GradeSchemeDTO scheme) {
        if (scheme == null) return "default";
        List<String> parts = new ArrayList<>();
        for (GradeComponentDTO component : scheme.getComponents()) {
            parts.add(component.getCode() + "=" + component.isEnabled() + ":"
                    + component.getWeightBasisPoints());
        }
        return String.join(",", parts);
    }

    private static String scoresText(GradeScoresDTO scores) {
        if (scores == null) return "blank";
        return scores.getDailyScore() + "," + scores.getMidtermScore() + ","
                + scores.getExperimentScore() + "," + scores.getFinaltermScore();
    }

    /** 成绩写操作的幂等记录：同 ID 同内容重放，同 ID 不同内容冲突。 */
    private record RecordedGradeOperation(String digest,
            TeacherOperationResultDTO<TeacherGradeBookDTO> result) {
    }

    /** 内存工作副本：方案、版本、状态与每个学生的当前分数。 */
    private static final class MockGradeBook {
        private final String offeringId;
        private final Map<String, GradeScoresDTO> scores = new LinkedHashMap<>();
        private GradeSchemeDTO scheme;
        private int revision;
        private String state = GRADE_STATE_DRAFT;
        private boolean canEdit = true;
        private String correctionReason;
        private String lastSubmissionId;
        private String reviewComment;
        private String rosterDigest = "";

        private MockGradeBook(String offeringId, GradeSchemeDTO scheme, int revision) {
            this.offeringId = offeringId;
            this.scheme = scheme;
            this.revision = revision;
        }

        private GradeSchemeDTO scheme() {
            return scheme;
        }

        private int revision() {
            return revision;
        }

        private String state() {
            return state;
        }

        private boolean canEdit() {
            return canEdit;
        }

        private String correctionReason() {
            return correctionReason;
        }

        private String lastSubmissionId() {
            return lastSubmissionId;
        }

        private String reviewComment() {
            return reviewComment;
        }

        private String rosterDigest() {
            return rosterDigest;
        }

        private Map<String, GradeScoresDTO> scores() {
            return scores;
        }

        private Map<String, GradeScoresDTO> scoresByEnrollment() {
            return new LinkedHashMap<>(scores);
        }

        /** 名单摘要由读取方在每次快照时刷新；写请求比对的是它。 */
        private void rosterDigest(String digest) {
            this.rosterDigest = digest;
        }

        private List<GradeScoresDTO> scores(List<TeacherRosterRowDTO> roster) {
            List<GradeScoresDTO> values = new ArrayList<>();
            for (TeacherRosterRowDTO student : roster) {
                GradeScoresDTO stored = scores.get(student.getEnrollmentId());
                values.add(stored == null ? blankScoresDto() : stored);
            }
            return values;
        }

        private void save(GradeSchemeDTO scheme, Map<String, GradeScoresDTO> updated) {
            this.scheme = scheme;
            this.scores.putAll(updated);
            this.revision = revision + 1;
            this.state = GRADE_STATE_DRAFT;
            this.canEdit = true;
        }

        private void submit(GradeSchemeDTO scheme, Map<String, GradeScoresDTO> updated) {
            this.scheme = scheme;
            this.scores.putAll(updated);
            this.revision = revision + 1;
            this.state = GRADE_STATE_PENDING;
            this.canEdit = false;
            this.lastSubmissionId = GRADE_SUBMISSION_ID;
        }
    }

    /**
     * 成绩夹具：一个可编辑草稿（权重配齐但每五人缺一个实验分，总评因此显示占位符）、
     * 一个已提交待审核（只读、分数完整）、一个被驳回（只读状态显示更正原因、可继续编辑），
     * 操作系统教学班（空班）没有工作副本，走 revision=0 的虚拟草稿路径。
     */
    private void seedGradeBooks() {
        List<TeacherRosterRowDTO> fullRoster = rosterOf(FULL_ROSTER_OFFERING);
        Map<String, GradeScoresDTO> draftScores = new LinkedHashMap<>();
        for (int index = 0; index < fullRoster.size(); index++) {
            draftScores.put(fullRoster.get(index).getEnrollmentId(), new GradeScoresDTO(
                    BigDecimal.valueOf(70 + index % 20),
                    BigDecimal.valueOf(65 + index % 25),
                    index % 5 == 3 ? null : BigDecimal.valueOf(75 + index % 15),
                    BigDecimal.valueOf(80 - index % 10)));
        }
        MockGradeBook draft = new MockGradeBook(FULL_ROSTER_OFFERING, weightedScheme(GRADE_WEIGHTS),
                3);
        draft.rosterDigest(digestOf(normalEnrollmentIds(FULL_ROSTER_OFFERING)));
        draft.save(weightedScheme(GRADE_WEIGHTS), draftScores);
        gradeBooks.put(draft.offeringId, draft);

        MockGradeBook submitted = new MockGradeBook(INTERACTION_OFFERING,
                weightedScheme(GRADE_WEIGHTS), 3);
        submitted.rosterDigest(digestOf(normalEnrollmentIds(INTERACTION_OFFERING)));
        submitted.save(weightedScheme(GRADE_WEIGHTS),
                completeScores(INTERACTION_OFFERING, 5));
        submitted.submit(weightedScheme(GRADE_WEIGHTS), Map.of());
        gradeBooks.put(submitted.offeringId, submitted);

        MockGradeBook rejected = new MockGradeBook(AUTUMN_OFFERING,
                weightedScheme(GRADE_WEIGHTS), 4);
        rejected.rosterDigest(digestOf(normalEnrollmentIds(AUTUMN_OFFERING)));
        rejected.save(weightedScheme(GRADE_WEIGHTS), completeScores(AUTUMN_OFFERING, 11));
        rejected.state = GRADE_STATE_REJECTED;
        rejected.canEdit = true;
        rejected.lastSubmissionId = GRADE_SUBMISSION_ID;
        // 已驳回的批次带管理员的审核意见：教师改这一版的时候它必须一直可见。
        // 被驳回不是更正草稿（更正草稿基于已通过的批次并带着更正原因），所以这里没有更正原因。
        rejected.reviewComment = GRADE_REVIEW_COMMENT;
        gradeBooks.put(rejected.offeringId, rejected);
    }

    /** 一份完整的确定性分数（四项都有），用于“已提交/被驳回”这类不该缺分的夹具。 */
    private Map<String, GradeScoresDTO> completeScores(String offeringId, int offset) {
        Map<String, GradeScoresDTO> scores = new LinkedHashMap<>();
        List<TeacherRosterRowDTO> roster = rosterOf(offeringId);
        for (int index = 0; index < roster.size(); index++) {
            scores.put(roster.get(index).getEnrollmentId(), new GradeScoresDTO(
                    BigDecimal.valueOf(70 + (index + offset) % 20),
                    BigDecimal.valueOf(65 + (index + offset) % 25),
                    BigDecimal.valueOf(75 + (index + offset) % 15),
                    BigDecimal.valueOf(80 - (index + offset) % 10)));
        }
        return scores;
    }

    // ------------------------------------------------------------------ 调课夹具与校验

    /**
     * mock 认为教师实际要去上的课次。9201/9202 已有生效调课（周课表里是 ADJUSTED_*），
     * 不能再申请；9203 未调整，是提交/预览演示的唯一可申请课次。
     */
    private static final List<MockOccurrence> MOCK_OCCURRENCES = List.of(
            new MockOccurrence("9201", FULL_ROSTER_OFFERING, 8, 1, 1, 2, CLASSROOM_A_101,
                    "A-101", "2026-10-26T00:00:00Z", "2026-10-26T01:35:00Z", false,
                    ADJUSTMENT_ASSISTANT),
            new MockOccurrence("9202", INTERACTION_OFFERING, 8, 2, 1, 2, CLASSROOM_A_101,
                    "A-101", "2026-10-27T00:00:00Z", "2026-10-27T01:35:00Z", false, null),
            new MockOccurrence("9203", OPERATING_SYSTEM_OFFERING, 8, 6, 12, 13, CLASSROOM_C_301,
                    "C-301", "2026-10-31T04:00:00Z", "2026-10-31T05:35:00Z", true, null));

    /**
     * 教师已有课的时间段（含生效调课的新位置）；目标落进其中一段且不是被调课次自己，
     * 预检查报告 TEACHER_OVERLAP。这就是 mock 的“同周冲突 / 跨周可用”语义来源。
     */
    private static final List<BusySlot> BUSY_SLOTS = List.of(
            new BusySlot(8, 1, 1, 2, "9201"), new BusySlot(8, 2, 1, 2, "9202"),
            new BusySlot(8, 5, 5, 6, "9202"), new BusySlot(8, 6, 12, 13, "9203"),
            new BusySlot(9, 3, 3, 4, "9201"));

    private record MockOccurrence(String occurrenceId, String offeringId, int week, int weekday,
            int startPeriod, int endPeriod, String classroomId, String classroomName,
            String startAt, String endAt, boolean canRequestAdjustment, String assistant) {
    }

    private record BusySlot(int week, int weekday, int startPeriod, int endPeriod,
            String occurrenceId) {
    }

    private record RecordedAdjustmentOperation(String digest,
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result) {
    }

    /** 预检查/提交的解析结果：冲突、目标快照与请求头的教学星期。 */
    private record Assessment(List<ScheduleConflictDTO> conflicts, List<AdjustmentTargetDTO> targets,
            int newWeekday) {
    }

    private static MockOccurrence requireOccurrence(String offeringId, String occurrenceId) {
        String id = requiredDecimal(occurrenceId, "originalOccurrenceId");
        for (MockOccurrence occurrence : MOCK_OCCURRENCES) {
            if (occurrence.occurrenceId().equals(id)
                    && occurrence.offeringId().equals(offeringId)) {
                return occurrence;
            }
        }
        throw forbidden("没有该课次的调课权限");
    }

    /**
     * 与真实服务同形的校验顺序：请求体 → 教学班 → 目标 → 原因 → 每个目标的日期/星期/冲突。
     * 冲突以列表报告（预览可显示），提交再按“非空即拒绝”处理；教师没有 force。
     */
    private Assessment assessAdjustment(TeacherAdjustmentWriteDTO write, boolean requireReason) {
        if (write == null) throw badRequest("请求体不能为空");
        String offeringId = requiredDecimal(write.getOfferingId(), "offeringId");
        if (!offerings.containsKey(offeringId)) {
            throw forbidden("没有该教学班的调课权限");
        }
        if (write.getTargets().isEmpty()) throw badRequest("调课目标不能为空");
        if (write.getNewStartPeriod() <= 0) throw badRequest("newStartPeriod 必须为正整数");
        if (write.getNewEndPeriod() < write.getNewStartPeriod()) {
            throw badRequest("newEndPeriod 不能小于 newStartPeriod");
        }
        String reason = blankToNull(write.getReason());
        if (requireReason && reason == null) throw badRequest("调课原因不能为空");
        if (reason != null && reason.length() > ADJUSTMENT_MAX_REASON) {
            throw badRequest("调课原因不能超过 " + ADJUSTMENT_MAX_REASON + " 字符");
        }
        String newClassroomId = blankToNull(write.getNewClassroomId());
        if (newClassroomId != null) {
            requiredDecimal(newClassroomId, "newClassroomId");
            if (!Set.of(CLASSROOM_A_101, CLASSROOM_B_203, CLASSROOM_C_301)
                    .contains(newClassroomId)) {
                throw badRequest("教室不存在");
            }
        }

        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        List<AdjustmentTargetDTO> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int newWeekday = 0;
        for (TeacherAdjustmentTargetInputDTO target : write.getTargets()) {
            String occurrenceId = requiredDecimal(target.getOriginalOccurrenceId(),
                    "originalOccurrenceId");
            if (!seen.add(occurrenceId)) {
                throw badRequest("同一课次不能在同一申请里重复出现");
            }
            MockOccurrence occurrence = requireOccurrence(offeringId, occurrenceId);
            String text = blankToNull(target.getTargetDate());
            if (text == null) throw badRequest("targetDate 不能为空");
            LocalDate date;
            try {
                date = LocalDate.parse(text);
            } catch (DateTimeParseException invalid) {
                throw badRequest("targetDate 必须是 ISO 本地日期");
            }
            int week = teachingWeek(date);
            int weekday = date.getDayOfWeek().getValue();
            if (!occurrence.canRequestAdjustment()) {
                conflicts.add(slotConflict("ADJUSTMENT_TARGET_ADJUSTED", occurrenceId, offeringId,
                        occurrence.week(), occurrence.weekday(), write,
                        "该课程实例已有生效的调课记录"));
                continue;
            }
            if (week == occurrence.week() && weekday == occurrence.weekday()
                    && write.getNewStartPeriod() == occurrence.startPeriod()
                    && write.getNewEndPeriod() == occurrence.endPeriod()
                    && (newClassroomId == null || newClassroomId.equals(occurrence.classroomId()))) {
                throw badRequest("新日期、节次与教室和原安排相同，无需调课");
            }
            if (week < MIN_TEACHING_WEEK || week > MAX_TEACHING_WEEK
                    || weekday > LAST_TEACHING_WEEKDAY) {
                conflicts.add(slotConflict("ADJUSTMENT_SLOT_INVALID", occurrenceId, offeringId,
                        week, weekday, write, "目标日期不在教学日历范围内"));
                continue;
            }
            if (newWeekday == 0) {
                newWeekday = weekday;
            } else if (newWeekday != weekday) {
                throw badRequest("一次申请的目标必须落在同一教学星期");
            }
            for (BusySlot busy : BUSY_SLOTS) {
                if (busy.week() == week && busy.weekday() == weekday
                        && !busy.occurrenceId().equals(occurrenceId)
                        && busy.startPeriod() <= write.getNewEndPeriod()
                        && write.getNewStartPeriod() <= busy.endPeriod()) {
                    conflicts.add(slotConflict("TEACHER_OVERLAP", busy.occurrenceId(), offeringId,
                            busy.week(), busy.weekday(), write, "任课教师在该时间已有其他课程"));
                }
            }
            targets.add(new AdjustmentTargetDTO(occurrenceId, occurrence.week(),
                    occurrence.startAt(), occurrence.endAt(), ADJUSTMENT_TEACHER,
                    occurrence.assistant(), occurrence.classroomName(), date.toString()));
        }

        for (AdjustmentRequestDetailDTO pending : adjustmentRequests.values()) {
            if (pending.getStatus() != AdjustmentRequestStatusDTO.PENDING
                    || !pending.getOfferingId().equals(offeringId)) {
                continue;
            }
            for (AdjustmentTargetDTO existing : pending.getTargets()) {
                if (seen.contains(existing.getOriginalOccurrenceId())) {
                    conflicts.add(slotConflict("ADJUSTMENT_TARGET_ADJUSTED",
                            existing.getOriginalOccurrenceId(), offeringId, existing.getWeek(),
                            pending.getNewDayOfWeek(), write,
                            "该课程实例已有待审批的调课申请"));
                }
            }
        }
        // 全部目标都不可用时 weekday 没有业务含义（冲突列表已让预览/提交失败），保持 1 以免 0。
        if (newWeekday == 0) newWeekday = 1;
        return new Assessment(List.copyOf(conflicts), List.copyOf(targets), newWeekday);
    }

    private static ScheduleConflictDTO slotConflict(String type, String subjectId,
            String offeringId, int week, int weekday, TeacherAdjustmentWriteDTO write,
            String message) {
        return new ScheduleConflictDTO(type, ScheduleConflictSeverityDTO.BLOCKING, subjectId,
                offeringId, week, weekday, write.getNewStartPeriod(), write.getNewEndPeriod(),
                message);
    }

    private void seedAdjustmentRequests() {
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9401", INTERACTION_OFFERING,
                ADJUSTMENT_APPLICANT, SAME_WEEK_REASON, AdjustmentRequestStatusDTO.APPROVED, 2, 5,
                5, 6, null, null, room(CLASSROOM_B_203),
                List.of(target("9202", 8, "2026-10-27T00:00:00Z", "2026-10-27T01:35:00Z",
                        "A-101", ADJUSTMENT_TEACHER, null, "2026-10-30")),
                List.of(), "2026-09-12T08:00:00Z", ADJUSTMENT_REVIEWER, ADJUSTMENT_REVIEWED_AT,
                "同意"));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9402", FULL_ROSTER_OFFERING,
                ADJUSTMENT_APPLICANT, CROSS_WEEK_REASON, AdjustmentRequestStatusDTO.APPROVED, 2, 3,
                3, 4, null, null, room(CLASSROOM_B_203),
                List.of(target("9201", 8, "2026-10-26T00:00:00Z", "2026-10-26T01:35:00Z",
                        "A-101", ADJUSTMENT_TEACHER, ADJUSTMENT_ASSISTANT, "2026-11-04")),
                List.of(), "2026-09-12T07:00:00Z", ADJUSTMENT_REVIEWER, ADJUSTMENT_REVIEWED_AT,
                "已协调教室"));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9403", OPERATING_SYSTEM_OFFERING,
                ADJUSTMENT_APPLICANT, "材料不全的申请", AdjustmentRequestStatusDTO.REJECTED, 2, 4, 7,
                8, null, null, room(CLASSROOM_B_203),
                List.of(target("9203", 8, "2026-10-31T04:00:00Z", "2026-10-31T05:35:00Z",
                        "C-301", ADJUSTMENT_TEACHER, null, "2026-10-29")),
                List.of(), "2026-09-12T06:00:00Z", ADJUSTMENT_REVIEWER, ADJUSTMENT_REVIEWED_AT,
                "材料不足"));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9404", OPERATING_SYSTEM_OFFERING,
                ADJUSTMENT_APPLICANT, CROSS_WEEK_REASON, AdjustmentRequestStatusDTO.WITHDRAWN, 2, 5,
                12, 13, null, null, room(CLASSROOM_B_203),
                List.of(target("9203", 8, "2026-10-31T04:00:00Z", "2026-10-31T05:35:00Z",
                        "C-301", ADJUSTMENT_TEACHER, null, "2026-11-06")),
                List.of(), "2026-09-12T05:00:00Z", null, null, null));
        // PENDING 挂在 9202：它不占用 9203（唯一可申请课次），否则预览/提交演示会被重复 PENDING
        // 检查拦下；9202 的这条申请仍可撤销，撤销后状态与版本按真实语义递增。
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9405", INTERACTION_OFFERING,
                ADJUSTMENT_APPLICANT, SAME_WEEK_REASON, AdjustmentRequestStatusDTO.PENDING, 1, 5,
                5, 6, null, null, room(CLASSROOM_B_203),
                List.of(target("9202", 8, "2026-10-27T00:00:00Z", "2026-10-27T01:35:00Z",
                        "A-101", ADJUSTMENT_TEACHER, null, "2026-11-06")),
                List.of(), "2026-09-14T07:00:00Z", null, null, null));
    }

    private void addAdjustmentRequest(AdjustmentRequestDetailDTO request) {
        adjustmentRequests.put(request.getRequestId(), request);
    }

    private static AdjustmentTargetDTO target(String occurrenceId, int week, String startAt,
            String endAt, String classroom, String teacher, String assistant, String targetDate) {
        return new AdjustmentTargetDTO(occurrenceId, week, startAt, endAt, teacher, assistant,
                classroom, targetDate);
    }

    private AdjustmentRequestSummaryDTO adjustmentSummary(AdjustmentRequestDetailDTO request) {
        TeacherOfferingDTO offering = offerings.get(request.getOfferingId());
        return new AdjustmentRequestSummaryDTO(request.getRequestId(),
                offering == null ? "" : offering.getCourseName(),
                offering == null ? "" : offering.getOfferingCode(), request.getApplicantUid(),
                ADJUSTMENT_TEACHER, request.getTargets().size(), request.getStatus(),
                request.getSubmittedAt());
    }

    private static ScheduleResourceDTO room(String classroomId) {
        return switch (classroomId) {
            case CLASSROOM_A_101 -> new ScheduleResourceDTO(CLASSROOM_A_101, "3001", "A-101",
                    "classroom", 120);
            case CLASSROOM_C_301 -> new ScheduleResourceDTO(CLASSROOM_C_301, "3005", "C-301",
                    "classroom", 90);
            default -> new ScheduleResourceDTO(CLASSROOM_B_203, "3003", "B-203", "classroom", 60);
        };
    }

    private static ScheduleResourceDTO classroomResource(String classroomId) {
        return classroomId == null ? null : room(classroomId);
    }

    /** 教学日历里 (week, teachingWeekday) 对应的 ISO 本地日期（与周课表 fixture 同一把尺）。 */
    private static int teachingWeek(LocalDate date) {
        long days = ChronoUnit.DAYS.between(WEEK_ONE_START, date);
        return days < 0 ? 0 : (int) (days / CALENDAR_DAYS_PER_WEEK) + 1;
    }

    private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replayAdjustment(
            String operationId, String digest) {
        RecordedAdjustmentOperation stored = adjustmentOperations.get(operationId);
        if (stored == null) return null;
        if (!stored.digest().equals(digest)) {
            throw conflict("operationId 已用于不同的业务请求", null, List.of());
        }
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> first = stored.result();
        return new TeacherOperationResultDTO<>(first.getOperationId(), first.getMessage(),
                first.getValue(), true);
    }

    private static String adjustmentDigest(String action, TeacherAdjustmentWriteDTO write) {
        if (write == null) return action + "|<null>";
        List<String> targets = new ArrayList<>();
        for (TeacherAdjustmentTargetInputDTO target : write.getTargets()) {
            targets.add(String.valueOf(target.getOriginalOccurrenceId()) + "@"
                    + String.valueOf(target.getTargetDate()));
        }
        return action + "|" + write.getOfferingId() + "|" + targets + "|"
                + write.getNewStartPeriod() + "|" + write.getNewEndPeriod() + "|"
                + write.getNewClassroomId() + "|" + write.getReason();
    }

    private static String requireOperationId(String operationId) {
        try {
            if (operationId == null || operationId.isBlank()
                    || !UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw badRequest("operationId 必须是 UUID");
        }
        return operationId;
    }

    private static String requiredDecimal(String value, String key) {
        String text = blankToNull(value);
        if (text == null || !text.matches("[0-9]+")) {
            throw badRequest(key + " 必须为十进制字符串");
        }
        try {
            if (Long.parseLong(text) <= 0) throw badRequest(key + " 必须为正整数");
        } catch (NumberFormatException failure) {
            throw badRequest(key + " 超出 BIGINT 范围");
        }
        return text;
    }

    // ------------------------------------------------------------------ 固定数据

    /** 一个学期的教学日历：id 与“今天”所在周；currentWeek 为 null 表示今天不在此学期。 */
    private record CalendarSpec(String calendarId, Integer currentWeek) {
    }

    /** 学期 → 日历；未登记的学期表示“该学期暂无已发布的教学日历”。 */
    private static CalendarSpec calendarFor(int academicYear, int semester) {
        if (academicYear == 2025 && semester == 3) {
            return new CalendarSpec(SPRING_CALENDAR_ID, SPRING_CURRENT_WEEK);
        }
        if (academicYear == 2025 && semester == 2) {
            return new CalendarSpec(AUTUMN_CALENDAR_ID, AUTUMN_CURRENT_WEEK);
        }
        return null;
    }

    /** 日期、节次与课次自洽的固定周：7 行日期、教学日 13 节、按周给出固定课次。 */
    private static TeacherScheduleWeekDTO buildWeek(CalendarSpec calendar, int week) {
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        for (int weekday = 1; weekday <= CALENDAR_DAYS_PER_WEEK; weekday++) {
            String date = localDate(week, weekday);
            boolean teachingDay = weekday <= LAST_TEACHING_WEEKDAY;
            dates.add(new TeacherCalendarDateDTO(date, week, weekday, teachingDay));
            if (!teachingDay) {
                continue;
            }
            for (int period = 1; period <= PERIOD_COUNT; period++) {
                LocalTime start = FIRST_PERIOD_START
                        .plusMinutes((long) (period - 1) * PERIOD_INTERVAL_MINUTES);
                periods.add(new TeacherPeriodDTO(date, period, start.format(PERIOD_TIME),
                        start.plusMinutes(PERIOD_LENGTH_MINUTES).format(PERIOD_TIME)));
            }
        }
        return new TeacherScheduleWeekDTO(calendar.calendarId(), SCHEDULE_TIMEZONE, week,
                MIN_TEACHING_WEEK, MAX_TEACHING_WEEK, calendar.currentWeek(), dates, periods,
                entriesOf(week));
    }

    /** 教学日历里 (week, teachingWeekday) 对应的 ISO 本地日期。 */
    private static String localDate(int week, int teachingWeekday) {
        return WEEK_ONE_START.plusDays((week - 1) * 7L + (teachingWeekday - 1L)).toString();
    }

    /**
     * 固定课次：周 8 两块同周调整 + 一块跨周原位置 + 一节未调整的周末第 13 节课；周 9 只有跨周新位置。
     * 其余所有周（含 week 5 这一无课周）为空，但 dates/periods 仍完整。
     */
    private static List<TeacherScheduleEntryDTO> entriesOf(int week) {
        if (week == 8) {
            return List.of(
                    entry("9201", FULL_ROSTER_OFFERING, "CS203", "数据结构与算法基础",
                            TEACHER_WITH_ASSISTANT, "A-101", 8, 1, 1, 2,
                            ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, CROSS_WEEK_ADJUSTMENT_ID,
                            CROSS_WEEK_ORIGINAL_TEXT, CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON),
                    entry("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                            "陈老师", "A-101", 8, 2, 1, 2,
                            ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, SAME_WEEK_ADJUSTMENT_ID,
                            SAME_WEEK_ORIGINAL_TEXT, SAME_WEEK_ADJUSTED_TEXT, SAME_WEEK_REASON),
                    entry("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                            "陈老师", "B-203", 8, 5, 5, 6,
                            ScheduleDisplayKindDTO.ADJUSTED_TARGET, SAME_WEEK_ADJUSTMENT_ID,
                            SAME_WEEK_ORIGINAL_TEXT, SAME_WEEK_ADJUSTED_TEXT, SAME_WEEK_REASON),
                    entry("9203", OPERATING_SYSTEM_OFFERING, "CS301", "操作系统原理",
                            "陈老师", "C-301", 8, 6, 12, 13,
                            ScheduleDisplayKindDTO.NORMAL, null, null, null, null));
        }
        if (week == 9) {
            return List.of(entry("9201", FULL_ROSTER_OFFERING, "CS203", "数据结构与算法基础",
                    TEACHER_WITH_ASSISTANT, "B-203", 9, 3, 3, 4,
                    ScheduleDisplayKindDTO.ADJUSTED_TARGET, CROSS_WEEK_ADJUSTMENT_ID,
                    CROSS_WEEK_ORIGINAL_TEXT, CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON));
        }
        return List.of();
    }

    private static TeacherScheduleEntryDTO entry(String occurrenceId, String offeringId,
            String courseCode, String courseName, String teacher, String location, int week,
            int weekday, int startPeriod, int endPeriod, ScheduleDisplayKindDTO displayKind,
            String adjustmentId, String originalText, String adjustedText, String reason) {
        // 只有未调整的课次还能再申请调课；ADJUSTED_* 两块都是既成事实。
        boolean canRequestAdjustment = adjustmentId == null;
        return new TeacherScheduleEntryDTO(occurrenceId, offeringId, courseCode, courseName,
                teacher, location, localDate(week, weekday), week, weekday, startPeriod, endPeriod,
                displayKind, adjustmentId, originalText, adjustedText, reason,
                canRequestAdjustment);
    }

    private void seedOfferings() {
        add(new TeacherOfferingDTO(FULL_ROSTER_OFFERING, "CS203-01", "数据结构 CS203-01",
                "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3, ROSTER_LENGTH, 30,
                OFFERING_STATUS_OPEN, true, true));
        add(new TeacherOfferingDTO(INTERACTION_OFFERING, "CS352-01", "人机交互 CS352-01",
                "2003", "CS352", "人机交互导论", 2.0, 2025, 2, 12, 30,
                OFFERING_STATUS_OPEN, false, true));
    }

    private void seedEmptyOffering() {
        // 空班的叙事是“尚未开放选课”，因此用真实状态 NOT_OPEN 而不是 OPEN。
        add(new TeacherOfferingDTO(OPERATING_SYSTEM_OFFERING, "CS301-01", "操作系统 CS301-01",
                "2002", "CS301", "操作系统原理", 3.5, 2025, 3, 0, 40,
                OFFERING_STATUS_NOT_OPEN, true, true));
    }

    private void seedAutumnOffering() {
        add(new TeacherOfferingDTO(AUTUMN_OFFERING, "CS204-01", "离散数学 CS204-01",
                "2004", "CS204", "离散数学", 3.0, 2025, 2, 12, 60,
                OFFERING_STATUS_STOPPED, true, true));
    }

    private void seedFullRoster() {
        List<TeacherRosterRowDTO> roster = new ArrayList<>();
        for (int index = 0; index < ROSTER_LENGTH; index++) {
            String uid = String.format("%08d", 5600 + index);
            String name = index == 0
                    ? "欧阳阿依古丽·买买提江·吐尔逊超长姓名测试"
                    : "学生" + String.format("%02d", index);
            boolean dropped = index % 8 == 3;
            roster.add(new TeacherRosterRowDTO(String.valueOf(50031 + index), uid, name,
                    index % 3 == 0 ? "计算机科学与技术（人工智能方向）实验班" : "计算机科学与技术",
                    dropped ? DROPPED : ENROLLED,
                    "2026-09-01T01:00:00Z",
                    dropped ? "2026-09-10T02:30:00Z" : null));
        }
        rosters.put(FULL_ROSTER_OFFERING, roster);
    }

    private void seedSchedules() {
        schedules.put(FULL_ROSTER_OFFERING, List.of(new ScheduleArrangementDTO(
                "9503", "7001", FULL_ROSTER_OFFERING,
                new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                1, 16, ACTIVE, 1)));
        schedules.put(INTERACTION_OFFERING, List.of(new ScheduleArrangementDTO(
                "9507", "7002", INTERACTION_OFFERING,
                new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60),
                List.of(new ScheduleSlotDTO(2, 5, 6)), 1, 16, ACTIVE, 1)));
    }

    private void add(TeacherOfferingDTO offering) {
        offerings.put(offering.getOfferingId(), offering);
        details.put(offering.getOfferingId(), new TeacherOfferingDetailDTO(offering,
                List.of(new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0),
                        new ScheduleResourceDTO("8002", "00009012", "王助教", "teacher", 0)),
                // 历史数据没有维护开课学院时保持 NULL，界面显示“未维护”。
                "CS352-01".equals(offering.getOfferingCode()) ? null : "计算机科学与工程学院",
                offering.getCourseName() + " 的课程简介"));
        rosters.putIfAbsent(offering.getOfferingId(), List.of());
    }

    private static <T> TeacherPageDTO<T> page(List<T> items, int page, int size) {
        if (page < 1) throw badRequest("page 必须大于 0");
        if (size < 1 || size > MAX_PAGE_SIZE) throw badRequest("size 必须为 1 至 100");
        int from = (int) Math.min((long) (page - 1) * size, items.size());
        int to = (int) Math.min((long) from + size, items.size());
        return new TeacherPageDTO<>(new ArrayList<>(items.subList(from, to)),
                items.size(), page, size);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean matches(String value, String keyword) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static TeacherCourseServiceException badRequest(String message) {
        return new TeacherCourseServiceException(MessageCode.BAD_REQUEST, message);
    }

    private static TeacherCourseServiceException notFound(String message) {
        return new TeacherCourseServiceException(MessageCode.NOT_FOUND, message);
    }

    private static TeacherCourseServiceException forbidden(String message) {
        return new TeacherCourseServiceException(MessageCode.FORBIDDEN, message);
    }

    /** 调课冲突：与真实服务一致地携带类型化冲突与最新可见详情，供界面刷新与分类展示。 */
    private static TeacherCourseServiceException conflict(String message,
            AdjustmentRequestDetailDTO latest, List<ScheduleConflictDTO> conflicts) {
        return new TeacherCourseServiceException(MessageCode.CONFLICT, message, conflicts, latest);
    }

    /** 成绩冲突：附带最新成绩表（版本过期/名单变化），界面据此提示重新加载并保留用户输入。 */
    private static TeacherCourseServiceException gradeConflict(String message,
            TeacherGradeBookDTO latest) {
        return new TeacherCourseServiceException(MessageCode.CONFLICT, message, List.of(), null,
                latest);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
