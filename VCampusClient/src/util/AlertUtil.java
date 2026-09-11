package util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import model.course.WaitlistDecision;

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

    public static WaitlistDecision showWaitlistDecision(String title, String message) {
        ButtonType accept = new ButtonType("接受", ButtonBar.ButtonData.OK_DONE);
        ButtonType abandon = new ButtonType("放弃", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION, message, accept, abandon, cancel);
        alert.setTitle(title);
        alert.setHeaderText(null);
        ButtonType selected = alert.showAndWait().orElse(null);
        if (selected == accept) return WaitlistDecision.ACCEPT;
        if (selected == abandon) return WaitlistDecision.ABANDON;
        return null;
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
