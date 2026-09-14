package ui;

import java.net.URL;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import service.MockTeacherCourseService;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台离线预览：安装 {@link MockTeacherCourseService}，加载真实教师外壳。
 *
 * <p>与 {@link CourseUiPreview}/{@link AdminCourseUiPreview} 同形：正常启动保持窗口打开供人工查看，
 * 传 {@code --smoke} 时短暂显示后自动退出。自动化截图由 {@link TeacherCourseUiSmokeTest} 负责，
 * 本类不注册进任何测试套件，否则一次无人值守运行会停在打开着的窗口上永不退出。
 */
public final class TeacherCourseUiPreview extends Application {
    private static final String VIEW_PATH =
            "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final double WIDTH = 860.0;
    private static final double HEIGHT = 580.0;

    static URL viewResource() {
        return TeacherCourseUiPreview.class.getResource(VIEW_PATH);
    }

    static boolean shouldAutoClose(List<String> arguments) {
        return arguments.contains("--smoke");
    }

    static void requireMockService(TeacherCourseService service) {
        if (!(service instanceof MockTeacherCourseService)) {
            throw new IllegalStateException(
                    "Teacher course UI preview requires MockTeacherCourseService");
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        TeacherCourseServices.install(new MockTeacherCourseService());
        requireMockService(TeacherCourseServices.current());

        URL resource = viewResource();
        if (resource == null) {
            throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
        }

        Parent root = FXMLLoader.load(resource);
        stage.setTitle("教务管理系统 - 教师预览");
        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.setResizable(false);
        stage.centerOnScreen();
        stage.show();

        if (shouldAutoClose(getParameters().getRaw())) {
            PauseTransition pause = new PauseTransition(Duration.millis(500));
            pause.setOnFinished(event -> Platform.exit());
            pause.play();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
