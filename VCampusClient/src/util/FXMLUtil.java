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
        return FXMLLoader.load(url);
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
