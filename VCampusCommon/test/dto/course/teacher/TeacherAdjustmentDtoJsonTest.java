package dto.course.teacher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;

/**
 * 教师调课申请公共契约的 JSON 保真测试。
 *
 * <p>覆盖：跨周目标日期保留为 ISO 本地日期（不是 UTC 时刻）、历史目标的未知日期反序列化为 null、
 * 调课状态是独立四态且 WITHDRAWN 不污染成绩专用 {@code ApprovalStatusDTO}、BIGINT 标识保持精确
 * 十进制字符串、targets 在构造和反序列化两条路径上都不可变，以及四类 DTO 的线格式键集合。
 */
public final class TeacherAdjustmentDtoJsonTest {
    private static final Gson GSON = new Gson();

    private static final String OCCURRENCE_ID = "9007199254740993";
    private static final String OFFERING_ID = "9007199254740995";
    private static final String CLASSROOM_ID = "9007199254740997";
    private static final String CALENDAR_ID = "9007199254740999";
    private static final String OPERATION_ID = "ab5a488c-74d1-4e16-9f16-e8f7741e454d";

    public static void main(String[] args) {
        adjustmentStatusIsAnIndependentFourStateEnum();
        adjustmentsKeepTheThreeStateGradeStatusUntouched();
        targetInputCarriesAnIsoLocalDate();
        writeDtoRoundTripsCrossWeekTargetsWithExactIds();
        previewDtoRoundTripsTypedConflicts();
        optionsDtoRoundTripsCalendarDatesPeriodsAndClassrooms();
        withdrawDtoRoundTripsOperationRequestAndVersion();
        historicalTargetsWithoutADateStayNull();
        targetsStayImmutableAndKeepExactIds();
        treatsNullListsAsEmptyImmutable();
        pinsExactJsonKeySets();
        System.out.println("TeacherAdjustmentDtoJsonTest passed (11 scenarios)");
    }

    private static void adjustmentStatusIsAnIndependentFourStateEnum() {
        AdjustmentRequestStatusDTO[] expected = {
                AdjustmentRequestStatusDTO.PENDING,
                AdjustmentRequestStatusDTO.APPROVED,
                AdjustmentRequestStatusDTO.REJECTED,
                AdjustmentRequestStatusDTO.WITHDRAWN};
        require(Arrays.equals(expected, AdjustmentRequestStatusDTO.values()),
                "adjustment status must expose exactly PENDING, APPROVED, REJECTED, WITHDRAWN");
        require(GSON.fromJson("\"WITHDRAWN\"", AdjustmentRequestStatusDTO.class)
                        == AdjustmentRequestStatusDTO.WITHDRAWN,
                "WITHDRAWN must deserialize from its wire name");
        require(GSON.fromJson("\"CANCELLED\"", AdjustmentRequestStatusDTO.class) == null,
                "an unmapped adjustment status must deserialize to null, not to a valid status");
    }

    private static void adjustmentsKeepTheThreeStateGradeStatusUntouched() {
        List<String> names = new ArrayList<>();
        for (dto.course.admin.approval.ApprovalStatusDTO status
                : dto.course.admin.approval.ApprovalStatusDTO.values()) {
            names.add(status.name());
        }
        require(names.equals(List.of("PENDING", "APPROVED", "REJECTED")),
                "the grade approval status must still expose exactly its three states");
        require(dto.course.admin.approval.ApprovalStatusDTO.valueOf("PENDING")
                        == dto.course.admin.approval.ApprovalStatusDTO.PENDING,
                "the grade status must stay usable for grade submissions");
    }

    private static void targetInputCarriesAnIsoLocalDate() {
        TeacherAdjustmentTargetInputDTO copy = GSON.fromJson(
                GSON.toJson(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08")),
                TeacherAdjustmentTargetInputDTO.class);

        require(OCCURRENCE_ID.equals(copy.getOriginalOccurrenceId()),
                "the original occurrence ID must stay exact beyond the JavaScript safe integer");
        require("2026-10-08".equals(copy.getTargetDate()),
                "the target date must stay an ISO local date");
        require(!copy.getTargetDate().contains("T") && !copy.getTargetDate().contains("Z"),
                "the target date must never be a UTC instant");
    }

    private static void writeDtoRoundTripsCrossWeekTargetsWithExactIds() {
        TeacherAdjustmentWriteDTO source = new TeacherAdjustmentWriteDTO(OPERATION_ID, OFFERING_ID,
                List.of(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08"),
                        new TeacherAdjustmentTargetInputDTO("9007199254740994", "2026-10-15")),
                3, 4, CLASSROOM_ID, "教师出差");
        TeacherAdjustmentWriteDTO copy = GSON.fromJson(
                GSON.toJson(source), TeacherAdjustmentWriteDTO.class);

        require(OPERATION_ID.equals(copy.getOperationId()), "the operation ID must survive JSON");
        require(OFFERING_ID.equals(copy.getOfferingId()), "the offering ID must stay exact");
        require(copy.getNewStartPeriod() == 3 && copy.getNewEndPeriod() == 4,
                "the proposed period span must survive JSON");
        require(CLASSROOM_ID.equals(copy.getNewClassroomId()),
                "the proposed classroom ID must stay exact");
        require("教师出差".equals(copy.getReason()), "the UTF-8 reason must survive JSON");
        require(copy.getTargets().size() == 2, "both cross-week targets must survive JSON");
        require("2026-10-08".equals(copy.getTargets().get(0).getTargetDate())
                        && "2026-10-15".equals(copy.getTargets().get(1).getTargetDate()),
                "each target must keep its own local date");
        require(!copy.getTargets().get(0).getTargetDate()
                        .equals(copy.getTargets().get(1).getTargetDate()),
                "cross-week targets must not collapse onto one date");
    }

    private static void previewDtoRoundTripsTypedConflicts() {
        TeacherAdjustmentPreviewDTO source = new TeacherAdjustmentPreviewDTO(
                List.of(new ScheduleConflictDTO("TEACHER_TIME_OVERLAP",
                        ScheduleConflictSeverityDTO.BLOCKING, OCCURRENCE_ID, OFFERING_ID,
                        8, 4, 3, 4, "与同教师的另一教学班重叠")),
                true);
        TeacherAdjustmentPreviewDTO copy = GSON.fromJson(
                GSON.toJson(source), TeacherAdjustmentPreviewDTO.class);

        require(copy.isCanSubmit(), "the submission capability flag must round-trip");
        require(copy.getConflicts().size() == 1, "the preview must retain its typed conflicts");
        require(copy.getConflicts().get(0).getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                "the conflict severity must stay a typed enum after JSON");
        require(OCCURRENCE_ID.equals(copy.getConflicts().get(0).getSubjectId()),
                "the conflict subject ID must stay exact");
    }

    private static void optionsDtoRoundTripsCalendarDatesPeriodsAndClassrooms() {
        TeacherAdjustmentOptionsDTO source = new TeacherAdjustmentOptionsDTO(CALENDAR_ID,
                "Asia/Shanghai",
                List.of(new TeacherCalendarDateDTO("2026-10-08", 8, 4, true)),
                List.of(new TeacherPeriodDTO("2026-10-08", 3, "10:00:00", "10:45:00")),
                List.of(new ScheduleResourceDTO(CLASSROOM_ID, "R-201", "教四-201", "CLASSROOM", 60)));
        TeacherAdjustmentOptionsDTO copy = GSON.fromJson(
                GSON.toJson(source), TeacherAdjustmentOptionsDTO.class);

        require(CALENDAR_ID.equals(copy.getCalendarId()), "the calendar ID must stay exact");
        require("Asia/Shanghai".equals(copy.getTimezone()),
                "the timezone must stay the teaching calendar's IANA name");
        require(copy.getDates().size() == 1 && "2026-10-08".equals(copy.getDates().get(0).getDate()),
                "the option dates must survive JSON");
        require(copy.getPeriods().size() == 1 && copy.getPeriods().get(0).getPeriod() == 3,
                "the option periods must survive JSON");
        require(copy.getClassrooms().size() == 1
                        && CLASSROOM_ID.equals(copy.getClassrooms().get(0).getResourceId()),
                "the classroom resources must stay exact");
    }

    private static void withdrawDtoRoundTripsOperationRequestAndVersion() {
        WithdrawTeacherAdjustmentRequestDTO copy = GSON.fromJson(
                GSON.toJson(new WithdrawTeacherAdjustmentRequestDTO(OPERATION_ID,
                        "9007199254740994", 3)),
                WithdrawTeacherAdjustmentRequestDTO.class);

        require(OPERATION_ID.equals(copy.getOperationId()), "the withdraw operation ID must survive");
        require("9007199254740994".equals(copy.getRequestId()),
                "the withdraw request ID must stay exact beyond the JavaScript safe integer");
        require(copy.getExpectedVersion() == 3, "the expected version must survive JSON");
    }

    private static void historicalTargetsWithoutADateStayNull() {
        TeacherAdjustmentTargetInputDTO absent = GSON.fromJson(
                GSON.toJson(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, null)),
                TeacherAdjustmentTargetInputDTO.class);
        require(absent.getTargetDate() == null,
                "a historical target without a date must keep a null target date");

        TeacherAdjustmentWriteDTO wire = GSON.fromJson(
                "{\"operationId\":\"" + OPERATION_ID + "\",\"offeringId\":\"" + OFFERING_ID + "\","
                        + "\"targets\":[{\"originalOccurrenceId\":\"" + OCCURRENCE_ID + "\","
                        + "\"targetDate\":null}],\"newStartPeriod\":1,\"newEndPeriod\":2,"
                        + "\"newClassroomId\":null,\"reason\":\"临时调课\"}",
                TeacherAdjustmentWriteDTO.class);
        require(wire.getTargets().get(0).getTargetDate() == null,
                "an explicit JSON null target date must stay null, not become today");
        require(wire.getNewClassroomId() == null,
                "an absent classroom must stay null instead of inheriting an arbitrary room");
    }

    private static void targetsStayImmutableAndKeepExactIds() {
        List<TeacherAdjustmentTargetInputDTO> targets = new ArrayList<>(List.of(
                new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08")));
        TeacherAdjustmentWriteDTO write = writeDto(targets);
        targets.clear();

        require(write.getTargets().size() == 1,
                "the write DTO must copy the caller's target list");
        requireUnmodifiable(write.getTargets(), "constructed write targets");

        TeacherAdjustmentWriteDTO copy = GSON.fromJson(GSON.toJson(writeDto(
                List.of(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08")))),
                TeacherAdjustmentWriteDTO.class);
        require(OCCURRENCE_ID.equals(copy.getTargets().get(0).getOriginalOccurrenceId()),
                "the deserialized target identity must stay exact");
        requireUnmodifiable(copy.getTargets(), "deserialized write targets");
    }

    private static void treatsNullListsAsEmptyImmutable() {
        TeacherAdjustmentWriteDTO write = new TeacherAdjustmentWriteDTO(OPERATION_ID, OFFERING_ID,
                null, 1, 2, null, "临时调课");
        require(write.getTargets() != null && write.getTargets().isEmpty(),
                "a null target list must become empty, not null");
        requireUnmodifiable(write.getTargets(), "null-list write targets");

        TeacherAdjustmentPreviewDTO preview = new TeacherAdjustmentPreviewDTO(null, false);
        require(preview.getConflicts() != null && preview.getConflicts().isEmpty(),
                "a null conflict list must become empty, not null");
        require(!preview.isCanSubmit(), "an incomplete preview must stay non-submittable");
        requireUnmodifiable(preview.getConflicts(), "null-list preview conflicts");

        TeacherAdjustmentOptionsDTO options =
                new TeacherAdjustmentOptionsDTO(CALENDAR_ID, "Asia/Shanghai", null, null, null);
        require(options.getDates().isEmpty() && options.getPeriods().isEmpty()
                        && options.getClassrooms().isEmpty(),
                "null option lists must become empty, not null");
        requireUnmodifiable(options.getDates(), "null-list option dates");
        requireUnmodifiable(options.getPeriods(), "null-list option periods");
        requireUnmodifiable(options.getClassrooms(), "null-list option classrooms");
    }

    private static void pinsExactJsonKeySets() {
        requireKeySet(new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08"),
                List.of("originalOccurrenceId", "targetDate"));
        requireKeySet(writeDto(List.of(
                        new TeacherAdjustmentTargetInputDTO(OCCURRENCE_ID, "2026-10-08"))),
                List.of("operationId", "offeringId", "targets", "newStartPeriod", "newEndPeriod",
                        "newClassroomId", "reason"));
        requireKeySet(new TeacherAdjustmentPreviewDTO(List.of(), true),
                List.of("conflicts", "canSubmit"));
        requireKeySet(new TeacherAdjustmentOptionsDTO(CALENDAR_ID, "Asia/Shanghai",
                        List.of(), List.of(), List.of()),
                List.of("calendarId", "timezone", "dates", "periods", "classrooms"));
        requireKeySet(new WithdrawTeacherAdjustmentRequestDTO(OPERATION_ID, "9001", 1),
                List.of("operationId", "requestId", "expectedVersion"));
    }

    private static TeacherAdjustmentWriteDTO writeDto(
            List<TeacherAdjustmentTargetInputDTO> targets) {
        return new TeacherAdjustmentWriteDTO(OPERATION_ID, OFFERING_ID, targets, 3, 4, CLASSROOM_ID,
                "教师出差");
    }

    private static void requireKeySet(Object sample, List<String> expected) {
        Set<String> actual =
                new LinkedHashSet<>(GSON.toJsonTree(sample).getAsJsonObject().keySet());
        Set<String> expectedSet = new LinkedHashSet<>(expected);

        require(actual.equals(expectedSet),
                "wire keys for " + sample.getClass().getSimpleName() + " must be "
                        + expectedSet + " but were " + actual);
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
