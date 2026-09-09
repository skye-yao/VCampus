package util.control;

import javafx.css.PseudoClass;
import javafx.scene.control.*;

/** Marks missing required inputs and clears the mark as the user corrects them. */
public final class RequiredFieldValidation {
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("required-missing");
    private RequiredFieldValidation() {}

    public static void install(Control control) {
        if (control.getProperties().putIfAbsent("required-validation-installed", true) != null) return;
        control.getStylesheets().add(RequiredFieldValidation.class.getResource(
            "/resources/css/required-validation.css").toExternalForm());
        if (control instanceof TextInputControl text)
            text.textProperty().addListener((o, before, after) -> clear(control));
        else if (control instanceof DatePicker date) {
            date.valueProperty().addListener((o, before, after) -> clear(control));
            date.getEditor().textProperty().addListener((o, before, after) -> clear(control));
        } else if (control instanceof ComboBox<?> combo)
            combo.valueProperty().addListener((o, before, after) -> clear(control));
    }

    public static boolean mark(Control control, boolean missing) {
        install(control);
        control.pseudoClassStateChanged(INVALID, missing);
        return missing;
    }

    private static void clear(Control control) { control.pseudoClassStateChanged(INVALID, false); }
}
