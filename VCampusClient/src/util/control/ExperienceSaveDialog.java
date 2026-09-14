package util.control;

import javafx.event.ActionEvent;
import javafx.scene.control.*;
import protocol.Message;
import protocol.MessageCode;
import util.Fx;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps an experience editor open until its asynchronous write succeeds. */
public final class ExperienceSaveDialog {
    private ExperienceSaveDialog() {}

    public static <T> void install(Dialog<?> dialog, Supplier<T> read,
            java.util.function.BiConsumer<T, Consumer<Message>> save, Runnable saved) {
        Button confirm = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        boolean[] busy = {false};
        dialog.setOnCloseRequest(event -> { if (busy[0]) event.consume(); });
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            if (event.isConsumed()) return;
            event.consume();
            if (busy[0]) return;
            try {
                T value = read.get();
                busy[0] = true;
                confirm.setDisable(true);
                dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                dialog.getDialogPane().getContent().setDisable(true);
                dialog.setHeaderText("正在保存，请稍候…");
                save.accept(value, response -> Fx.run(() -> {
                    if (!busy[0]) return;
                    busy[0] = false;
                    confirm.setDisable(false);
                    dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                    dialog.getDialogPane().getContent().setDisable(false);
                    if (response != null && response.getCode() == MessageCode.SUCCESS
                            && Boolean.TRUE.equals(response.getData().get("updated"))) {
                        dialog.close();
                        saved.run();
                    } else {
                        String reason = response == null ? null : response.getMessage();
                        dialog.setHeaderText(reason == null || reason.isBlank()
                                ? "保存失败，请重试" : "保存未完成：" + reason);
                    }
                }));
            } catch (Exception error) {
                busy[0] = false;
                confirm.setDisable(false);
                dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                dialog.getDialogPane().getContent().setDisable(false);
                dialog.setHeaderText(error.getMessage() == null ? "保存失败，请检查输入后重试" : error.getMessage());
            }
        });
    }

    /** Parse editor text explicitly; an invalid date must not reuse the old value. */
    public static void commitDate(DatePicker picker, String label) {
        String text = picker.getEditor().getText();
        try {
            picker.setValue(text == null || text.isBlank() ? null
                    : picker.getConverter().fromString(text.trim()));
        } catch (RuntimeException error) {
            RequiredFieldValidation.mark(picker, true);
            throw new IllegalArgumentException(label + "格式错误，请输入有效日期");
        }
    }
}
