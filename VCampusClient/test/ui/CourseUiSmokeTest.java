package ui;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class CourseUiSmokeTest extends Application {
    private static final String[] FILES = {
            "selection.png", "schedule.png", "grades.png", "training-plan.png"
    };
    private static final String[] NAV_IDS = {
            null, "#scheduleNavButton", "#gradeNavButton", "#planNavButton"
    };
    private Parent root;
    private int pageIndex;

    @Override
    public void start(Stage stage) throws Exception {
        root = FXMLLoader.load(getClass().getResource(
                "/resources/fxml/CourseManagementView.fxml"));
        stage.setScene(new Scene(root, 860, 580));
        stage.setResizable(false);
        stage.show();
        captureAfterPulse();
    }

    private void captureAfterPulse() {
        PauseTransition pause = new PauseTransition(Duration.millis(180));
        pause.setOnFinished(event -> {
            try {
                Path output = Path.of(".codex-tmp", "course-ui-snapshots");
                Files.createDirectories(output);
                WritableImage image = root.snapshot(null, null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        output.resolve(FILES[pageIndex]).toFile());
                pageIndex++;
                if (pageIndex == FILES.length) {
                    Platform.exit();
                    return;
                }
                ((ButtonBase) root.lookup(NAV_IDS[pageIndex])).fire();
                captureAfterPulse();
            } catch (Exception exception) {
                exception.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
        pause.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
