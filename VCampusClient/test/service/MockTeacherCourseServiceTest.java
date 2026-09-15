package service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;

/**
 * 无服务器预览用确定性实现的契约回归。
 *
 * <p>固定断言：两个学期、超过一页的名单（20+7 且 totalCount 恒为 27）、超长姓名行、空班、退课行
 * 与 droppedAt、能力字段、已知教学班无安排时返回空列表（而不是 NOT_FOUND），以及失败一律以
 * 异常完成的 Future 表达而不是同步抛出——后者是与真实 Socket 实现保持一致的关键形状。
 * 全程 headless：不连数据库、不开 socket、不启动 JavaFX。
 */
public final class MockTeacherCourseServiceTest {
    /** 名单超过一页（size=20）的教学班。 */
    private static final String FULL_ROSTER_CODE = "CS203-01";
    private static final String FULL_ROSTER_ID = "9007199254740993";
    private static final int ROSTER_LENGTH = 27;
    private static final int PAGE_SIZE = 20;
    private static final int ACADEMIC_YEAR = 2025;
    private static final int SPRING = 3;
    private static final int AUTUMN = 2;
    private static final String LONG_NAME = "欧阳阿依古丽·买买提江·吐尔逊超长姓名测试";
    /** 调课夹具的固定教学日历第 1 周周一，与 mock 的日期推导保持同一把尺。 */
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2026, 9, 7);
    private static final String WITHDRAW_OPERATION = "50000000-0000-0000-0000-000000000001";
    private static final String SUBMIT_OPERATION = "50000000-0000-0000-0000-000000000002";

    private MockTeacherCourseServiceTest() {
    }

    public static void main(String[] args) {
        termsAreDeterministicAndCoverTwoSemesters();
        rosterPaginatesBeyondOnePageWithConsistentTotals();
        longNameRowSurvivesIntact();
        droppedRowsKeepTheirDropTime();
        knownOfferingWithoutArrangementsReturnsEmptyList();
        emptyClassReturnsEmptyRosterAndEmptySchedules();
        capabilityFieldsMatchTheServerContract();
        offeringStatusesStayWithinTheServerDomain();
        filtersAndPagingSurfaceAsFailedFutures();
        adjustmentRequestsCoverTheFourStatesAndBothWeekPatterns();
        adjustmentOptionsStayInsideTheTeachingCalendar();
        previewDistinguishesSameWeekConflictsFromCrossWeekAvailability();
        submitAddsAPendingRequestVisibleInQueries();
        withdrawIncrementsTheVersionAndChangesTheQuerySnapshot();
        adjustmentFailuresStayFailedFutures();
        System.out.println("MockTeacherCourseServiceTest: PASS");
    }

    private static void termsAreDeterministicAndCoverTwoSemesters() {
        MockTeacherCourseService service = new MockTeacherCourseService();

        List<CourseTermDTO> terms = service.listTerms().join();
        require(terms.size() >= 2, "the mock must offer at least two terms, saw " + terms.size());
        require(terms.get(0).getAcademicYear() == ACADEMIC_YEAR
                        && terms.get(0).getSemester() == SPRING
                        && terms.get(0).getDisplayName() != null,
                "the first term must be a complete 2025 spring term");
        require(terms.get(1).getAcademicYear() == ACADEMIC_YEAR
                        && terms.get(1).getSemester() == AUTUMN,
                "the second term must be the 2025 autumn term");
        require(service.listOfferings(ACADEMIC_YEAR, SPRING, null, 1, PAGE_SIZE).join()
                        .getTotalCount() == 2,
                "the spring term must be non-empty");
        require(service.listOfferings(ACADEMIC_YEAR, AUTUMN, null, 1, PAGE_SIZE).join()
                        .getTotalCount() == 2,
                "the autumn term must be non-empty");
        require(offeringCodes(service, ACADEMIC_YEAR, SPRING)
                        .equals(offeringCodes(service, ACADEMIC_YEAR, SPRING)),
                "repeated offering reads must be identical and stably ordered");
    }

    private static void rosterPaginatesBeyondOnePageWithConsistentTotals() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = fullRosterOfferingId(service);

        TeacherPageDTO<TeacherRosterRowDTO> first =
                service.listOfferingStudents(offeringId, null, null, 1, PAGE_SIZE).join();
        TeacherPageDTO<TeacherRosterRowDTO> second =
                service.listOfferingStudents(offeringId, null, null, 2, PAGE_SIZE).join();

        require(first.getItems().size() == PAGE_SIZE,
                "page 1 must be full, saw " + first.getItems().size());
        require(second.getItems().size() == ROSTER_LENGTH - PAGE_SIZE,
                "page 2 must hold the remainder, saw " + second.getItems().size());
        require(first.getTotalCount() == ROSTER_LENGTH && second.getTotalCount() == ROSTER_LENGTH,
                "totalCount must be the roster size on every page, saw "
                        + first.getTotalCount() + "/" + second.getTotalCount());
        require(first.getPage() == 1 && second.getPage() == 2
                        && first.getSize() == PAGE_SIZE && second.getSize() == PAGE_SIZE,
                "the paging metadata must be echoed back");
        require(first.getItems().get(0).getStudentUid().matches("0[0-9]{7}"),
                "student UIDs must keep their fixed-width leading zeroes");

        List<TeacherRosterRowDTO> union = new ArrayList<>(first.getItems());
        union.addAll(second.getItems());
        require(union.size() == ROSTER_LENGTH, "the two pages must not overlap");
        require(union.stream().map(TeacherRosterRowDTO::getEnrollmentId).distinct().count()
                        == ROSTER_LENGTH,
                "every roster row must have a unique enrollment ID");
        List<String> uids = union.stream().map(TeacherRosterRowDTO::getStudentUid).toList();
        List<String> sorted = new ArrayList<>(uids);
        sorted.sort(Comparator.naturalOrder());
        require(uids.equals(sorted), "the roster order must be stable and unique-ID ordered");
        require(service.listOfferingStudents(offeringId, null, null, 3, PAGE_SIZE).join()
                        .getItems().isEmpty(),
                "a page beyond the roster must be an empty page, not an error");
    }

    private static void longNameRowSurvivesIntact() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = fullRosterOfferingId(service);

        List<TeacherRosterRowDTO> rows = new ArrayList<>();
        for (int page = 1; rows.size() < ROSTER_LENGTH; page++) {
            rows.addAll(service.listOfferingStudents(offeringId, null, null, page, PAGE_SIZE)
                    .join().getItems());
        }
        TeacherRosterRowDTO longNamed = rows.stream()
                .filter(row -> LONG_NAME.equals(row.getStudentName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the long-name row must be on the roster"));
        require(longNamed.getStudentName().length() > 20,
                "the long name must survive the page mapping intact");
        require(longNamed.getStudentUid() != null && longNamed.getMajor() != null
                        && !longNamed.getMajor().isBlank(),
                "the long-name row must keep its uid and major");
        require(rows.stream().anyMatch(row -> row.getStudentName() != null
                        && row.getStudentName().length() > 20),
                "at least one row must exercise a long name");
    }

    private static void droppedRowsKeepTheirDropTime() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = fullRosterOfferingId(service);

        List<TeacherRosterRowDTO> dropped = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            dropped.addAll(service.listOfferingStudents(offeringId, null, 3, page, PAGE_SIZE)
                    .join().getItems());
        }
        require(!dropped.isEmpty(), "the mock must expose at least one dropped (退课) row");
        require(dropped.stream().allMatch(row -> "DROPPED".equals(row.getEnrollmentStatus())),
                "the dropped filter must return only dropped rows");
        require(dropped.stream().allMatch(row -> row.getDroppedAt() != null
                        && !row.getDroppedAt().isBlank()),
                "a dropped row must carry a non-null UTC drop time");
        require(dropped.stream().allMatch(row -> row.getSelectedAt() != null),
                "a dropped row must keep its selection time");

        List<TeacherRosterRowDTO> enrolled =
                service.listOfferingStudents(offeringId, null, 2, 1, PAGE_SIZE).join().getItems();
        require(!enrolled.isEmpty()
                        && enrolled.stream().allMatch(
                                row -> "ENROLLED".equals(row.getEnrollmentStatus())),
                "the enrolled filter must return only active rows");
        require(enrolled.stream().allMatch(row -> row.getDroppedAt() == null),
                "an active row must keep its drop time null");
    }

    private static void emptyClassReturnsEmptyRosterAndEmptySchedules() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        TeacherOfferingDTO empty = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01");

        TeacherPageDTO<TeacherRosterRowDTO> roster =
                service.listOfferingStudents(empty.getOfferingId(), null, null, 1, PAGE_SIZE).join();
        require(roster.getItems().isEmpty() && roster.getTotalCount() == 0,
                "an empty class must be an empty page, not an error");
        require(roster.getPage() == 1 && roster.getSize() == PAGE_SIZE,
                "an empty class must still echo its paging metadata");
        require(service.listOfferingSchedules(empty.getOfferingId()).join().isEmpty(),
                "an empty class must have no arrangements");
    }

    /** 已知教学班没有正式安排时返回空列表：这是本轮修掉的缺陷，必须被钉住。 */
    private static void knownOfferingWithoutArrangementsReturnsEmptyList() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        require(!service.listOfferingSchedules(FULL_ROSTER_ID).join().isEmpty(),
                "the seeded offering must still expose its arrangement");

        TeacherOfferingDTO empty = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01");
        List<ScheduleArrangementDTO> arrangements =
                service.listOfferingSchedules(empty.getOfferingId()).join();
        require(arrangements != null && arrangements.isEmpty(),
                "a known offering without arrangements must return an empty list, never NOT_FOUND");
    }

    /**
     * 教学班状态必须落在服务端真实能给出的取值域内。
     *
     * <p>{@code course_offering.status} 是 TINYINT 1..4，服务端经 {@code AdminOfferingDAO.statusLabel}
     * 映射为 {@code NOT_OPEN|OPEN|STOPPED|CANCELLED}（{@code TeacherCourseQueryDAO.mapOffering} 用的就是
     * 它）。这里对照**字面量集合**断言，而不是对照客户端的标签映射——否则 mock 与客户端可能一起漂移出
     * 真实域而不被发现（客户端对未知值只能回显原值，预览就会显示服务端不可能发出的英文）。
     */
    private static void offeringStatusesStayWithinTheServerDomain() {
        Set<String> realStatuses =
                Set.of("NOT_OPEN", "OPEN", "STOPPED", "CANCELLED");
        MockTeacherCourseService service = new MockTeacherCourseService();
        Set<String> seen = new LinkedHashSet<>();

        for (int semester : new int[] {SPRING, AUTUMN}) {
            for (TeacherOfferingDTO offering : service
                    .listOfferings(ACADEMIC_YEAR, semester, null, 1, PAGE_SIZE).join()
                    .getItems()) {
                require(realStatuses.contains(offering.getStatus()),
                        "offering " + offering.getOfferingCode() + " must carry a status the server"
                                + " can actually emit " + realStatuses + ", saw "
                                + offering.getStatus());
                seen.add(offering.getStatus());
            }
        }

        TeacherOfferingDTO detail = service.getOffering(FULL_ROSTER_ID).join().getOffering();
        require(realStatuses.contains(detail.getStatus()),
                "the detail payload must carry a real offering status, saw " + detail.getStatus());

        require(seen.size() >= 2,
                "the mock must exercise more than one real status so the client's label mapping is"
                        + " covered on more than one value, saw " + seen);
    }

    private static void capabilityFieldsMatchTheServerContract() {
        MockTeacherCourseService service = new MockTeacherCourseService();

        TeacherOfferingDTO editable = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS203-01");
        require(editable.isCanEditGrades() && editable.isCanRequestAdjustment(),
                "the teaching offering must report both capabilities");
        require(FULL_ROSTER_ID.equals(editable.getOfferingId()),
                "offering IDs must stay exact decimal strings beyond the safe integer");
        require(editable.getCapacity() == 30 && editable.getEnrolledCount() == ROSTER_LENGTH,
                "the offering must report its real capacity and enrolled count");

        TeacherOfferingDTO readOnly = offeringByCode(service, ACADEMIC_YEAR, AUTUMN, "CS352-01");
        require(!readOnly.isCanEditGrades() && readOnly.isCanRequestAdjustment(),
                "a non-teaching relation must not claim the grade-edit capability");
        require(service.getOffering(readOnly.getOfferingId()).join().getOfferingCollege() == null,
                "an unmaintained offering college must stay null, never a teacher's own college");
        require(service.getOffering(FULL_ROSTER_ID).join().getTeachers().size() == 2,
                "the detail must expose the teacher and assistant rows");
    }

    private static void filtersAndPagingSurfaceAsFailedFutures() {
        MockTeacherCourseService service = new MockTeacherCourseService();

        requireCode(MessageCode.NOT_FOUND,
                () -> service.getOffering("1234567890123"),
                "an unknown offering must be NOT_FOUND");
        requireCode(MessageCode.NOT_FOUND,
                () -> service.listOfferingStudents("1234567890123", null, null, 1, PAGE_SIZE),
                "an unknown offering roster must be NOT_FOUND");
        requireCode(MessageCode.NOT_FOUND,
                () -> service.listOfferingSchedules("1234567890123"),
                "unknown offering schedules must be NOT_FOUND");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listOfferingStudents(FULL_ROSTER_ID, null, 1, 1, PAGE_SIZE),
                "an illegal enrollmentStatus must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listOfferings(ACADEMIC_YEAR, SPRING, null, 0, PAGE_SIZE),
                "page 0 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listOfferings(ACADEMIC_YEAR, SPRING, null, 1, 0),
                "size 0 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listOfferings(ACADEMIC_YEAR, SPRING, null, 1, 101),
                "size 101 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listOfferingStudents(FULL_ROSTER_ID, null, null, 1, 101),
                "an out-of-range roster size must be BAD_REQUEST");
    }

    /**
     * 四种状态各至少一条，且目标覆盖同周与跨周：这是 T5 的“我的调课申请”页与已撤销显示路径
     * 依赖的确定性数据契约。
     */
    private static void adjustmentRequestsCoverTheFourStatesAndBothWeekPatterns() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        Set<String> seen = new LinkedHashSet<>();
        boolean sameWeek = false;
        boolean crossWeek = false;

        for (AdjustmentRequestStatusDTO status : AdjustmentRequestStatusDTO.values()) {
            TeacherPageDTO<AdjustmentRequestSummaryDTO> page =
                    service.listMyAdjustmentRequests(status, 1, PAGE_SIZE).join();
            require(!page.getItems().isEmpty(),
                    "the mock must seed at least one " + status + " request");
            require(page.getItems().stream().allMatch(item -> item.getStatus() == status),
                    "the " + status + " filter must return only " + status + " rows");
            for (AdjustmentRequestSummaryDTO summary : page.getItems()) {
                seen.add(summary.getStatus().name());
                AdjustmentRequestDetailDTO detail =
                        service.getAdjustmentRequest(summary.getRequestId()).join();
                require(detail.getTargets().size() >= 1,
                        "a summary must resolve to a detail with its targets");
                for (AdjustmentTargetDTO target : detail.getTargets()) {
                    String targetDate = target.getTargetDate();
                    require(targetDate != null,
                            "a teacher-submitted target must carry its ISO target date");
                    int targetWeek = teachingWeek(targetDate);
                    if (targetWeek == target.getWeek()) sameWeek = true;
                    else crossWeek = true;
                }
            }
        }
        require(seen.containsAll(Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")),
                "the mock must provide every four-state value, saw " + seen);
        require(sameWeek && crossWeek,
                "the fixtures must cover both a same-week and a cross-week target");

        TeacherPageDTO<AdjustmentRequestSummaryDTO> defaulted =
                service.listMyAdjustmentRequests(null, 1, PAGE_SIZE).join();
        require(defaulted.getItems().stream()
                        .allMatch(item -> item.getStatus() == AdjustmentRequestStatusDTO.PENDING),
                "a null status must keep the server's PENDING default");
    }

    private static void adjustmentOptionsStayInsideTheTeachingCalendar() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        TeacherOfferingDTO offering = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01");

        TeacherAdjustmentOptionsDTO options = service.getAdjustmentOptions(
                offering.getOfferingId(), "9203").join();
        require(options.getCalendarId() != null && "Asia/Shanghai".equals(options.getTimezone()),
                "the options must expose the teaching calendar identity");
        require(options.getDates().stream().allMatch(date -> date.isTeachingDay()
                        && date.getTeachingWeekday() >= 1 && date.getTeachingWeekday() <= 6),
                "every option date must be a teaching day of the mock calendar");
        require(!options.getClassrooms().isEmpty()
                        && options.getClassrooms().stream()
                        .allMatch(room -> room.getResourceId() != null
                                && room.getCapacity() >= 0),
                "the classroom resources must carry exact IDs and capacities");
        require(options.getPeriods().stream()
                        .allMatch(period -> period.getDate() != null && period.getPeriod() >= 1),
                "the period template must be keyed by teaching date");
    }

    private static void previewDistinguishesSameWeekConflictsFromCrossWeekAvailability() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01")
                .getOfferingId();

        TeacherAdjustmentPreviewDTO busy = service.previewAdjustment(adjustmentWrite(
                "not-a-uuid", offeringId, "9203", "2026-10-26", 1, 2, "8103",
                "教师出差")).join();
        require(!busy.isCanSubmit() && !busy.getConflicts().isEmpty(),
                "a same-week target that collides with the teacher's own class must not be submittable");
        require(busy.getConflicts().stream().anyMatch(
                        conflict -> "TEACHER_OVERLAP".equals(conflict.getType())
                                && conflict.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING),
                "the collision must be reported as a blocking teacher overlap");

        TeacherAdjustmentPreviewDTO free = service.previewAdjustment(adjustmentWrite(
                "not-a-uuid", offeringId, "9203", "2026-11-02", 1, 2, "8103",
                "教师出差")).join();
        require(free.isCanSubmit() && free.getConflicts().isEmpty(),
                "a cross-week target on a free slot must be submittable");

        TeacherAdjustmentPreviewDTO sunday = service.previewAdjustment(adjustmentWrite(
                "not-a-uuid", offeringId, "9203", "2026-11-01", 1, 2, "8103",
                "教师出差")).join();
        require(!sunday.isCanSubmit() && sunday.getConflicts().stream().anyMatch(
                        conflict -> "ADJUSTMENT_SLOT_INVALID".equals(conflict.getType())),
                "a non-teaching day target must be a blocking slot conflict");

        requireCode(MessageCode.BAD_REQUEST,
                () -> service.previewAdjustment(adjustmentWrite("not-a-uuid", offeringId, "9203",
                        "2026-10-31", 12, 13, "8105", "教师出差")),
                "an unchanged arrangement must be BAD_REQUEST, not a phantom conflict");
    }

    private static void submitAddsAPendingRequestVisibleInQueries() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01")
                .getOfferingId();
        long before = service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.PENDING, 1,
                PAGE_SIZE).join().getTotalCount();
        TeacherAdjustmentWriteDTO write = adjustmentWrite(SUBMIT_OPERATION, offeringId, "9203",
                "2026-11-02", 1, 2, "8103", "教师出差");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                service.submitAdjustment(write).join();
        AdjustmentRequestDetailDTO created = result.getValue();
        require(created != null && created.getStatus() == AdjustmentRequestStatusDTO.PENDING
                        && created.getVersion() == 1 && !result.isReplayed(),
                "a submit must create a fresh PENDING request at version 1");
        require("2026-11-02".equals(created.getTargets().get(0).getTargetDate()),
                "the created request must keep the requested target date");
        require(created.getNewTeacher() == null && created.getNewAssistant() == null,
                "the mock must never invent a substituted teacher or assistant");

        require(created.getRequestId().equals(
                        service.getAdjustmentRequest(created.getRequestId()).join().getRequestId()),
                "the created request must be readable by ID");
        TeacherPageDTO<AdjustmentRequestSummaryDTO> pending =
                service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.PENDING, 1, PAGE_SIZE)
                        .join();
        require(pending.getTotalCount() == before + 1
                        && pending.getItems().stream().anyMatch(item -> created.getRequestId()
                        .equals(item.getRequestId())),
                "the query snapshot must show the new PENDING request, not just the returned value");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replay =
                service.submitAdjustment(write).join();
        require(replay.isReplayed()
                        && created.getRequestId().equals(replay.getValue().getRequestId()),
                "the same operationId and content must replay the stored result");
        require(service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.PENDING, 1, PAGE_SIZE)
                        .join().getTotalCount() == before + 1,
                "a replay must not write a second request");
    }

    private static void withdrawIncrementsTheVersionAndChangesTheQuerySnapshot() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String pendingId = service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.PENDING, 1,
                PAGE_SIZE).join().getItems().get(0).getRequestId();
        AdjustmentRequestDetailDTO before = service.getAdjustmentRequest(pendingId).join();
        require(before.getStatus() == AdjustmentRequestStatusDTO.PENDING
                        && before.getVersion() == 1,
                "the withdrawable fixture must start PENDING at version 1");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> withdrawn =
                service.withdrawAdjustment(new WithdrawTeacherAdjustmentRequestDTO(
                        WITHDRAW_OPERATION, pendingId, before.getVersion())).join();
        require(withdrawn.getValue().getStatus() == AdjustmentRequestStatusDTO.WITHDRAWN
                        && withdrawn.getValue().getVersion() == before.getVersion() + 1,
                "a withdraw must move the request to WITHDRAWN and increment its version");
        require(service.getAdjustmentRequest(pendingId).join().getStatus()
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the detail query snapshot must change after the withdraw");
        require(service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.PENDING, 1, PAGE_SIZE)
                        .join().getItems().stream().noneMatch(
                                item -> pendingId.equals(item.getRequestId())),
                "the PENDING list must no longer contain the withdrawn request");
        require(service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.WITHDRAWN, 1, PAGE_SIZE)
                        .join().getItems().stream().anyMatch(
                                item -> pendingId.equals(item.getRequestId())),
                "the WITHDRAWN list must show the withdrawn request");

        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replay =
                service.withdrawAdjustment(new WithdrawTeacherAdjustmentRequestDTO(
                        WITHDRAW_OPERATION, pendingId, before.getVersion())).join();
        require(replay.isReplayed()
                        && replay.getValue().getVersion() == before.getVersion() + 1,
                "a repeated withdraw must replay, never increment twice");
    }

    private static void adjustmentFailuresStayFailedFutures() {
        MockTeacherCourseService service = new MockTeacherCourseService();
        String offeringId = offeringByCode(service, ACADEMIC_YEAR, SPRING, "CS301-01")
                .getOfferingId();
        String approvedId = service.listMyAdjustmentRequests(AdjustmentRequestStatusDTO.APPROVED,
                1, PAGE_SIZE).join().getItems().get(0).getRequestId();

        requireCode(MessageCode.NOT_FOUND, () -> service.getAdjustmentRequest("1234567890123"),
                "an unknown request must be NOT_FOUND");
        requireCode(MessageCode.BAD_REQUEST, () -> service.getAdjustmentRequest("abc"),
                "a non-decimal request ID must be BAD_REQUEST");
        requireCode(MessageCode.FORBIDDEN, () -> service.getAdjustmentOptions(
                        "1234567890123", "9203"),
                "an unknown offering must not expose adjustment options");
        requireCode(MessageCode.FORBIDDEN, () -> service.getAdjustmentOptions(offeringId, "9201"),
                "an occurrence of another offering must be denied");

        requireCode(MessageCode.BAD_REQUEST, () -> service.previewAdjustment(
                        adjustmentWrite("not-a-uuid", offeringId, "9203", "2026/11/02", 1, 2,
                                "8103", "教师出差")),
                "a non-ISO target date must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST, () -> service.submitAdjustment(
                        adjustmentWrite("not-a-uuid", offeringId, "9203", "2026-11-02", 1, 2,
                                "8103", "教师出差")),
                "a non-UUID operationId must be BAD_REQUEST on submit");
        requireCode(MessageCode.BAD_REQUEST, () -> service.submitAdjustment(
                        adjustmentWrite(SUBMIT_OPERATION, offeringId, "9203", "2026-11-02", 1, 2,
                                "8103", "   ")),
                "submit must require a reason while preview does not");
        requireCode(MessageCode.CONFLICT, () -> service.submitAdjustment(
                        adjustmentWrite(SUBMIT_OPERATION, offeringId, "9203", "2026-10-26", 1, 2,
                                "8103", "教师出差")),
                "a colliding target must be CONFLICT on submit");
        requireCode(MessageCode.BAD_REQUEST, () -> service.withdrawAdjustment(
                        new WithdrawTeacherAdjustmentRequestDTO("not-a-uuid", approvedId, 1)),
                "a non-UUID withdraw operationId must be BAD_REQUEST");
        requireCode(MessageCode.CONFLICT, () -> service.withdrawAdjustment(
                        new WithdrawTeacherAdjustmentRequestDTO(WITHDRAW_OPERATION, approvedId, 2)),
                "a terminal request must not be withdrawable");
        requireCode(MessageCode.BAD_REQUEST, () -> service.listMyAdjustmentRequests(null, 0,
                        PAGE_SIZE), "page 0 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST, () -> service.listMyAdjustmentRequests(null, 1, 101),
                "size 101 must be BAD_REQUEST");

        AdjustmentRequestDetailDTO approved = service.getAdjustmentRequest(approvedId).join();
        try {
            service.withdrawAdjustment(new WithdrawTeacherAdjustmentRequestDTO(
                    "50000000-0000-0000-0000-000000000009", approvedId,
                    approved.getVersion())).join();
            throw new AssertionError("a terminal request must not be withdrawable");
        } catch (CompletionException failure) {
            TeacherCourseServiceException error =
                    (TeacherCourseServiceException) failure.getCause();
            require(error.getLatest() != null
                            && approvedId.equals(error.getLatest().getRequestId()),
                    "the conflict must carry the latest detail so the page can refresh");
        }
    }

    private static int teachingWeek(String isoDate) {
        return (int) (ChronoUnit.DAYS.between(WEEK_ONE_START, LocalDate.parse(isoDate)) / 7) + 1;
    }

    private static TeacherAdjustmentWriteDTO adjustmentWrite(String operationId, String offeringId,
            String occurrenceId, String targetDate, int startPeriod, int endPeriod,
            String classroomId, String reason) {
        return new TeacherAdjustmentWriteDTO(operationId, offeringId,
                List.of(new TeacherAdjustmentTargetInputDTO(occurrenceId, targetDate)),
                startPeriod, endPeriod, classroomId, reason);
    }

    private static String fullRosterOfferingId(MockTeacherCourseService service) {
        TeacherOfferingDTO offering = offeringByCode(service, ACADEMIC_YEAR, SPRING, FULL_ROSTER_CODE);
        require(FULL_ROSTER_ID.equals(offering.getOfferingId()),
                "the full-roster offering must keep its BIGINT decimal ID");
        return offering.getOfferingId();
    }

    private static List<String> offeringCodes(MockTeacherCourseService service, int academicYear,
            int semester) {
        return service.listOfferings(academicYear, semester, null, 1, PAGE_SIZE).join().getItems()
                .stream()
                .map(TeacherOfferingDTO::getOfferingCode)
                .toList();
    }

    private static TeacherOfferingDTO offeringByCode(MockTeacherCourseService service,
            int academicYear, int semester, String offeringCode) {
        return service.listOfferings(academicYear, semester, null, 1, PAGE_SIZE).join().getItems()
                .stream()
                .filter(offering -> offeringCode.equals(offering.getOfferingCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing offering " + offeringCode));
    }

    /**
     * 失败必须由异常完成的 Future 表达：同步抛出会在这里被单独识别，而不是与真实 Socket 实现
     * 的失败形状混为一谈。
     */
    private static void requireCode(MessageCode expected, Supplier<CompletableFuture<?>> call,
            String message) {
        CompletableFuture<?> future;
        try {
            future = call.get();
        } catch (RuntimeException synchronous) {
            throw new AssertionError(
                    message + "; the mock must fail the future, not throw synchronously",
                    synchronous);
        }
        try {
            future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof TeacherCourseServiceException error) {
                require(error.getCode() == expected,
                        message + "; expected " + expected + " but was " + error.getCode());
                return;
            }
            throw new AssertionError(message + "; unexpected cause " + failure.getCause(),
                    failure.getCause());
        }
        throw new AssertionError(message + "; expected failure with " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
