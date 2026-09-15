package model.course.teacher;

import java.util.List;
import java.util.Optional;

/**
 * 成绩录入表的键盘导航规则（纯计算，与 JavaFX 无关，因此可以被无工具包的单元测试直接钉住）。
 *
 * <p>表格的可编辑区域是四个成绩列（{@link dto.course.teacher.GradeComponentCodeDTO} 的声明顺序），
 * 学号/姓名/总评/绩点/行错误五列不在导航范围内——它们本来就不接受输入。调用方把“当前在哪一格”
 * （行号 + 成绩列序号）与“按了哪个键”（{@link Move}）交给 {@link #resolve}，拿回“应该去哪一格”。
 *
 * <p>三条边界规则：
 * <ul>
 *   <li><b>夹在网格内</b>：越界的移动返回空，调用方因此留在原地，而不是跳到别的行或别的列去。
 *       这里刻意不做 Excel 的 Tab 换行回绕，也不做左右换行——用户按的是“右边一格”，不是“下一个”。</li>
 *   <li><b>跳过禁用列</b>：禁用的组成列是只读展示位（{@code teacher-course-grade-cell-disabled}），
 *       横向移动会跳过它落到下一个可编辑的成绩列；一个方向上都找不到就返回空（留在原地），
 *       而不是打开一个接收不了输入的编辑器。</li>
 *   <li><b>行列都不合法时返回空</b>：只读页面（{@code canEdit() == false}）或空名单传进来的
 *       可编辑标记全为 false，任何移动都不会发生——这正是“不能编辑时不要打开编辑器”的那道闸。</li>
 * </ul>
 *
 * <p>上下移动永远停留在同一列：纵向不换列符合“一列一列往下录”的实际录入手势。
 */
public final class GradeBookNavigator {

    /**
     * 一次导航的意图，由具体按键映射而来：
     * {@code ↑}→{@link #UP}、{@code ↓}/{@code Enter}→{@link #DOWN}、{@code ←}→{@link #LEFT}、
     * {@code →}→{@link #RIGHT}、{@code Tab}→{@link #NEXT}、{@code Shift+Tab}→{@link #PREVIOUS}。
     */
    public enum Move {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        NEXT,
        PREVIOUS
    }

    /** 一格的位置：行下标（从 0 开始）与成绩列序号（0..3，对应四个成绩组成）。 */
    public record Position(int row, int column) {
    }

    private GradeBookNavigator() {
    }

    /**
     * 从 {@code from} 出发按 {@code move} 走到哪一格；无处可去（越界或那个方向没有可编辑列）返回空。
     *
     * @param rowCount        当前名单行数
     * @param columnEditable  四个成绩列是否可编辑，下标即成绩列序号
     */
    public static Optional<Position> resolve(Position from, Move move, int rowCount,
            List<Boolean> columnEditable) {
        if (from == null || move == null || columnEditable == null) return Optional.empty();
        int columns = columnEditable.size();
        if (rowCount <= 0 || columns == 0) return Optional.empty();
        if (from.row() < 0 || from.row() >= rowCount
                || from.column() < 0 || from.column() >= columns) {
            return Optional.empty();
        }
        return switch (move) {
            case UP -> vertical(from, -1, rowCount, columnEditable);
            case DOWN -> vertical(from, 1, rowCount, columnEditable);
            case LEFT, PREVIOUS -> horizontal(from, -1, columnEditable);
            case RIGHT, NEXT -> horizontal(from, 1, columnEditable);
        };
    }

    /** 同列上下移动：行越界或这一列不可编辑（只读页面）时不移动。 */
    private static Optional<Position> vertical(Position from, int delta, int rowCount,
            List<Boolean> columnEditable) {
        int row = from.row() + delta;
        if (row < 0 || row >= rowCount) return Optional.empty();
        if (!Boolean.TRUE.equals(columnEditable.get(from.column()))) return Optional.empty();
        return Optional.of(new Position(row, from.column()));
    }

    /** 同行横向移动：跳过不可编辑的列；这一方向上没有可编辑列时不移动（夹在边界上）。 */
    private static Optional<Position> horizontal(Position from, int delta,
            List<Boolean> columnEditable) {
        for (int column = from.column() + delta; column >= 0 && column < columnEditable.size();
                column += delta) {
            if (Boolean.TRUE.equals(columnEditable.get(column))) {
                return Optional.of(new Position(from.row(), column));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code ←}/{@code →} 在编辑中的分工：光标还有地方走就走在文本里，走到头了才离开这一格。
     *
     * <p>规则（用户会被明确告知的就是这一条）：
     * <ol>
     *   <li>只要编辑器里有选中的文本（包括刚打开时的全选），{@code ←}/{@code →} 都离开当前格——
     *       此时“在文本里挪光标”没有意义，用户想挪的是格子。</li>
     *   <li>没有选中时按光标位置判断：{@code →} 在文本末尾离开，{@code ←} 在文本开头离开；
     *       否则光标在文本里移动一格（不离开）。留空的格子两个方向都直接离开。</li>
     * </ol>
     *
     * <p>这样“刚输完成绩，光标停在末尾”时 {@code →} 就是移到右边的格子，而打错的数字仍然能用
     * {@code ←} 回去改。
     *
     * @param caretPosition  编辑器光标位置
     * @param selectionLength 选中的字符数
     * @param textLength     编辑器文本长度
     * @param forward        true 表示 {@code →}，false 表示 {@code ←}
     */
    public static boolean shouldLeaveCell(int caretPosition, int selectionLength, int textLength,
            boolean forward) {
        if (selectionLength > 0) return true;
        return forward ? caretPosition >= textLength : caretPosition <= 0;
    }
}
