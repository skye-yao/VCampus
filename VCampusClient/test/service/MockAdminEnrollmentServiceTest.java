package service;

import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.StudentSearchResultView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** The mock is the production preview service, so state changes are exercised directly. */
public final class MockAdminEnrollmentServiceTest {
    private static final String OFFERING = "1001";
    private static final String NORMAL = "20240031";
    private static final String WARNING = "20240032";
    private static final String PREREQUISITE = "20240033";
    private static final String LOCKED = "20240001";

    public static void main(String[] args) {
        seededRosterMatchesVisibleCountAndSupportsPages();
        searchUsesExactUidOrNameAndRejectsBadPages();
        addRemoveRestorePreserveHistoryAndCopiedSnapshots();
        replayChecksActionTargetForceAndNormalizedReason();
        warningsRequireForceAndTrimmedReason();
        fullCapacityCanBeForcedWithoutChangingOfferingVersion();
        gradeLocksAndSameCourseEnrollmentAlwaysBlock();
        missingAndUnavailableTargetsNeverMutate();
        draftWithDroppedEnrollmentHistoryCannotBeDeleted();
        System.out.println("MockAdminEnrollmentServiceTest: PASS");
    }

    private static void seededRosterMatchesVisibleCountAndSupportsPages() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminOfferingView offering = offering(service);
        require(offering.getEnrolledCount() == 30 && offering.getCapacity() == 120,
                "existing catalog fixture must stay at 30 / 120");
        AdminEnrollmentPageView<OfferingStudentView> first = service.listOfferingStudentsPage(
                OFFERING, null, 1, 20).join();
        AdminEnrollmentPageView<OfferingStudentView> second = service.listOfferingStudentsPage(
                OFFERING, " ", 2, 20).join();
        require(first.getTotalCount() == 30 && first.getItems().size() == 20
                        && second.getItems().size() == 10 && second.getPageNumber() == 2,
                "seeded active rows must match the displayed count and page boundaries");
        OfferingStudentView locked = service.listOfferingStudents(OFFERING, LOCKED, 1, 20).join().get(0);
        require(!locked.isRemovable() && locked.getBlockedReason() != null && !locked.getBlockedReason().isBlank(),
                "the removal dialog needs a seeded grade-locked row with a reason");
        require(service.listOfferingStudentsPage(OFFERING, null, Integer.MAX_VALUE, 100).join().getItems().isEmpty(),
                "large valid page numbers must return an empty page without offset overflow");
    }

    private static void searchUsesExactUidOrNameAndRejectsBadPages() {
        MockAdminCourseService service = new MockAdminCourseService();
        List<StudentSearchResultView> exact = service.searchStudents("  " + NORMAL + "  ", 1, 20).join();
        require(exact.size() == 1 && NORMAL.equals(exact.get(0).getUid()) && "陈晨".equals(exact.get(0).getName()),
                "trimmed exact student UID must find the deterministic normal candidate");
        require(service.searchStudents("晓雨", 1, 20).join().get(0).getUid().equals(WARNING),
                "student names must support partial matching");
        AdminEnrollmentPageView<StudentSearchResultView> students = service.searchStudentsPage("同学", 2, 10).join();
        require(students.getTotalCount() >= 20 && students.getItems().size() == 10 && students.getPageSize() == 10,
                "search must use filtered totals before slicing a page");
        require(service.searchStudents("not-a-student", 1, 20).join().isEmpty(), "unknown searches must be empty");
        failure(service.searchStudents(" ", 1, 20), MessageCode.BAD_REQUEST);
        failure(service.searchStudents("陈", 0, 20), MessageCode.BAD_REQUEST);
        failure(service.searchStudents("陈", 1, 101), MessageCode.BAD_REQUEST);
        failure(service.listOfferingStudents(OFFERING, null, 1, 0), MessageCode.BAD_REQUEST);
    }

    private static void addRemoveRestorePreserveHistoryAndCopiedSnapshots() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminOfferingView before = offering(service);
        require(service.previewAdminEnrollment(OFFERING, NORMAL).join().getRisks().isEmpty(),
                "the normal candidate must be usable without force");
        AdminEnrollmentRequestDTO add = request(1, OFFERING, NORMAL, false, null);
        AdminOperationResultView<OfferingStudentView> added = service.addStudentToOffering(add).join();
        String history = added.getEntity().getEnrollmentId();
        require("ENROLLED".equals(added.getEntity().getEnrollmentStatus()) && offering(service).getEnrolledCount() == 31,
                "adding must create a live row and increment count exactly once");
        require(service.addStudentToOffering(request(2, OFFERING, NORMAL, false, null)).join()
                        .getEntity().getEnrollmentId().equals(history) && offering(service).getEnrolledCount() == 31,
                "another add to the same offering must be harmless");
        AdminEnrollmentRequestDTO remove = request(3, OFFERING, NORMAL, false, null);
        OfferingStudentView dropped = service.removeStudentFromOffering(remove).join().getEntity();
        require(history.equals(dropped.getEnrollmentId()) && "DROPPED".equals(dropped.getEnrollmentStatus())
                        && !dropped.isRemovable() && offering(service).getEnrolledCount() == 30,
                "removal must preserve the history row and decrement once");
        require(service.listOfferingStudents(OFFERING, NORMAL, 1, 20).join().isEmpty(),
                "the active roster must omit dropped students");
        require("ENROLLED".equals(added.getEntity().getEnrollmentStatus()) && before.getEnrolledCount() == 30,
                "prior row and offering snapshots must not change in place");
        service.removeStudentFromOffering(remove).join();
        service.addStudentToOffering(add).join();
        require(offering(service).getEnrolledCount() == 30,
                "replaying older success after removal must not reapply either state change");
        OfferingStudentView restored = service.addStudentToOffering(request(4, OFFERING, NORMAL, false, null)).join().getEntity();
        require(history.equals(restored.getEnrollmentId()) && offering(service).getEnrolledCount() == 31,
                "a new add must restore the same historical enrollment ID");
        require(offering(service).getVersion() == before.getVersion(),
                "enrollment count-only changes must preserve offering edit versions");
    }

    private static void replayChecksActionTargetForceAndNormalizedReason() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminEnrollmentRequestDTO first = request(10, OFFERING, NORMAL, true, "  教务批准  ");
        service.addStudentToOffering(first).join();
        service.addStudentToOffering(request(10, OFFERING, NORMAL, true, "教务批准")).join();
        failure(service.addStudentToOffering(request(10, OFFERING, WARNING, true, "教务批准")), MessageCode.CONFLICT);
        failure(service.addStudentToOffering(request(10, OFFERING, NORMAL, true, "另一原因")), MessageCode.CONFLICT);
        failure(service.addStudentToOffering(request(10, OFFERING, NORMAL, false, "教务批准")), MessageCode.CONFLICT);
        failure(service.removeStudentFromOffering(first), MessageCode.CONFLICT);
        require(offering(service).getEnrolledCount() == 31, "replay or mismatched digest must not apply twice");
    }

    private static void warningsRequireForceAndTrimmedReason() {
        MockAdminCourseService service = new MockAdminCourseService();
        List<ScheduleConflictDTO> risks = service.previewAdminEnrollment(OFFERING, WARNING).join().getRisks();
        require(!risks.isEmpty() && risks.stream().allMatch(r -> r.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE),
                "the schedule-warning candidate must expose overridable preview risks");
        AdminCourseServiceException rejected = failure(service.addStudentToOffering(
                request(20, OFFERING, WARNING, false, null)), MessageCode.CONFLICT);
        require(!rejected.getConflicts().isEmpty(), "risk rejection must retain typed dialog information");
        failure(service.addStudentToOffering(request(20, OFFERING, WARNING, true, "  ")), MessageCode.BAD_REQUEST);
        failure(service.addStudentToOffering(request(20, OFFERING, WARNING, true, "x".repeat(501))), MessageCode.BAD_REQUEST);
        require(offering(service).getEnrolledCount() == 30, "rejected attempts must not mutate count");
        service.addStudentToOffering(request(20, OFFERING, WARNING, true, "协调上课时间")).join();
        require(offering(service).getEnrolledCount() == 31, "confirmed warning must allow enrollment");
        require(service.previewAdminEnrollment(OFFERING, PREREQUISITE).join().getRisks().stream()
                        .anyMatch(r -> "PREREQUISITE".equals(r.getType())
                                && r.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE),
                "the fixture must support a prerequisite warning");
    }

    private static void fullCapacityCanBeForcedWithoutChangingOfferingVersion() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminOfferingView current = offering(service);
        service.updateOffering(new OfferingEditorRequestDTO("mock-capacity-edit", OFFERING,
                current.getVersion(), "101", "OFF-1001", 2026, 1, 30, "T1001", null, 2)).join();
        int editedVersion = offering(service).getVersion();
        require(service.previewAdminEnrollment(OFFERING, NORMAL).join().getRisks().stream()
                        .anyMatch(r -> "CAPACITY".equals(r.getType())
                                && r.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE),
                "full capacity must be computed from mutable count and capacity");
        failure(service.addStudentToOffering(request(30, OFFERING, NORMAL, false, null)), MessageCode.CONFLICT);
        service.addStudentToOffering(request(30, OFFERING, NORMAL, true, "批准超额一人")).join();
        require(offering(service).getEnrolledCount() == 31 && offering(service).getCapacity() == 30
                        && offering(service).getVersion() == editedVersion,
                "forced enrollment may exceed capacity without bumping the edit version");
        service.addStudentToOffering(request(31, OFFERING, NORMAL, false, null)).join();
        require(offering(service).getEnrolledCount() == 31, "already enrolled must succeed even above capacity");
    }

    private static void gradeLocksAndSameCourseEnrollmentAlwaysBlock() {
        MockAdminCourseService service = new MockAdminCourseService();
        AdminCourseServiceException locked = failure(service.removeStudentFromOffering(
                request(40, OFFERING, LOCKED, true, "请求例外")), MessageCode.CONFLICT);
        require(locked.getLatest() instanceof OfferingStudentView latest && !latest.isRemovable()
                        && locked.getConflicts().stream().anyMatch(r -> r.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING),
                "force may not bypass a grade-locked row, and latest restrictions must remain typed");
        AdminOfferingView other = service.createOffering(new OfferingEditorRequestDTO("mock-second-section",
                null, 0, "101", "OFF-OTHER", 2026, 1, 120, "T1001", null, 2)).join().getEntity();
        AdminCourseServiceException duplicate = failure(service.addStudentToOffering(
                request(41, other.getOfferingId(), "20240002", true, "请求例外")), MessageCode.CONFLICT);
        require(duplicate.getConflicts().stream().anyMatch(r -> "SAME_COURSE_ACTIVE".equals(r.getType())
                        && r.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING),
                "another active offering of the same course must block even when forced");
        require(offering(service).getEnrolledCount() == 30
                        && service.listOfferingStudents(other.getOfferingId(), null, 1, 20).join().isEmpty(),
                "blocked mutations must preserve both rosters");
    }

    private static void missingAndUnavailableTargetsNeverMutate() {
        MockAdminCourseService service = new MockAdminCourseService();
        failure(service.previewAdminEnrollment("999999", NORMAL), MessageCode.NOT_FOUND);
        failure(service.previewAdminEnrollment(OFFERING, "missing-student"), MessageCode.NOT_FOUND);
        failure(service.previewAdminEnrollment("0", NORMAL), MessageCode.BAD_REQUEST);
        failure(service.addStudentToOffering(new AdminEnrollmentRequestDTO("not-uuid", OFFERING, NORMAL, false, null)),
                MessageCode.BAD_REQUEST);
        failure(service.addStudentToOffering(request(50, "2001", NORMAL, true, "请求例外")), MessageCode.CONFLICT);
        failure(service.addStudentToOffering(request(51, "3001", NORMAL, true, "请求例外")), MessageCode.CONFLICT);
        failure(service.addStudentToOffering(request(52, OFFERING, "20240034", true, "请求例外")), MessageCode.CONFLICT);
        failure(service.removeStudentFromOffering(request(53, OFFERING, NORMAL, false, null)), MessageCode.CONFLICT);
        require(offering(service).getEnrolledCount() == 30, "all rejected mutations must leave seed count intact");
    }

    private static AdminEnrollmentRequestDTO request(int operation, String offeringId, String uid,
            boolean force, String reason) {
        return new AdminEnrollmentRequestDTO(String.format("30000000-0000-0000-0000-%012d", operation),
                offeringId, uid, force, reason);
    }

    private static void draftWithDroppedEnrollmentHistoryCannotBeDeleted() {
        MockAdminCourseService service = new MockAdminCourseService();
        OfferingStudentView added = service.addStudentToOffering(
                request(60, "2002", NORMAL, false, null)).join().getEntity();
        OfferingStudentView dropped = service.removeStudentFromOffering(
                request(61, "2002", NORMAL, false, null)).join().getEntity();
        require(added.getEnrollmentId().equals(dropped.getEnrollmentId())
                        && "DROPPED".equals(dropped.getEnrollmentStatus()),
                "removal must leave historical enrollment on the draft offering");
        AdminOfferingView draft = service.listOfferings("201").join().stream()
                .filter(row -> "2002".equals(row.getOfferingId())).findFirst().orElseThrow();
        require(draft.getEnrolledCount() == 0
                        && service.listOfferingStudents("2002", null, 1, 20).join().isEmpty(),
                "an empty active roster does not make historical enrollment disappear");
        failure(service.deleteDraftOffering("2002", draft.getVersion(), "mock-delete-history"),
                MessageCode.CONFLICT);
        require(service.listOfferings("201").join().stream().anyMatch(row -> "2002".equals(row.getOfferingId())),
                "rejecting deletion must keep the offering that owns the historical row");
        OfferingStudentView restored = service.addStudentToOffering(
                request(62, "2002", NORMAL, false, null)).join().getEntity();
        require(added.getEnrollmentId().equals(restored.getEnrollmentId()),
                "the preserved offering must still restore its original enrollment history");
    }

    private static AdminOfferingView offering(MockAdminCourseService service) {
        return service.listOfferings("101").join().stream().filter(o -> OFFERING.equals(o.getOfferingId()))
                .findFirst().orElseThrow();
    }

    private static AdminCourseServiceException failure(CompletableFuture<?> future, MessageCode code) {
        try {
            future.join();
            throw new AssertionError("expected " + code);
        } catch (CompletionException failure) {
            require(failure.getCause() instanceof AdminCourseServiceException, "failures must use stable service exceptions");
            AdminCourseServiceException error = (AdminCourseServiceException) failure.getCause();
            require(error.getCode() == code, "expected " + code + ", got " + error.getCode() + ": " + error.getMessage());
            return error;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
