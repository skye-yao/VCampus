package dto.course.teacher;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import dto.course.admin.schedule.ScheduleResourceDTO;

public final class TeacherQueryDtoJsonTest {
    private static final Gson GSON = new Gson();
    private static final Type PAGE_OF_STRING = new TypeToken<TeacherPageDTO<String>>() { }.getType();
    private static final Type RESULT_OF_STRING =
            new TypeToken<TeacherOperationResultDTO<String>>() { }.getType();

    private static final String OFFERING_ID = "9007199254740993";
    private static final String COURSE_ID = "9007199254740995";
    private static final String ENROLLMENT_ID = "9007199254740997";
    private static final String TEACHER_UID = "00001234";
    private static final String STUDENT_UID = "00005678";
    private static final String SELECTED_AT = "2026-09-01T01:00:00Z";
    private static final String DROPPED_AT = "2026-09-10T02:30:00Z";

    public static void main(String[] args) {
        briefSnippetKeepsBigintPrecisionAndRejectsMutation();
        pageRoundTripsMetadataAndEveryItem();
        pageCopiesCallerItemsDefensively();
        pageTreatsNullItemsAsEmptyAndUnmodifiable();
        deserializedPageItemsAreUnmodifiable();
        offeringRoundTripsAndKeepsIdsAsDecimalStrings();
        offeringExposesReadOnlyCapabilityFlags();
        detailRoundTripsOfferingAndTeachers();
        detailTreatsNullTeachersAndCollegeAsAbsent();
        detailCopiesCallerTeachersDefensively();
        rosterRowKeepsNullableDropFieldsNull();
        operationResultRoundTripsValueAndReplayFlag();
        System.out.println("TeacherQueryDtoJsonTest passed (12 scenarios)");
    }

    private static void briefSnippetKeepsBigintPrecisionAndRejectsMutation() {
        TeacherPageDTO<String> page = new TeacherPageDTO<>(List.of("9007199254740993"), 121, 2, 20);
        String json = new Gson().toJson(page);
        if (!json.contains("9007199254740993")) throw new AssertionError("BIGINT precision");
        try {
            page.getItems().add("x");
            throw new AssertionError("mutable items");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
        require(json.contains("\"totalCount\":121"), "totalCount must survive serialization");
        require(json.contains("\"page\":2") && json.contains("\"size\":20"),
                "page metadata must survive serialization");
    }

    private static void pageRoundTripsMetadataAndEveryItem() {
        TeacherPageDTO<String> source = new TeacherPageDTO<>(
                List.of(OFFERING_ID, "9007199254740994"), 123L, 3, 20);
        TeacherPageDTO<String> copy = GSON.fromJson(GSON.toJson(source), PAGE_OF_STRING);

        require(copy.getTotalCount() == 123L,
                "total count must not be replaced by the number of rows returned");
        require(copy.getPage() == 3 && copy.getSize() == 20,
                "page number and page size must survive JSON");
        require(copy.getItems().size() == 2, "page must keep every item");
        require(OFFERING_ID.equals(copy.getItems().get(0)),
                "a BIGINT identifier must stay an exact decimal string");
        require("9007199254740994".equals(copy.getItems().get(1)),
                "a neighbouring BIGINT identifier must stay exact");
    }

    private static void pageCopiesCallerItemsDefensively() {
        List<String> items = new ArrayList<>(List.of(OFFERING_ID));
        TeacherPageDTO<String> page = new TeacherPageDTO<>(items, 1L, 1, 20);
        items.clear();

        require(page.getItems().size() == 1, "page must copy the caller's item list");
        requireUnmodifiable(page.getItems(), "constructed page items");
    }

    private static void pageTreatsNullItemsAsEmptyAndUnmodifiable() {
        TeacherPageDTO<String> page = new TeacherPageDTO<>(null, 0L, 1, 20);
        require(page.getItems().isEmpty(), "a null item list must become empty, not null");
        requireUnmodifiable(page.getItems(), "null-list page items");

        TeacherPageDTO<String> wire = GSON.fromJson(
                "{\"items\":null,\"totalCount\":0,\"page\":1,\"size\":20}", PAGE_OF_STRING);
        require(wire.getItems().isEmpty(), "a JSON null item list must become empty");
        requireUnmodifiable(wire.getItems(), "deserialized null-list page items");
    }

    private static void deserializedPageItemsAreUnmodifiable() {
        TeacherPageDTO<String> copy = GSON.fromJson(GSON.toJson(
                new TeacherPageDTO<>(List.of(OFFERING_ID), 1L, 1, 20)), PAGE_OF_STRING);

        require(copy.getItems().size() == 1, "deserialized page must keep its item");
        requireUnmodifiable(copy.getItems(), "deserialized page items");
    }

    private static void offeringRoundTripsAndKeepsIdsAsDecimalStrings() {
        TeacherOfferingDTO source = offering();
        TeacherOfferingDTO copy = GSON.fromJson(GSON.toJson(source), TeacherOfferingDTO.class);

        require(OFFERING_ID.equals(copy.getOfferingId()),
                "offering ID must stay exact beyond the JavaScript safe integer");
        require(COURSE_ID.equals(copy.getCourseId()), "course ID must stay exact");
        require("CS203-01".equals(copy.getOfferingCode()), "offering code must survive JSON");
        require("数据结构".equals(copy.getOfferingName()), "offering display name must survive JSON");
        require("CS203".equals(copy.getCourseCode()), "course code must survive JSON");
        require("数据结构".equals(copy.getCourseName()), "course name must survive JSON");
        require(copy.getCredit() == 4.0, "credit must survive JSON");
        require(copy.getAcademicYear() == 2026 && copy.getSemester() == 2,
                "term must survive JSON as integers");
        require(copy.getEnrolledCount() == 58 && copy.getCapacity() == 60,
                "roster counts must survive JSON");
        require("ACTIVE".equals(copy.getStatus()), "offering status must survive JSON");

        JsonObject json = GSON.toJsonTree(source).getAsJsonObject();
        require(json.getAsJsonPrimitive("offeringId").isString(),
                "offeringId must be a JSON string");
        require(json.getAsJsonPrimitive("courseId").isString(),
                "courseId must be a JSON string");
        require(json.get("offeringId").getAsString().equals(OFFERING_ID),
                "offeringId must remain exact on the wire");
    }

    private static void offeringExposesReadOnlyCapabilityFlags() {
        TeacherOfferingDTO editable = new TeacherOfferingDTO(
                OFFERING_ID, "CS203-01", "数据结构", COURSE_ID, "CS203", "数据结构",
                4.0, 2026, 2, 58, 60, "ACTIVE", true, true);
        TeacherOfferingDTO copy = GSON.fromJson(GSON.toJson(editable), TeacherOfferingDTO.class);
        require(copy.isCanEditGrades(), "canEditGrades must survive JSON");
        require(copy.isCanRequestAdjustment(), "canRequestAdjustment must survive JSON");

        TeacherOfferingDTO readOnly = new TeacherOfferingDTO(
                OFFERING_ID, "CS203-01", "数据结构", COURSE_ID, "CS203", "数据结构",
                4.0, 2026, 2, 58, 60, "ACTIVE", false, false);
        TeacherOfferingDTO readOnlyCopy = GSON.fromJson(
                GSON.toJson(readOnly), TeacherOfferingDTO.class);
        require(!readOnlyCopy.isCanEditGrades(), "false canEditGrades must stay false");
        require(!readOnlyCopy.isCanRequestAdjustment(),
                "false canRequestAdjustment must stay false");
    }

    private static void detailRoundTripsOfferingAndTeachers() {
        TeacherOfferingDetailDTO source = new TeacherOfferingDetailDTO(
                offering(),
                List.of(teacher(TEACHER_UID, "陈老师"), teacher("00009012", "王助教")),
                "计算机科学与工程学院",
                "专业核心课，含实验");
        TeacherOfferingDetailDTO copy = GSON.fromJson(
                GSON.toJson(source), TeacherOfferingDetailDTO.class);

        require(OFFERING_ID.equals(copy.getOffering().getOfferingId()),
                "detail must keep its offering contract");
        require(copy.getTeachers().size() == 2, "detail must keep every teacher");
        require(TEACHER_UID.equals(copy.getTeachers().get(0).getBusinessId()),
                "teacher UID must keep its leading zeroes");
        require("陈老师".equals(copy.getTeachers().get(0).getName()),
                "teacher name must survive JSON");
        require("计算机科学与工程学院".equals(copy.getOfferingCollege()),
                "offering college must survive JSON");
        require("专业核心课，含实验".equals(copy.getDescription()),
                "description must survive JSON");
        requireUnmodifiable(copy.getTeachers(), "deserialized detail teachers");
    }

    private static void detailTreatsNullTeachersAndCollegeAsAbsent() {
        TeacherOfferingDetailDTO source = new TeacherOfferingDetailDTO(
                offering(), null, null, null);
        TeacherOfferingDetailDTO copy = GSON.fromJson(
                GSON.toJson(source), TeacherOfferingDetailDTO.class);

        require(copy.getTeachers() != null && copy.getTeachers().isEmpty(),
                "a null teacher list must become empty, not null");
        require(copy.getOfferingCollege() == null,
                "an unmaintained offering college must stay null instead of a teacher's own college");
        require(copy.getDescription() == null, "an absent description must stay null");
        require(source.getTeachers().isEmpty(), "a null teacher list must read back empty");
        requireUnmodifiable(source.getTeachers(), "null-list detail teachers");
    }

    private static void detailCopiesCallerTeachersDefensively() {
        List<ScheduleResourceDTO> teachers = new ArrayList<>(List.of(teacher(TEACHER_UID, "陈老师")));
        TeacherOfferingDetailDTO detail = new TeacherOfferingDetailDTO(
                offering(), teachers, "计算机科学与工程学院", null);
        teachers.clear();

        require(detail.getTeachers().size() == 1, "detail must copy the caller's teacher list");
        requireUnmodifiable(detail.getTeachers(), "constructed detail teachers");
    }

    private static void rosterRowKeepsNullableDropFieldsNull() {
        TeacherRosterRowDTO active = new TeacherRosterRowDTO(ENROLLMENT_ID, STUDENT_UID, "张三",
                "计算机科学与技术", "ENROLLED", SELECTED_AT, null);
        TeacherRosterRowDTO activeCopy = GSON.fromJson(
                GSON.toJson(active), TeacherRosterRowDTO.class);

        require(ENROLLMENT_ID.equals(activeCopy.getEnrollmentId()),
                "enrollment ID must stay exact beyond the JavaScript safe integer");
        require(STUDENT_UID.equals(activeCopy.getStudentUid()),
                "student UID must keep its leading zeroes");
        require("张三".equals(activeCopy.getStudentName()), "student name must survive JSON");
        require("计算机科学与技术".equals(activeCopy.getMajor()), "student major must survive JSON");
        require("ENROLLED".equals(activeCopy.getEnrollmentStatus()),
                "enrollment status must survive JSON");
        require(SELECTED_AT.equals(activeCopy.getSelectedAt()),
                "selection time must stay a UTC ISO-8601 string");
        require(activeCopy.getDroppedAt() == null,
                "an active enrollment must keep its drop time null, not empty");

        TeacherRosterRowDTO dropped = new TeacherRosterRowDTO(ENROLLMENT_ID, STUDENT_UID, "张三",
                "计算机科学与技术", "DROPPED", SELECTED_AT, DROPPED_AT);
        TeacherRosterRowDTO droppedCopy = GSON.fromJson(
                GSON.toJson(dropped), TeacherRosterRowDTO.class);
        require("DROPPED".equals(droppedCopy.getEnrollmentStatus()),
                "dropped enrollment status must survive JSON");
        require(DROPPED_AT.equals(droppedCopy.getDroppedAt()),
                "drop time must stay a UTC ISO-8601 string");
    }

    private static void operationResultRoundTripsValueAndReplayFlag() {
        TeacherOperationResultDTO<String> source = new TeacherOperationResultDTO<>(
                "3f1a4c22-5b6d-4e7f-8a90-1b2c3d4e5f60", "已保存", "9007199254740993", true);
        TeacherOperationResultDTO<String> copy = GSON.fromJson(
                GSON.toJson(source), RESULT_OF_STRING);

        require("3f1a4c22-5b6d-4e7f-8a90-1b2c3d4e5f60".equals(copy.getOperationId()),
                "operation ID must survive JSON");
        require("已保存".equals(copy.getMessage()), "message must survive JSON");
        require("9007199254740993".equals(copy.getValue()), "operation value must survive JSON");
        require(copy.isReplayed(), "the replay flag must survive JSON");

        TeacherOperationResultDTO<String> fresh = new TeacherOperationResultDTO<>(
                "3f1a4c22-5b6d-4e7f-8a90-1b2c3d4e5f61", "已保存", null, false);
        TeacherOperationResultDTO<String> freshCopy = GSON.fromJson(
                GSON.toJson(fresh), RESULT_OF_STRING);
        require(freshCopy.getValue() == null, "an absent result value must stay null");
        require(!freshCopy.isReplayed(), "a first execution must not be reported as replayed");
    }

    private static TeacherOfferingDTO offering() {
        return new TeacherOfferingDTO(OFFERING_ID, "CS203-01", "数据结构", COURSE_ID, "CS203",
                "数据结构", 4.0, 2026, 2, 58, 60, "ACTIVE", true, false);
    }

    private static ScheduleResourceDTO teacher(String uid, String name) {
        return new ScheduleResourceDTO(uid, uid, name, "TEACHER", 0);
    }

    private static <T> void requireUnmodifiable(List<T> values, String label) {
        try {
            values.add(null);
            throw new AssertionError(label + " allowed append");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
        if (!values.isEmpty()) {
            try {
                values.set(0, values.get(0));
                throw new AssertionError(label + " allowed replacement");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable contract.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
