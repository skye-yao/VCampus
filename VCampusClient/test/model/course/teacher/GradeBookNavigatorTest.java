package model.course.teacher;

import java.util.List;
import java.util.Optional;

import model.course.teacher.GradeBookNavigator.Move;
import model.course.teacher.GradeBookNavigator.Position;

/**
 * 成绩录入表的键盘导航规则（设计：像 Excel 一样录入）的无工具包测试。
 *
 * <p>钉住三件事：上下移动停在同一列、横向移动跳过禁用列、任何方向上无路可走时留在原地
 * （不换行回绕、不打开一个接收不了输入的编辑器）。{@code ←}/{@code →} 在编辑中的分工
 * （光标还有地方走就走在文本里）单独用 {@code shouldLeaveCell} 断言。
 */
public final class GradeBookNavigatorTest {
    private static final List<Boolean> ALL_EDITABLE = List.of(true, true, true, true);

    private GradeBookNavigatorTest() {
    }

    public static void main(String[] args) {
        verticalMovesStayInTheSameColumnAndClamp();
        horizontalMovesSkipDisabledColumnsAndClamp();
        readOnlyOrEmptyGridNeverMoves();
        arrowKeysInsideTheEditorDecideCaretVersusNextCell();
        System.out.println("GradeBookNavigatorTest: PASS");
    }

    /** 上下移动停在同一列；第一行再往上、最后一行再往下都留在原地（夹在网格里）。 */
    private static void verticalMovesStayInTheSameColumnAndClamp() {
        require(position(2, 1), GradeBookNavigator.resolve(new Position(1, 1), Move.DOWN, 24,
                ALL_EDITABLE), "↓ 必须下移一行、同列");
        require(position(0, 3), GradeBookNavigator.resolve(new Position(1, 3), Move.UP, 24,
                ALL_EDITABLE), "↑ 必须上移一行、同列");
        require(GradeBookNavigator.resolve(new Position(0, 0), Move.UP, 24, ALL_EDITABLE).isEmpty(),
                "第一行再往上必须留在原地");
        require(GradeBookNavigator.resolve(new Position(23, 0), Move.DOWN, 24, ALL_EDITABLE)
                        .isEmpty(),
                "最后一行再往下必须留在原地");
    }

    /** 横向一格一格走，遇到禁用的成绩列跳过它；走到两端就停下，不回绕到下一行。 */
    private static void horizontalMovesSkipDisabledColumnsAndClamp() {
        require(position(1, 2), GradeBookNavigator.resolve(new Position(1, 1), Move.NEXT, 24,
                ALL_EDITABLE), "Tab 必须右移一格");
        require(position(1, 1), GradeBookNavigator.resolve(new Position(1, 2), Move.PREVIOUS, 24,
                ALL_EDITABLE), "Shift+Tab 必须左移一格");
        require(position(1, 0), GradeBookNavigator.resolve(new Position(1, 1), Move.LEFT, 24,
                ALL_EDITABLE), "← 必须移到左邻格");
        require(position(1, 2), GradeBookNavigator.resolve(new Position(1, 1), Move.RIGHT, 24,
                ALL_EDITABLE), "→ 必须移到右邻格");

        List<Boolean> experimentOff = List.of(true, true, false, true);
        require(position(1, 3), GradeBookNavigator.resolve(new Position(1, 1), Move.RIGHT, 24,
                experimentOff), "向右必须跳过被禁用的实验列");
        require(position(1, 1), GradeBookNavigator.resolve(new Position(1, 3), Move.LEFT, 24,
                experimentOff), "向左必须跳过被禁用的实验列");
        require(position(0, 3), GradeBookNavigator.resolve(new Position(0, 1), Move.NEXT, 24,
                experimentOff), "Tab 同样要跳过禁用列（它是“右边下一个可填的格子”）");

        require(GradeBookNavigator.resolve(new Position(1, 3), Move.RIGHT, 24, ALL_EDITABLE)
                        .isEmpty(), "最右边的成绩列再往右必须留在原地，不能绕到下一行");
        require(GradeBookNavigator.resolve(new Position(1, 0), Move.LEFT, 24, ALL_EDITABLE)
                        .isEmpty(), "最左边的成绩列再往左必须留在原地");

        List<Boolean> onlyDailyAndFinal = List.of(true, false, false, true);
        require(position(0, 3), GradeBookNavigator.resolve(new Position(0, 0), Move.NEXT, 24,
                onlyDailyAndFinal), "中间两列都禁用时必须一路跳到最后一个启用列");
        require(GradeBookNavigator.resolve(new Position(0, 3), Move.NEXT, 24, onlyDailyAndFinal)
                        .isEmpty(), "这个方向上没有更多启用列时留在原地");
    }

    /** 只读页面（canEdit=false 传进来全为 false）与空名单：任何键都不产生移动。 */
    private static void readOnlyOrEmptyGridNeverMoves() {
        List<Boolean> noneEditable = List.of(false, false, false, false);
        for (Move move : Move.values()) {
            require(GradeBookNavigator.resolve(new Position(1, 1), move, 24, noneEditable).isEmpty(),
                    "一格都不能编辑时 " + move + " 不得打开任何编辑器");
            require(GradeBookNavigator.resolve(new Position(1, 1), move, 0, ALL_EDITABLE).isEmpty(),
                    "空名单时 " + move + " 不得产生落点");
        }
        require(GradeBookNavigator.resolve(new Position(-1, 0), Move.DOWN, 24, ALL_EDITABLE)
                        .isEmpty(), "起点本身越界时不得产生落点");
        require(GradeBookNavigator.resolve(null, Move.DOWN, 24, ALL_EDITABLE).isEmpty(),
                "没有起点时不得产生落点");
    }

    /** ←/→ 的取舍：有选中或光标已经到头才离开这一格，否则把按键留给文本光标。 */
    private static void arrowKeysInsideTheEditorDecideCaretVersusNextCell() {
        require(GradeBookNavigator.shouldLeaveCell(2, 1, 2, true),
                "有选中内容时 → 必须离开当前格");
        require(GradeBookNavigator.shouldLeaveCell(2, 1, 2, false),
                "有选中内容时 ← 必须离开当前格");
        require(GradeBookNavigator.shouldLeaveCell(2, 0, 2, true),
                "光标在末尾时 → 必须离开当前格");
        require(!GradeBookNavigator.shouldLeaveCell(1, 0, 2, true),
                "光标在文本中间时 → 必须留在这一格里挪光标");
        require(GradeBookNavigator.shouldLeaveCell(0, 0, 2, false),
                "光标在开头时 ← 必须离开当前格");
        require(!GradeBookNavigator.shouldLeaveCell(1, 0, 2, false),
                "光标在文本中间时 ← 必须留在这一格里挪光标");
        require(GradeBookNavigator.shouldLeaveCell(0, 0, 0, true)
                        && GradeBookNavigator.shouldLeaveCell(0, 0, 0, false),
                "空格子里两个方向都直接离开");
    }

    // ------------------------------------------------------------------ 辅助

    private static Optional<Position> position(int row, int column) {
        return Optional.of(new Position(row, column));
    }

    private static void require(Optional<Position> expected, Optional<Position> actual,
            String message) {
        require(expected.equals(actual), message + "，实际 " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
