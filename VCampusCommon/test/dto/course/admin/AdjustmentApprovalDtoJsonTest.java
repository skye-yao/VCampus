package dto.course.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;

public final class AdjustmentApprovalDtoJsonTest {
    private static final Gson GSON = new Gson();

    private static final String REQUEST_ID = "9007199254740993";
    private static final String OFFERING_ID = "9007199254740995";
    private static final String OCCURRENCE_ID = "9007199254740997";
    private static final String APPLICANT_UID = "00001234";
    private static final String SUBMITTED_AT = "2026-09-13T01:05:00Z";
    private static final String REVIEWED_AT = "2026-09-13T03:05:00Z";
    private static final String REVIEWER_UID = "00005678";

    public static void main(String[] args) {
        statusEnumValuesMatchTheWireContract();
        unknownWireStatusBecomesNullInsteadOfValidStatus();
        summaryRoundTripsExactIdsAndApplicantFields();
        targetRoundTripsOriginalOccurrenceIdentity();
        detailRoundTripsProposedScheduleAndReviewFields();
        detailKeepsNullableResourceAndReviewFieldsNull();
        decisionRoundTripsOperationVersionAndApprovalFlag();
        decisionPreservesForceAndReviewReason();
        detailCollectionFieldsAreDefensiveAndUnmodifiable();
        deserializedDetailCollectionFieldsAreUnmodifiable();
        pageRoundTripsTotalCountMetadataAndItems();
        pageItemsAreDefensiveAndUnmodifiable();
        deserializedPageItemsAreUnmodifiable();
        System.out.println("AdjustmentApprovalDtoJsonTest passed (13 scenarios)");
    }

    private static void statusEnumValuesMatchTheWireContract() {
        AdjustmentRequestStatusDTO[] expected = {
                AdjustmentRequestStatusDTO.PENDING,
                AdjustmentRequestStatusDTO.APPROVED,
                AdjustmentRequestStatusDTO.REJECTED,
                AdjustmentRequestStatusDTO.WITHDRAWN};
        require(Arrays.equals(expected, AdjustmentRequestStatusDTO.values()),
                "adjustment status must expose exactly PENDING, APPROVED, REJECTED, WITHDRAWN");
        require(GSON.fromJson("\"PENDING\"", AdjustmentRequestStatusDTO.class)
                        == AdjustmentRequestStatusDTO.PENDING,
                "PENDING must deserialize from its wire name");
        require(GSON.fromJson("\"WITHDRAWN\"", AdjustmentRequestStatusDTO.class)
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "a withdrawn request must deserialize from its own wire name");
        require(GSON.fromJson("\"REJECTED\"", AdjustmentRequestStatusDTO.class)
                        == AdjustmentRequestStatusDTO.REJECTED,
                "REJECTED must deserialize from its wire name");
    }

    private static void unknownWireStatusBecomesNullInsteadOfValidStatus() {
        require(GSON.fromJson("\"CANCELLED\"", AdjustmentRequestStatusDTO.class) == null,
                "an unmapped status name must deserialize to null, not to a valid status");

        AdjustmentRequestSummaryDTO wire = GSON.fromJson(
                "{\"requestId\":\"" + REQUEST_ID + "\",\"courseName\":\"数据结构\","
                        + "\"offeringCode\":\"CS203-01\",\"applicantUid\":\"" + APPLICANT_UID + "\","
                        + "\"applicantName\":\"陈老师\",\"targetWeekCount\":6,"
                        + "\"status\":\"CANCELLED\",\"submittedAt\":\"" + SUBMITTED_AT + "\"}",
                AdjustmentRequestSummaryDTO.class);
        require(wire.getStatus() == null,
                "an unmapped wire status must leave the summary status null rather than valid");
        require(!AdjustmentRequestStatusDTO.PENDING.equals(wire.getStatus()),
                "an unmapped wire status must not fall back to PENDING");
    }

    private static void summaryRoundTripsExactIdsAndApplicantFields() {
        AdjustmentRequestSummaryDTO source = new AdjustmentRequestSummaryDTO(
                REQUEST_ID, "数据结构", "CS203-01", APPLICANT_UID, "陈老师",
                6, AdjustmentRequestStatusDTO.PENDING, SUBMITTED_AT);
        AdjustmentRequestSummaryDTO copy = GSON.fromJson(
                GSON.toJson(source), AdjustmentRequestSummaryDTO.class);

        require(REQUEST_ID.equals(copy.getRequestId()),
                "summary request ID must stay exact beyond the JavaScript safe integer");
        require("数据结构".equals(copy.getCourseName()), "summary course name must survive JSON");
        require("CS203-01".equals(copy.getOfferingCode()), "summary offering code must survive JSON");
        require(APPLICANT_UID.equals(copy.getApplicantUid()),
                "summary applicant UID must keep its leading zeroes");
        require("陈老师".equals(copy.getApplicantName()), "summary applicant name must survive JSON");
        require(copy.getTargetWeekCount() == 6, "summary target week count must survive JSON");
        require(copy.getStatus() == AdjustmentRequestStatusDTO.PENDING, "summary status must survive JSON");
        require(SUBMITTED_AT.equals(copy.getSubmittedAt()),
                "summary submission time must stay a UTC ISO-8601 string");
        requireStringId(source, "requestId", REQUEST_ID);
    }

    private static void targetRoundTripsOriginalOccurrenceIdentity() {
        AdjustmentTargetDTO source = new AdjustmentTargetDTO(
                OCCURRENCE_ID, 6, "2026-10-08T01:00:00Z", "2026-10-08T02:40:00Z",
                "张老师", null, "教四-201");
        AdjustmentTargetDTO copy = GSON.fromJson(GSON.toJson(source), AdjustmentTargetDTO.class);

        require(OCCURRENCE_ID.equals(copy.getOriginalOccurrenceId()),
                "original occurrence ID must stay exact beyond the JavaScript safe integer");
        require(copy.getWeek() == 6, "original occurrence week must survive JSON");
        require("2026-10-08T01:00:00Z".equals(copy.getOriginalStartAt()),
                "original start must stay a UTC ISO-8601 string");
        require("2026-10-08T02:40:00Z".equals(copy.getOriginalEndAt()),
                "original end must stay a UTC ISO-8601 string");
        require("张老师".equals(copy.getOriginalTeacher()), "original teacher must survive JSON");
        require(copy.getOriginalAssistant() == null, "absent original assistant must stay null");
        require("教四-201".equals(copy.getOriginalClassroom()), "original classroom must survive JSON");
        requireStringId(source, "originalOccurrenceId", OCCURRENCE_ID);
    }

    private static void detailRoundTripsProposedScheduleAndReviewFields() {
        AdjustmentRequestDetailDTO source = approvedDetail();
        AdjustmentRequestDetailDTO copy = GSON.fromJson(
                GSON.toJson(source), AdjustmentRequestDetailDTO.class);

        require(REQUEST_ID.equals(copy.getRequestId()), "detail request ID must stay exact");
        require(OFFERING_ID.equals(copy.getOfferingId()), "detail offering ID must stay exact");
        require(APPLICANT_UID.equals(copy.getApplicantUid()),
                "detail applicant UID must keep its leading zeroes");
        require("教师出差，申请调至第五节".equals(copy.getReason()), "detail reason must survive JSON");
        require(copy.getStatus() == AdjustmentRequestStatusDTO.APPROVED, "detail status must survive JSON");
        require(copy.getVersion() == 4, "detail version must survive JSON as an integer");
        require(copy.getNewDayOfWeek() == 5, "proposed weekday must survive JSON");
        require(copy.getNewStartPeriod() == 3 && copy.getNewEndPeriod() == 4,
                "proposed period range must survive JSON");
        require(copy.getNewTeacher() != null && "张老师".equals(copy.getNewTeacher().getName()),
                "proposed teacher must survive JSON");
        require("9007199254740999".equals(copy.getNewTeacher().getResourceId()),
                "proposed teacher resource ID must stay exact");
        require(copy.getNewAssistant() == null, "absent proposed assistant must stay null");
        require(copy.getNewClassroom() != null && "教四-201".equals(copy.getNewClassroom().getName()),
                "proposed classroom must survive JSON");
        require(SUBMITTED_AT.equals(copy.getSubmittedAt()),
                "detail submission time must stay a UTC ISO-8601 string");
        require(REVIEWER_UID.equals(copy.getReviewedBy()),
                "reviewer UID must keep its leading zeroes");
        require(REVIEWED_AT.equals(copy.getReviewedAt()),
                "review time must stay a UTC ISO-8601 string");
        require("同意调整".equals(copy.getReviewComment()), "review comment must survive JSON");

        require(copy.getTargets().size() == 2, "detail must retain both original occurrences");
        require(OCCURRENCE_ID.equals(copy.getTargets().get(0).getOriginalOccurrenceId()),
                "first target identity must survive JSON");
        require("教四-201".equals(copy.getTargets().get(0).getOriginalClassroom()),
                "first target classroom must survive JSON");
        require("9007199254740998".equals(copy.getTargets().get(1).getOriginalOccurrenceId()),
                "second target identity must stay exact");
        require(copy.getTargets().get(1).getWeek() == 7, "second target week must survive JSON");
        require("李老师".equals(copy.getTargets().get(1).getOriginalTeacher()),
                "second target teacher must survive JSON");

        require(copy.getConflicts().size() == 1, "detail must retain its typed conflicts");
        require("TEACHER_TIME_OVERLAP".equals(copy.getConflicts().get(0).getType()),
                "conflict type must survive JSON");
        require(copy.getConflicts().get(0).getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "conflict severity must stay a typed enum after JSON");
        require("9007199254741003".equals(copy.getConflicts().get(0).getRelatedOfferingId()),
                "conflict related offering ID must stay exact");
        require(copy.getConflicts().get(0).getStartPeriod() == 3,
                "conflict period range must survive JSON");

        requireStringId(source, "requestId", REQUEST_ID);
        requireStringId(source, "offeringId", OFFERING_ID);
    }

    private static void detailKeepsNullableResourceAndReviewFieldsNull() {
        AdjustmentRequestDetailDTO pending = new AdjustmentRequestDetailDTO(
                "77", "88", "S-42", null, AdjustmentRequestStatusDTO.PENDING, 1, 2, 1, 2,
                null, null, null, List.of(), List.of(), SUBMITTED_AT, null, null, null);
        AdjustmentRequestDetailDTO copy = GSON.fromJson(
                GSON.toJson(pending), AdjustmentRequestDetailDTO.class);

        require(copy.getReason() == null, "absent adjustment reason must stay null");
        require(copy.getNewTeacher() == null && copy.getNewAssistant() == null
                        && copy.getNewClassroom() == null,
                "absent proposed resources must stay null");
        require(copy.getReviewedBy() == null && copy.getReviewedAt() == null
                        && copy.getReviewComment() == null,
                "an unreviewed request must keep every review field null");
        require(copy.getStatus() == AdjustmentRequestStatusDTO.PENDING,
                "an unreviewed request must stay pending");
    }

    private static void decisionRoundTripsOperationVersionAndApprovalFlag() {
        ApprovalDecisionRequestDTO source = new ApprovalDecisionRequestDTO(
                "ab5a488c-74d1-4e16-9f16-e8f7741e454d", REQUEST_ID, 4,
                true, false, null, "同意调整");
        ApprovalDecisionRequestDTO copy = GSON.fromJson(
                GSON.toJson(source), ApprovalDecisionRequestDTO.class);

        require("ab5a488c-74d1-4e16-9f16-e8f7741e454d".equals(copy.getOperationId()),
                "decision operation ID must survive JSON");
        require(REQUEST_ID.equals(copy.getRequestId()), "decision request ID must stay exact");
        require(copy.getExpectedVersion() == 4, "decision expected version must survive JSON");
        require(copy.isApproved(), "an approving decision must keep its approval flag");
        require(!copy.isForce(), "a decision without force must not become forced");
        require(copy.getOverrideReason() == null, "absent override reason must stay null");
        require("同意调整".equals(copy.getReviewComment()), "decision review comment must survive JSON");
        requireStringId(source, "requestId", REQUEST_ID);
    }

    private static void decisionPreservesForceAndReviewReason() {
        ApprovalDecisionRequestDTO copy = GSON.fromJson(GSON.toJson(
                new ApprovalDecisionRequestDTO(
                        "df56fe55-b53a-4d96-b1a7-f4ee5229ddf7", "42", 9,
                        false, true, "教务处已批准教师出差调课", "冲突已由主管确认")),
                ApprovalDecisionRequestDTO.class);

        require(!copy.isApproved(), "a rejecting decision must keep its rejection flag");
        require(copy.isForce(), "a forced decision must keep its force flag");
        require("教务处已批准教师出差调课".equals(copy.getOverrideReason()),
                "force reason must survive JSON");
        require("冲突已由主管确认".equals(copy.getReviewComment()),
                "rejection comment must survive JSON");
    }

    private static void detailCollectionFieldsAreDefensiveAndUnmodifiable() {
        List<AdjustmentTargetDTO> targets = new ArrayList<>(List.of(firstTarget(), secondTarget()));
        List<ScheduleConflictDTO> conflicts = new ArrayList<>(List.of(overridableConflict()));
        AdjustmentRequestDetailDTO detail = new AdjustmentRequestDetailDTO(
                REQUEST_ID, OFFERING_ID, APPLICANT_UID, "教师出差", AdjustmentRequestStatusDTO.PENDING,
                1, 2, 1, 2, null, null, null, targets, conflicts,
                SUBMITTED_AT, null, null, null);
        targets.clear();
        conflicts.clear();

        require(detail.getTargets().size() == 2 && detail.getConflicts().size() == 1,
                "detail must defensively copy the caller's target and conflict lists");
        requireUnmodifiable(detail.getTargets(), "constructed detail targets");
        requireUnmodifiable(detail.getConflicts(), "constructed detail conflicts");
    }

    private static void deserializedDetailCollectionFieldsAreUnmodifiable() {
        AdjustmentRequestDetailDTO copy = GSON.fromJson(
                GSON.toJson(approvedDetail()), AdjustmentRequestDetailDTO.class);

        requireUnmodifiable(copy.getTargets(), "deserialized detail targets");
        requireUnmodifiable(copy.getConflicts(), "deserialized detail conflicts");
    }

    private static void pageRoundTripsTotalCountMetadataAndItems() {
        AdjustmentRequestPageDTO source = new AdjustmentRequestPageDTO(
                List.of(summary(REQUEST_ID, AdjustmentRequestStatusDTO.PENDING),
                        summary("9007199254740994", AdjustmentRequestStatusDTO.APPROVED)),
                123L, 3, 20);
        AdjustmentRequestPageDTO copy = GSON.fromJson(
                GSON.toJson(source), AdjustmentRequestPageDTO.class);

        require(copy.getTotalCount() == 123L,
                "page total count must not be replaced by the page length");
        require(copy.getPageNumber() == 3 && copy.getPageSize() == 20,
                "page number and size must survive JSON");
        require(copy.getItems().size() == 2, "page must retain every summary row");
        require(REQUEST_ID.equals(copy.getItems().get(0).getRequestId()),
                "first page row request ID must stay exact");
        require(copy.getItems().get(1).getStatus() == AdjustmentRequestStatusDTO.APPROVED,
                "second page row status must survive JSON");
    }

    private static void pageItemsAreDefensiveAndUnmodifiable() {
        List<AdjustmentRequestSummaryDTO> items = new ArrayList<>(
                List.of(summary("7", AdjustmentRequestStatusDTO.PENDING)));
        AdjustmentRequestPageDTO page = new AdjustmentRequestPageDTO(items, 1L, 1, 20);
        items.clear();

        require(page.getItems().size() == 1, "page must copy the caller's summary list");
        requireUnmodifiable(page.getItems(), "constructed page items");
    }

    private static void deserializedPageItemsAreUnmodifiable() {
        AdjustmentRequestPageDTO copy = GSON.fromJson(GSON.toJson(
                new AdjustmentRequestPageDTO(
                        List.of(summary("7", AdjustmentRequestStatusDTO.REJECTED)), 1L, 1, 20)),
                AdjustmentRequestPageDTO.class);

        requireUnmodifiable(copy.getItems(), "deserialized page items");
    }

    private static AdjustmentRequestDetailDTO approvedDetail() {
        return new AdjustmentRequestDetailDTO(
                REQUEST_ID, OFFERING_ID, APPLICANT_UID, "教师出差，申请调至第五节",
                AdjustmentRequestStatusDTO.APPROVED, 4, 5, 3, 4,
                new ScheduleResourceDTO("9007199254740999", "T001", "张老师", "TEACHER", 0),
                null,
                new ScheduleResourceDTO("9007199254741001", "R-201", "教四-201", "CLASSROOM", 60),
                new ArrayList<>(List.of(firstTarget(), secondTarget())),
                new ArrayList<>(List.of(overridableConflict())),
                SUBMITTED_AT, REVIEWER_UID, REVIEWED_AT, "同意调整");
    }

    private static AdjustmentRequestSummaryDTO summary(String requestId, AdjustmentRequestStatusDTO status) {
        return new AdjustmentRequestSummaryDTO(
                requestId, "数据结构", "CS203-01", APPLICANT_UID, "陈老师",
                6, status, SUBMITTED_AT);
    }

    private static AdjustmentTargetDTO firstTarget() {
        return new AdjustmentTargetDTO(
                OCCURRENCE_ID, 6, "2026-10-08T01:00:00Z", "2026-10-08T02:40:00Z",
                "张老师", null, "教四-201");
    }

    private static AdjustmentTargetDTO secondTarget() {
        return new AdjustmentTargetDTO(
                "9007199254740998", 7, "2026-10-15T01:00:00Z", "2026-10-15T02:40:00Z",
                "李老师", "助教甲", "教四-305");
    }

    private static ScheduleConflictDTO overridableConflict() {
        return new ScheduleConflictDTO("TEACHER_TIME_OVERLAP",
                ScheduleConflictSeverityDTO.OVERRIDABLE, APPLICANT_UID,
                "9007199254741003", 6, 5, 3, 4, "与同教师的另一教学班重叠");
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
