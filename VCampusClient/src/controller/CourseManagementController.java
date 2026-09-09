package controller;

import app.ClientMain;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;

public final class CourseManagementController {

    @FXML private Node selectionPage;
    @FXML private Node schedulePage;
    @FXML private Node gradePage;
    @FXML private Node planPage;

    @FXML private CourseSelectionController selectionPageController;
    @FXML private ScheduleController schedulePageController;
    @FXML private GradeController gradePageController;
    @FXML private TrainingPlanController planPageController;

    @FXML private ToggleButton selectionNavButton;
    @FXML private ToggleButton scheduleNavButton;
    @FXML private ToggleButton gradeNavButton;
    @FXML private ToggleButton planNavButton;

    @FXML
    public void initialize() {
        activate(selectionPage, selectionNavButton, selectionPageController::refresh);
    }

    @FXML
    private void showSelection() {
        activate(selectionPage, selectionNavButton, selectionPageController::refresh);
    }

    @FXML
    private void showSchedule() {
        activate(schedulePage, scheduleNavButton, schedulePageController::refresh);
    }

    @FXML
    private void showGrades() {
        activate(gradePage, gradeNavButton, gradePageController::refresh);
    }

    @FXML
    private void showTrainingPlan() {
        activate(planPage, planNavButton, planPageController::refresh);
    }

    @FXML
    private void handleBack() {
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }

    private void activate(Node page, ToggleButton button, Runnable refreshAction) {
        setPageState(selectionPage, false);
        setPageState(schedulePage, false);
        setPageState(gradePage, false);
        setPageState(planPage, false);

        selectionNavButton.setSelected(false);
        scheduleNavButton.setSelected(false);
        gradeNavButton.setSelected(false);
        planNavButton.setSelected(false);

        setPageState(page, true);
        button.setSelected(true);

        refreshAction.run();
    }

    private void setPageState(Node page, boolean active) {
        page.setVisible(active);
        page.setManaged(active);
    }
}
