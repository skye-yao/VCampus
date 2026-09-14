package controller;

import java.util.Objects;
import app.ClientMain;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台外壳：左上“返回首页”，右上四入口（教学课程表、教学班、成绩录入、我的申请）。
 *
 * <p>本阶段只交付外壳：四个入口按分阶段计划先渲染为禁用占位，并在页面上提示
 * {@link #STAGING_NOTICE}——不伪造尚未实现的页面，也不提前搭好没有子页可用的导航状态机。
 * 四个 {@code open*} 方法是后续阶段接管这些入口的接入点：教学班页面在 Task 5 装入，
 * 课表、成绩与申请按各自阶段装入，届时它们从 {@link #service()} 取用共享的教师课程服务。
 */
public final class TeacherCourseManagementController {
    /** 供未交付入口使用的阶段性提示文案。 */
    static final String STAGING_NOTICE = "该功能将在后续阶段接入";
    /** “返回首页”的目标视图。 */
    static final String HOME_VIEW = "/resources/fxml/MainView.fxml";

    private final TeacherCourseService service;
    private Runnable backAction = () -> ClientMain.switchScene(HOME_VIEW);

    @FXML private Label statusLabel;

    public TeacherCourseManagementController() {
        this(TeacherCourseServices.current());
    }

    TeacherCourseManagementController(TeacherCourseService service) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
    }

    @FXML
    public void initialize() {
        showStagingNotice();
    }

    @FXML
    void handleBack() {
        backAction.run();
    }

    void setBackAction(Runnable action) {
        this.backAction = action == null ? () -> { } : action;
    }

    /** 后续阶段装入的子页从这里取用共享的教师课程服务，避免各自新建实例。 */
    TeacherCourseService service() {
        return service;
    }

    // 四个入口是分阶段接入点：本阶段只提示尚未接入，不打开任何页面。

    void openOfferings() {
        showStagingNotice();
    }

    void openTimetable() {
        showStagingNotice();
    }

    void openGrades(String offeringId) {
        showStagingNotice();
    }

    void openApplications() {
        showStagingNotice();
    }

    private void showStagingNotice() {
        if (statusLabel != null) {
            statusLabel.setText(STAGING_NOTICE);
        }
    }
}
