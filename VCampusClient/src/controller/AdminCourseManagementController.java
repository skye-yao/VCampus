package controller;

import app.ClientMain;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;

/**
 * 管理员后台外壳：课程与审批导航，默认进入课程页面。
 */
public final class AdminCourseManagementController {
    private Runnable backAction =
            () -> ClientMain.switchScene("/resources/fxml/MainView.fxml");

    @FXML private Node coursePage;
    @FXML private Node approvalPage;
    @FXML private AdminCourseCatalogController coursePageController;
    @FXML private AdminApprovalController approvalPageController;
    @FXML private ToggleButton courseNavButton;
    @FXML private ToggleButton approvalNavButton;

    @FXML
    public void initialize() {
        showCourses();
    }

    @FXML
    private void showCourses() {
        activate(coursePage, courseNavButton, coursePageController::refresh);
    }

    @FXML
    private void showApproval() {
        activate(approvalPage, approvalNavButton,
                approvalPageController == null ? () -> { } : approvalPageController::refresh);
    }

    @FXML
    void handleBack() {
        backAction.run();
    }

    void setBackAction(Runnable action) {
        this.backAction = action == null ? () -> { } : action;
    }

    private void activate(Node page, ToggleButton button, Runnable refreshAction) {
        setPageState(coursePage, false);
        setPageState(approvalPage, false);
        courseNavButton.setSelected(false);
        approvalNavButton.setSelected(false);

        setPageState(page, true);
        button.setSelected(true);
        refreshAction.run();
    }

    private static void setPageState(Node page, boolean active) {
        page.setVisible(active);
        page.setManaged(active);
    }
}
