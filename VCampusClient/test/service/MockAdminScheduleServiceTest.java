package service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import model.course.admin.AdminOperationResultView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

public final class MockAdminScheduleServiceTest {
    private static final String PLAN_ID = "7001";
    private static final String OFFERING_ID = "1001";
    private static final String ARRANGEMENT_ID = "9001";

    private MockAdminScheduleServiceTest() {
    }

    public static void main(String[] args) {
        schedulingMethodsAreDeclaredInTheMock();
        oneDraftPlanLoadsForItsTerm();
        resourcesAreDeterministicAndFiltered();
        oneTwoSlotArrangementLoads();
        teacherWarningIsOverridable();
        selfOverlapIsBlocking();
        overridableSaveRequiresForce();
        forceSaveRequiresNonblankTrimmedReason();
        forceSaveIncrementsArrangementVersion();
        deleteRemovesAndValidatesVersion();
        publishReturnsPublishedPlan();
        System.out.println("MockAdminScheduleServiceTest: PASS");
    }

    private static void schedulingMethodsAreDeclaredInTheMock() {
        declared("listScheduleResources", String.class, String.class);
        declared("loadSchedulePlan", int.class, int.class);
        declared("loadOfferingArrangements", String.class, String.class);
        declared("checkArrangement", SaveArrangementRequestDTO.class);
        declared("saveArrangement", SaveArrangementRequestDTO.class);
        declared("deleteArrangement", String.class, int.class, String.class);
        declared("publishSchedulePlan", String.class, int.class, String.class, boolean.class,
                String.class);
    }

    private static void declared(String name, Class<?>... parameters) {
        try {
            MockAdminCourseService.class.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError("MockAdminCourseService must override " + name
                    + "; an inherited throwing default must not remain");
        }
    }

    private static void oneDraftPlanLoadsForItsTerm() {
        MockAdminCourseService service = new MockAdminCourseService();

        SchedulePlanDTO plan = service.loadSchedulePlan(2026, 1).join();
        require(PLAN_ID.equals(plan.getPlanId()), "the mock must expose one stable draft plan");
        require("DRAFT".equals(plan.getStatus()) && plan.getRevision() == 1 && !plan.isCurrent(),
                "the draft plan must start at revision 1, DRAFT and not current");

        requireCode(MessageCode.NOT_FOUND,
                () -> service.loadSchedulePlan(2025, 1).join(),
                "an unknown term must be NOT_FOUND");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.loadSchedulePlan(0, 1).join(),
                "an invalid academic year must be BAD_REQUEST");
    }

    private static void resourcesAreDeterministicAndFiltered() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<ScheduleResourceDTO> first = service.listScheduleResources("teacher", null).join();
        List<ScheduleResourceDTO> second = service.listScheduleResources("teacher", null).join();
        require(ids(first).equals(List.of("8001", "8002")),
                "teacher resources must be deterministic: " + ids(first));
        require(ids(second).equals(ids(first)),
                "repeated resource reads must be identical");
        require("张老师".equals(first.get(0).getName())
                        && "T1001".equals(first.get(0).getBusinessId())
                        && "teacher".equals(first.get(0).getResourceType()),
                "teacher resource fields must be stable");

        List<ScheduleResourceDTO> classrooms = service.listScheduleResources("classroom", null).join();
        require(ids(classrooms).equals(List.of("8101", "8102")),
                "classroom resources must be deterministic: " + ids(classrooms));
        require(classrooms.get(0).getCapacity() == 120 && classrooms.get(1).getCapacity() == 60,
                "classroom capacities must be deterministic");

        List<ScheduleResourceDTO> filtered = service.listScheduleResources(null, "张").join();
        require(filtered.size() == 1 && "8001".equals(filtered.get(0).getResourceId()),
                "a null type must return every resource and the query must filter by name");

        requireCode(MessageCode.BAD_REQUEST,
                () -> service.listScheduleResources("invalid", null).join(),
                "an unknown resource type must be BAD_REQUEST");
    }

    private static void oneTwoSlotArrangementLoads() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<ScheduleArrangementView> views =
                service.loadOfferingArrangements(PLAN_ID, OFFERING_ID).join();
        require(views.size() == 1, "the mock must hold one arrangement for the offering");
        ScheduleArrangementView view = views.get(0);
        require(ARRANGEMENT_ID.equals(view.getArrangementId()) && PLAN_ID.equals(view.getPlanId())
                        && OFFERING_ID.equals(view.getOfferingId()),
                "the seeded arrangement identity must be stable");
        require(view.getSlots().size() == 2, "the seeded arrangement must hold exactly two slots");
        require(view.getSlots().get(0).getDayOfWeek() == 1
                        && view.getSlots().get(0).getStartPeriod() == 1
                        && view.getSlots().get(1).getDayOfWeek() == 3,
                "the two seeded slots must be stable");
        require("T1001".equals(view.getTeacher().getBusinessId())
                        && view.getAssistant() == null
                        && "3001".equals(view.getClassroom().getBusinessId()),
                "the seeded arrangement resources must be stable");
        require(view.getStartWeek() == 1 && view.getEndWeek() == 16 && view.getVersion() == 1,
                "the seeded arrangement weeks and version must be stable");

        requireCode(MessageCode.NOT_FOUND,
                () -> service.loadOfferingArrangements("7002", OFFERING_ID).join(),
                "an unknown plan must be NOT_FOUND");
    }

    private static void teacherWarningIsOverridable() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<ScheduleConflictDTO> conflicts =
                service.checkArrangement(teacherWarning()).join().getArrangementConflicts();
        require(conflicts.size() == 1, "the teacher warning case must report one conflict");
        ScheduleConflictDTO conflict = conflicts.get(0);
        require(conflict.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "a repeated teacher must be an OVERRIDABLE warning, not a blocker");
        require("TEACHER".equals(conflict.getType()) && OFFERING_ID.equals(conflict.getRelatedOfferingId()),
                "the warning must name the teacher conflict and the related offering");
        require(conflict.getWeek() == 1 && conflict.getDayOfWeek() == 1
                        && conflict.getStartPeriod() == 1 && conflict.getEndPeriod() == 2,
                "the warning must point at the overlapping window");

        MockAdminCourseService clean = new MockAdminCourseService();
        require(clean.checkArrangement(noConflict()).join().getArrangementConflicts().isEmpty(),
                "a free teacher, classroom and slot must report no conflict");
    }

    private static void selfOverlapIsBlocking() {
        MockAdminCourseService service = new MockAdminCourseService();

        List<ScheduleConflictDTO> conflicts =
                service.checkArrangement(selfOverlap()).join().getArrangementConflicts();
        require(conflicts.size() == 1, "the self-overlap case must report one conflict");
        ScheduleConflictDTO conflict = conflicts.get(0);
        require(conflict.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                "a same-offering slot overlap must be BLOCKING");
        require("OFFERING_SELF_OVERLAP".equals(conflict.getType()),
                "the blocking conflict must be typed as a self overlap");
    }

    private static void overridableSaveRequiresForce() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireCode(MessageCode.CONFLICT,
                () -> service.saveArrangement(unforced(teacherWarning())).join(),
                "an overridable conflict must block an unforced save");

        AdminOperationResultView<ScheduleArrangementView> saved = service
                .saveArrangement(forced(teacherWarning(), "  教室协调  ")).join();
        require("OK".equals(saved.getOutcomeCode()) && saved.getEntity() != null,
                "a forced save with a reason must succeed");
        require(saved.getEntity().getVersion() == 1,
                "a newly forced arrangement must start at version 1");
    }

    private static void forceSaveRequiresNonblankTrimmedReason() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireCode(MessageCode.BAD_REQUEST,
                () -> service.saveArrangement(forced(teacherWarning(), "   ")).join(),
                "a forced save with a whitespace-only reason must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.saveArrangement(forced(teacherWarning(), null)).join(),
                "a forced save with a null reason must be BAD_REQUEST");
    }

    private static void forceSaveIncrementsArrangementVersion() {
        MockAdminCourseService service = new MockAdminCourseService();

        AdminOperationResultView<ScheduleArrangementView> created = service
                .saveArrangement(forced(teacherWarning(), "教室协调")).join();
        String arrangementId = created.getEntity().getArrangementId();
        require(created.getEntity().getVersion() == 1,
                "the forced create must report version 1");

        SaveArrangementRequestDTO update = new SaveArrangementRequestDTO("op-update-version",
                arrangementId, 1, PLAN_ID, "2001", "T1001", null, "3002",
                List.of(new ScheduleSlotDTO(3, 3, 4)), 1, 16, true, " 再次调整 ");
        AdminOperationResultView<ScheduleArrangementView> updated =
                service.saveArrangement(update).join();
        require(updated.getEntity().getVersion() == 2,
                "a forced update must increment the arrangement version to 2");

        List<ScheduleArrangementView> reloaded =
                service.loadOfferingArrangements(PLAN_ID, "2001").join();
        require(reloaded.size() == 1 && reloaded.get(0).getVersion() == 2,
                "the incremented version must persist across a reload");
    }

    private static void deleteRemovesAndValidatesVersion() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireCode(MessageCode.CONFLICT,
                () -> service.deleteArrangement(ARRANGEMENT_ID, 2, "op-delete-stale").join(),
                "a stale expected version must be CONFLICT");

        AdminOperationResultView<Void> deleted =
                service.deleteArrangement(ARRANGEMENT_ID, 1, "op-delete").join();
        require(deleted.getEntity() == null && "教学安排已删除".equals(deleted.getMessage()),
                "a successful delete must map a null entity and a message");
        require(service.loadOfferingArrangements(PLAN_ID, OFFERING_ID).join().isEmpty(),
                "the deleted arrangement must no longer load");

        requireCode(MessageCode.NOT_FOUND,
                () -> service.deleteArrangement(ARRANGEMENT_ID, 1, "op-delete-again").join(),
                "deleting a missing arrangement must be NOT_FOUND");
    }

    private static void publishReturnsPublishedPlan() {
        MockAdminCourseService service = new MockAdminCourseService();

        requireCode(MessageCode.BAD_REQUEST,
                () -> service.publishSchedulePlan(PLAN_ID, 1, "op-publish", true, "   ").join(),
                "a forced publish with a blank reason must be BAD_REQUEST");

        AdminOperationResultView<SchedulePlanView> published = service
                .publishSchedulePlan(PLAN_ID, 1, "op-publish", false, null).join();
        require(published.getEntity() != null
                        && "PUBLISHED".equals(published.getEntity().getStatus())
                        && published.getEntity().isCurrent()
                        && published.getEntity().getRevision() == 2,
                "a publish must return the plan advanced to revision 2 and marked current");

        requireCode(MessageCode.CONFLICT,
                () -> service.publishSchedulePlan(PLAN_ID, 1, "op-publish-stale", false, null).join(),
                "a stale revision or a non-draft plan must be CONFLICT");
    }

    private static List<String> ids(List<ScheduleResourceDTO> resources) {
        List<String> ids = new ArrayList<>();
        for (ScheduleResourceDTO resource : resources) {
            ids.add(resource.getResourceId());
        }
        return ids;
    }

    private static SaveArrangementRequestDTO teacherWarning() {
        return request("op-check-warning", null, 0, "2001", "T1001", "3002",
                List.of(new ScheduleSlotDTO(1, 1, 2)), false, null);
    }

    private static SaveArrangementRequestDTO selfOverlap() {
        return request("op-check-self", null, 0, OFFERING_ID, "T1001", "3001",
                List.of(new ScheduleSlotDTO(1, 1, 2)), false, null);
    }

    private static SaveArrangementRequestDTO noConflict() {
        return request("op-check-free", null, 0, OFFERING_ID, "T2001", "3002",
                List.of(new ScheduleSlotDTO(5, 5, 6)), false, null);
    }

    private static SaveArrangementRequestDTO unforced(SaveArrangementRequestDTO base) {
        return withForce(base, false, null);
    }

    private static SaveArrangementRequestDTO forced(SaveArrangementRequestDTO base, String reason) {
        return withForce(base, true, reason);
    }

    private static SaveArrangementRequestDTO withForce(SaveArrangementRequestDTO base,
            boolean force, String reason) {
        return new SaveArrangementRequestDTO(base.getOperationId(), base.getArrangementId(),
                base.getExpectedVersion(), base.getPlanId(), base.getOfferingId(),
                base.getTeacherUid(), base.getAssistantUid(), base.getClassroomId(),
                base.getSlots(), base.getStartWeek(), base.getEndWeek(), force, reason);
    }

    private static SaveArrangementRequestDTO request(String operationId, String arrangementId,
            int expectedVersion, String offeringId, String teacherUid, String classroomId,
            List<ScheduleSlotDTO> slots, boolean force, String reason) {
        return new SaveArrangementRequestDTO(operationId, arrangementId, expectedVersion,
                PLAN_ID, offeringId, teacherUid, null, classroomId, slots, 1, 16, force, reason);
    }

    private static void requireCode(MessageCode expected, Runnable call, String message) {
        try {
            call.run();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AdminCourseServiceException error) {
                require(error.getCode() == expected,
                        message + "; expected " + expected + " but was " + error.getCode());
                return;
            }
            throw new AssertionError(message + "; unexpected cause " + failure.getCause());
        }
        throw new AssertionError(message + "; expected failure with " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
