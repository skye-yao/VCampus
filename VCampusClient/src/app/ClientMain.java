package app;

import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import network.SocketClient;
import util.AlertUtil;
import util.FXMLUtil;

import java.io.InputStream;

/**
 * 虚拟校园系统客户端启动主入口
 */
public class ClientMain extends Application {

    private static Stage primaryStage;

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

        // 监听窗口关闭事件，释放网络资源
        primaryStage.setOnCloseRequest(event -> {
            System.out.println("VCampus 客户端正在退出...");
            SocketClient.getInstance().disconnect();
        });

        // 初始加载登录界面
        switchScene("/resources/fxml/LoginView.fxml");
        primaryStage.setResizable(false);
        primaryStage.show();

        // 异步预连接服务端
        new Thread(() -> {
            try {
                SocketClient.getInstance().connect();
            } catch (Exception e) {
                System.out.println("提示: 服务端暂未启动，将在发起请求时重试连接。");
            }
        }).start();
    }

    /**
     * 场景切换核心方法
     *
     * @param fxmlPath FXML 页面相对路径
     */
    public static void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLUtil.load(fxmlPath);
            Scene scene = new Scene(root, 860, 580);
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
            AlertUtil.showError("界面加载失败", "无法加载界面: " + fxmlPath + "\n错误详情: " + e.getMessage());
        }
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
