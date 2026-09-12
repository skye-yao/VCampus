package controller;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import model.course.admin.AdminCourseView;

/**
 * 课程新增/编辑对话框：字段校验在此完成，返回 {@link CourseEditorRequestDTO}。
 */
public final class CourseEditorDialogController {
    private AdminCourseView editing;
    private Runnable onSubmit = () -> { };
    private Runnable closeAction = this::hideWindow;

    @FXML private Node dialogRoot;
    @FXML private Label dialogTitleLabel;
    @FXML private TextField courseCodeField;
    @FXML private TextField courseNameField;
    @FXML private ComboBox<String> courseTypeField;
    @FXML private TextField creditField;
    @FXML private TextField creditHoursField;
    @FXML private TextArea descriptionField;
    @FXML private TextField prerequisitesField;
    @FXML private CheckBox allowCrossMajorField;
    @FXML private CheckBox finalExamField;
    @FXML private Label validationLabel;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    @FXML
    public void initialize() {
        courseTypeField.getItems().setAll("必修", "限选", "选修", "通选");
        prepareForCreate();
    }

    public void prepareForCreate() {
        editing = null;
        dialogTitleLabel.setText("新增课程");
        courseCodeField.setDisable(false);
        courseCodeField.clear();
        courseNameField.clear();
        courseTypeField.setValue("必修");
        creditField.setText("3.0");
        creditHoursField.setText("48");
        descriptionField.clear();
        prerequisitesField.clear();
        allowCrossMajorField.setSelected(true);
        finalExamField.setSelected(true);
        setValidationMessage(null);
    }

    public void prepareForEdit(AdminCourseView course) {
        editing = course;
        dialogTitleLabel.setText("编辑课程");
        courseCodeField.setText(course.getCourseCode());
        courseCodeField.setDisable(true);
        courseNameField.setText(course.getCourseName());
        courseTypeField.setValue(course.getCourseType());
        creditField.setText(String.valueOf(course.getCredit()));
        creditHoursField.setText(String.valueOf(course.getCreditHours()));
        descriptionField.setText(course.getDescription() == null ? "" : course.getDescription());
        prerequisitesField.setText(
                course.getPrerequisites() == null ? "" : course.getPrerequisites());
        allowCrossMajorField.setSelected(course.isAllowCrossMajor());
        finalExamField.setSelected(course.isFinalExam());
        setValidationMessage(null);
    }

    public boolean isEditing() {
        return editing != null;
    }

    public boolean isCourseCodeEditable() {
        return !courseCodeField.isDisable();
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
    public CourseEditorRequestDTO collectRequest(String operationId) {
        if (!validate()) return null;
        String courseId = editing == null ? null : editing.getCourseId();
        int expectedVersion = editing == null ? 0 : editing.getVersion();
        return new CourseEditorRequestDTO(operationId, courseId, expectedVersion,
                courseCodeField.getText().trim(), courseNameField.getText().trim(),
                courseTypeField.getValue(), parseDouble(creditField.getText()),
                parseInt(creditHoursField.getText()), emptyToNull(descriptionField.getText()),
                emptyToNull(prerequisitesField.getText()), allowCrossMajorField.isSelected(),
                finalExamField.isSelected());
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
        if (blank(courseCodeField.getText())) {
            setValidationMessage("请填写课程代码");
            return false;
        }
        if (blank(courseNameField.getText())) {
            setValidationMessage("请填写课程名称");
            return false;
        }
        if (courseTypeField.getValue() == null) {
            setValidationMessage("请选择课程类型");
            return false;
        }
        Double credit = parseDoubleOrNull(creditField.getText());
        if (credit == null || credit <= 0) {
            setValidationMessage("学分必须是大于 0 的数字");
            return false;
        }
        Integer creditHours = parseIntOrNull(creditHoursField.getText());
        if (creditHours == null || creditHours <= 0) {
            setValidationMessage("学时必须是大于 0 的整数");
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static double parseDouble(String value) {
        Double parsed = parseDoubleOrNull(value);
        return parsed == null ? 0.0 : parsed;
    }

    private static Double parseDoubleOrNull(String value) {
        if (blank(value)) return null;
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static int parseInt(String value) {
        Integer parsed = parseIntOrNull(value);
        return parsed == null ? 0 : parsed;
    }

    private static Integer parseIntOrNull(String value) {
        if (blank(value)) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }
}
