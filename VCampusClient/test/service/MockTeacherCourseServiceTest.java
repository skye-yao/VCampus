package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
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
        filtersAndPagingSurfaceAsFailedFutures();
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
