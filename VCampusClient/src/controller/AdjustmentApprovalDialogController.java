package controller;

import java.util.List;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 调课申请详情弹窗：左侧原安排、右侧调整后安排，并列出全部目标周。
 *
 * <p>文案完全来自 {@link AdminApprovalController} 的纯文本函数，避免同一份说明出现两种版本。
 */
public final class AdjustmentApprovalDialogController {
    private AdjustmentRequestDetailDTO detail;

    @FXML private Label titleLabel;
    @FXML private Label reasonLabel;
    @FXML private Label statusLabel;
    @FXML private VBox arrangementRows;
    @FXML private VBox conflictRows;
    @FXML private Label emptyConflictsLabel;

    /** 由打开方注入要展示的申请详情。 */
    void showDetail(AdjustmentRequestDetailDTO value) {
        this.detail = value;
        if (titleLabel != null) titleLabel.setText(header(value));
        if (reasonLabel != null) reasonLabel.setText(reasonLine(value));
        if (statusLabel != null) statusLabel.setText("当前状态："
                + AdminApprovalController.statusLabel(value.getStatus()) + "　版本：v"
                + value.getVersion());
        renderArrangements(value);
        renderConflicts(value);
    }

    AdjustmentRequestDetailDTO detail() {
        return detail;
    }

    private void renderArrangements(AdjustmentRequestDetailDTO value) {
        if (arrangementRows == null) return;
        arrangementRows.getChildren().clear();
        Label headerRow = new Label("目标周　｜　原安排　→　调整后安排");
        headerRow.getStyleClass().add("course-approval-dialog-header");
        arrangementRows.getChildren().add(headerRow);
        for (AdminApprovalController.ArrangementRow row
                : AdminApprovalController.arrangementRows(value)) {
            Label week = new Label(row.week());
            week.getStyleClass().add("course-approval-dialog-week");
            week.setMinWidth(84.0);

            Label original = new Label(originalColumn(row));
            original.getStyleClass().add("course-approval-dialog-original");
            original.setWrapText(true);
            original.setMaxWidth(Double.MAX_VALUE);

            Label adjusted = new Label(adjustedColumn(row));
            adjusted.getStyleClass().add("course-approval-dialog-adjusted");
            adjusted.setWrapText(true);
            adjusted.setMaxWidth(Double.MAX_VALUE);

            HBox columns = new HBox(8.0, week, original, adjusted);
            columns.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(original, Priority.ALWAYS);
            HBox.setHgrow(adjusted, Priority.ALWAYS);
            columns.getStyleClass().add("course-approval-dialog-row");
            arrangementRows.getChildren().add(columns);
        }
    }

    private void renderConflicts(AdjustmentRequestDetailDTO value) {
        if (conflictRows == null) return;
        conflictRows.getChildren().clear();
        List<String> lines = AdminApprovalController.conflictLines(value.getConflicts());
        if (emptyConflictsLabel != null) {
            emptyConflictsLabel.setVisible(lines.isEmpty());
            emptyConflictsLabel.setManaged(lines.isEmpty());
        }
        for (String line : lines) {
            Label label = new Label(line);
            label.getStyleClass().add("course-approval-dialog-conflict");
            label.setWrapText(true);
            conflictRows.getChildren().add(label);
        }
    }

    /** 弹窗标题行。 */
    static String header(AdjustmentRequestDetailDTO detail) {
        return "调课申请 " + detail.getRequestId() + "（"
                + AdminApprovalController.statusLabel(detail.getStatus()) + "）";
    }

    /** 申请原因行；没有填写原因时给出占位，避免出现空行。 */
    static String reasonLine(AdjustmentRequestDetailDTO detail) {
        String reason = detail.getReason();
        return "申请原因：" + (reason == null || reason.isBlank() ? "—" : reason);
    }

    /** 左列文案：被替换掉的旧安排。 */
    static String originalColumn(AdminApprovalController.ArrangementRow row) {
        return "原安排：" + row.original();
    }

    /** 右列文案：生效中的新安排。 */
    static String adjustedColumn(AdminApprovalController.ArrangementRow row) {
        return "调课后：" + row.adjusted();
    }
}
