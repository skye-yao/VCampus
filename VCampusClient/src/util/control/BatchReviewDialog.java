package util.control;

import enums.StudentChangeStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import util.InformationRules;

import java.util.Optional;

/** Shared batch-review dialog for student and teacher change requests. */
public final class BatchReviewDialog {
    public record Decision(StudentChangeStatus result, String remark) {}

    private BatchReviewDialog() {}

    public static Optional<Decision> show(Window owner, int count) {
        Dialog<Decision> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("批量审核");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ToggleButton approve = new ToggleButton("通过");
        ToggleButton reject = new ToggleButton("不通过");
        ToggleGroup group = new ToggleGroup();
        approve.setToggleGroup(group); reject.setToggleGroup(group); approve.setSelected(true);
        applyChoiceStyles(approve, reject);
        group.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null && previous != null) previous.setSelected(true);
            else applyChoiceStyles(approve, reject);
        });
        approve.setMaxWidth(Double.MAX_VALUE); reject.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(approve, Priority.ALWAYS); HBox.setHgrow(reject, Priority.ALWAYS);
        HBox choices = new HBox(16, approve, reject); choices.setAlignment(Pos.CENTER);

        TextArea remark = new TextArea();
        remark.setPromptText("可不填写，最多255个字符");
        remark.setWrapText(true); remark.setPrefRowCount(8);
        Label title = new Label("审核处理"); title.getStyleClass().add("student-section-title");
        Label countLabel = new Label("已选择 " + count + " 条待审核申请"); countLabel.getStyleClass().add("hint-text");
        VBox content = new VBox(14, title, countLabel, choices, new Label("审核意见（可选）"), remark);
        content.setPadding(new Insets(20)); content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType("确认审核", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirm);
        confirmButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try { InformationRules.reviewNote(remark.getText()); }
            catch (IllegalArgumentException exception) {
                event.consume(); remark.requestFocus();
                new Alert(Alert.AlertType.WARNING, exception.getMessage(), ButtonType.OK).showAndWait();
            }
        });
        dialog.setResultConverter(button -> button == confirm
                ? new Decision(approve.isSelected() ? StudentChangeStatus.APPROVED : StudentChangeStatus.REJECTED,
                remark.getText() == null ? "" : remark.getText().trim()) : null);
        return dialog.showAndWait();
    }

    private static void applyChoiceStyles(ToggleButton approve, ToggleButton reject) {
        approve.getStyleClass().removeAll("btn-primary", "btn-secondary");
        reject.getStyleClass().removeAll("btn-primary", "btn-secondary");
        approve.getStyleClass().add(approve.isSelected() ? "btn-primary" : "btn-secondary");
        reject.getStyleClass().add(reject.isSelected() ? "btn-primary" : "btn-secondary");
    }
}
