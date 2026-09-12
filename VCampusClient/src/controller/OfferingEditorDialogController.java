package controller;

import dto.course.admin.catalog.OfferingEditorRequestDTO;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import model.course.admin.AdminOfferingView;

/**
 * 教学班新增/编辑对话框：标识保持十进制字符串，数值字段在此校验。
 */
public final class OfferingEditorDialogController {
    private static final String NOT_OPEN = "NOT_OPEN";
    private static final String OPEN = "OPEN";
    private static final String STOPPED = "STOPPED";
    private static final String CANCELLED = "CANCELLED";

    private static final String NOT_OPEN_LABEL = "未开放";
    private static final String OPEN_LABEL = "开放";
    private static final String STOPPED_LABEL = "停止";

    private AdminOfferingView editing;
    private String courseId;
    private Runnable onSubmit = () -> { };
    private Runnable closeAction = this::hideWindow;

    @FXML private Node dialogRoot;
    @FXML private Label dialogTitleLabel;
    @FXML private TextField offeringCodeField;
    @FXML private TextField academicYearField;
    @FXML private TextField semesterField;
    @FXML private TextField capacityField;
    @FXML private TextField teacherUidField;
    @FXML private TextField assistantUidField;
    @FXML private ComboBox<String> statusField;
    @FXML private Label validationLabel;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    @FXML
    public void initialize() {
        statusField.getItems().setAll(NOT_OPEN_LABEL, OPEN_LABEL);
        statusField.setValue(NOT_OPEN_LABEL);
    }

    /**
     * 已取消的教学班不允许通过对话框编辑。
     */
    public static boolean isEditable(AdminOfferingView offering) {
        return offering != null && !CANCELLED.equals(offering.getStatus());
    }

    public void prepareForCreate(String courseId) {
        this.courseId = courseId;
        editing = null;
        dialogTitleLabel.setText("新增教学班");
        statusField.getItems().setAll(NOT_OPEN_LABEL, OPEN_LABEL);
        statusField.setValue(NOT_OPEN_LABEL);
        offeringCodeField.clear();
        academicYearField.setText("2026");
        semesterField.setText("1");
        capacityField.setText("60");
        teacherUidField.clear();
        assistantUidField.clear();
        setValidationMessage(null);
    }

    public void prepareForEdit(AdminOfferingView offering) {
        if (!isEditable(offering)) {
            throw new IllegalArgumentException("已取消的教学班不能编辑");
        }
        editing = offering;
        courseId = offering.getCourseId();
        dialogTitleLabel.setText("编辑教学班");
        statusField.getItems().setAll(NOT_OPEN_LABEL, OPEN_LABEL, STOPPED_LABEL);
        statusField.setValue(statusLabel(offering.getStatus()));
        offeringCodeField.setText(offering.getOfferingCode());
        academicYearField.setText(String.valueOf(offering.getAcademicYear()));
        semesterField.setText(String.valueOf(offering.getSemester()));
        capacityField.setText(String.valueOf(offering.getCapacity()));
        teacherUidField.setText(offering.getTeacherUid() == null ? "" : offering.getTeacherUid());
        assistantUidField.setText(
                offering.getAssistantUid() == null ? "" : offering.getAssistantUid());
        setValidationMessage(null);
    }

    public boolean isEditing() {
        return editing != null;
    }

    public boolean isOfferingCodeEditable() {
        return !offeringCodeField.isDisable();
    }

    public void setOnSubmit(Runnable handler) {
        this.onSubmit = handler == null ? () -> { } : handler;
    }

    void setCloseAction(Runnable action) {
        this.closeAction = action == null ? this::hideWindow : action;
    }

    /**
     * 校验失败时返回 {@code null} 并在对话框内展示原因。
     */
    public OfferingEditorRequestDTO collectRequest(String operationId) {
        if (!validate()) return null;
        String offeringId = editing == null ? null : editing.getOfferingId();
        int expectedVersion = editing == null ? 0 : editing.getVersion();
        return new OfferingEditorRequestDTO(operationId, offeringId, expectedVersion, courseId,
                offeringCodeField.getText().trim(), parseInt(academicYearField.getText()),
                parseInt(semesterField.getText()), parseInt(capacityField.getText()),
                teacherUidField.getText().trim(), emptyToNull(assistantUidField.getText()),
                statusCode(statusField.getValue()));
    }

    @FXML
    private void handleSave() {
        if (!validate()) return;
        onSubmit.run();
        closeAction.run();
    }

    @FXML
    private void handleCancel() {
        closeAction.run();
    }

    private boolean validate() {
        if (blank(offeringCodeField.getText())) {
            setValidationMessage("请填写教学班代码");
            return false;
        }
        if (blank(courseId)) {
            setValidationMessage("缺少课程标识");
            return false;
        }
        Integer year = positiveIntOrNull(academicYearField.getText());
        if (year == null) {
            setValidationMessage("学年必须是大于 0 的整数");
            return false;
        }
        Integer semester = positiveIntOrNull(semesterField.getText());
        if (semester == null) {
            setValidationMessage("学期必须是大于 0 的整数");
            return false;
        }
        Integer capacity = positiveIntOrNull(capacityField.getText());
        if (capacity == null) {
            setValidationMessage("容量必须是大于 0 的整数");
            return false;
        }
        if (blank(teacherUidField.getText())) {
            setValidationMessage("请填写任课教师工号");
            return false;
        }
        if (statusCode(statusField.getValue()) == 0) {
            setValidationMessage("请选择教学班状态");
            return false;
        }
        setValidationMessage(null);
        return true;
    }

    private void setValidationMessage(String message) {
        if (validationLabel == null) return;
        validationLabel.setText(message == null ? "" : message);
        validationLabel.setVisible(message != null);
        validationLabel.setManaged(message != null);
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    private static String statusLabel(String status) {
        if (NOT_OPEN.equals(status)) return NOT_OPEN_LABEL;
        if (OPEN.equals(status)) return OPEN_LABEL;
        if (STOPPED.equals(status)) return STOPPED_LABEL;
        if (CANCELLED.equals(status)) return NOT_OPEN_LABEL;
        return NOT_OPEN_LABEL;
    }

    private static int statusCode(String label) {
        if (NOT_OPEN_LABEL.equals(label)) return 1;
        if (OPEN_LABEL.equals(label)) return 2;
        if (STOPPED_LABEL.equals(label)) return 3;
        return 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static int parseInt(String value) {
        Integer parsed = positiveIntOrNull(value);
        return parsed == null ? 0 : parsed;
    }

    private static Integer positiveIntOrNull(String value) {
        if (blank(value)) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }
}
