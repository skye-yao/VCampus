package service;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

public final class MockAdminCourseServiceTest {

    public static void main(String[] args) {
        listCoursesFiltersSeedsByStatusAndQuery();
        createThenUpdateBumpsVersionAndStaleUpdateConflicts();
        duplicateCourseCodeConflictsAndCourseCodeIsImmutable();
        archiveNeedsEveryOfferingCancelledThenRestores();
        offeringCreateAndUpdateBumpVersions();
        emptyDraftIsDeletableWhileOtherOfferingIsNot();
        operationIdReplayDoesNotApplyTwice();
        System.out.println("MockAdminCourseServiceTest: PASS");
    }

    private static void listCoursesFiltersSeedsByStatusAndQuery() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<AdminCourseView> all = service.listCourses(null, null).join();
        require(all.size() == 3, "the mock must seed three courses");

        List<AdminCourseView> archived = service.listCourses(null, "ARCHIVED").join();
        require(archived.size() == 1 && "ARCHIVED".equals(archived.get(0).getStatus()),
                "status filter must keep only archived courses");

        List<AdminCourseView> active = service.listCourses(null, "ACTIVE").join();
        require(active.size() == 2, "status filter must keep the two active courses");

        List<AdminCourseView> byCode = service.listCourses("CS301", null).join();
        require(byCode.size() == 1 && "CS301".equals(byCode.get(0).getCourseCode()),
                "query must match the course code");

        List<AdminCourseView> byName = service.listCourses("操作系统", null).join();
        require(byName.size() == 1 && "操作系统".equals(byName.get(0).getCourseName()),
                "query must match the course name");
    }

    private static void createThenUpdateBumpsVersionAndStaleUpdateConflicts() {
        MockAdminCourseService service = new MockAdminCourseService();

        AdminOperationResultView<AdminCourseView> created = service.createCourse(
                courseRequest("op-create", null, 0, "NEW101", "新课程", 3.0, 48)).join();
        AdminCourseView course = created.getEntity();
        require(course != null && course.getVersion() == 1,
                "a created course must start at version 1");
        require("ACTIVE".equals(course.getStatus()), "a created course must be ACTIVE");
        require("NEW101".equals(course.getCourseCode()), "the course code must round-trip");
        require("op-create".equals(created.getOperationId())
                        && "OK".equals(created.getOutcomeCode()),
                "a create result must carry the operation id and outcome code");

        AdminOperationResultView<AdminCourseView> updated = service.updateCourse(
                courseRequest("op-update", course.getCourseId(), 1, "NEW101", "新课程改", 3.5, 48))
                .join();
        require(updated.getEntity().getVersion() == 2,
                "an update must bump the course version to 2");
        require("新课程改".equals(updated.getEntity().getCourseName()),
                "an update must apply the new name");

        requireConflict(service.updateCourse(
                courseRequest("op-stale", course.getCourseId(), 1, "NEW101", "过期写", 3.5, 48)));
    }

    private static void duplicateCourseCodeConflictsAndCourseCodeIsImmutable() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireConflict(service.createCourse(
                courseRequest("op-dup", null, 0, "CS203", "重复课程", 3.0, 48)));

        AdminCourseServiceException immutable = failureOf(service.updateCourse(
                courseRequest("op-code", "101", 1, "CS999", "数据结构", 4.0, 64)));
        require(immutable.getCode() == MessageCode.BAD_REQUEST,
                "changing the course code on update must be BAD_REQUEST");
    }

    private static void archiveNeedsEveryOfferingCancelledThenRestores() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireConflict(service.archiveCourse("101", 1, "op-archive-blocked"));

        AdminOperationResultView<AdminOfferingView> cancelled =
                service.cancelOffering("1001", 1, "op-cancel").join();
        require("CANCELLED".equals(cancelled.getEntity().getStatus()),
                "cancelling an offering must set CANCELLED");
        require(cancelled.getEntity().getVersion() == 2,
                "cancelling an offering must bump its version");

        AdminOperationResultView<AdminCourseView> archived =
                service.archiveCourse("101", 1, "op-archive").join();
        require("ARCHIVED".equals(archived.getEntity().getStatus()),
                "archiving must set ARCHIVED once every offering is cancelled");
        require(archived.getEntity().getVersion() == 2,
                "archiving must bump the course version");

        AdminOperationResultView<AdminCourseView> restored =
                service.restoreCourse("101", 2, "op-restore").join();
        require("ACTIVE".equals(restored.getEntity().getStatus()),
                "restoring must set ACTIVE");
        require(restored.getEntity().getVersion() == 3,
                "restoring must bump the course version");
    }

    private static void offeringCreateAndUpdateBumpVersions() {
        MockAdminCourseService service = new MockAdminCourseService();

        AdminOperationResultView<AdminOfferingView> created = service.createOffering(
                offeringRequest("op-offer-create", null, 0, "101", 1, 100)).join();
        AdminOfferingView offering = created.getEntity();
        require(offering.getVersion() == 1, "a created offering must start at version 1");
        require("NOT_OPEN".equals(offering.getStatus()), "status 1 must map to NOT_OPEN");
        require("UNSCHEDULED".equals(offering.getScheduleStatus()),
                "a new offering must be UNSCHEDULED");

        AdminOperationResultView<AdminOfferingView> updated = service.updateOffering(
                offeringRequest("op-offer-update", offering.getOfferingId(), 1, "101", 2, 120))
                .join();
        require(updated.getEntity().getVersion() == 2,
                "an offering update must bump the version");
        require("OPEN".equals(updated.getEntity().getStatus()), "status 2 must map to OPEN");
        require(updated.getEntity().getCapacity() == 120,
                "an offering update must apply the new capacity");
    }

    private static void emptyDraftIsDeletableWhileOtherOfferingIsNot() {
        MockAdminCourseService service = new MockAdminCourseService();

        require(containsOffering(service.listOfferings("201").join(), "2002"),
                "the seeded empty draft must be listed");

        AdminOperationResultView<Void> deleted =
                service.deleteDraftOffering("2002", 1, "op-delete").join();
        require(deleted.getEntity() == null, "deleting a draft must map a null entity");
        require("OK".equals(deleted.getOutcomeCode()),
                "deleting a draft must report the OK outcome");
        require(!containsOffering(service.listOfferings("201").join(), "2002"),
                "the deleted draft must vanish from listOfferings");

        requireConflict(service.deleteDraftOffering("2001", 1, "op-delete-cancelled"));
    }

    private static void operationIdReplayDoesNotApplyTwice() {
        MockAdminCourseService service = new MockAdminCourseService();
        int before = service.listCourses(null, null).join().size();
        CourseEditorRequestDTO request =
                courseRequest("op-replay", null, 0, "REP101", "回放课程", 2.0, 32);

        AdminOperationResultView<AdminCourseView> first = service.createCourse(request).join();
        AdminOperationResultView<AdminCourseView> second = service.createCourse(request).join();
        require(first.getEntity().getCourseId().equals(second.getEntity().getCourseId()),
                "replaying an operationId must return the same entity");
        require(service.listCourses(null, null).join().size() == before + 1,
                "replaying an operationId must not create a second course");
    }

    private static CourseEditorRequestDTO courseRequest(String operationId, String courseId,
            int expectedVersion, String code, String name, double credit, int creditHours) {
        return new CourseEditorRequestDTO(operationId, courseId, expectedVersion, code, name,
                "必修", credit, creditHours, "简介", "无", true, true);
    }

    private static OfferingEditorRequestDTO offeringRequest(String operationId, String offeringId,
            int expectedVersion, String courseId, int status, int capacity) {
        return new OfferingEditorRequestDTO(operationId, offeringId, expectedVersion, courseId,
                "OFF-" + status, 2026, 1, capacity, "T1", null, status);
    }

    private static boolean containsOffering(List<AdminOfferingView> offerings, String offeringId) {
        for (AdminOfferingView offering : offerings) {
            if (offeringId.equals(offering.getOfferingId())) return true;
        }
        return false;
    }

    private static void requireConflict(CompletableFuture<?> future) {
        AdminCourseServiceException failure = failureOf(future);
        require(failure.getCode() == MessageCode.CONFLICT,
                "expected CONFLICT but was " + failure.getCode() + ": " + failure.getMessage());
    }

    private static AdminCourseServiceException failureOf(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                return error;
            }
            throw new AssertionError("unexpected failure cause: " + failure.getCause(),
                    failure.getCause());
        }
        throw new AssertionError("expected the future to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
