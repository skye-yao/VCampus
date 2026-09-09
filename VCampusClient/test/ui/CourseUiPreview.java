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

public final class CourseUiPreview extends Application {
    private static final String VIEW_PATH =
            "/resources/fxml/CourseManagementView.fxml";
    private static final double WIDTH = 860.0;
    private static final double HEIGHT = 580.0;

    static URL viewResource() {
        return CourseUiPreview.class.getResource(VIEW_PATH);
    }

    static boolean shouldAutoClose(List<String> arguments) {
        return arguments.contains("--smoke");
    }

    @Override
    public void start(Stage stage) throws Exception {
        URL resource = viewResource();
        if (resource == null) {
            throw new IllegalStateException("Missing FXML resource: " + VIEW_PATH);
        }

        Parent root = FXMLLoader.load(resource);
        stage.setTitle("选课管理系统 - 客户端预览");
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
