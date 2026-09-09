package ui;

import java.net.URL;
import java.util.List;

public final class CourseUiPreviewTest {
    public static void main(String[] args) {
        URL resource = CourseUiPreview.viewResource();
        require(resource != null, "course management FXML must be available");
        require(resource.toExternalForm().endsWith(
                "/resources/fxml/CourseManagementView.fxml"),
                "preview must load the course management shell");
        require(!CourseUiPreview.shouldAutoClose(List.of()),
                "normal preview must remain open");
        require(CourseUiPreview.shouldAutoClose(List.of("--smoke")),
                "smoke preview must close automatically");
        System.out.println("CourseUiPreviewTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
