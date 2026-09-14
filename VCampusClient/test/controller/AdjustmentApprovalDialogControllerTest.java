package controller;

import java.util.List;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;

/**
 * 无 JavaFX 依赖的详情弹窗测试：弹窗文案与并排行的构造完全复用审批控制器的纯文本函数。
 */
public final class AdjustmentApprovalDialogControllerTest {
    public static void main(String[] args) {
        showsEveryTargetWeekSideBySide();
        reusesTheApprovalControllerText();
        coversMissingResourcesAndReasons();
        showsTheRealTargetDateWhenPresent();
        System.out.println("AdjustmentApprovalDialogControllerTest: PASS");
    }

    /** T5：有明确目标日期的申请必须在弹窗里显示日期，教师申请回落到目标原快照。 */
    private static void showsTheRealTargetDateWhenPresent() {
        AdjustmentRequestDetailDTO detail = new AdjustmentRequestDetailDTO("970703", "2001", "T1001",
                "教师出差", AdjustmentRequestStatusDTO.PENDING, 1, 5, 5, 6, null, null, null,
                List.of(new AdjustmentTargetDTO("8005", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-10-30")),
                List.of(), "2026-09-10T02:00:00Z", null, null, null);

        List<AdminApprovalController.ArrangementRow> rows =
                AdminApprovalController.arrangementRows(detail);
        String adjusted = AdjustmentApprovalDialogController.adjustedColumn(rows.get(0));
        require(adjusted.startsWith("调课后：2026-10-30 周五 第 5-6 节"),
                "the dialog must show the real target date, saw " + adjusted);
        require(adjusted.contains("陈老师, 王助教") && adjusted.contains("A-101"),
                "a teacher request must fall back to the target snapshot, saw " + adjusted);
    }

    private static void showsEveryTargetWeekSideBySide() {
        AdjustmentRequestDetailDTO detail = detail("教师出差", AdjustmentRequestStatusDTO.PENDING,
                List.of(target("8001", 1, "2026-09-08T00:00:00Z", "张老师"),
                        target("8002", 3, "2026-09-22T00:00:00Z", "张老师")),
                List.of(conflict()));

        List<AdminApprovalController.ArrangementRow> rows =
                AdminApprovalController.arrangementRows(detail);

        require(rows.size() == 2, "every target week must produce one row, saw " + rows.size());
        require("第 1 周".equals(rows.get(0).week()) && "第 3 周".equals(rows.get(1).week()),
                "rows must keep the target weeks in order");
        require(AdjustmentApprovalDialogController.originalColumn(rows.get(0))
                        .startsWith("原安排：2026-09-08T00:00:00Z")
                        && AdjustmentApprovalDialogController.originalColumn(rows.get(0))
                        .contains("张老师"),
                "the left column must describe the replaced arrangement");
        require(AdminApprovalController.detailLines(detail).stream()
                        .anyMatch(line -> line.startsWith("新安排：周五 第 3-4 节")),
                "the detail lines must describe the approved arrangement, saw "
                        + AdminApprovalController.detailLines(detail));
        require(AdjustmentApprovalDialogController.adjustedColumn(rows.get(0))
                        .startsWith("调课后：周五 第 3-4 节"),
                "the right column must describe the approved arrangement, saw "
                        + AdjustmentApprovalDialogController.adjustedColumn(rows.get(0)));
        require(AdjustmentApprovalDialogController.header(detail).contains("970701")
                        && AdjustmentApprovalDialogController.header(detail).contains("待审批"),
                "the dialog header must identify the request and its status");
    }

    private static void reusesTheApprovalControllerText() {
        AdjustmentRequestDetailDTO detail = detail("教师出差", AdjustmentRequestStatusDTO.PENDING,
                List.of(target("8001", 1, "2026-09-08T00:00:00Z", "张老师")), List.of(conflict()));

        require(AdminApprovalController.conflictLines(detail.getConflicts()).size() == 1
                        && AdminApprovalController.conflictLines(detail.getConflicts()).get(0)
                        .contains("教师时间冲突"),
                "the dialog must render the same conflict lines as the approval page");
        require(AdminApprovalController.detailLines(detail).contains("申请原因：教师出差"),
                "the dialog must render the same detail lines as the approval page");
    }

    private static void coversMissingResourcesAndReasons() {
        AdjustmentRequestDetailDTO bare = new AdjustmentRequestDetailDTO("970702", "2001", "T1001",
                "  ", AdjustmentRequestStatusDTO.APPROVED, 1, 2, 1, 2, null, null, null,
                List.of(new AdjustmentTargetDTO("8003", 2, "2026-09-15T00:00:00Z",
                        "2026-09-15T01:35:00Z", null, null, null)),
                List.of(), "2026-09-10T02:00:00Z", null, null, null);

        require("申请原因：—".equals(AdjustmentApprovalDialogController.reasonLine(bare)),
                "a blank reason must render as a placeholder");
        List<AdminApprovalController.ArrangementRow> rows =
                AdminApprovalController.arrangementRows(bare);
        require(rows.get(0).original().contains("—"),
                "missing snapshot resources must render as a placeholder, saw "
                        + rows.get(0).original());
        // T5 起每个目标的新安排显示该目标原快照里的教师/教室；目标快照也为空时只留占位符，
        // 请求级的“新安排”行才用 沿用原安排 表示没有替换资源。
        require(rows.get(0).adjusted().contains("—"),
                "missing snapshot resources must render as a placeholder, saw "
                        + rows.get(0).adjusted());
        require(AdminApprovalController.detailLines(bare).stream()
                        .anyMatch(line -> line.startsWith("新安排：") && line.contains("沿用原安排")),
                "the request level line must read a null proposed resource as inheriting the "
                        + "original, saw " + AdminApprovalController.detailLines(bare));
        require(AdminApprovalController.detailLines(bare).contains("冲突：无"),
                "a request without conflicts must say so explicitly");
    }

    private static AdjustmentRequestDetailDTO detail(String reason, AdjustmentRequestStatusDTO status,
            List<AdjustmentTargetDTO> targets, List<ScheduleConflictDTO> conflicts) {
        return new AdjustmentRequestDetailDTO("970701", "2001", "T1001", reason, status, 3, 5, 3, 4,
                new ScheduleResourceDTO("T2001", "T2001", "李老师", "teacher", 0),
                new ScheduleResourceDTO("T2002", "T2002", "王老师", "teacher", 0),
                new ScheduleResourceDTO("3002", "3002", "教二-305", "classroom", 120),
                targets, conflicts, "2026-09-10T02:00:00Z", null, null, null);
    }

    private static AdjustmentTargetDTO target(String occurrenceId, int week, String startAt,
                                              String teacher) {
        return new AdjustmentTargetDTO(occurrenceId, week, startAt, startAt, teacher, null,
                "教四-201");
    }

    private static ScheduleConflictDTO conflict() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "T2001", "2001", 1, 3, 3, 4, "教师时间冲突");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
