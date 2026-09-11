package ui;

import java.io.IOException;
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
import service.CourseServices;
import service.MockCourseService;

public final class CourseUiSmokeTest {
    private static final String[] FILES = {
            "selection.png", "schedule.png", "grades.png", "training-plan.png"
    };
    private static final String[] NAV_IDS = {
            null, "#scheduleNavButton", "#gradeNavButton", "#planNavButton"
    };
    private static final Path OUTPUT =
            Path.of(".codex-tmp", "course-ui-snapshots");

    static void prepareOutputDirectory() throws IOException {
        Files.createDirectories(OUTPUT);
        for (String file : FILES) {
            Files.deleteIfExists(OUTPUT.resolve(file));
        }
    }

    static void requireImageWritten(boolean written, Path target) throws IOException {
        if (!written) {
            throw new IOException("No PNG writer available for " + target);
        }
    }

    public static void main(String[] args) throws Exception {
        CourseServices.install(new MockCourseService());
        prepareOutputDirectory();
        Application.launch(SnapshotApplication.class, args);
    }

    public static final class SnapshotApplication extends Application {
        private Parent root;
        private int pageIndex;

        @Override
        public void start(Stage stage) throws Exception {
            root = FXMLLoader.load(getClass().getResource(
                    "/resources/fxml/CourseManagementView.fxml"));
            stage.setScene(new Scene(root, 860, 580));
            stage.setResizable(false);
            stage.show();
            expandFirstCourse();
        }

        private void expandFirstCourse() {
            PauseTransition pause = new PauseTransition(Duration.millis(180));
            pause.setOnFinished(event -> {
                try {
                    requireNode("#termFilter", "term selector");
                    ButtonBase expandButton = (ButtonBase) requireNode(
                            ".course-expand-button", "course expand button");
                    expandButton.fire();
                    captureAfterPulse();
                } catch (Exception exception) {
                    exception.printStackTrace();
                    Platform.exit();
                    System.exit(1);
                }
            });
            pause.play();
        }

        private void captureAfterPulse() {
            PauseTransition pause = new PauseTransition(Duration.millis(180));
            pause.setOnFinished(event -> {
                try {
                    if (pageIndex == 0) {
                        requireNode(".course-offering-row",
                                "expanded teaching-class row");
                    }
                    Path target = OUTPUT.resolve(FILES[pageIndex]);
                    WritableImage image = root.snapshot(null, null);
                    boolean written = ImageIO.write(
                            SwingFXUtils.fromFXImage(image, null), "png",
                            target.toFile());
                    requireImageWritten(written, target);
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

        private javafx.scene.Node requireNode(String selector, String description) {
            javafx.scene.Node node = root.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("Missing " + description
                        + " for selector " + selector);
            }
            return node;
        }
    }
}
