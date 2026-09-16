package controller;

import java.util.ArrayList;
import java.util.List;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 成绩提交详情弹窗：统计指标、成绩分布、逐项成绩与审批信息。
 *
 * <p>文案完全来自 {@link GradeApprovalController} 的纯文本函数，避免同一份统计说明出现两种版本。
 */
public final class GradeApprovalDialogController {
    private GradeSubmissionDetailDTO detail;

    @FXML private Label titleLabel;
    @FXML private Label metaLabel;
    @FXML private HBox metricCardBox;
    @FXML private VBox distributionRows;
    @FXML private VBox itemRows;
    @FXML private VBox comparisonRows;
    @FXML private VBox detailBody;

    /** 由打开方注入要展示的成绩提交详情。 */
    void showDetail(GradeSubmissionDetailDTO value) {
        this.detail = value;
        if (titleLabel != null) titleLabel.setText(header(value));
        if (metaLabel != null) metaLabel.setText(metaLine(value));
        renderMetrics(value);
        renderDistribution(value);
        renderItems(value);
        renderComparison(value);
        if (detailBody != null) {
            detailBody.getChildren().clear();
            for (String line : reviewLines(value)) {
                Label label = new Label(line);
                label.getStyleClass().add("course-approval-detail-line");
                label.setWrapText(true);
                detailBody.getChildren().add(label);
            }
        }
    }

    GradeSubmissionDetailDTO detail() {
        return detail;
    }

    private void renderMetrics(GradeSubmissionDetailDTO value) {
        if (metricCardBox == null) return;
        metricCardBox.getChildren().clear();
        for (GradeApprovalController.MetricCard card : GradeApprovalController.metricCards(value)) {
            Label title = new Label(card.title());
            title.getStyleClass().add("course-approval-metric-title");
            Label number = new Label(card.value());
            number.getStyleClass().add("course-approval-metric-value");
            VBox box = new VBox(2.0, title, number);
            box.getStyleClass().add("course-approval-metric-card");
            metricCardBox.getChildren().add(box);
        }
    }

    private void renderDistribution(GradeSubmissionDetailDTO value) {
        if (distributionRows == null) return;
        distributionRows.getChildren().clear();
        for (String line : distributionLines(value)) {
            Label label = new Label(line);
            label.getStyleClass().add("course-approval-distribution-row");
            distributionRows.getChildren().add(label);
        }
    }

    private void renderItems(GradeSubmissionDetailDTO value) {
        if (itemRows == null) return;
        itemRows.getChildren().clear();
        for (String line : itemLines(value)) {
            Label label = new Label(line);
            label.getStyleClass().add("course-approval-item-row");
            label.setWrapText(true);
            itemRows.getChildren().add(label);
        }
    }

    /**
     * 更正比较一节：普通批次没有比较对象，整节留空。文案与审批页的详情面板同源
     * （{@link GradeApprovalController#correctionLines(GradeSubmissionDetailDTO)}），
     * 同一份差异不会出现两种说法。
     */
    private void renderComparison(GradeSubmissionDetailDTO value) {
        if (comparisonRows == null) return;
        comparisonRows.getChildren().clear();
        for (String line : comparisonLines(value)) {
            Label label = new Label(line);
            label.getStyleClass().add("course-approval-detail-line");
            label.setWrapText(true);
            comparisonRows.getChildren().add(label);
        }
    }

    /** 弹窗标题行。 */
    static String header(GradeSubmissionDetailDTO detail) {
        return "成绩提交 " + detail.getSummary().getSubmissionId() + "（"
                + AdminApprovalController.statusLabel(detail.getSummary().getStatus()) + "）";
    }

    /** 弹窗副标题：课程、教学班、任课教师与学生人数。 */
    static String metaLine(GradeSubmissionDetailDTO detail) {
        GradeSubmissionSummaryDTO summary = detail.getSummary();
        return summary.getCourseName() + "　" + summary.getOfferingCode() + "　教师："
                + summary.getTeacherName() + "　学生：" + summary.getStudentCount() + " 人";
    }

    /** 指标行：与审批页指标卡同源。 */
    static List<String> metricLines(GradeSubmissionDetailDTO detail) {
        List<String> lines = new ArrayList<>();
        for (GradeApprovalController.MetricCard card : GradeApprovalController.metricCards(detail)) {
            lines.add(card.title() + "：" + card.value());
        }
        return List.copyOf(lines);
    }

    static List<String> distributionLines(GradeSubmissionDetailDTO detail) {
        return GradeApprovalController.distributionLines(detail.getDistribution());
    }

    /** 单个学生的整行文本，复用审批页成绩表的单元格。 */
    static String itemLine(GradeSubmissionItemDTO item) {
        return String.join("　", GradeApprovalController.itemCells(item));
    }

    static List<String> itemLines(GradeSubmissionDetailDTO detail) {
        List<String> lines = new ArrayList<>();
        for (GradeSubmissionItemDTO item : detail.getItems()) {
            lines.add(itemLine(item));
        }
        return List.copyOf(lines);
    }

    /**
     * 更正比较：原批准版本、本次更正原因与真的改变了的学生及其旧/新值，逐行复用审批页的纯文本。
     * 普通批次（没有比较对象）返回空列表，弹窗因此不显示这一节。
     */
    static List<String> comparisonLines(GradeSubmissionDetailDTO detail) {
        return GradeApprovalController.correctionLines(detail);
    }

    /** 审批信息：仅对已完成的批次返回，空白意见给出占位。 */
    static List<String> reviewLines(GradeSubmissionDetailDTO detail) {
        List<String> lines = new ArrayList<>();
        if (detail.getReviewedBy() != null) {
            lines.add("审批人：" + detail.getReviewedBy() + "　审批时间：" + detail.getReviewedAt());
        }
        if (detail.getReviewComment() != null) {
            String comment = detail.getReviewComment().isBlank() ? "—"
                    : detail.getReviewComment().trim();
            lines.add("审批意见：" + comment);
        }
        return List.copyOf(lines);
    }
}
