package util.control;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextInputControl;

/**
 * Marks missing required inputs and clears the mark
 * as the user corrects them.
 */
public final class RequiredFieldValidation {

    /**
     * 与 required-validation.css 中的
     * .required-missing 对应
     */
    private static final String INVALID_STYLE = "required-missing";

    private RequiredFieldValidation() {
    }

    /**
     * 给控件安装一次必填项校验监听器。
     *
     * 用户开始修改内容后，自动清除红色错误样式。
     */
    public static void install(Control control) {

        if (control == null) {
            return;
        }

        // 防止一个控件重复安装监听器
        if (control.getProperties().putIfAbsent(
                "required-validation-installed",
                true
        ) != null) {
            return;
        }

        // 加载必填项校验样式
        String stylesheet =
                RequiredFieldValidation.class
                        .getResource(
                                "/resources/css/required-validation.css"
                        )
                        .toExternalForm();

        if (!control.getStylesheets().contains(stylesheet)) {
            control.getStylesheets().add(stylesheet);
        }

        // TextField / TextArea
        if (control instanceof TextInputControl text) {

            text.textProperty().addListener(
                    (observable, before, after) ->
                            clear(control)
            );

        }

        // DatePicker
        else if (control instanceof DatePicker date) {

            date.valueProperty().addListener(
                    (observable, before, after) ->
                            clear(control)
            );

            date.getEditor()
                    .textProperty()
                    .addListener(
                            (observable, before, after) ->
                                    clear(control)
                    );

        }

        // ComboBox
        else if (control instanceof ComboBox<?> combo) {

            combo.valueProperty().addListener(
                    (observable, before, after) ->
                            clear(control)
            );
        }
    }

    /**
     * 设置或清除必填项错误状态。
     *
     * @return missing，方便调用方直接累计判断
     */
    public static boolean mark(
            Control control,
            boolean missing) {

        if (control == null) {
            return missing;
        }

        install(control);

        if (missing) {

            if (!control.getStyleClass()
                    .contains(INVALID_STYLE)) {

                control.getStyleClass()
                        .add(INVALID_STYLE);
            }

        } else {

            clear(control);
        }

        return missing;
    }

    /**
     * 清除必填项错误样式。
     */
    private static void clear(Control control) {

        if (control == null) {
            return;
        }

        control.getStyleClass()
                .remove(INVALID_STYLE);
    }
}