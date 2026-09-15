package model.course.teacher;

import java.util.ArrayList;
import java.util.List;

/**
 * 剪贴板文本 → 成绩块（纯计算，与 JavaFX 无关）。
 *
 * <p>从 Excel/WPS 复制一片单元格时，行之间是 {@code \r\n}（Windows）、{@code \n}（Unix/mac 复制）
 * 或 {@code \r}（老式 mac），列之间是制表符。三种行分隔符都要认；末尾那个换行只是“最后一行结束了”，
 * 不是“再清空一行”，所以要丢掉——否则粘贴一列 24 个分数会顺手把第 25 格清空。
 *
 * <p>单元格内容原样保留（不 trim）：怎么解析成成绩是 {@link GradeBookEditorModel} 的事，
 * 这里只负责切分。整块是否为空由 {@link #isEmpty} 判断，调用方据此决定要不要真的粘贴。
 */
public final class GradeClipboardParser {

    private GradeClipboardParser() {
    }

    /** 切分成“行 × 列”的文本块；空/纯空白文本返回空列表。 */
    public static List<List<String>> parse(String clipboardText) {
        if (clipboardText == null || clipboardText.isEmpty()) return List.of();
        List<List<String>> block = new ArrayList<>();
        for (String line : clipboardText.split("\\r\\n|\\n|\\r", -1)) {
            block.add(List.of(line.split("\t", -1)));
        }
        // 末尾换行（一个或多个）产生的空行是复制时的收尾，不是用户的“清空这一行”。
        while (!block.isEmpty() && isTrailingBlankRow(block.get(block.size() - 1))) {
            block.remove(block.size() - 1);
        }
        return List.copyOf(block);
    }

    /** 整块是否没有任何内容：{} 或全是空白单元格。 */
    public static boolean isEmpty(List<List<String>> block) {
        if (block == null) return true;
        for (List<String> row : block) {
            for (String cell : row) {
                if (!cell.isBlank()) return false;
            }
        }
        return true;
    }

    /** 只有一个空白单元格的行：只可能是行分隔符的副产品，不可能是用户想粘贴的内容。 */
    private static boolean isTrailingBlankRow(List<String> cells) {
        return cells.size() == 1 && cells.get(0).isBlank();
    }
}
