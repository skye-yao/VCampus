package controller;

import java.util.List;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;

/**
 * 无 JavaFX 依赖的成绩详情弹窗测试：弹窗文案与逐项成绩完全复用审批控制器的纯文本函数，
 * 避免同一份统计说明出现两种版本。
 */
public final class GradeApprovalDialogControllerTest {
    public static void main(String[] args) {
        showsTheHeaderAndMetrics();
        listsDistributionAndEveryStudent();
        coversMissingScoresAndReviews();
        reusesTheGradeApprovalText();
        System.out.println("GradeApprovalDialogControllerTest: PASS");
    }

    private static void showsTheHeaderAndMetrics() {
        GradeSubmissionDetailDTO detail = detail(ApprovalStatusDTO.PENDING, null, null, null);

        require(GradeApprovalDialogController.header(detail).contains("9001")
                        && GradeApprovalDialogController.header(detail).contains("待审批"),
                "the dialog header must identify the submission and its status");
        require(GradeApprovalDialogController.metaLine(detail).contains("张老师")
                        && GradeApprovalDialogController.metaLine(detail).contains("42"),
                "the dialog meta line must name the teacher and the student count, saw "
                        + GradeApprovalDialogController.metaLine(detail));

        List<String> metrics = GradeApprovalDialogController.metricLines(detail);
        require(metrics.stream().anyMatch(line -> line.startsWith("平均分")),
                "the dialog must show the average metric, saw " + metrics);
        require(metrics.stream().anyMatch(line -> line.startsWith("最高分")),
                "the dialog must show the highest metric, saw " + metrics);
        require(metrics.stream().anyMatch(line -> line.startsWith("最低分")),
                "the dialog must show the lowest metric, saw " + metrics);
        require(metrics.stream().anyMatch(line -> line.startsWith("不及格")),
                "the dialog must show the fail count metric, saw " + metrics);
    }

    private static void listsDistributionAndEveryStudent() {
        GradeSubmissionDetailDTO detail = detail(ApprovalStatusDTO.PENDING, null, null, null);

        List<String> distribution = GradeApprovalDialogController.distributionLines(detail);
        require(distribution.size() == 2
                        && distribution.get(0).startsWith("90-100：1 人"),
                "every distribution bucket must render one line, saw " + distribution);

        List<String> itemLines = GradeApprovalDialogController.itemLines(detail);
        require(itemLines.size() == 2 && itemLines.get(0).contains("张三"),
                "every student must render one line, saw " + itemLines);
    }

    private static void coversMissingScoresAndReviews() {
        GradeSubmissionDetailDTO sparse = detail(ApprovalStatusDTO.REJECTED, "审核员",
                "2026-09-11T03:00:00Z", "   ");

        String sparseLine = GradeApprovalDialogController.itemLine(sparse.getItems().get(1));
        require(sparseLine.contains("--") && !sparseLine.contains("null"),
                "a student without scores must render dashes, saw " + sparseLine);

        List<String> review = GradeApprovalDialogController.reviewLines(sparse);
        require(review.stream().anyMatch(line -> line.contains("审批人：审核员")),
                "a reviewed batch must name the reviewer, saw " + review);
        require(review.stream().anyMatch(line -> line.contains("审批意见：—")),
                "a blank comment must render as a placeholder, saw " + review);

        require(GradeApprovalDialogController.reviewLines(detail(ApprovalStatusDTO.PENDING, null,
                        null, null)).isEmpty(),
                "a pending batch must not render a review section");
    }

    private static void reusesTheGradeApprovalText() {
        GradeSubmissionDetailDTO detail = detail(ApprovalStatusDTO.PENDING, null, null, null);

        require(GradeApprovalDialogController.metricLines(detail).equals(
                        GradeApprovalController.metricCards(detail).stream()
                                .map(card -> card.title() + "：" + card.value()).toList()),
                "the dialog metrics must reuse the tab metric cards");
        require(GradeApprovalDialogController.itemLine(detail.getItems().get(0))
                        .equals(String.join("　", GradeApprovalController.itemCells(
                                detail.getItems().get(0)))),
                "the dialog student line must reuse the tab cell text");
    }

    private static GradeSubmissionDetailDTO detail(ApprovalStatusDTO status, String reviewedBy,
            String reviewedAt, String reviewComment) {
        GradeSubmissionSummaryDTO summary = new GradeSubmissionSummaryDTO("9001", "1001",
                "数据结构", "OFF-1001", 3, "T1001", "张老师", 42, 86.5, 98.0, 55.0, 4, status,
                "2026-09-10T02:00:00Z");
        return new GradeSubmissionDetailDTO(summary,
                List.of(new GradeDistributionBucketDTO("90-100", 1),
                        new GradeDistributionBucketDTO("0-59", 1)),
                List.of(new GradeSubmissionItemDTO("8101", "S1001", "张三", 88.0, 78.0, 90.0, 92.0,
                                87.0, 3, 3.7),
                        new GradeSubmissionItemDTO("8102", "S1002", "李四", null, null, null, null,
                                null, null, null)),
                reviewedBy, reviewedAt, reviewComment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
