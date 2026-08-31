package util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/**
 * JavaFX 提示框工具类（线程安全）
 */
public final class AlertUtil {

    private AlertUtil() {
    }

    public static void showInfo(String title, String message) {
        runOnFxThread(() -> showAlert(Alert.AlertType.INFORMATION, title, message));
    }

    public static void showWarning(String title, String message) {
        runOnFxThread(() -> showAlert(Alert.AlertType.WARNING, title, message));
    }

    public static void showError(String title, String message) {
        runOnFxThread(() -> showAlert(Alert.AlertType.ERROR, title, message));
    }

    public static ButtonType showConfirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().orElse(ButtonType.CANCEL);
    }

    private static void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
