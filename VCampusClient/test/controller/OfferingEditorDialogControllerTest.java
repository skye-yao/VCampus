package controller;

import java.util.List;

public final class OfferingEditorDialogControllerTest {

    private OfferingEditorDialogControllerTest() {
    }

    public static void main(String[] args) {
        List<String> options = OfferingEditorDialogController.semesterOptions();
        require(options.size() == 3,
                "the term picker must offer exactly the three terms the server accepts, saw "
                        + options);
        require(options.equals(List.of("暑期学校", "秋学期", "春学期")),
                "term options must use the shared labels in semester order, saw " + options);

        for (int semester = 1; semester <= 3; semester++) {
            require(OfferingEditorDialogController.semesterCode(
                            OfferingEditorDialogController.semesterLabel(semester)) == semester,
                    "label and code must round-trip for semester " + semester);
        }
        require(OfferingEditorDialogController.semesterCode(null) == 0,
                "an unselected term must not be read as semester 1");
        require(OfferingEditorDialogController.semesterCode("99") == 0,
                "an out-of-range term must be rejected rather than accepted");
        require(OfferingEditorDialogController.semesterCode("第4学期") == 0,
                "TermLabels' fallback wording must not be a selectable term");

        System.out.println("Offering editor dialog test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
