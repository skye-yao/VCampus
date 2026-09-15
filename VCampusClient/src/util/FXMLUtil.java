package util;

import app.ClientMain;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * FXML 加载与路由切换工具类
 */
public final class FXMLUtil {

    /**
     * 最后一次成功 {@link #load(String)} 用过的 loader。页面控制器要在加载之后接上生命周期钩子
     * （例如 {@code PageLeaveGuard}），只返回根节点会让调用方拿不到 controller；这里保留 loader
     * 而不是 controller 本身，是因为 controller 要等 {@code load()} 返回后才有值。
     */
    private static FXMLLoader lastLoader;

    private FXMLUtil() {
    }

    /**
     * 加载指定路径的 FXML 根节点，自动适配路径前缀
     */
    public static Parent load(String fxmlPath) throws IOException {
        URL url = resolveFxmlUrl(fxmlPath);
        if (url == null) {
            throw new IOException("找不到 FXML 资源文件: " + fxmlPath);
        }
        FXMLLoader loader = new FXMLLoader(url);
        Parent root = loader.load();
        lastLoader = loader;
        return root;
    }

    /**
     * 最后一次 {@link #load(String)} 加载到的 Controller；从未加载过、加载失败或该 FXML 没有
     * {@code fx:controller} 时返回 null。调用方按“拿不到 controller 就不做任何事”的方式使用它，
     * 因此没有 controller 的页面与引入本方法之前完全一致。
     */
    public static Object loadedController() {
        FXMLLoader loader = lastLoader;
        return loader == null ? null : loader.getController();
    }

    /**
     * 获取指定 FXML 的 FXMLLoader
     */
    public static FXMLLoader getLoader(String fxmlPath) {
        URL url = resolveFxmlUrl(fxmlPath);
        if (url == null) {
            throw new RuntimeException("找不到 FXML 资源文件: " + fxmlPath);
        }
        return new FXMLLoader(url);
    }

    /**
     * 智能解析 FXML 资源 URL
     */
    public static URL resolveFxmlUrl(String fxmlPath) {
        if (fxmlPath == null) return null;
        
        // 尝试直接获取
        URL url = FXMLUtil.class.getResource(fxmlPath);
        if (url != null) return url;

        // 尝试加上 /resources 前缀
        if (!fxmlPath.startsWith("/resources")) {
            url = FXMLUtil.class.getResource("/resources" + (fxmlPath.startsWith("/") ? "" : "/") + fxmlPath);
            if (url != null) return url;
        }

        // 尝试加上 /resources/fxml/ 前缀
        String fileName = fxmlPath.substring(fxmlPath.lastIndexOf('/') + 1);
        url = FXMLUtil.class.getResource("/resources/fxml/" + fileName);
        if (url != null) return url;

        return FXMLUtil.class.getResource("/" + fileName);
    }
}
