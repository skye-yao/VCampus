//package com.vcampus.client.util;
//
//import java.io.IOException;
//
//import javafx.fxml.FXMLLoader;
//import javafx.scene.Parent;
//
///**
// * FXML 加载工具类
// *
// * <p>
// * 用于统一加载 JavaFX 的 FXML 界面文件。
// * 各个 Controller 或界面跳转代码不需要重复创建
// * FXMLLoader，可直接调用本工具类提供的方法。
// * </p>
// *
// * <p>
// * 主要用途：
// * </p>
// *
// * <ul>
// *     <li>加载 FXML 文件并获取界面根节点</li>
// *     <li>创建 FXMLLoader 并获取对应 Controller</li>
// *     <li>统一处理 FXML 文件的加载过程</li>
// * </ul>
// *
// * <p>
// * 使用示例：
// * </p>
// *
// * <pre>
// * Parent root = FXMLUtil.load("/fxml/login.fxml");
// * </pre>
// *
// * <p>
// * 如果需要获取 FXML 对应的 Controller，可以使用：
// * </p>
// *
// * <pre>
// * FXMLLoader loader = FXMLUtil.getLoader("/fxml/login.fxml");
// * Parent root = loader.load();
// * LoginController controller = loader.getController();
// * </pre>
// *
// * <p>
// * FXML 文件路径以 resources 目录为根目录，
// * 通常以 "/" 开头。
// * </p>
// *
// * @author VirtualCampus 架构组
// * @version 1.0
// */
//public final class FXMLUtil {
//
//    /**
//     * 工具类不允许实例化。
//     */
//    private FXMLUtil() {
//    }
//
//    /**
//     * 加载指定的 FXML 文件。
//     *
//     * @param fxmlPath FXML 文件路径，例如 "/fxml/login.fxml"
//     * @return FXML 文件对应的根节点
//     * @throws IOException FXML 文件加载失败时抛出
//     */
//    public static Parent load(String fxmlPath) throws IOException {
//
//        FXMLLoader loader = new FXMLLoader(
//                FXMLUtil.class.getResource(fxmlPath)
//        );
//
//        return loader.load();
//    }
//
//    /**
//     * 获取指定 FXML 文件对应的 FXMLLoader。
//     *
//     * <p>
//     * 当调用方需要获取 Controller 或向 Controller
//     * 传递数据时，应使用此方法。
//     * </p>
//     *
//     * @param fxmlPath FXML 文件路径，例如 "/fxml/login.fxml"
//     * @return 对应的 FXMLLoader
//     */
//    public static FXMLLoader getLoader(String fxmlPath) {
//
//        return new FXMLLoader(
//                FXMLUtil.class.getResource(fxmlPath)
//        );
//    }
//}