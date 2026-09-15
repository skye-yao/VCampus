package dto.course.admin;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;

public final class GradeApprovalDtoJsonTest {
    private static final Gson GSON = new Gson();

    private static final String SUBMISSION_ID = "9007199254740993";
    private static final String OFFERING_ID = "9007199254740995";
    private static final String ENROLLMENT_ID = "9007199254740997";
    private static final String TEACHER_UID = "00001234";
    private static final String STUDENT_UID = "00005678";
    private static final String SUBMITTED_AT = "2026-09-13T01:05:00Z";
    private static final String REVIEWED_AT = "2026-09-13T03:05:00Z";
    private static final String REVIEWER_UID = "00009012";

    public static void main(String[] args) {
        summaryRoundTripsStatisticsAndTeacherFields();
        itemKeepsAbsentComponentScoresNullInsteadOfZero();
        itemRoundTripsComponentScoresAndDecimalGradePoint();
        itemIdsAreDecimalStringsOnTheWire();
        detailRoundTripsDistributionBucketsAndReviewFields();
        detailKeepsUnreviewedFieldsNull();
        unknownWireStatusBecomesNullInsteadOfValidStatus();
        detailCollectionFieldsAreDefensiveAndUnmodifiable();
        deserializedDetailCollectionFieldsAreUnmodifiable();
        pageRoundTripsTotalCountMetadataAndItems();
        pageItemsAreDefensiveAndUnmodifiable();
        deserializedPageItemsAreUnmodifiable();
        System.out.println("GradeApprovalDtoJsonTest passed (12 scenarios)");
    }

    private static void summaryRoundTripsStatisticsAndTeacherFields() {
        GradeSubmissionSummaryDTO source = new GradeSubmissionSummaryDTO(
                SUBMISSION_ID, OFFERING_ID, "数据结构", "CS203-01", 4,
                TEACHER_UID, "陈老师", 60, 78.5, 98.0, 42.0, 3,
                ApprovalStatusDTO.PENDING, SUBMITTED_AT);
        GradeSubmissionSummaryDTO copy = GSON.fromJson(
                GSON.toJson(source), GradeSubmissionSummaryDTO.class);

        require(SUBMISSION_ID.equals(copy.getSubmissionId()),
                "summary submission ID must stay exact beyond the JavaScript safe integer");
        require(OFFERING_ID.equals(copy.getOfferingId()), "summary offering ID must stay exact");
        require("数据结构".equals(copy.getCourseName()), "summary course name must survive JSON");
        require("CS203-01".equals(copy.getOfferingCode()), "summary offering code must survive JSON");
        require(copy.getVersion() == 4, "summary version must survive JSON as an integer");
        require(TEACHER_UID.equals(copy.getTeacherUid()),
                "summary teacher UID must keep its leading zeroes");
        require("陈老师".equals(copy.getTeacherName()), "summary teacher name must survive JSON");
        require(copy.getStudentCount() == 60, "summary student count must survive JSON");
        require(copy.getAverage() == 78.5, "summary average must survive JSON");
        require(copy.getHighest() == 98.0, "summary highest must survive JSON");
        require(copy.getLowest() == 42.0, "summary lowest must survive JSON");
        require(copy.getFailCount() == 3, "summary fail count must survive JSON");
        require(copy.getStatus() == ApprovalStatusDTO.PENDING, "summary status must survive JSON");
        require(SUBMITTED_AT.equals(copy.getSubmittedAt()),
                "summary submission time must stay a UTC ISO-8601 string");
        requireStringId(source, "submissionId", SUBMISSION_ID);
    }

    private static void itemKeepsAbsentComponentScoresNullInsteadOfZero() {
        GradeSubmissionItemDTO source = new GradeSubmissionItemDTO(
                ENROLLMENT_ID, STUDENT_UID, "张三",
                null, null, null, null, null, null, null);
        GradeSubmissionItemDTO copy = GSON.fromJson(
                GSON.toJson(source), GradeSubmissionItemDTO.class);

        require(copy.getDailyScore() == null, "absent daily score must stay null, not zero");
        require(copy.getMidtermScore() == null, "absent midterm score must stay null, not zero");
        require(copy.getExperimentScore() == null, "absent experiment score must stay null, not zero");
        require(copy.getFinaltermScore() == null, "absent finalterm score must stay null, not zero");
        require(copy.getScore() == null, "absent final score must stay null, not zero");
        require(copy.getGradeLevel() == null, "absent grade level must stay null, not zero");
        require(copy.getGradePoint() == null, "absent grade point must stay null, not zero");
        requireStringId(source, "enrollmentId", ENROLLMENT_ID);
    }

    private static void itemRoundTripsComponentScoresAndDecimalGradePoint() {
        GradeSubmissionItemDTO source = new GradeSubmissionItemDTO(
                ENROLLMENT_ID, STUDENT_UID, "张三",
                88.5, 91.0, 76.25, 84.0, 85.75, 4, 3.75);
        GradeSubmissionItemDTO copy = GSON.fromJson(
                GSON.toJson(source), GradeSubmissionItemDTO.class);

        require(ENROLLMENT_ID.equals(copy.getEnrollmentId()), "item enrollment ID must stay exact");
        require(STUDENT_UID.equals(copy.getStudentUid()),
                "item student UID must keep its leading zeroes");
        require("张三".equals(copy.getStudentName()), "item student name must survive JSON");
        require(copy.getDailyScore() == 88.5, "item daily score must survive JSON");
        require(copy.getMidtermScore() == 91.0, "item midterm score must survive JSON");
        require(copy.getExperimentScore() == 76.25, "item experiment score must survive JSON");
        require(copy.getFinaltermScore() == 84.0, "item finalterm score must survive JSON");
        require(copy.getScore() == 85.75, "item final score must survive JSON");
        require(copy.getGradeLevel() == 4, "item grade level must survive JSON as an integer");
        require(copy.getGradePoint() == 3.75, "item decimal grade point must survive JSON");
    }

    private static void itemIdsAreDecimalStringsOnTheWire() {
        GradeSubmissionItemDTO source = new GradeSubmissionItemDTO(
                ENROLLMENT_ID, STUDENT_UID, "张三",
                88.5, 91.0, 76.25, 84.0, 85.75, 4, 3.75);
        JsonObject json = GSON.toJsonTree(source).getAsJsonObject();

        require(json.getAsJsonPrimitive("enrollmentId").isString(),
                "enrollmentId must be a JSON string");
        require(json.getAsJsonPrimitive("studentUid").isString(),
                "studentUid must be a JSON string");
        requireStringId(source, "enrollmentId", ENROLLMENT_ID);
    }

    private static void detailRoundTripsDistributionBucketsAndReviewFields() {
        GradeSubmissionDetailDTO source = approvedDetail();
        GradeSubmissionDetailDTO copy = GSON.fromJson(
                GSON.toJson(source), GradeSubmissionDetailDTO.class);

        require(SUBMISSION_ID.equals(copy.getSummary().getSubmissionId()),
                "detail summary submission ID must stay exact");
        require(copy.getSummary().getStudentCount() == 60,
                "detail summary student count must survive JSON");
        require(copy.getSummary().getStatus() == ApprovalStatusDTO.APPROVED,
                "detail summary status must survive JSON");

        require(copy.getDistribution().size() == 3, "detail must retain every distribution bucket");
        require("[90,100]".equals(copy.getDistribution().get(0).getLabel()),
                "first bucket label must survive JSON");
        require(copy.getDistribution().get(0).getCount() == 18,
                "first bucket count must survive JSON as an integer");
        require("<60".equals(copy.getDistribution().get(2).getLabel()),
                "decimal-safe bucket label must survive JSON");
        require(copy.getDistribution().get(2).getCount() == 3,
                "last bucket count must survive JSON");

        require(copy.getItems().size() == 2, "detail must retain every grade item");
        require(ENROLLMENT_ID.equals(copy.getItems().get(0).getEnrollmentId()),
                "first item enrollment ID must stay exact");
        require(copy.getItems().get(0).getGradePoint() == 3.75,
                "first item grade point must survive JSON");
        require(copy.getItems().get(1).getFinaltermScore() == null,
                "second item absent finalterm score must stay null after JSON");
        require(copy.getItems().get(1).getGradeLevel() == null,
                "second item absent grade level must stay null after JSON");

        require(REVIEWER_UID.equals(copy.getReviewedBy()),
                "reviewer UID must keep its leading zeroes");
        require(REVIEWED_AT.equals(copy.getReviewedAt()),
                "review time must stay a UTC ISO-8601 string");
        require("成绩无误，同意归档".equals(copy.getReviewComment()),
                "review comment must survive JSON");

        requireStringId(copy.getSummary(), "submissionId", SUBMISSION_ID);
        requireStringId(copy.getSummary(), "offeringId", OFFERING_ID);

        // T4 的提交快照字段同样走这条线：管理员按它显示组成与权重、基础批次和未纳入批次的新成员。
        GradeSubmissionDetailDTO captured = new GradeSubmissionDetailDTO(
                summary(SUBMISSION_ID, ApprovalStatusDTO.PENDING), List.of(), List.of(), null, null,
                null, new GradeSchemeDTO(List.of(
                        new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 4000),
                        new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                        new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                        new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 4000))),
                "9007199254740999", 2);
        GradeSubmissionDetailDTO capturedCopy = GSON.fromJson(
                GSON.toJson(captured), GradeSubmissionDetailDTO.class);
        require(capturedCopy.getSchemeSnapshot() != null
                        && capturedCopy.getSchemeSnapshot().getComponents().size() == 4
                        && capturedCopy.getSchemeSnapshot().getComponents().get(0)
                        .getWeightBasisPoints() == 4000
                        && !capturedCopy.getSchemeSnapshot().getComponents().get(2).isEnabled(),
                "the captured scheme must survive JSON with its weights and enabled flags");
        require("9007199254740999".equals(capturedCopy.getBaseSubmissionId()),
                "the base batch ID must stay exact beyond the JavaScript safe integer");
        require(capturedCopy.getUncoveredCount() == 2,
                "the uncovered-member count must survive JSON");
    }

    private static void detailKeepsUnreviewedFieldsNull() {
        GradeSubmissionDetailDTO pending = new GradeSubmissionDetailDTO(
                summary(SUBMISSION_ID, ApprovalStatusDTO.PENDING),
                List.of(), List.of(), null, null, null);
        GradeSubmissionDetailDTO copy = GSON.fromJson(
                GSON.toJson(pending), GradeSubmissionDetailDTO.class);

        require(copy.getReviewedBy() == null && copy.getReviewedAt() == null
                        && copy.getReviewComment() == null,
                "an unreviewed submission must keep every review field null");
        require(copy.getSummary().getStatus() == ApprovalStatusDTO.PENDING,
                "an unreviewed submission must stay pending");
        require(copy.getDistribution().isEmpty() && copy.getItems().isEmpty(),
                "an empty submission must keep its empty collections empty");
        require(copy.getSchemeSnapshot() == null && copy.getBaseSubmissionId() == null
                        && copy.getUncoveredCount() == 0,
                "a legacy detail built without a captured scheme must keep those fields empty");
    }

    private static void unknownWireStatusBecomesNullInsteadOfValidStatus() {
        GradeSubmissionSummaryDTO wire = GSON.fromJson(
                "{\"submissionId\":\"" + SUBMISSION_ID + "\",\"offeringId\":\"" + OFFERING_ID + "\","
                        + "\"courseName\":\"数据结构\",\"offeringCode\":\"CS203-01\","
                        + "\"version\":4,\"teacherUid\":\"" + TEACHER_UID + "\","
                        + "\"teacherName\":\"陈老师\",\"studentCount\":60,"
                        + "\"average\":78.5,\"highest\":98.0,\"lowest\":42.0,\"failCount\":3,"
                        + "\"status\":\"CANCELLED\",\"submittedAt\":\"" + SUBMITTED_AT + "\"}",
                GradeSubmissionSummaryDTO.class);
        require(wire.getStatus() == null,
                "an unmapped wire status must leave the summary status null rather than valid");
        require(!ApprovalStatusDTO.PENDING.equals(wire.getStatus()),
                "an unmapped wire status must not fall back to PENDING");
    }

    private static void detailCollectionFieldsAreDefensiveAndUnmodifiable() {
        List<GradeDistributionBucketDTO> buckets = new ArrayList<>(
                List.of(new GradeDistributionBucketDTO("[90,100]", 18),
                        new GradeDistributionBucketDTO("<60", 3)));
        List<GradeSubmissionItemDTO> items = new ArrayList<>(
                List.of(fullItem(ENROLLMENT_ID)));
        GradeSubmissionDetailDTO detail = new GradeSubmissionDetailDTO(
                summary(SUBMISSION_ID, ApprovalStatusDTO.PENDING), buckets, items,
                null, null, null);
        buckets.clear();
        items.clear();

        require(detail.getDistribution().size() == 2 && detail.getItems().size() == 1,
                "detail must defensively copy the caller's distribution and item lists");
        requireUnmodifiable(detail.getDistribution(), "constructed detail distribution");
        requireUnmodifiable(detail.getItems(), "constructed detail items");
    }

    private static void deserializedDetailCollectionFieldsAreUnmodifiable() {
        GradeSubmissionDetailDTO copy = GSON.fromJson(
                GSON.toJson(approvedDetail()), GradeSubmissionDetailDTO.class);

        requireUnmodifiable(copy.getDistribution(), "deserialized detail distribution");
        requireUnmodifiable(copy.getItems(), "deserialized detail items");
    }

    private static void pageRoundTripsTotalCountMetadataAndItems() {
        GradeSubmissionPageDTO source = new GradeSubmissionPageDTO(
                List.of(summary(SUBMISSION_ID, ApprovalStatusDTO.PENDING),
                        summary("9007199254740994", ApprovalStatusDTO.APPROVED)),
                123L, 3, 20);
        GradeSubmissionPageDTO copy = GSON.fromJson(
                GSON.toJson(source), GradeSubmissionPageDTO.class);

        require(copy.getTotalCount() == 123L,
                "page total count must not be replaced by the page length");
        require(copy.getPageNumber() == 3 && copy.getPageSize() == 20,
                "page number and size must survive JSON");
        require(copy.getItems().size() == 2, "page must retain every summary row");
        require(SUBMISSION_ID.equals(copy.getItems().get(0).getSubmissionId()),
                "first page row submission ID must stay exact");
        require(copy.getItems().get(1).getStatus() == ApprovalStatusDTO.APPROVED,
                "second page row status must survive JSON");
    }

    private static void pageItemsAreDefensiveAndUnmodifiable() {
        List<GradeSubmissionSummaryDTO> items = new ArrayList<>(
                List.of(summary("7", ApprovalStatusDTO.PENDING)));
        GradeSubmissionPageDTO page = new GradeSubmissionPageDTO(items, 1L, 1, 20);
        items.clear();

        require(page.getItems().size() == 1, "page must copy the caller's summary list");
        requireUnmodifiable(page.getItems(), "constructed page items");
    }

    private static void deserializedPageItemsAreUnmodifiable() {
        GradeSubmissionPageDTO copy = GSON.fromJson(GSON.toJson(
                new GradeSubmissionPageDTO(
                        List.of(summary("7", ApprovalStatusDTO.REJECTED)), 1L, 1, 20)),
                GradeSubmissionPageDTO.class);

        requireUnmodifiable(copy.getItems(), "deserialized page items");
    }

    private static GradeSubmissionDetailDTO approvedDetail() {
        return new GradeSubmissionDetailDTO(
                new GradeSubmissionSummaryDTO(
                        SUBMISSION_ID, OFFERING_ID, "数据结构", "CS203-01", 4,
                        TEACHER_UID, "陈老师", 60, 78.5, 98.0, 42.0, 3,
                        ApprovalStatusDTO.APPROVED, SUBMITTED_AT),
                new ArrayList<>(List.of(
                        new GradeDistributionBucketDTO("[90,100]", 18),
                        new GradeDistributionBucketDTO("[60,70)", 22),
                        new GradeDistributionBucketDTO("<60", 3))),
                new ArrayList<>(List.of(
                        fullItem(ENROLLMENT_ID),
                        new GradeSubmissionItemDTO("9007199254740998", STUDENT_UID, "李四",
                                70.0, 65.0, null, null, null, null, null))),
                REVIEWER_UID, REVIEWED_AT, "成绩无误，同意归档");
    }

    private static GradeSubmissionSummaryDTO summary(String submissionId, ApprovalStatusDTO status) {
        return new GradeSubmissionSummaryDTO(
                submissionId, OFFERING_ID, "数据结构", "CS203-01", 4,
                TEACHER_UID, "陈老师", 60, 78.5, 98.0, 42.0, 3, status, SUBMITTED_AT);
    }

    private static GradeSubmissionItemDTO fullItem(String enrollmentId) {
        return new GradeSubmissionItemDTO(
                enrollmentId, STUDENT_UID, "张三",
                88.5, 91.0, 76.25, 84.0, 85.75, 4, 3.75);
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
