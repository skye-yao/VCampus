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

        // 监听窗口关闭事件，释放网络资源
        primaryStage.setOnCloseRequest(event -> {
            System.out.println("VCampus 客户端正在退出...");
            cleanupPage();
            service.LeaseClient.shutdown();
            util.BackgroundTasks.shutdown();
            SocketClient.getInstance().shutdown();
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

            if (primaryStage.getScene() == null) {
                // 首次初始化：登录页使用紧凑的竖向小窗口 (440x620)
                double initWidth = isLogin ? 440 : 1024;
                double initHeight = isLogin ? 620 : 720;
                Scene scene = new Scene(root, initWidth, initHeight);
                primaryStage.setScene(scene);
            } else {
                primaryStage.getScene().setRoot(root);
            }

            if (isLogin) {
                // 登录页：固定为中间竖向卡片大小，禁止用户手动放大拉伸
                primaryStage.setResizable(false);
                primaryStage.setWidth(440);
                primaryStage.setHeight(620);
                primaryStage.centerOnScreen();
            } else {
                // 登录成功进入主界面或其他系统：允许自由放大/最大化
                primaryStage.setResizable(true);
                // 若此前是登录小窗口，自动展开至标准宽屏尺寸
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

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
