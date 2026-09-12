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
import service.AdminCourseService;
import service.AdminCourseServices;
import service.MockAdminCourseService;

/**
 * 管理员课程页面离线预览：安装 {@link MockAdminCourseService}，仅加载管理员外壳。
 */
public final class AdminCourseUiPreview extends Application {
    private static final String VIEW_PATH =
            "/resources/fxml/AdminCourseManagementView.fxml";
    private static final double WIDTH = 860.0;
    private static final double HEIGHT = 580.0;

    static URL viewResource() {
        return AdminCourseUiPreview.class.getResource(VIEW_PATH);
    }

    static boolean shouldAutoClose(List<String> arguments) {
        return arguments.contains("--smoke");
    }

    static void requireMockService(AdminCourseService service) {
        if (!(service instanceof MockAdminCourseService)) {
            throw new IllegalStateException(
                    "Admin course UI preview requires MockAdminCourseService");
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        AdminCourseServices.install(new MockAdminCourseService());
        requireMockService(AdminCourseServices.current());

        URL resource = viewResource();
        if (resource == null) {
            throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
        }

        Parent root = FXMLLoader.load(resource);
        stage.setTitle("教务管理系统 - 管理员预览");
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
