package util.control;

import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import util.InformationDateRules;

import java.time.LocalDate;

/** Common UI behavior for dates that describe facts which have already happened. */
public final class InformationDatePicker {
    private InformationDatePicker() {}

    public static void install(DatePicker picker) {
        picker.setDayCellFactory(view -> new DateCell() {
            @Override public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || InformationDateRules.isFuture(date));
            }
        });
    }

    /** Commits manually typed text before checking it. */
    public static LocalDate value(DatePicker picker, String label) {
        String text = picker.getEditor().getText();
        LocalDate value = picker.getValue();
        if (text != null && !text.isBlank()) {
            try {
                value = picker.getConverter().fromString(text.trim());
                picker.setValue(value);
            } catch (RuntimeException exception) {
                picker.requestFocus();
                throw new IllegalArgumentException(label + "格式无效");
            }
        }
        InformationDateRules.requireNotFuture(value, label);
        return value;
    }
}
