package dto.course.admin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import service.AdminCourseService;

public final class AdminScheduleDtoJsonTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        roundTripsSlot();
        roundTripsResourceWithDecimalStringId();
        roundTripsArrangementWithTwoSlotsAndWeeksFiveToTen();
        roundTripsArrangementWithNullableAssistant();
        roundTripsSaveRequestWithOperationIdentityAndForceReason();
        roundTripsPlanWithBlockingAndOverridableSeverities();
        roundTripsConflictWithMergedWeekRange();
        normalizesNullListsToEmptyImmutableLists();
        slotListsAreDefensiveAndUnmodifiable();
        deserializedListsAreUnmodifiable();
        pinsExactJsonKeySets();
        exposesExactSchedulingServiceMethods();
        clientViewsExposeSchedulingStateWithoutJavaFx();
    }

    private static void roundTripsSlot() {
        ScheduleSlotDTO copy = GSON.fromJson(
                GSON.toJson(new ScheduleSlotDTO(3, 5, 6)), ScheduleSlotDTO.class);

        require(copy.getDayOfWeek() == 3, "slot day of week must round-trip");
        require(copy.getStartPeriod() == 5, "slot start period must round-trip");
        require(copy.getEndPeriod() == 6, "slot end period must round-trip");
    }

    private static void roundTripsResourceWithDecimalStringId() {
        ScheduleResourceDTO copy = GSON.fromJson(
                GSON.toJson(teacher()), ScheduleResourceDTO.class);

        require("9007199254740999".equals(copy.getResourceId()),
                "BIGINT resource ID must remain an exact decimal string");
        require("T1001".equals(copy.getBusinessId()), "business ID must round-trip");
        require("张老师".equals(copy.getName()), "UTF-8 resource name must survive JSON");
        require("TEACHER".equals(copy.getResourceType()),
                "resource type must round-trip");
        require(copy.getCapacity() == 0, "capacity must round-trip");
    }

    private static void roundTripsArrangementWithTwoSlotsAndWeeksFiveToTen() {
        ScheduleArrangementDTO source = arrangement(teacher(), assistant(), classroom());
        ScheduleArrangementDTO copy =
                GSON.fromJson(GSON.toJson(source), ScheduleArrangementDTO.class);

        require("9007199254740993".equals(copy.getArrangementId()),
                "BIGINT arrangement ID must remain an exact decimal string");
        require("9007199254740995".equals(copy.getPlanId()),
                "BIGINT plan ID must remain an exact decimal string");
        require("9007199254740997".equals(copy.getOfferingId()),
                "BIGINT offering ID must remain an exact decimal string");
        require(copy.getTeacher() != null, "teacher must survive JSON");
        require("9007199254740999".equals(copy.getTeacher().getResourceId()),
                "nested teacher resource ID must remain exact");
        require("9007199254741001".equals(copy.getAssistant().getResourceId()),
                "nested assistant resource ID must remain exact");
        require("9007199254741003".equals(copy.getClassroom().getResourceId()),
                "nested classroom resource ID must remain exact");
        require(copy.getSlots().size() == 2, "both slots must survive JSON");
        require(copy.getSlots().get(0).getDayOfWeek() == 1,
                "first slot day of week must survive JSON");
        require(copy.getSlots().get(0).getStartPeriod() == 3,
                "first slot start period must survive JSON");
        require(copy.getSlots().get(0).getEndPeriod() == 4,
                "first slot end period must survive JSON");
        require(copy.getSlots().get(1).getDayOfWeek() == 3,
                "second slot day of week must survive JSON");
        require(copy.getSlots().get(1).getStartPeriod() == 1,
                "second slot start period must survive JSON");
        require(copy.getSlots().get(1).getEndPeriod() == 2,
                "second slot end period must survive JSON");
        require(copy.getStartWeek() == 5, "shared start week must round-trip");
        require(copy.getEndWeek() == 10, "shared end week must round-trip");
        require("ACTIVE".equals(copy.getStatus()), "arrangement status must round-trip");
        require(copy.getVersion() == 3, "optimistic version must round-trip");
    }

    private static void roundTripsArrangementWithNullableAssistant() {
        ScheduleArrangementDTO assigned = GSON.fromJson(
                GSON.toJson(arrangement(teacher(), assistant(), classroom())),
                ScheduleArrangementDTO.class);
        require(assigned.getAssistant() != null, "an assigned assistant must survive JSON");
        require("李助教".equals(assigned.getAssistant().getName()),
                "assistant name must round-trip");

        ScheduleArrangementDTO unassigned = GSON.fromJson(
                GSON.toJson(arrangement(teacher(), null, classroom())),
                ScheduleArrangementDTO.class);
        require(unassigned.getAssistant() == null,
                "a nullable assistant arrangement must stay null");
        require(!GSON.toJson(unassigned).contains("assistant\":{"),
                "a null assistant must serialize as a null property, not an object");
    }

    private static void roundTripsSaveRequestWithOperationIdentityAndForceReason() {
        SaveArrangementRequestDTO update = saveRequest("operation-71", true);
        SaveArrangementRequestDTO copy = GSON.fromJson(
                GSON.toJson(update), SaveArrangementRequestDTO.class);

        require("operation-71".equals(copy.getOperationId()),
                "operation ID must round-trip");
        require("9007199254740993".equals(copy.getArrangementId()),
                "BIGINT arrangement ID must remain an exact decimal string");
        require(copy.getExpectedVersion() == 3, "expected version must round-trip");
        require("9007199254740995".equals(copy.getPlanId()),
                "BIGINT plan ID must remain an exact decimal string");
        require("9007199254740997".equals(copy.getOfferingId()),
                "BIGINT offering ID must remain an exact decimal string");
        require("T1001".equals(copy.getTeacherUid()), "teacher UID must round-trip");
        require(copy.getAssistantUid() == null, "nullable assistant UID must stay null");
        require("9007199254741003".equals(copy.getClassroomId()),
                "BIGINT classroom ID must remain an exact decimal string");
        require(copy.getSlots().size() == 2, "both requested slots must survive JSON");
        require(copy.getSlots().get(1).getEndPeriod() == 2,
                "second requested slot must survive JSON");
        require(copy.getStartWeek() == 5, "requested start week must round-trip");
        require(copy.getEndWeek() == 10, "requested end week must round-trip");
        require(copy.isForce(), "true force must round-trip");
        require("教师时间冲突，已确认".equals(copy.getOverrideReason()),
                "UTF-8 override reason must survive JSON");

        SaveArrangementRequestDTO create = new SaveArrangementRequestDTO(
                "operation-72", null, 0, "9007199254740995", "9007199254740997",
                "T1001", null, "9007199254741003", twoSlots(), 5, 10, false, null);
        SaveArrangementRequestDTO createCopy = GSON.fromJson(
                GSON.toJson(create), SaveArrangementRequestDTO.class);
        require(createCopy.getArrangementId() == null,
                "a create request must keep a null arrangement ID");
        require(!createCopy.isForce(), "false force must round-trip");
        require(createCopy.getOverrideReason() == null,
                "an unforced request must keep a null override reason");
    }

    private static void roundTripsPlanWithBlockingAndOverridableSeverities() {
        SchedulePlanDTO source = new SchedulePlanDTO(
                "9007199254740995", "2025-2026 学年第一学期", 4, "DRAFT", true,
                Arrays.asList(attributedConflict(), overridableConflict()));
        SchedulePlanDTO copy = GSON.fromJson(GSON.toJson(source), SchedulePlanDTO.class);

        require("9007199254740995".equals(copy.getPlanId()),
                "BIGINT plan ID must remain an exact decimal string");
        require("2025-2026 学年第一学期".equals(copy.getName()),
                "UTF-8 plan name must survive JSON");
        require(copy.getRevision() == 4, "plan revision must round-trip");
        require("DRAFT".equals(copy.getStatus()), "plan status must round-trip");
        require(copy.isCurrent(), "current plan flag must round-trip");
        require(copy.getConflicts().size() == 2, "both conflicts must survive JSON");
        require(copy.getConflicts().get(0).getSeverity()
                        == ScheduleConflictSeverityDTO.BLOCKING,
                "BLOCKING severity must survive JSON");
        require(copy.getConflicts().get(1).getSeverity()
                        == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "OVERRIDABLE severity must survive JSON");
        require("TEACHER_OVERLAP".equals(copy.getConflicts().get(1).getType()),
                "conflict type must round-trip");
        require("2004".equals(copy.getConflicts().get(0).getOfferingId()),
                "the owning offering id must survive JSON");
        require("CS202-2026-2-A".equals(copy.getConflicts().get(0).getOfferingLabel()),
                "the owning offering label must survive JSON");
        require(copy.getConflicts().get(1).getOfferingId() == null
                        && copy.getConflicts().get(1).getOfferingLabel() == null,
                "a conflict without attribution must keep both ownership fields null");

        SchedulePlanDTO published = new SchedulePlanDTO(
                "9007199254740995", "Plan", 0, "PUBLISHED", false, null);
        SchedulePlanDTO publishedCopy =
                GSON.fromJson(GSON.toJson(published), SchedulePlanDTO.class);
        require(!publishedCopy.isCurrent(), "false current flag must round-trip");
        require(publishedCopy.getConflicts().isEmpty(),
                "a plan without conflicts must serialize an empty list");
    }

    /**
     * 甲4：冲突的周次区间必须往返 JSON；旧构造（未给 endWeek）委托 endWeek=week，而在此之前写下的
     * journal JSON 缺 endWeek 时反序列化为 0——渲染端一律按 max(week, endWeek) 当单周。
     */
    private static void roundTripsConflictWithMergedWeekRange() {
        ScheduleConflictDTO range = new ScheduleConflictDTO("CLASSROOM_CAPACITY",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "3101", "2004", "2004",
                "CS202-2026-2-A", 8, 16, 3, 3, 4, "教室容量 40 小于教学班容量 45");
        ScheduleConflictDTO copy = GSON.fromJson(GSON.toJson(range), ScheduleConflictDTO.class);
        require(copy.getWeek() == 8 && copy.getEndWeek() == 16,
                "the merged week range must round-trip, got " + copy.getWeek() + "-"
                        + copy.getEndWeek());

        ScheduleConflictDTO single = new ScheduleConflictDTO("TEACHER_OVERLAP",
                ScheduleConflictSeverityDTO.OVERRIDABLE, "T1001", "9007199254740997",
                6, 3, 1, 2, "教师时间冲突");
        require(single.getEndWeek() == 6,
                "the constructor without endWeek must default it to week, got "
                        + single.getEndWeek());
        ScheduleConflictDTO singleCopy =
                GSON.fromJson(GSON.toJson(single), ScheduleConflictDTO.class);
        require(singleCopy.getWeek() == 6 && singleCopy.getEndWeek() == 6,
                "a single-week conflict must round-trip as exactly one week, got "
                        + singleCopy.getWeek() + "-" + singleCopy.getEndWeek());

        ScheduleConflictDTO legacy = GSON.fromJson("{\"type\":\"TEACHER_OVERLAP\","
                + "\"severity\":\"OVERRIDABLE\",\"subjectId\":\"T1001\","
                + "\"relatedOfferingId\":\"9007199254740997\",\"week\":6,\"dayOfWeek\":3,"
                + "\"startPeriod\":1,\"endPeriod\":2,\"message\":\"教师时间冲突\"}",
                ScheduleConflictDTO.class);
        require(legacy.getEndWeek() == 0,
                "a journal JSON written before endWeek existed must deserialize it as 0, got "
                        + legacy.getEndWeek());
    }

    private static void normalizesNullListsToEmptyImmutableLists() {
        ScheduleArrangementDTO arrangement =
                arrangement(teacher(), null, classroom(), null);
        require(arrangement.getSlots().isEmpty(),
                "null slots must normalize to an empty list on construction");
        requireUnmodifiable(arrangement.getSlots(), "normalized slots must be unmodifiable");
        require(GSON.toJson(arrangement).contains("\"slots\":[]"),
                "constructor normalization must serialize an explicit empty slot list");

        SaveArrangementRequestDTO request = new SaveArrangementRequestDTO(
                "operation-81", null, 0, "1", "2", "T1001", null, "3",
                null, 5, 10, false, null);
        require(request.getSlots().isEmpty(),
                "null request slots must normalize to an empty list");
        requireUnmodifiable(request.getSlots(),
                "normalized request slots must be unmodifiable");
        require(GSON.toJson(request).contains("\"slots\":[]"),
                "an empty request slot list must serialize explicitly");

        SchedulePlanDTO plan =
                new SchedulePlanDTO("9007199254740995", "Plan", 1, "DRAFT", false, null);
        require(plan.getConflicts().isEmpty(),
                "null plan conflicts must normalize to an empty list");
        requireUnmodifiable(plan.getConflicts(),
                "normalized plan conflicts must be unmodifiable");
    }

    private static void slotListsAreDefensiveAndUnmodifiable() {
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 3, 4));
        slots.add(new ScheduleSlotDTO(3, 1, 2));

        ScheduleArrangementDTO arrangement =
                arrangement(teacher(), null, classroom(), slots);
        slots.clear();

        require(arrangement.getSlots().size() == 2,
                "arrangement slots must be a defensive copy");
        requireUnmodifiable(arrangement.getSlots(),
                "arrangement slots must be unmodifiable");

        List<ScheduleSlotDTO> requestSlots = new ArrayList<>();
        requestSlots.add(new ScheduleSlotDTO(1, 3, 4));
        SaveArrangementRequestDTO request = new SaveArrangementRequestDTO(
                "operation-91", "9007199254740993", 3, "9007199254740995",
                "9007199254740997", "T1001", null, "9007199254741003",
                requestSlots, 5, 10, false, null);
        requestSlots.clear();

        require(request.getSlots().size() == 1,
                "request slots must be a defensive copy");
        requireUnmodifiable(request.getSlots(), "request slots must be unmodifiable");
    }

    private static void deserializedListsAreUnmodifiable() {
        ScheduleArrangementDTO arrangement = GSON.fromJson(
                GSON.toJson(arrangement(teacher(), null, classroom())),
                ScheduleArrangementDTO.class);
        requireUnmodifiable(arrangement.getSlots(),
                "deserialized arrangement slots must be unmodifiable");

        SchedulePlanDTO plan = GSON.fromJson(
                GSON.toJson(new SchedulePlanDTO("9007199254740995", "Plan", 1, "DRAFT",
                        false, Arrays.asList(blockingConflict()))),
                SchedulePlanDTO.class);
        requireUnmodifiable(plan.getConflicts(),
                "deserialized plan conflicts must be unmodifiable");

        SaveArrangementRequestDTO request = GSON.fromJson(
                GSON.toJson(saveRequest("operation-92", false)),
                SaveArrangementRequestDTO.class);
        requireUnmodifiable(request.getSlots(),
                "deserialized request slots must be unmodifiable");

        String explicitNullSlots = "{\"operationId\":\"operation-93\","
                + "\"expectedVersion\":0,\"planId\":\"1\",\"offeringId\":\"2\","
                + "\"teacherUid\":\"T1\",\"classroomId\":\"3\",\"slots\":null,"
                + "\"startWeek\":5,\"endWeek\":10,\"force\":false}";
        SaveArrangementRequestDTO explicitNullCopy =
                GSON.fromJson(explicitNullSlots, SaveArrangementRequestDTO.class);
        require(explicitNullCopy.getSlots().isEmpty(),
                "explicit JSON null slots must normalize to an empty list");
        requireUnmodifiable(explicitNullCopy.getSlots(),
                "explicit JSON null slots must be unmodifiable");
    }

    private static void pinsExactJsonKeySets() {
        requireKeySet(new ScheduleSlotDTO(1, 3, 4), Arrays.asList(
                "dayOfWeek", "startPeriod", "endPeriod"));

        requireKeySet(teacher(), Arrays.asList(
                "resourceId", "businessId", "name", "resourceType", "capacity"));

        requireKeySet(arrangement(teacher(), assistant(), classroom()), Arrays.asList(
                "arrangementId", "planId", "offeringId", "teacher", "assistant",
                "classroom", "slots", "startWeek", "endWeek", "status", "version"));

        requireKeySet(new SchedulePlanDTO("1", "Plan", 1, "DRAFT", false, null),
                Arrays.asList(
                        "planId", "name", "revision", "status", "current", "conflicts"));

        requireKeySet(fullyPopulatedSaveRequest(), Arrays.asList(
                "operationId", "arrangementId", "expectedVersion", "planId",
                "offeringId", "teacherUid", "assistantUid", "classroomId", "slots",
                "startWeek", "endWeek", "force", "overrideReason"));
    }

    private static void exposesExactSchedulingServiceMethods() throws Exception {
        String future = "java.util.concurrent.CompletableFuture";
        String list = "java.util.List";
        String resource = "dto.course.admin.schedule.ScheduleResourceDTO";
        String plan = "dto.course.admin.schedule.SchedulePlanDTO";
        String check = "dto.course.admin.schedule.CheckArrangementResultDTO";
        String result = "model.course.admin.AdminOperationResultView";
        String arrangementView = "model.course.admin.ScheduleArrangementView";
        String planView = "model.course.admin.SchedulePlanView";

        requireMethod(AdminCourseService.class, "listScheduleResources",
                future + "<" + list + "<" + resource + ">>",
                String.class, String.class);
        requireMethod(AdminCourseService.class, "loadSchedulePlan",
                future + "<" + plan + ">", int.class, int.class);
        requireMethod(AdminCourseService.class, "loadOfferingArrangements",
                future + "<" + list + "<" + arrangementView + ">>",
                String.class, String.class);
        requireMethod(AdminCourseService.class, "checkArrangement",
                future + "<" + check + ">", SaveArrangementRequestDTO.class);
        requireMethod(AdminCourseService.class, "saveArrangement",
                future + "<" + result + "<" + arrangementView + ">>",
                SaveArrangementRequestDTO.class);
        requireMethod(AdminCourseService.class, "deleteArrangement",
                future + "<" + result + "<java.lang.Void>>",
                String.class, int.class, String.class);
        requireMethod(AdminCourseService.class, "publishSchedulePlan",
                future + "<" + result + "<" + planView + ">>",
                String.class, int.class, String.class, boolean.class, String.class);
    }

    private static void clientViewsExposeSchedulingStateWithoutJavaFx() {
        ScheduleArrangementView view = new ScheduleArrangementView(
                "9007199254740993", "9007199254740995", "9007199254740997",
                teacher(), null, classroom(), Arrays.asList(new ScheduleSlotDTO(1, 3, 4)),
                5, 10, "ACTIVE", 3);

        require("9007199254740993".equals(view.getArrangementId()),
                "view arrangement ID must be an exact decimal string");
        require(view.getAssistant() == null, "view assistant must stay nullable");
        require(view.getSlots().size() == 1, "view must expose its slots");
        require(view.getStartWeek() == 5 && view.getEndWeek() == 10,
                "view must expose the shared week range");
        require(view.getVersion() == 3, "view must expose its version");

        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 3, 4));
        ScheduleArrangementView defensive = new ScheduleArrangementView(
                "1", "2", "3", teacher(), null, classroom(), slots, 5, 10, "ACTIVE", 1);
        slots.clear();
        require(defensive.getSlots().size() == 1,
                "view slots must be a defensive copy");
        requireUnmodifiable(defensive.getSlots(), "view slots must be unmodifiable");

        SchedulePlanView planView = new SchedulePlanView(
                "9007199254740995", "2025-2026 学年第一学期", 4, "DRAFT", true,
                Arrays.asList(blockingConflict()));
        require(planView.isCurrent(), "plan view must expose the current flag");
        require(planView.getConflicts().size() == 1,
                "plan view must expose its conflicts");
        requireUnmodifiable(planView.getConflicts(),
                "plan view conflicts must be unmodifiable");

        for (Class<?> viewType : Arrays.asList(
                ScheduleArrangementView.class, SchedulePlanView.class)) {
            for (Method method : viewType.getDeclaredMethods()) {
                Class<?> returned = method.getReturnType();
                require(!javafx.scene.Node.class.isAssignableFrom(returned),
                        viewType.getSimpleName() + "." + method.getName()
                                + " must not expose a JavaFX control");
                require(!javafx.scene.Parent.class.isAssignableFrom(returned),
                        viewType.getSimpleName() + "." + method.getName()
                                + " must not expose a JavaFX control");
            }
        }
    }

    private static void requireMethod(Class<?> owner, String name, String returnType,
            Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        require(method.getGenericReturnType().getTypeName().equals(returnType),
                owner.getSimpleName() + "." + name + " must return " + returnType
                        + " but returned " + method.getGenericReturnType().getTypeName());
    }

    private static void requireKeySet(Object sample, List<String> expected) {
        Set<String> actual =
                new LinkedHashSet<>(GSON.toJsonTree(sample).getAsJsonObject().keySet());
        Set<String> expectedSet = new LinkedHashSet<>(expected);

        require(actual.equals(expectedSet),
                "wire keys for " + sample.getClass().getSimpleName() + " must be "
                        + expectedSet + " but were " + actual);
    }

    private static <T> void requireUnmodifiable(List<T> values, String message) {
        requireAppendRejected(values, message);

        if (values.isEmpty()) {
            return;
        }
        try {
            values.clear();
            throw new AssertionError(message + " (clear allowed)");
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
        try {
            values.set(0, values.get(0));
            throw new AssertionError(message + " (element replacement allowed)");
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
    }

    private static <T> void requireAppendRejected(List<T> values, String message) {
        try {
            values.add(null);
            throw new AssertionError(message + " (element append allowed)");
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
    }

    private static ScheduleArrangementDTO arrangement(ScheduleResourceDTO teacher,
            ScheduleResourceDTO assistant, ScheduleResourceDTO classroom) {
        return arrangement(teacher, assistant, classroom, twoSlots());
    }

    private static ScheduleArrangementDTO arrangement(ScheduleResourceDTO teacher,
            ScheduleResourceDTO assistant, ScheduleResourceDTO classroom,
            List<ScheduleSlotDTO> slots) {
        return new ScheduleArrangementDTO(
                "9007199254740993", "9007199254740995", "9007199254740997",
                teacher, assistant, classroom, slots, 5, 10, "ACTIVE", 3);
    }

    private static List<ScheduleSlotDTO> twoSlots() {
        return Arrays.asList(
                new ScheduleSlotDTO(1, 3, 4),
                new ScheduleSlotDTO(3, 1, 2));
    }

    private static SaveArrangementRequestDTO saveRequest(String operationId, boolean force) {
        return new SaveArrangementRequestDTO(
                operationId, "9007199254740993", 3, "9007199254740995",
                "9007199254740997", "T1001", null, "9007199254741003",
                twoSlots(), 5, 10, force, force ? "教师时间冲突，已确认" : null);
    }

    private static SaveArrangementRequestDTO fullyPopulatedSaveRequest() {
        return new SaveArrangementRequestDTO(
                "operation-101", "9007199254740993", 3, "9007199254740995",
                "9007199254740997", "T1001", "T2001", "9007199254741003",
                twoSlots(), 5, 10, true, "教师时间冲突，已确认");
    }

    private static ScheduleResourceDTO teacher() {
        return new ScheduleResourceDTO("9007199254740999", "T1001", "张老师", "TEACHER", 0);
    }

    private static ScheduleResourceDTO assistant() {
        return new ScheduleResourceDTO("9007199254741001", "T2001", "李助教", "ASSISTANT", 0);
    }

    private static ScheduleResourceDTO classroom() {
        return new ScheduleResourceDTO(
                "9007199254741003", "R200", "理科楼 201", "CLASSROOM", 120);
    }

    private static ScheduleConflictDTO blockingConflict() {
        return new ScheduleConflictDTO(
                "OFFERING_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                "9007199254740993", "9007199254740997",
                5, 1, 3, 4, "同一教学班排课重叠");
    }

    /** 带归属教学班的新构造：新增的 offeringId/offeringLabel 必须往返 JSON。 */
    private static ScheduleConflictDTO attributedConflict() {
        return new ScheduleConflictDTO(
                "OFFERING_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                "9007199254740993", "9007199254740997", "2004", "CS202-2026-2-A",
                5, 1, 3, 4, "同一教学班排课重叠");
    }

    private static ScheduleConflictDTO overridableConflict() {
        return new ScheduleConflictDTO(
                "TEACHER_OVERLAP", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "T1001", "9007199254740997",
                6, 3, 1, 2, "教师时间冲突");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
