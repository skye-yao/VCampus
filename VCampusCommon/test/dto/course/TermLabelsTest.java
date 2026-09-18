package dto.course;

public final class TermLabelsTest {

    private TermLabelsTest() {
    }

    public static void main(String[] args) {
        require("2026-2027 秋学期".equals(TermLabels.displayName(2026, 2)),
                "semester 2 must read as 秋学期 on the academic-year range");
        require("2026-2027 暑期学校".equals(TermLabels.displayName(2026, 1)),
                "season 1 is the summer school");
        require("2026-2027 春学期".equals(TermLabels.displayName(2026, 3)),
                "season 3 is the spring term");
        require("2026-2027 第4学期".equals(TermLabels.displayName(2026, 4)),
                "an out-of-range semester must degrade to 第N学期, not throw");
        require("秋学期".equals(TermLabels.label(2)),
                "the bare season label must be available without the year range");
        require("第9学期".equals(TermLabels.label(9)),
                "an out-of-range semester must degrade on label itself, not only via displayName");
        System.out.println("Term labels test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
