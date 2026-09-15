package app;

import javafx.application.Application;
import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import network.SocketClient;
import util.AlertUtil;
import util.FXMLUtil;
import util.PageLeaveGuard;

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

        // 监听窗口关闭事件，释放网络资源；有页面注册离开守卫时先问它（设计 §5.4）。
        primaryStage.setOnCloseRequest(ClientMain::requestWindowClose);

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
     * <p>切换前先注销上一场景可能残留的离开守卫（新页面在 {@code initialize} 里注册自己的守卫，
     * 因此不会被这一步清掉），加载完成后用 {@link FXMLUtil#loadedController()} 接上页面自带的
     * 生命周期钩子：实现了 {@link PageLeaveGuard} 的页面成为当前守卫，其余页面不改变注册表，
     * 与引入守卫之前的行为完全一致。
     *
     * @param fxmlPath FXML 页面相对路径
     */
    public static void switchScene(String fxmlPath) {
        try {
            PageLeaveGuard.clear();
            Parent root = FXMLUtil.load(fxmlPath);
            PageLeaveGuard.registerFrom(FXMLUtil.loadedController());
            Scene scene = new Scene(root, 860, 580);
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
            AlertUtil.showError("界面加载失败", "无法加载界面: " + fxmlPath + "\n错误详情: " + e.getMessage());
        }
    }

    /**
     * 关闭窗体的统一入口（{@code setOnCloseRequest} 与测试都走它）。
     *
     * <p>当前页面注册了守卫且它拒绝离开时，关闭事件被 {@code consume} 掉并保持窗口打开——页面
     * 自己负责把拒绝的原因显示给用户（例如“有未保存的成绩”）。只有允许离开之后才调用守卫的
     * {@code onClosed()} 取消页面在途请求，然后沿用原有的断开连接逻辑。
     *
     * <p>没有页面注册守卫时（登录页、只读页）本方法退化成原来的行为：直接断开连接。
     *
     * @param closeEvent 窗体的关闭请求事件；被拒绝时它会被消费掉
     */
    public static void requestWindowClose(Event closeEvent) {
        PageLeaveGuard guard = PageLeaveGuard.active();
        if (guard != null && !guard.requestLeave()) {
            closeEvent.consume();
            return;
        }
        if (guard != null) {
            guard.onClosed();
        }
        System.out.println("VCampus 客户端正在退出...");
        SocketClient.getInstance().disconnect();
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
