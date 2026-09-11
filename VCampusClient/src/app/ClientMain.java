package app;

import javafx.application.Application;
import javafx.application.Platform;
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

import java.io.InputStream;
import java.util.Optional;

/**
 * 虚拟校园系统客户端启动主入口
 */
public class ClientMain extends Application {

    private static Stage primaryStage;
    private static Runnable pageCleanup=()->{};
    public static void setPageCleanup(Runnable cleanup){pageCleanup=cleanup;}
    private static void cleanupPage(){Runnable old=pageCleanup;pageCleanup=()->{};old.run();}

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        primaryStage.setTitle("东南大学虚拟校园系统 - VCampus Client");

        // 加载窗体图标
        try {
            InputStream iconStream = getClass().getResourceAsStream("/resources/image/icon.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception e) {
            System.err.println("图标加载失败: " + e.getMessage());
        }

        // 监听窗口关闭事件，弹出模态退出确认对话框（冻结主窗口）
        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            showExitConfirmation();
        });

        // 初始加载登录界面
        switchScene("/resources/fxml/LoginView.fxml");
        //primaryStage.setResizable(false);
        primaryStage.show();

        // 异步预连接服务端
        SocketClient.getInstance().connectAsync();
    }

    /**
     * 场景切换核心方法
     *
     * @param fxmlPath FXML 页面相对路径
     */
    public static void switchScene(String fxmlPath) {
        try {
            Runnable previousCleanup=pageCleanup;
            pageCleanup=()->{};
            Parent root;
            try {root=FXMLUtil.load(fxmlPath);}
            catch(Exception error){cleanupPage();pageCleanup=previousCleanup;throw error;}
            previousCleanup.run();
            boolean isLogin = fxmlPath != null && fxmlPath.contains("LoginView");
            boolean isRegister = fxmlPath != null && fxmlPath.contains("RegisterView");
            boolean isForgot = fxmlPath != null && fxmlPath.contains("ForgotPasswordView");
            boolean isAuth = isLogin || isRegister || isForgot;

            if (primaryStage.getScene() == null) {
                // 首次初始化：登录/注册/找回密码页使用紧凑的竖向小窗口
                double initWidth = isAuth ? 396 : 1024;
                double initHeight = isRegister ? 720 : (isForgot ? 680 : (isLogin ? 620 : 720));
                Scene scene = new Scene(root, initWidth, initHeight);
                primaryStage.setScene(scene);
            } else {
                primaryStage.getScene().setRoot(root);
            }

            if (isAuth) {
                // 登录/注册/找回密码页：固定为中间竖向卡片大小，禁止手动放大拉伸
                primaryStage.setResizable(false);
                primaryStage.setWidth(396);
                primaryStage.setHeight(isRegister ? 720 : (isForgot ? 680 : 620));
                primaryStage.centerOnScreen();
            } else {
                // 登录成功进入主界面或其他系统：允许自由放大/最大化
                primaryStage.setResizable(true);
                // 若此前是小窗口，自动展开至标准宽屏尺寸
                if (primaryStage.getWidth() < 600) {
                    primaryStage.setWidth(1024);
                    primaryStage.setHeight(720);
                    primaryStage.centerOnScreen();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            AlertUtil.showError("界面加载失败", "无法加载界面: " + fxmlPath + "\n错误详情: " + e.getMessage());
        }
    }

    /**
     * 弹出退出系统确认对话框（模态，冻结主窗口）。
     * 点击“确定”执行安全登出+关闭窗口；点击“取消”则返回主窗口继续操作。
     */
    public static void showExitConfirmation() {
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
        if (result.isPresent() && result.get() == yesBtn) {
            System.out.println("用户确认退出，正在执行安全登出...");
            // 若当前处于登录状态，向服务端发起登出通知
            if (ClientSession.getInstance().isLoggedIn()) {
                try {
                    Message logoutMsg = new Message(MessageType.REQUEST, "user", "logout");
                    SocketClient.getInstance().sendSync(logoutMsg, 2);
                } catch (Exception ignored) {
                } finally {
                    ClientSession.getInstance().logout();
                }
            }

            // 清理页面租约、定时任务及 Socket 连接
            cleanupPage();
            service.LeaseClient.shutdown();
            util.BackgroundTasks.shutdown();
            SocketClient.getInstance().shutdown();

            if (primaryStage != null) {
                primaryStage.close();
            }
            Platform.exit();
            System.exit(0);
        }
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}