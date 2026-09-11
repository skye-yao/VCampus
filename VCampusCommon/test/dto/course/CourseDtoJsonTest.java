package dto.course;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public final class CourseDtoJsonTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws ReflectiveOperationException {
        roundTripsSixStateOfferingContract();
        roundTripsCourseTermListWithTypeToken();
        preservesExplicitCourseOfferingRelationInSnapshot();
        collectionFieldsAreDefensiveAndUnmodifiable();
        deserializedCollectionFieldsAreUnmodifiable();
        roundTripsMutationResultFieldsIndependently();
        roundTripsPushEventContract();
        exposesExactCourseActions();
    }

    private static void roundTripsSixStateOfferingContract() {
        require(SelectionStateDTO.values().length == 6, "six selection states required");

        CourseOfferingDTO source = offeredOffering();
        CourseOfferingDTO copy = GSON.fromJson(GSON.toJson(source), CourseOfferingDTO.class);

        require("9007199254740993".equals(copy.getOfferingId()), "BIGINT must remain exact");
        require("course-203".equals(copy.getCourseId()), "course ID must round-trip");
        require(copy.getTeachers().size() == 2, "multiple teachers must survive JSON");
        require("teacher-2".equals(copy.getTeachers().get(1).getUid()),
                "teacher fields must survive JSON");
        require(copy.getMeetings().size() == 2, "multiple meetings must survive JSON");
        require("Lab 2".equals(copy.getMeetings().get(1).getLocation()),
                "meeting fields must survive JSON");
        require(copy.getSelectionState() == SelectionStateDTO.WAITLIST_OFFERED,
                "WAITLIST_OFFERED must survive JSON");
        require("2026-09-10T01:05:00Z".equals(copy.getOfferedAt()),
                "offer timestamp required");
        require("2026-09-10T02:05:00Z".equals(copy.getExpiresAt()), "deadline required");

        CourseOfferingDTO available = new CourseOfferingDTO(
                "42", "course-101", new ArrayList<>(), new ArrayList<>(),
                10, 30, SelectionStateDTO.AVAILABLE, null, null, null);
        CourseOfferingDTO availableCopy = GSON.fromJson(
                GSON.toJson(available), CourseOfferingDTO.class);
        require(availableCopy.getFailureReason() == null,
                "nullable failure reason must stay null");
        require(availableCopy.getOfferedAt() == null,
                "nullable offer timestamp must stay null");
        require(availableCopy.getExpiresAt() == null,
                "nullable deadline must stay null");
    }

    private static void roundTripsCourseTermListWithTypeToken() {
        List<CourseTermDTO> source = Arrays.asList(
                new CourseTermDTO(2025, 1, "2025-2026 Term 1"),
                new CourseTermDTO(2024, 2, "2024-2025 Term 2"));
        Type type = new TypeToken<List<CourseTermDTO>>() {}.getType();

        List<CourseTermDTO> copy = GSON.fromJson(GSON.toJson(source), type);

        require(copy.size() == 2, "term list size must round-trip");
        require(copy.get(0).getAcademicYear() == 2025,
                "integer academic year must round-trip");
        require(copy.get(0).getSemester() == 1, "semester must round-trip");
        require("2025-2026 Term 1".equals(copy.get(0).getDisplayName()),
                "display name must round-trip");
    }

    private static void preservesExplicitCourseOfferingRelationInSnapshot() {
        CoursePlanSnapshotDTO snapshot = completeSnapshot();
        CoursePlanSnapshotDTO copy = GSON.fromJson(
                GSON.toJson(snapshot), CoursePlanSnapshotDTO.class);

        require(copy.getPlanItems().get(0).getCourse().getCourseId()
                .equals(copy.getPlanItems().get(0).getOffering().getCourseId()),
                "course/offering relation must stay explicit");
        require("CS203".equals(copy.getPlanItems().get(0).getCourse().getCourseCode()),
                "course fields must survive JSON");
    }

    private static void collectionFieldsAreDefensiveAndUnmodifiable() {
        List<CourseTeacherDTO> teachers = new ArrayList<>(teacherList());
        List<CourseMeetingDTO> meetings = new ArrayList<>(meetingList());
        CourseOfferingDTO offering = new CourseOfferingDTO(
                "101", "course-203", teachers, meetings, 96, 120,
                SelectionStateDTO.AVAILABLE, null, null, null);
        teachers.clear();
        meetings.clear();
        require(offering.getTeachers().size() == 2,
                "teachers must be a defensive copy");
        require(offering.getMeetings().size() == 2,
                "meetings must be a defensive copy");
        requireUnmodifiable(offering.getTeachers(), "teachers must be unmodifiable");
        requireUnmodifiable(offering.getMeetings(), "meetings must be unmodifiable");

        List<CourseSelectionItemDTO> planItems = new ArrayList<>();
        List<CourseSelectionItemDTO> waitlistItems = new ArrayList<>();
        List<CourseSelectionItemDTO> enrolledItems = new ArrayList<>();
        planItems.add(selectionItem(SelectionStateDTO.PLANNED));
        waitlistItems.add(selectionItem(SelectionStateDTO.WAITLISTED));
        enrolledItems.add(selectionItem(SelectionStateDTO.ENROLLED));
        CoursePlanSnapshotDTO snapshot = new CoursePlanSnapshotDTO(
                new CourseTermDTO(2025, 1, "2025-2026 Term 1"),
                planItems, waitlistItems, enrolledItems);
        planItems.clear();
        waitlistItems.clear();
        enrolledItems.clear();
        require(snapshot.getPlanItems().size() == 1,
                "plan items must be a defensive copy");
        require(snapshot.getWaitlistItems().size() == 1,
                "waitlist items must be a defensive copy");
        require(snapshot.getEnrolledItems().size() == 1,
                "enrolled items must be a defensive copy");
        requireUnmodifiable(snapshot.getPlanItems(), "plan items must be unmodifiable");
        requireUnmodifiable(snapshot.getWaitlistItems(),
                "waitlist items must be unmodifiable");
        requireUnmodifiable(snapshot.getEnrolledItems(),
                "enrolled items must be unmodifiable");
    }

    private static void deserializedCollectionFieldsAreUnmodifiable() {
        CourseOfferingDTO offeringCopy = GSON.fromJson(
                GSON.toJson(offeredOffering()), CourseOfferingDTO.class);
        requireUnmodifiable(offeringCopy.getTeachers(),
                "deserialized teachers must be unmodifiable");
        requireUnmodifiable(offeringCopy.getMeetings(),
                "deserialized meetings must be unmodifiable");

        CoursePlanSnapshotDTO snapshotCopy = GSON.fromJson(
                GSON.toJson(completeSnapshot()), CoursePlanSnapshotDTO.class);
        requireUnmodifiable(snapshotCopy.getPlanItems(),
                "deserialized plan items must be unmodifiable");
        requireUnmodifiable(snapshotCopy.getWaitlistItems(),
                "deserialized waitlist items must be unmodifiable");
        requireUnmodifiable(snapshotCopy.getEnrolledItems(),
                "deserialized enrolled items must be unmodifiable");
    }

    private static void roundTripsMutationResultFieldsIndependently() {
        CourseSelectionItemDTO affectedItem = selectionItem(SelectionStateDTO.WAITLIST_OFFERED);
        CoursePlanSnapshotDTO snapshot = completeSnapshot();
        CourseMutationResultDTO source = new CourseMutationResultDTO(
                "operation-77", affectedItem, SelectionStateDTO.WAITLIST_OFFERED,
                "WAITLIST_OFFER_CREATED", "Offer expires in one hour", snapshot);

        CourseMutationResultDTO copy = GSON.fromJson(
                GSON.toJson(source), CourseMutationResultDTO.class);

        require("operation-77".equals(copy.getOperationId()),
                "operation ID must survive JSON");
        require("9007199254740993".equals(copy.getItem().getOffering().getOfferingId()),
                "affected item must survive JSON");
        require(copy.getFinalState() == SelectionStateDTO.WAITLIST_OFFERED,
                "explicit final state must survive JSON");
        require("WAITLIST_OFFER_CREATED".equals(copy.getOutcomeCode()),
                "outcome code must survive JSON");
        require("Offer expires in one hour".equals(copy.getMessage()),
                "message must survive JSON");
        require(copy.getSnapshot().getTerm().getAcademicYear() == 2025,
                "snapshot term must survive JSON");
        require(copy.getSnapshot().getPlanItems().size() == 1,
                "snapshot plan items must survive JSON");
        require(copy.getSnapshot().getWaitlistItems().size() == 1,
                "snapshot waitlist items must survive JSON");
        require(copy.getSnapshot().getEnrolledItems().size() == 1,
                "snapshot enrolled items must survive JSON");
        require(copy.getItem().getOffering().getSelectionState()
                        != copy.getSnapshot().getPlanItems().get(0).getOffering().getSelectionState(),
                "direct mutation item must not be replaced by snapshot data");
    }

    private static void roundTripsPushEventContract() {
        CoursePushEventTypeDTO[] expectedTypes = {
                CoursePushEventTypeDTO.WAITLIST_OFFERED,
                CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED,
                CoursePushEventTypeDTO.WAITLIST_OFFER_EXPIRED,
                CoursePushEventTypeDTO.WAITLIST_OFFER_ABANDONED
        };
        require(Arrays.equals(expectedTypes, CoursePushEventTypeDTO.values()),
                "push event types must match the wire contract");

        CoursePushEventDTO source = new CoursePushEventDTO(
                "event-9", CoursePushEventTypeDTO.WAITLIST_OFFERED,
                new CourseTermDTO(2025, 1, "2025-2026 Term 1"),
                "9007199254740993", "2026-09-10T01:05:00Z",
                "2026-09-10T02:05:00Z", "A seat is available");
        CoursePushEventDTO copy = GSON.fromJson(
                GSON.toJson(source), CoursePushEventDTO.class);

        require("event-9".equals(copy.getEventId()), "event ID must survive JSON");
        require(copy.getEventType() == CoursePushEventTypeDTO.WAITLIST_OFFERED,
                "event type must survive JSON");
        require(copy.getTerm().getAcademicYear() == 2025,
                "event term must survive JSON");
        require("9007199254740993".equals(copy.getOfferingId()),
                "event offering ID must remain exact");
        require("2026-09-10T01:05:00Z".equals(copy.getOccurredAt()),
                "event occurrence timestamp must survive JSON");
        require("2026-09-10T02:05:00Z".equals(copy.getExpiresAt()),
                "event deadline must survive JSON");
        require("A seat is available".equals(copy.getMessage()),
                "event message must survive JSON");

        CoursePushEventDTO withoutDeadline = new CoursePushEventDTO(
                "event-10", CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED,
                new CourseTermDTO(2025, 1, "2025-2026 Term 1"),
                "42", "2026-09-10T03:05:00Z", null, "Enrolled");
        CoursePushEventDTO withoutDeadlineCopy = GSON.fromJson(
                GSON.toJson(withoutDeadline), CoursePushEventDTO.class);
        require(withoutDeadlineCopy.getExpiresAt() == null,
                "nullable event deadline must stay null");
    }

    private static void exposesExactCourseActions() throws IllegalAccessException {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("LIST_TERMS", "listTerms");
        expected.put("LIST_COURSES", "listCourses");
        expected.put("LIST_COURSE_OFFERINGS", "listCourseOfferings");
        expected.put("LOAD_SELECTION_SNAPSHOT", "loadSelectionSnapshot");
        expected.put("ADD_TO_PLAN", "addToPlan");
        expected.put("REMOVE_FROM_PLAN", "removeFromPlan");
        expected.put("SELECT_OFFERING", "selectOffering");
        expected.put("JOIN_WAITLIST", "joinWaitlist");
        expected.put("CANCEL_WAITLIST", "cancelWaitlist");
        expected.put("RESOLVE_WAITLIST_OFFER", "resolveWaitlistOffer");
        expected.put("DROP_OFFERING", "dropOffering");
        expected.put("ACK_COURSE_EVENT", "ackCourseEvent");
        expected.put("LOAD_SCHEDULE", "loadSchedule");
        expected.put("LOAD_NOTICES", "loadNotices");
        expected.put("LOAD_GRADES", "loadGrades");
        expected.put("LOAD_TRAINING_PLAN", "loadTrainingPlan");
        expected.put("SELECTION_EVENT", "selectionEvent");

        Map<String, String> actual = new LinkedHashMap<>();
        for (Field field : CourseActions.class.getDeclaredFields()) {
            if (field.getType() == String.class
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                actual.put(field.getName(), (String) field.get(null));
            }
        }

        require(expected.equals(actual),
                "CourseActions must contain exactly the specified action constants");
        require(!actual.containsKey("CONFIRM_PLAN"), "CONFIRM_PLAN must be removed");
        require(!actual.containsKey("LEAVE_WAITLIST"), "LEAVE_WAITLIST must be removed");
        require(!actual.containsKey("DROP_COURSE"), "DROP_COURSE must be removed");
    }

    private static CourseOfferingDTO offeredOffering() {
        return new CourseOfferingDTO(
                "9007199254740993", "course-203", teacherList(), meetingList(),
                96, 120, SelectionStateDTO.WAITLIST_OFFERED, null,
                "2026-09-10T01:05:00Z", "2026-09-10T02:05:00Z");
    }

    private static CoursePlanSnapshotDTO completeSnapshot() {
        return new CoursePlanSnapshotDTO(
                new CourseTermDTO(2025, 1, "2025-2026 Term 1"),
                Arrays.asList(selectionItem(SelectionStateDTO.PLANNED)),
                Arrays.asList(selectionItem(SelectionStateDTO.WAITLISTED)),
                Arrays.asList(selectionItem(SelectionStateDTO.ENROLLED)));
    }

    private static CourseSelectionItemDTO selectionItem(SelectionStateDTO state) {
        CourseDTO course = new CourseDTO(
                "course-203", "CS203", "Data Structures", "Required",
                4.0, 64, "Lists, trees, and graphs", "Programming Fundamentals");
        CourseOfferingDTO offering = new CourseOfferingDTO(
                "9007199254740993", course.getCourseId(), teacherList(), meetingList(),
                96, 120, state, null,
                state == SelectionStateDTO.WAITLIST_OFFERED
                        ? "2026-09-10T01:05:00Z" : null,
                state == SelectionStateDTO.WAITLIST_OFFERED
                        ? "2026-09-10T02:05:00Z" : null);
        return new CourseSelectionItemDTO(course, offering);
    }

    private static List<CourseTeacherDTO> teacherList() {
        return Arrays.asList(
                new CourseTeacherDTO("teacher-1", "Teacher One"),
                new CourseTeacherDTO("teacher-2", "Teacher Two"));
    }

    private static List<CourseMeetingDTO> meetingList() {
        return Arrays.asList(
                new CourseMeetingDTO(2, 3, 4, 1, 16, "ALL", "Room 201",
                        "2026-09-08T01:00:00Z", "2026-09-08T02:40:00Z"),
                new CourseMeetingDTO(4, 5, 6, 1, 16, "ODD", "Lab 2",
                        "2026-09-10T03:00:00Z", "2026-09-10T04:40:00Z"));
    }

    private static <T> void requireUnmodifiable(List<T> values, String message) {
        try {
            values.clear();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }

        if (values.isEmpty()) {
            return;
        }
        try {
            values.set(0, values.get(0));
            throw new AssertionError(message + " (element replacement allowed)");
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
