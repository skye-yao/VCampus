package app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;
import network.SocketClient;
import protocol.Message;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;
import util.FXMLUtil;
import util.PageLeaveGuard;

import java.io.InputStream;
import java.util.Optional;

/** 虚拟校园系统客户端启动主入口。 */
public class ClientMain extends Application {

    private static Stage primaryStage;
    private static Runnable pageCleanup = () -> { };

    public static void setPageCleanup(Runnable cleanup) {
        pageCleanup = cleanup == null ? () -> { } : cleanup;
    }

    public static void cleanupPage() {
        Runnable old = pageCleanup;
        pageCleanup = () -> { };
        old.run();
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        primaryStage.setTitle("东南大学虚拟校园系统 - VCampus Client");

        try {
            InputStream iconStream = getClass().getResourceAsStream("/resources/image/icon.png");
            if (iconStream != null) primaryStage.getIcons().add(new Image(iconStream));
        } catch (Exception failure) {
            System.err.println("图标加载失败: " + failure.getMessage());
        }

        primaryStage.setOnCloseRequest(ClientMain::requestWindowClose);
        switchScene("/resources/fxml/LoginView.fxml");
        primaryStage.show();
        SocketClient.getInstance().connectAsync();
    }

    /** SPA 内部路由交给 MainController；整场景替换也遵守当前页面离开守卫。 */
    public static void switchScene(String fxmlPath) {
        try {
            boolean isLogin = fxmlPath != null && fxmlPath.contains("LoginView");
            boolean isRegister = fxmlPath != null && fxmlPath.contains("RegisterView");
            boolean isForgot = fxmlPath != null && fxmlPath.contains("ForgotPasswordView");
            boolean isAuth = isLogin || isRegister || isForgot;

            controller.MainController main = controller.MainController.getInstance();
            if (!isAuth && main != null && main.isAttachedToScene()) {
                if (fxmlPath != null && fxmlPath.endsWith("MainView.fxml")) main.showHome();
                else main.loadCenterView(fxmlPath);
                return;
            }

            PageLeaveGuard previousGuard = PageLeaveGuard.active();
            if (previousGuard != null && !previousGuard.requestLeave()) return;

            Runnable previousCleanup = pageCleanup;
            pageCleanup = () -> { };
            Parent root;
            try {
                root = FXMLUtil.load(fxmlPath);
            } catch (Exception failure) {
                pageCleanup = previousCleanup;
                throw failure;
            }

            if (previousGuard != null) previousGuard.onClosed();
            PageLeaveGuard.clear(previousGuard);
            previousCleanup.run();
            PageLeaveGuard.registerFrom(FXMLUtil.loadedController());

            if (primaryStage.getScene() == null) {
                double initWidth = isAuth ? 396 : 1100;
                double initHeight = isRegister ? 720 : (isForgot ? 680 : (isLogin ? 620 : 740));
                primaryStage.setScene(new Scene(root, initWidth, initHeight));
            } else {
                primaryStage.getScene().setRoot(root);
            }

            if (isAuth) {
                primaryStage.setResizable(false);
                primaryStage.setWidth(396);
                primaryStage.setHeight(isRegister ? 720 : (isForgot ? 680 : 620));
                primaryStage.centerOnScreen();
            } else {
                primaryStage.setResizable(true);
                if (primaryStage.getWidth() < 800) {
                    primaryStage.setWidth(1100);
                    primaryStage.setHeight(740);
                    primaryStage.centerOnScreen();
                }
            }
        } catch (Exception failure) {
            failure.printStackTrace();
            AlertUtil.showError("界面加载失败",
                    "无法加载界面: " + fxmlPath + "\n错误详情: " + failure.getMessage());
        }
    }

    /** 窗口关闭先经过页面守卫，再显示 main 原有的退出确认。 */
    public static void requestWindowClose(Event closeEvent) {
        closeEvent.consume();
        PageLeaveGuard guard = PageLeaveGuard.active();
        if (guard != null && !guard.requestLeave()) return;
        showExitConfirmationAfterLeaveApproved(guard);
    }

    public static void showExitConfirmation() {
        PageLeaveGuard guard = PageLeaveGuard.active();
        if (guard != null && !guard.requestLeave()) return;
        showExitConfirmationAfterLeaveApproved(guard);
    }

    private static void showExitConfirmationAfterLeaveApproved(PageLeaveGuard guard) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("退出确认");
        alert.setHeaderText(null);
        alert.setContentText("确定退出吗？");
        alert.getDialogPane().setMinWidth(360);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
            alert.initModality(Modality.APPLICATION_MODAL);
        }

        ButtonType yesBtn = new ButtonType("确定", ButtonBar.ButtonData.YES);
        ButtonType noBtn = new ButtonType("取消", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(yesBtn, noBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != yesBtn) return;

        System.out.println("用户确认退出，正在执行安全登出...");
        if (guard != null) guard.onClosed();
        PageLeaveGuard.clear(guard);

        if (ClientSession.getInstance().isLoggedIn()) {
            try {
                Message logoutMsg = new Message(MessageType.REQUEST, "user", "logout");
                SocketClient.getInstance().sendSync(logoutMsg, 2);
            } catch (Exception ignored) {
                // 退出流程继续释放本地资源。
            } finally {
                ClientSession.getInstance().logout();
            }
        }

        cleanupPage();
        service.LeaseClient.shutdown();
        util.BackgroundTasks.shutdown();
        SocketClient.getInstance().shutdown();

        if (primaryStage != null) {
            primaryStage.setOnCloseRequest(null);
            primaryStage.close();
        }
        Platform.exit();
        System.exit(0);
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
