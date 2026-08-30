//package com.vcampus.client.util;
//
//import javafx.scene.control.Alert;
//import javafx.scene.control.ButtonType;
//
///**
// * JavaFX 提示框工具类
// *
// * <p>
// * 用于统一处理客户端中的各种提示框。
// * 各业务 Controller 不需要重复创建和配置 Alert，
// * 可直接调用本工具类提供的方法。
// * </p>
// *
// * <p>
// * 提示框类型及使用场景：
// * </p>
// *
// * <ul>
// *     <li>
// *         showInfo()：用于操作成功、普通信息提示，
// *         例如登录成功、预约成功等。
// *     </li>
// *     <li>
// *         showWarning()：用于警告信息，
// *         例如余额不足、操作条件不满足等。
// *     </li>
// *     <li>
// *         showError()：用于错误信息，
// *         例如登录失败、网络异常、服务器错误等。
// *     </li>
// *     <li>
// *         showConfirm()：用于需要用户确认的操作，
// *         例如删除、取消订单、退出登录等。
// *     </li>
// * </ul>
// *
// * <p>
// * 使用示例：
// * </p>
// *
// * <pre>
// * AlertUtil.showInfo("操作成功", "图书预约成功！");
// *
// * AlertUtil.showWarning("操作提示", "当前账户余额不足。");
// *
// * AlertUtil.showError("登录失败", "用户名或密码错误。");
// *
// * ButtonType result = AlertUtil.showConfirm(
// *         "确认操作",
// *         "确定要删除该商品吗？"
// * );
// *
// * if (result == ButtonType.OK) {
// *     // 执行删除操作
// * }
// * </pre>
// *
// * @author VirtualCampus 架构组
// * @version 1.0
// */
//public final class AlertUtil {
//
//    /**
//     * 工具类不允许实例化。
//     */
//    private AlertUtil() {
//    }
//
//    /**
//     * 显示信息提示框。
//     *
//     * <p>
//     * 用于显示普通信息或操作成功提示。
//     * </p>
//     *
//     * @param title 提示框标题
//     * @param message 提示内容
//     */
//    public static void showInfo(
//            String title,
//            String message) {
//
//        showAlert(
//                Alert.AlertType.INFORMATION,
//                title,
//                message
//        );
//    }
//
//    /**
//     * 显示警告提示框。
//     *
//     * <p>
//     * 用于提醒用户当前操作存在限制或需要注意的问题。
//     * </p>
//     *
//     * @param title 提示框标题
//     * @param message 警告内容
//     */
//    public static void showWarning(
//            String title,
//            String message) {
//
//        showAlert(
//                Alert.AlertType.WARNING,
//                title,
//                message
//        );
//    }
//
//    /**
//     * 显示错误提示框。
//     *
//     * <p>
//     * 用于显示操作失败、网络错误或服务器错误等信息。
//     * </p>
//     *
//     * @param title 提示框标题
//     * @param message 错误内容
//     */
//    public static void showError(
//            String title,
//            String message) {
//
//        showAlert(
//                Alert.AlertType.ERROR,
//                title,
//                message
//        );
//    }
//
//    /**
//     * 显示确认提示框。
//     *
//     * <p>
//     * 用于需要用户确认后才能继续执行的操作。
//     * </p>
//     *
//     * @param title 提示框标题
//     * @param message 确认内容
//     * @return 用户选择的按钮类型
//     */
//    public static ButtonType showConfirm(
//            String title,
//            String message) {
//
//        Alert alert = new Alert(
//                Alert.AlertType.CONFIRMATION
//        );
//
//        alert.setTitle(title);
//        alert.setHeaderText(null);
//        alert.setContentText(message);
//
//        return alert.showAndWait()
//                .orElse(ButtonType.CANCEL);
//    }
//
//    /**
//     * 创建并显示普通提示框。
//     *
//     * @param type 提示框类型
//     * @param title 提示框标题
//     * @param message 提示内容
//     */
//    private static void showAlert(
//            Alert.AlertType type,
//            String title,
//            String message) {
//
//        Alert alert = new Alert(type);
//
//        alert.setTitle(title);
//        alert.setHeaderText(null);
//        alert.setContentText(message);
//
//        alert.showAndWait();
//    }
//}