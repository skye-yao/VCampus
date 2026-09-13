package dto.course.admin;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;

public final class AdminEnrollmentDtoJsonTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        studentSearchPreservesUidAndProfile();
        offeringStudentPreservesDecimalIdAndRemovalRestriction();
        previewPreservesTypedRisks();
        previewRisksAreDefensiveAndUnmodifiable();
        absentRisksAreEmptyAndUnmodifiable();
        requestPreservesOperationAndForceReason();
        typedPagesPreserveMetadataAndRows();
        pageItemsAreDefensiveAndUnmodifiable();
        System.out.println("AdminEnrollmentDtoJsonTest passed (8 scenarios)");
    }

    private static void studentSearchPreservesUidAndProfile() {
        StudentSearchResultDTO source = new StudentSearchResultDTO(
                "00001234", "陈同学", "计算机科学", 2024, "ACTIVE");
        StudentSearchResultDTO copy = GSON.fromJson(
                GSON.toJson(source), StudentSearchResultDTO.class);
        require("00001234".equals(copy.getUid()), "student UID must retain leading zeroes");
        require("陈同学".equals(copy.getName()), "student name must survive JSON");
        require("计算机科学".equals(copy.getMajor()), "major must survive JSON");
        require(copy.getCohortYear() == 2024, "cohort year must survive JSON");
        require("ACTIVE".equals(copy.getAcademicStatus()), "academic status must survive JSON");
        requireStringId(source, "uid", "00001234");
    }

    private static void offeringStudentPreservesDecimalIdAndRemovalRestriction() {
        OfferingStudentDTO source = new OfferingStudentDTO(
                "9007199254740993", "00001234", "陈同学", "计算机科学", 2024,
                "ENROLLED", false, "成绩正在审批");
        OfferingStudentDTO copy = GSON.fromJson(GSON.toJson(source), OfferingStudentDTO.class);
        require("9007199254740993".equals(copy.getEnrollmentId()),
                "enrollment BIGINT must remain exact");
        require("00001234".equals(copy.getUid()), "enrollment must retain the exact student UID");
        require("陈同学".equals(copy.getName()), "enrolled student's name must survive JSON");
        require("计算机科学".equals(copy.getMajor()), "enrolled student's major must survive JSON");
        require(copy.getCohortYear() == 2024, "enrolled student's cohort must survive JSON");
        require("ENROLLED".equals(copy.getEnrollmentStatus()), "enrollment status must survive JSON");
        require(!copy.isRemovable(), "grade-locked student must remain non-removable");
        require("成绩正在审批".equals(copy.getBlockedReason()), "blocked reason must survive JSON");
        requireStringId(source, "enrollmentId", "9007199254740993");

        OfferingStudentDTO removable = GSON.fromJson(GSON.toJson(new OfferingStudentDTO(
                "9007199254740995", "S-42", "李同学", "数学", 2023,
                "ENROLLED", true, null)), OfferingStudentDTO.class);
        require(removable.isRemovable(), "eligible student must remain removable");
        require(removable.getBlockedReason() == null, "absent blocked reason must remain null");
    }

    private static void previewPreservesTypedRisks() {
        AdminEnrollmentPreviewDTO source = new AdminEnrollmentPreviewDTO(
                "9007199254740997", "00001234", List.of(capacityRisk(), blockingRisk()));
        AdminEnrollmentPreviewDTO copy = GSON.fromJson(
                GSON.toJson(source), AdminEnrollmentPreviewDTO.class);
        require("9007199254740997".equals(copy.getOfferingId()), "preview offering ID must remain exact");
        require("00001234".equals(copy.getStudentUid()), "preview student UID must remain exact");
        require(copy.getRisks().size() == 2, "preview must retain all risks");
        require("CAPACITY".equals(copy.getRisks().get(0).getType()), "risk type must survive JSON");
        require(copy.getRisks().get(0).getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "capacity risk must remain overridable");
        require(copy.getRisks().get(1).getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                "same-course risk must remain blocking");
        require("9007199254740999".equals(copy.getRisks().get(1).getRelatedOfferingId()),
                "related offering ID must remain exact");
        requireStringId(source, "offeringId", "9007199254740997");
        requireUnmodifiable(copy.getRisks(), "deserialized risks");
    }

    private static void previewRisksAreDefensiveAndUnmodifiable() {
        List<ScheduleConflictDTO> risks = new ArrayList<>(List.of(capacityRisk()));
        AdminEnrollmentPreviewDTO preview = new AdminEnrollmentPreviewDTO("7", "S-42", risks);
        risks.clear();
        require(preview.getRisks().size() == 1, "preview must copy the caller's risk list");
        requireUnmodifiable(preview.getRisks(), "constructed risks");
    }

    private static void absentRisksAreEmptyAndUnmodifiable() {
        List<AdminEnrollmentPreviewDTO> previews = List.of(
                new AdminEnrollmentPreviewDTO("7", "S-42", null),
                GSON.fromJson("{\"offeringId\":\"7\",\"studentUid\":\"S-42\",\"risks\":null}",
                        AdminEnrollmentPreviewDTO.class),
                GSON.fromJson("{\"offeringId\":\"7\",\"studentUid\":\"S-42\"}",
                        AdminEnrollmentPreviewDTO.class));
        for (AdminEnrollmentPreviewDTO preview : previews) {
            require(preview.getRisks().isEmpty(), "absent risks must be an empty list");
            requireUnmodifiable(preview.getRisks(), "absent risks");
        }
    }

    private static void requestPreservesOperationAndForceReason() {
        AdminEnrollmentRequestDTO source = new AdminEnrollmentRequestDTO(
                "ab5a488c-74d1-4e16-9f16-e8f7741e454d", "9007199254740997", "00001234",
                true, "教务已批准扩容");
        AdminEnrollmentRequestDTO copy = GSON.fromJson(GSON.toJson(source), AdminEnrollmentRequestDTO.class);
        require("ab5a488c-74d1-4e16-9f16-e8f7741e454d".equals(copy.getOperationId()),
                "operation ID must survive JSON");
        require("9007199254740997".equals(copy.getOfferingId()), "request offering ID must remain exact");
        require("00001234".equals(copy.getStudentUid()), "request student UID must remain exact");
        require(copy.isForce(), "forced request must retain its force flag");
        require("教务已批准扩容".equals(copy.getOverrideReason()), "force reason must survive JSON");
        requireStringId(source, "offeringId", "9007199254740997");

        AdminEnrollmentRequestDTO normal = GSON.fromJson(GSON.toJson(new AdminEnrollmentRequestDTO(
                "df56fe55-b53a-4d96-b1a7-f4ee5229ddf7", "7", "S-42", false, null)),
                AdminEnrollmentRequestDTO.class);
        require(!normal.isForce(), "normal request must not become forced");
        require(normal.getOverrideReason() == null, "normal request reason may be null");
    }

    private static void typedPagesPreserveMetadataAndRows() {
        AdminEnrollmentPageDTO<StudentSearchResultDTO> source = new AdminEnrollmentPageDTO<>(
                List.of(new StudentSearchResultDTO("00001234", "陈同学", "计算机科学", 2024, "ACTIVE")),
                123L, 3, 20);
        Type searchType = new TypeToken<AdminEnrollmentPageDTO<StudentSearchResultDTO>>() {}.getType();
        AdminEnrollmentPageDTO<StudentSearchResultDTO> copy = GSON.fromJson(GSON.toJson(source), searchType);
        require(copy.getTotalCount() == 123L, "total count must not be replaced by page length");
        require(copy.getPageNumber() == 3 && copy.getPageSize() == 20, "page metadata must survive JSON");
        require(copy.getItems().size() == 1 && "00001234".equals(copy.getItems().get(0).getUid()),
                "search page must retain typed student rows");
        requireUnmodifiable(copy.getItems(), "deserialized page items");

        Type offeringType = new TypeToken<AdminEnrollmentPageDTO<OfferingStudentDTO>>() {}.getType();
        AdminEnrollmentPageDTO<OfferingStudentDTO> offeringPage = GSON.fromJson(GSON.toJson(
                new AdminEnrollmentPageDTO<>(List.of(new OfferingStudentDTO(
                        "9007199254740993", "S-42", "李同学", "数学", 2023,
                        "ENROLLED", true, null)), 1L, 1, 20)), offeringType);
        require("9007199254740993".equals(offeringPage.getItems().get(0).getEnrollmentId()),
                "offering page must retain typed enrollment IDs");
    }

    private static void pageItemsAreDefensiveAndUnmodifiable() {
        List<StudentSearchResultDTO> rows = new ArrayList<>(List.of(
                new StudentSearchResultDTO("S-42", "李同学", "数学", 2023, "ACTIVE")));
        AdminEnrollmentPageDTO<StudentSearchResultDTO> page = new AdminEnrollmentPageDTO<>(rows, 1L, 1, 20);
        rows.clear();
        require(page.getItems().size() == 1, "page must copy the caller's item list");
        requireUnmodifiable(page.getItems(), "constructed page items");
        Type type = new TypeToken<AdminEnrollmentPageDTO<StudentSearchResultDTO>>() {}.getType();
        AdminEnrollmentPageDTO<StudentSearchResultDTO> missing = GSON.fromJson(
                "{\"totalCount\":0,\"pageNumber\":1,\"pageSize\":20}", type);
        require(missing.getItems().isEmpty(), "absent page items must normalize to empty");
        requireUnmodifiable(missing.getItems(), "absent page items");
    }

    private static void requireStringId(Object source, String key, String expected) {
        JsonObject json = GSON.toJsonTree(source).getAsJsonObject();
        require(json.getAsJsonPrimitive(key).isString(), key + " must be a JSON string");
        require(expected.equals(json.get(key).getAsString()), key + " must remain exact on the wire");
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

    private static ScheduleConflictDTO capacityRisk() {
        return new ScheduleConflictDTO("CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "9007199254740997", null, 0, 0, 0, 0, "教学班已满");
    }

    private static ScheduleConflictDTO blockingRisk() {
        return new ScheduleConflictDTO("SAME_COURSE_ACTIVE", ScheduleConflictSeverityDTO.BLOCKING,
                "00001234", "9007199254740999", 0, 0, 0, 0, "已选同一课程的其他教学班");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
