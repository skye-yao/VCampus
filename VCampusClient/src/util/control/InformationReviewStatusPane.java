package util.control;

import enums.StudentChangeStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/** 学籍、教师详情页的只读审核进度。 */
public class InformationReviewStatusPane extends VBox {
    private final Label submitLabel = new Label("学生提交");
    private final Label submitTimeLabel = new Label("提交时间：—");
    private final Label reviewLabel = new Label("待管理员审核");
    private final Label noteLabel = new Label("暂无提交记录");
    private final Label firstStep = step("1");
    private final Label secondStep = step("2");

    public InformationReviewStatusPane() {
        setMinWidth(280);
        setPrefWidth(280);
        setMaxWidth(Double.MAX_VALUE);
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(66, 20, 20, 20));
        getStylesheets().add(getClass().getResource("/resources/css/information-review.css").toExternalForm());
        Label heading = new Label("审核状态");
        heading.getStyleClass().add("information-review-heading");
        submitLabel.getStyleClass().add("information-review-title");
        reviewLabel.getStyleClass().add("information-review-title");
        submitTimeLabel.getStyleClass().add("information-review-note");
        noteLabel.getStyleClass().add("information-review-note");
        submitTimeLabel.setWrapText(true);
        noteLabel.setWrapText(true);
        VBox submitted = new VBox(8, submitLabel, submitTimeLabel);
        HBox first = new HBox(12, firstStep, submitted);
        Region line = new Region();
        line.setMinSize(2, 34);
        line.setPrefSize(2, 34);
        line.setMaxSize(2, 34);
        line.getStyleClass().add("information-review-line");
        VBox.setMargin(line, new Insets(0, 0, 0, 13));
        HBox second = new HBox(12, secondStep, new VBox(8, reviewLabel, noteLabel));
        VBox timeline = new VBox(10, first, line, second);
        VBox card = new VBox(24, heading, timeline);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setMinWidth(240);
        card.setPrefWidth(240);
        card.setMaxWidth(240);
        card.getStyleClass().add("information-review-card");
        getChildren().add(card);
        showRequest(null, null);
    }

    private static Label step(String number) {
        Label label = new Label(number);
        label.setAlignment(Pos.CENTER);
        label.setMinSize(28, 28);
        label.setPrefSize(28, 28);
        label.setMaxSize(28, 28);
        label.getStyleClass().add("information-review-step");
        return label;
    }

    public void setSubmitter(String submitter) {
        submitLabel.setText(submitter + "提交");
    }

    public String getSubmitter() {
        return submitLabel.getText().replace("提交", "");
    }

    public void showRequest(StudentChangeStatus status, Timestamp submitTime) {
        boolean submitted = status != null;
        boolean reviewed = status == StudentChangeStatus.APPROVED || status == StudentChangeStatus.REJECTED;
        firstStep.getStyleClass().remove("information-review-done");
        secondStep.getStyleClass().removeAll("information-review-done", "information-review-pending");
        if (submitted) firstStep.getStyleClass().add("information-review-done");
        if (reviewed) secondStep.getStyleClass().add("information-review-done");
        else if (status == StudentChangeStatus.PENDING) secondStep.getStyleClass().add("information-review-pending");
        submitTimeLabel.setText("提交时间：\n" + (submitTime == null ? "—" :
                submitTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        reviewLabel.setText(reviewed ? "管理员已审核" : status == StudentChangeStatus.CANCELLED ? "审核已取消" : "待管理员审核");
        noteLabel.setText(status == null ? "暂无提交记录" : switch (status) {
            case PENDING -> "申请已提交，请等待审核";
            case APPROVED -> "审核结果：已通过";
            case REJECTED -> "审核结果：未通过";
            case CANCELLED -> "申请已取消";
        });
    }
}
