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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;
import service.CourseServices;
import service.MockCourseService;

/**
 * Task 9 Step 4 state coverage: captures the selection page "全部" view with a two-class course
 * expanded, then FULL, WAITLISTED, WAITLIST_OFFERED and ENROLLED states in their relevant tabs.
 *
 * <p>Runs entirely against {@link MockCourseService}; no server connection is opened.
 */
public final class CourseUiStateCapture {

    private static final Path OUTPUT =
            Path.of(".codex-tmp", "task9-verify-20260911", "step4-ui");

    private CourseUiStateCapture() {
    }

    public static void main(String[] args) throws Exception {
        CourseServices.install(new MockCourseService());
        Files.createDirectories(OUTPUT);
        Application.launch(CaptureApplication.class, args);
    }

    public static final class CaptureApplication extends Application {
        private static final String[][] STEPS = {
                // name, tab id (null = default 全部), expanded course row index (-1 = none)
                {"selection-all-two-classes", "", "0"},
                {"selection-full", "", "1"},
                {"selection-waitlisted-and-offered", "", "2"},
                {"selection-tab-plan", "#planTabButton", "-1"},
                {"selection-tab-waitlist", "#waitlistTabButton", "-1"},
                {"selection-tab-enrolled", "#enrolledTabButton", "-1"},
        };

        private Stage stage;
        private int stepIndex;

        @Override
        public void start(Stage primaryStage) {
            this.stage = primaryStage;
            try {
                Files.createDirectories(OUTPUT);
                runStep();
            } catch (Exception exception) {
                exception.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        }

        private void runStep() throws Exception {
            if (stepIndex >= STEPS.length) {
                Platform.exit();
                return;
            }
            String[] step = STEPS[stepIndex];
            Parent root = FXMLLoader.load(getClass().getResource(
                    "/resources/fxml/CourseManagementView.fxml"));
            stage.setScene(new Scene(root, 860, 580));
            stage.setResizable(false);
            stage.show();

            PauseTransition settle = new PauseTransition(Duration.millis(250));
            settle.setOnFinished(ignored -> {
                try {
                    String tabId = step[1];
                    if (!tabId.isEmpty()) {
                        ((ToggleButton) requireNode(root, tabId, "tab " + tabId)).fire();
                    }
                    int expandIndex = Integer.parseInt(step[2]);
                    if (expandIndex >= 0) {
                        expandCourse(root, expandIndex);
                    }
                    PauseTransition after = new PauseTransition(Duration.millis(220));
                    after.setOnFinished(done -> {
                        try {
                            capture(root, step[0]);
                            stepIndex++;
                            runStep();
                        } catch (Exception exception) {
                            exception.printStackTrace();
                            Platform.exit();
                            System.exit(1);
                        }
                    });
                    after.play();
                } catch (Exception exception) {
                    exception.printStackTrace();
                    Platform.exit();
                    System.exit(1);
                }
            });
            settle.play();
        }

        private void expandCourse(Parent root, int index) {
            Node listNode = requireNode(root, "#courseList", "course list");
            if (!(listNode instanceof Pane list)) {
                throw new IllegalStateException("#courseList is not a pane");
            }
            if (list.getChildren().size() <= index) {
                throw new IllegalStateException("course list has only "
                        + list.getChildren().size() + " rows");
            }
            Node row = list.getChildren().get(index);
            Node button = row.lookup(".course-expand-button");
            if (!(button instanceof ButtonBase expandButton)) {
                throw new IllegalStateException("row " + index + " has no expand button");
            }
            expandButton.fire();
        }

        private void capture(Parent root, String name) throws IOException {
            Path target = OUTPUT.resolve(name + ".png");
            WritableImage image = root.snapshot(null, null);
            boolean written = ImageIO.write(
                    SwingFXUtils.fromFXImage(image, null), "png", target.toFile());
            if (!written) {
                throw new IOException("no PNG writer for " + target);
            }
            System.out.println("[UI] wrote " + target + " ("
                    + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
        }

        private Node requireNode(Parent root, String selector, String description) {
            Node node = root.lookup(selector);
            if (node == null) {
                throw new IllegalStateException("missing " + description + " for " + selector);
            }
            return node;
        }
    }
}
