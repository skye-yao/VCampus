package ui;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.List;
import service.CourseService;
import service.CourseServices;
import service.MockCourseService;

public final class CourseUiPreviewTest {
    public static void main(String[] args) {
        CourseServices.install(new MockCourseService());
        URL resource = CourseUiPreview.viewResource();
        require(resource != null, "course management FXML must be available");
        require(resource.toExternalForm().endsWith(
                "/resources/fxml/CourseManagementView.fxml"),
                "preview must load the course management shell");
        require(!CourseUiPreview.shouldAutoClose(List.of()),
                "normal preview must remain open");
        require(CourseUiPreview.shouldAutoClose(List.of("--smoke")),
                "smoke preview must close automatically");
        testMockServiceGuard();
        System.out.println("CourseUiPreviewTest: PASS");
    }

    private static void testMockServiceGuard() {
        CourseUiPreview.requireMockService(CourseServices.current());

        CourseService nonMockService = (CourseService) Proxy.newProxyInstance(
                CourseService.class.getClassLoader(),
                new Class<?>[] {CourseService.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException();
                });
        try {
            CourseUiPreview.requireMockService(nonMockService);
            throw new AssertionError("preview must reject a non-mock course service");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("MockCourseService"),
                    "guard failure must identify the required service type");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
