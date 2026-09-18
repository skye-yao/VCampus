package dto.course.admin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;

public final class AdminCatalogDtoJsonTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws ReflectiveOperationException {
        roundTripsAdminCourseWithBigintVersionAndFlags();
        roundTripsAdminOfferingWithNullableAssistant();
        roundTripsCourseEditorRequestWithOptimisticVersion();
        roundTripsOfferingEditorRequestWithOptimisticVersion();
        roundTripsScheduleConflictFields();
        roundTripsOperationResultWithTypedEntityAndConflicts();
        normalizesNullConflictsToEmptyImmutableList();
        conflictListsAreDefensiveAndUnmodifiable();
        deserializedConflictListsAreUnmodifiable();
        pinsExactJsonKeySets();
        exposesExactScheduleConflictSeverities();
        exposesExactAdminCourseActions();
    }

    private static void roundTripsAdminCourseWithBigintVersionAndFlags() {
        AdminCourseDTO source = course("9007199254740993");

        AdminCourseDTO copy = GSON.fromJson(GSON.toJson(source), AdminCourseDTO.class);

        require("9007199254740993".equals(copy.getCourseId()),
                "BIGINT course ID must remain exact");
        require("CS203".equals(copy.getCourseCode()), "course code must round-trip");
        require("Data Structures".equals(copy.getCourseName()),
                "course name must round-trip");
        require("Required".equals(copy.getCourseType()), "course type must round-trip");
        require(copy.getCredit() == 4.0, "credit must round-trip");
        require(copy.getCreditHours() == 64, "credit hours must round-trip");
        require("Lists, trees, and graphs".equals(copy.getDescription()),
                "description must round-trip");
        require("Programming Fundamentals".equals(copy.getPrerequisites()),
                "prerequisites must round-trip");
        require(copy.isAllowCrossMajor(),
                "true allowCrossMajor must round-trip");
        require(!copy.isFinalExam(), "false finalExam must round-trip");
        require("ACTIVE".equals(copy.getStatus()), "status must round-trip");
        require(copy.getOfferingCount() == 3, "offering count must round-trip");
        require(copy.getVersion() == 7, "optimistic version must round-trip");

        AdminCourseDTO minimal = new AdminCourseDTO(
                "42", "CS101", "Programming", "Required",
                3.0, 48, null, null, false, true, "DRAFT", 0, 0);
        AdminCourseDTO minimalCopy = GSON.fromJson(GSON.toJson(minimal), AdminCourseDTO.class);
        require(!minimalCopy.isAllowCrossMajor(),
                "false allowCrossMajor must round-trip");
        require(minimalCopy.isFinalExam(), "true finalExam must round-trip");
        require(minimalCopy.getDescription() == null,
                "nullable description must stay null");
        require(minimalCopy.getPrerequisites() == null,
                "nullable prerequisites must stay null");
    }

    private static void roundTripsAdminOfferingWithNullableAssistant() {
        AdminOfferingDTO assigned = offering("9007199254740993");

        AdminOfferingDTO copy = GSON.fromJson(GSON.toJson(assigned), AdminOfferingDTO.class);

        require("9007199254740993".equals(copy.getOfferingId()),
                "BIGINT offering ID must remain exact");
        require("CS203-2025-1-A".equals(copy.getOfferingCode()),
                "offering code must round-trip");
        require("course-203".equals(copy.getCourseId()), "course ID must round-trip");
        require(copy.getAcademicYear() == 2025, "academic year must round-trip");
        require(copy.getSemester() == 1, "semester must round-trip");
        require(copy.getCapacity() == 120, "capacity must round-trip");
        require(copy.getEnrolledCount() == 96, "enrolled count must round-trip");
        require("OPEN".equals(copy.getStatus()), "offering status must round-trip");
        require("teacher-1".equals(copy.getTeacherUid()), "teacher UID must round-trip");
        require("Teacher One".equals(copy.getTeacherName()),
                "teacher name must round-trip");
        require("assistant-9".equals(copy.getAssistantUid()),
                "assistant UID must round-trip");
        require("Assistant Nine".equals(copy.getAssistantName()),
                "assistant name must round-trip");
        require("PUBLISHED".equals(copy.getScheduleStatus()),
                "schedule status must round-trip");
        require(copy.getVersion() == 4, "optimistic version must round-trip");

        AdminOfferingDTO unassigned = new AdminOfferingDTO(
                "9007199254740995", "CS203-2025-1-B", "course-203",
                2025, 1, 60, 0, "DRAFT", "teacher-2", "Teacher Two",
                null, null, "UNSCHEDULED", 1);
        AdminOfferingDTO unassignedCopy = GSON.fromJson(
                GSON.toJson(unassigned), AdminOfferingDTO.class);
        require(unassignedCopy.getAssistantUid() == null,
                "nullable assistant UID must stay null");
        require(unassignedCopy.getAssistantName() == null,
                "nullable assistant name must stay null");
    }

    private static void roundTripsCourseEditorRequestWithOptimisticVersion() {
        CourseEditorRequestDTO create = courseEditorCreate();

        CourseEditorRequestDTO createCopy = GSON.fromJson(
                GSON.toJson(create), CourseEditorRequestDTO.class);

        require("operation-11".equals(createCopy.getOperationId()),
                "operation ID must round-trip");
        require(createCopy.getCourseId() == null,
                "create request must keep a null course ID");
        require(createCopy.getExpectedVersion() == 0,
                "create request version must round-trip");
        require("CS999".equals(createCopy.getCourseCode()),
                "UTF-8 course code must survive JSON");
        require("测试课程".equals(createCopy.getCourseName()),
                "UTF-8 course name must survive JSON");
        require("选修".equals(createCopy.getCourseType()),
                "UTF-8 course type must survive JSON");
        require(createCopy.getCredit() == 2.0, "credit must round-trip");
        require(createCopy.getCreditHours() == 32, "credit hours must round-trip");
        require("说明".equals(createCopy.getDescription()),
                "UTF-8 description must survive JSON");
        require("无".equals(createCopy.getPrerequisites()),
                "UTF-8 prerequisites must survive JSON");
        require(!createCopy.isAllowCrossMajor(),
                "false allowCrossMajor must round-trip");
        require(createCopy.isFinalExam(), "finalExam must round-trip");

        CourseEditorRequestDTO update = courseEditorUpdate();
        CourseEditorRequestDTO updateCopy = GSON.fromJson(
                GSON.toJson(update), CourseEditorRequestDTO.class);
        require("9007199254740993".equals(updateCopy.getCourseId()),
                "BIGINT course ID must remain exact");
        require(updateCopy.getExpectedVersion() == 5,
                "optimistic expected version must round-trip");
        require(updateCopy.isAllowCrossMajor(), "allowCrossMajor must round-trip");
        require(!updateCopy.isFinalExam(), "false finalExam must round-trip");
    }

    private static void roundTripsOfferingEditorRequestWithOptimisticVersion() {
        OfferingEditorRequestDTO update = offeringEditorUpdate();
        OfferingEditorRequestDTO copy = GSON.fromJson(
                GSON.toJson(update), OfferingEditorRequestDTO.class);

        require("operation-21".equals(copy.getOperationId()),
                "operation ID must round-trip");
        require("9007199254740993".equals(copy.getOfferingId()),
                "BIGINT offering ID must remain exact");
        require(copy.getExpectedVersion() == 5,
                "optimistic expected version must round-trip");
        require("course-203".equals(copy.getCourseId()), "course ID must round-trip");
        require("CS203-2025-1-A".equals(copy.getOfferingCode()),
                "offering code must round-trip");
        require(copy.getAcademicYear() == 2025, "academic year must round-trip");
        require(copy.getSemester() == 2,
                "semester must round-trip distinctly from status");
        require(copy.getCapacity() == 120, "capacity must round-trip");
        require("teacher-1".equals(copy.getTeacherUid()), "teacher UID must round-trip");
        require("assistant-9".equals(copy.getAssistantUid()),
                "assistant UID must round-trip");
        require(copy.getStatus() == 1, "status must round-trip distinctly from semester");

        OfferingEditorRequestDTO create = offeringEditorCreate();
        OfferingEditorRequestDTO createCopy = GSON.fromJson(
                GSON.toJson(create), OfferingEditorRequestDTO.class);
        require("operation-22".equals(createCopy.getOperationId()),
                "operation ID must round-trip");
        require(createCopy.getOfferingId() == null,
                "create request must keep a null offering ID");
        require(createCopy.getExpectedVersion() == 0,
                "create request version must round-trip");
        require("course-203".equals(createCopy.getCourseId()),
                "course ID must round-trip");
        require("CS203-2025-1-B".equals(createCopy.getOfferingCode()),
                "offering code must round-trip");
        require(createCopy.getAcademicYear() == 2024, "academic year must round-trip");
        require(createCopy.getSemester() == 1,
                "semester must round-trip distinctly from status");
        require(createCopy.getCapacity() == 60, "capacity must round-trip");
        require("teacher-2".equals(createCopy.getTeacherUid()),
                "teacher UID must round-trip");
        require(createCopy.getAssistantUid() == null,
                "nullable assistant UID must stay null");
        require(createCopy.getStatus() == 2,
                "status must round-trip distinctly from expected version");
    }

    private static void roundTripsScheduleConflictFields() {
        ScheduleConflictDTO copy = GSON.fromJson(
                GSON.toJson(blockingConflict()), ScheduleConflictDTO.class);

        require("TEACHER_DOUBLE_BOOKED".equals(copy.getType()), "type must round-trip");
        require(copy.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                "severity must round-trip");
        require("offering-9007199254740993".equals(copy.getSubjectId()),
                "conflict subject must round-trip");
        require("9007199254740995".equals(copy.getRelatedOfferingId()),
                "BIGINT related offering ID must remain exact");
        require(copy.getWeek() == 1, "week must round-trip distinctly from periods");
        require(copy.getDayOfWeek() == 2, "day of week must round-trip");
        require(copy.getStartPeriod() == 3, "start period must round-trip");
        require(copy.getEndPeriod() == 4, "end period must round-trip");
        require("Teacher is booked in this period".equals(copy.getMessage()),
                "conflict message must round-trip");

        ScheduleConflictDTO standalone = scheduleConflictWithoutRelatedOffering();
        ScheduleConflictDTO standaloneCopy = GSON.fromJson(
                GSON.toJson(standalone), ScheduleConflictDTO.class);
        require("ROOM_CAPACITY".equals(standaloneCopy.getType()), "type must round-trip");
        require(standaloneCopy.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "severity must round-trip");
        require("room-7".equals(standaloneCopy.getSubjectId()),
                "conflict subject must round-trip");
        require(standaloneCopy.getRelatedOfferingId() == null,
                "nullable related offering ID must stay null");
        require(standaloneCopy.getWeek() == 5, "week must round-trip");
        require(standaloneCopy.getDayOfWeek() == 4, "day of week must round-trip");
        require(standaloneCopy.getStartPeriod() == 1, "start period must round-trip");
        require(standaloneCopy.getEndPeriod() == 2, "end period must round-trip");
        require("Room capacity exceeded".equals(standaloneCopy.getMessage()),
                "conflict message must round-trip");
    }

    private static void roundTripsOperationResultWithTypedEntityAndConflicts() {
        AdminOperationResultDTO<AdminOfferingDTO> source = offeringResult();
        Type type = new TypeToken<AdminOperationResultDTO<AdminOfferingDTO>>() {}.getType();

        AdminOperationResultDTO<AdminOfferingDTO> copy =
                GSON.fromJson(GSON.toJson(source), type);

        require("operation-31".equals(copy.getOperationId()),
                "operation ID must round-trip");
        require("OFFERING_CREATED".equals(copy.getOutcomeCode()),
                "outcome code must round-trip");
        require("Offering created".equals(copy.getMessage()), "message must round-trip");
        require(copy.getEntity() != null, "typed entity must survive JSON");
        require("9007199254740993".equals(copy.getEntity().getOfferingId()),
                "typed entity BIGINT ID must remain exact");
        require(copy.getConflicts().size() == 2, "conflict list must round-trip");
        require(copy.getConflicts().get(0).getSeverity()
                        == ScheduleConflictSeverityDTO.BLOCKING,
                "first conflict severity must survive JSON");
        require(copy.getConflicts().get(1).getSeverity()
                        == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "second conflict severity must survive JSON");
    }

    private static void normalizesNullConflictsToEmptyImmutableList() {
        AdminOperationResultDTO<AdminCourseDTO> source =
                new AdminOperationResultDTO<>(
                        "operation-41", "COURSE_NOT_FOUND", "Course not found",
                        null, null);

        require(source.getConflicts().isEmpty(),
                "null conflicts must normalize to an empty list on construction");
        requireUnmodifiable(source.getConflicts(),
                "normalized conflicts must be unmodifiable");
        require(source.getEntity() == null, "null entity must stay null");

        Type type = new TypeToken<AdminOperationResultDTO<AdminCourseDTO>>() {}.getType();
        AdminOperationResultDTO<AdminCourseDTO> copy =
                GSON.fromJson(GSON.toJson(source), type);

        require(GSON.toJson(source).contains("\"conflicts\":[]"),
                "constructor normalization must serialize an explicit empty list");
        require(copy.getConflicts().isEmpty(),
                "an empty serialized conflict list must deserialize to an empty list");
        requireUnmodifiable(copy.getConflicts(),
                "deserialized empty conflicts must be unmodifiable");
        require(copy.getEntity() == null, "absent entity must stay null");
        require("COURSE_NOT_FOUND".equals(copy.getOutcomeCode()),
                "outcome code must round-trip");

        String explicitNull = "{\"operationId\":\"operation-42\","
                + "\"outcomeCode\":\"COURSE_NOT_FOUND\","
                + "\"message\":\"Course not found\",\"conflicts\":null}";
        AdminOperationResultDTO<AdminCourseDTO> explicitNullCopy =
                GSON.fromJson(explicitNull, type);

        require("operation-42".equals(explicitNullCopy.getOperationId()),
                "operation ID must deserialize from literal JSON");
        require(explicitNullCopy.getEntity() == null,
                "absent entity must deserialize as null");
        require(explicitNullCopy.getConflicts().isEmpty(),
                "explicit JSON null conflicts must normalize to an empty list");
        requireUnmodifiable(explicitNullCopy.getConflicts(),
                "explicit JSON null conflicts must be unmodifiable");
    }

    private static void conflictListsAreDefensiveAndUnmodifiable() {
        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        conflicts.add(blockingConflict());
        conflicts.add(overridableConflict());

        AdminOperationResultDTO<AdminCourseDTO> result =
                new AdminOperationResultDTO<>(
                        "operation-51", "SCHEDULE_CONFLICT", "Conflicts detected",
                        course("9007199254740993"), conflicts);
        conflicts.clear();

        require(result.getConflicts().size() == 2,
                "conflicts must be a defensive copy");
        requireUnmodifiable(result.getConflicts(), "conflicts must be unmodifiable");
        require(!result.getConflicts().isEmpty(), "conflicts must remain populated");
    }

    private static void deserializedConflictListsAreUnmodifiable() {
        AdminOperationResultDTO<AdminOfferingDTO> source =
                new AdminOperationResultDTO<>(
                        "operation-61", "OFFERING_UPDATED", "Offering updated",
                        offering("9007199254740993"),
                        Arrays.asList(blockingConflict()));
        Type type = new TypeToken<AdminOperationResultDTO<AdminOfferingDTO>>() {}.getType();

        AdminOperationResultDTO<AdminOfferingDTO> copy =
                GSON.fromJson(GSON.toJson(source), type);

        requireUnmodifiable(copy.getConflicts(),
                "deserialized conflicts must be unmodifiable");
    }

    private static void pinsExactJsonKeySets() {
        requireKeySet(course("9007199254740993"), Arrays.asList(
                "courseId", "courseCode", "courseName", "courseType", "credit",
                "creditHours", "description", "prerequisites", "allowCrossMajor",
                "finalExam", "status", "offeringCount", "version"));

        requireKeySet(offering("9007199254740993"), Arrays.asList(
                "offeringId", "offeringCode", "courseId", "academicYear", "semester",
                "capacity", "enrolledCount", "status", "teacherUid", "teacherName",
                "assistantUid", "assistantName", "scheduleStatus", "version"));

        requireKeySet(courseEditorUpdate(), Arrays.asList(
                "operationId", "courseId", "expectedVersion", "courseCode",
                "courseName", "courseType", "credit", "creditHours", "description",
                "prerequisites", "allowCrossMajor", "finalExam"));

        requireKeySet(offeringEditorUpdate(), Arrays.asList(
                "operationId", "offeringId", "expectedVersion", "courseId",
                "offeringCode", "academicYear", "semester", "capacity", "teacherUid",
                "assistantUid", "status"));

        requireKeySet(blockingConflict(), Arrays.asList(
                "type", "severity", "subjectId", "relatedOfferingId", "week", "endWeek",
                "dayOfWeek", "startPeriod", "endPeriod", "message"));

        requireKeySet(offeringResult(), Arrays.asList(
                "operationId", "outcomeCode", "message", "entity", "conflicts"));
    }

    private static void requireKeySet(Object sample, List<String> expected) {
        Set<String> actual =
                new LinkedHashSet<>(GSON.toJsonTree(sample).getAsJsonObject().keySet());
        Set<String> expectedSet = new LinkedHashSet<>(expected);

        require(actual.equals(expectedSet),
                "wire keys for " + sample.getClass().getSimpleName() + " must be "
                        + expectedSet + " but were " + actual);
    }

    private static void exposesExactScheduleConflictSeverities() {
        ScheduleConflictSeverityDTO[] expected = {
                ScheduleConflictSeverityDTO.BLOCKING,
                ScheduleConflictSeverityDTO.OVERRIDABLE
        };

        require(Arrays.equals(expected, ScheduleConflictSeverityDTO.values()),
                "severity must expose exactly BLOCKING and OVERRIDABLE");
    }

    private static void exposesExactAdminCourseActions() throws IllegalAccessException {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("LIST_COURSES", "listCourses");
        expected.put("CREATE_COURSE", "createCourse");
        expected.put("UPDATE_COURSE", "updateCourse");
        expected.put("ARCHIVE_COURSE", "archiveCourse");
        expected.put("RESTORE_COURSE", "restoreCourse");
        expected.put("LIST_OFFERINGS", "listOfferings");
        expected.put("LIST_OFFERING_TERMS", "listOfferingTerms");
        expected.put("CREATE_OFFERING", "createOffering");
        expected.put("UPDATE_OFFERING", "updateOffering");
        expected.put("CANCEL_OFFERING", "cancelOffering");
        expected.put("DELETE_DRAFT_OFFERING", "deleteDraftOffering");
        expected.put("LIST_SCHEDULE_RESOURCES", "listScheduleResources");
        expected.put("LOAD_SCHEDULE_PLAN", "loadSchedulePlan");
        expected.put("LOAD_OFFERING_ARRANGEMENTS", "loadOfferingArrangements");
        expected.put("CHECK_ARRANGEMENT", "checkArrangement");
        expected.put("SAVE_ARRANGEMENT", "saveArrangement");
        expected.put("DELETE_ARRANGEMENT", "deleteArrangement");
        expected.put("PUBLISH_SCHEDULE_PLAN", "publishSchedulePlan");
        expected.put("CREATE_SCHEDULE_PLAN", "createSchedulePlan");
        expected.put("SEARCH_STUDENTS", "searchStudents");
        expected.put("LIST_OFFERING_STUDENTS", "listOfferingStudents");
        expected.put("PREVIEW_ADMIN_ENROLLMENT", "previewAdminEnrollment");
        expected.put("ADD_STUDENT_TO_OFFERING", "addStudentToOffering");
        expected.put("REMOVE_STUDENT_FROM_OFFERING", "removeStudentFromOffering");
        expected.put("LIST_ADJUSTMENT_REQUESTS", "listAdjustmentRequests");
        expected.put("GET_ADJUSTMENT_REQUEST", "getAdjustmentRequest");
        expected.put("REVIEW_ADJUSTMENT_REQUEST", "reviewAdjustmentRequest");
        expected.put("LIST_GRADE_SUBMISSIONS", "listGradeSubmissions");
        expected.put("GET_GRADE_SUBMISSION", "getGradeSubmission");
        expected.put("REVIEW_GRADE_SUBMISSION", "reviewGradeSubmission");

        Map<String, String> actual = new LinkedHashMap<>();
        for (Field field : AdminCourseActions.class.getDeclaredFields()) {
            if (field.getType() == String.class
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                actual.put(field.getName(), (String) field.get(null));
            }
        }

        require(expected.equals(actual),
                "AdminCourseActions must contain exactly the complete action registry");
        require(actual.size() == 30, "the administrator action registry has 30 actions");
    }

    private static AdminCourseDTO course(String courseId) {
        return new AdminCourseDTO(
                courseId, "CS203", "Data Structures", "Required",
                4.0, 64, "Lists, trees, and graphs", "Programming Fundamentals",
                true, false, "ACTIVE", 3, 7);
    }

    private static AdminOfferingDTO offering(String offeringId) {
        return new AdminOfferingDTO(
                offeringId, "CS203-2025-1-A", "course-203",
                2025, 1, 120, 96, "OPEN", "teacher-1", "Teacher One",
                "assistant-9", "Assistant Nine", "PUBLISHED", 4);
    }

    private static ScheduleConflictDTO blockingConflict() {
        return new ScheduleConflictDTO(
                "TEACHER_DOUBLE_BOOKED", ScheduleConflictSeverityDTO.BLOCKING,
                "offering-9007199254740993", "9007199254740995",
                1, 2, 3, 4, "Teacher is booked in this period");
    }

    private static ScheduleConflictDTO scheduleConflictWithoutRelatedOffering() {
        return new ScheduleConflictDTO(
                "ROOM_CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "room-7", null, 5, 4, 1, 2, "Room capacity exceeded");
    }

    private static CourseEditorRequestDTO courseEditorCreate() {
        return new CourseEditorRequestDTO(
                "operation-11", null, 0, "CS999", "测试课程", "选修",
                2.0, 32, "说明", "无", false, true);
    }

    private static CourseEditorRequestDTO courseEditorUpdate() {
        return new CourseEditorRequestDTO(
                "operation-12", "9007199254740993", 5, "CS203", "Data Structures",
                "Required", 4.0, 64, "Lists and trees", "Programming Fundamentals",
                true, false);
    }

    private static OfferingEditorRequestDTO offeringEditorUpdate() {
        return new OfferingEditorRequestDTO(
                "operation-21", "9007199254740993", 5, "course-203",
                "CS203-2025-1-A", 2025, 2, 120, "teacher-1", "assistant-9", 1);
    }

    private static OfferingEditorRequestDTO offeringEditorCreate() {
        return new OfferingEditorRequestDTO(
                "operation-22", null, 0, "course-203", "CS203-2025-1-B",
                2024, 1, 60, "teacher-2", null, 2);
    }

    private static AdminOperationResultDTO<AdminOfferingDTO> offeringResult() {
        return new AdminOperationResultDTO<>(
                "operation-31", "OFFERING_CREATED", "Offering created",
                offering("9007199254740993"),
                Arrays.asList(blockingConflict(), overridableConflict()));
    }

    private static ScheduleConflictDTO overridableConflict() {
        return new ScheduleConflictDTO(
                "ROOM_CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "room-7", "9007199254740995",
                3, 2, 3, 4, "Room capacity exceeded");
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
