package ui;

import controller.OfferingEditorDialogController;
import dto.course.TermLabels;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import service.AdminCourseServices;
import service.MockAdminCourseService;

/**
 * FXML 装载门：本计划改过的两个界面必须能真的装载出来，且工具栏上的学期下拉是控件、
 * 有选项、并且默认选中了一个。只看 FXML 能不能装载——不驱动交互。
 */
public final class AdminCatalogFxmlLoadTest extends Application {

    public static void main(String[] args) {
        AdminCourseServices.install(new MockAdminCourseService());
        Application.launch(AdminCatalogFxmlLoadTest.class, args);
    }

    /**
     * 学期下拉是**异步**装上的：外壳 {@code initialize()} → {@code showCourses()} →
     * {@code refresh()} → {@code loadTerms()}，而 {@code loadTerms()} 把回调经
     * {@code Platform.runLater} 投进 FX 事件队列——{@code start()} 返回前那些回调不会执行。
     * 所以断言要再投一个 {@code runLater}：它排在已排队的那批回调之后，看到的是加载完成后的
     * 控件状态。这是把断言对准真实时序，不是放宽断言。
     */
    @Override
    public void start(Stage stage) throws Exception {
        Parent shell = new FXMLLoader(
                AdminCatalogFxmlLoadTest.class.getResource("/resources/fxml/AdminCourseManagementView.fxml"))
                .load();
        require(shell != null, "管理员外壳必须装载成功");
        Scene scene = new Scene(shell);

        Platform.runLater(() -> {
            try {
                verifyLoadedShell(scene);
            } catch (Throwable failure) {
                failure.printStackTrace();
                Platform.exit();
                System.exit(1);
                return;
            }
            System.out.println("Admin catalog FXML load test passed.");
            Platform.exit();
        });
    }

    private static void verifyLoadedShell(Scene scene) throws Exception {
        ComboBox<?> picker = (ComboBox<?>) scene.lookup("#termFilter");
        require(picker != null, "外壳里必须存在 #termFilter（学期下拉）");
        require(!picker.getItems().isEmpty(),
                "学期下拉必须有选项——没有选项说明 loadTerms() 没跑到，或假服务没被装上");
        require(picker.getValue() != null, "学期下拉必须默认选中一个学期");

        FXMLLoader editorLoader = new FXMLLoader(
                AdminCatalogFxmlLoadTest.class.getResource("/resources/fxml/OfferingEditorDialog.fxml"));
        Parent editor = editorLoader.load();
        require(editor != null, "教学班编辑器必须装载成功");
        Scene editorScene = new Scene(editor);
        require(editorScene.lookup("#semesterField") instanceof ComboBox,
                "教学班编辑器的学期必须已经是下拉，不再是文本框");

        // 「开设教学班」从当前选中的学期起步——这条是生产路径，不是只测 static 助手。
        // Task 9 的评审指出：`semesterLabel(int)` 在生产侧没有调用方，所以那条往返断言
        // 走的是测试专用路径，将来 prepareForCreate 不再用 TermLabels 也照样绿。这里把它钉住。
        OfferingEditorDialogController editorController = editorLoader.getController();
        editorController.prepareForCreate("101", 2027, 3);
        ComboBox<?> semesterPicker = (ComboBox<?>) editorScene.lookup("#semesterField");
        require(TermLabels.label(3).equals(semesterPicker.getValue()),
                "prepareForCreate 必须把学期下拉预选到传入的学期，saw " + semesterPicker.getValue());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
