package model.course.teacher;

import java.util.List;

/**
 * 剪贴板块解析的无工具包测试：三种行分隔符、制表符分列，以及“末尾换行不是一行空数据”。
 *
 * <p>这些断言直接决定批量粘贴的落点：多认一行就会把名单之外的格清空，少认一行就会丢掉一个成绩。
 */
public final class GradeClipboardParserTest {
    private GradeClipboardParserTest() {
    }

    public static void main(String[] args) {
        singleValueAndSingleColumn();
        blockUsesTabsForColumns();
        allThreeRowSeparatorsAreUnderstood();
        trailingNewlinesDoNotClearAnExtraRow();
        blankMiddleRowStillClearsThatRow();
        blankCellsAreKeptSoTheyCanClearTheTarget();
        emptyTextProducesNothing();
        System.out.println("GradeClipboardParserTest: PASS");
    }

    /** 从一格复制出来的就是普通文本；一列成绩是一条条换行分开的。 */
    private static void singleValueAndSingleColumn() {
        require(List.of(List.of("88.5")), GradeClipboardParser.parse("88.5"),
                "单个值必须解析成一格");
        require(List.of(List.of("80"), List.of("81"), List.of("82")),
                GradeClipboardParser.parse("80\n81\n82"), "一列成绩必须解析成三行一列");
    }

    /** Excel 复制一片区域：列之间是制表符。 */
    private static void blockUsesTabsForColumns() {
        require(List.of(List.of("80", "90"), List.of("81", "91")),
                GradeClipboardParser.parse("80\t90\n81\t91"), "2×2 区域必须按制表符分列");
        require(List.of(List.of("80", "90", "", "70")),
                GradeClipboardParser.parse("80\t90\t\t70"),
                "中间的空列必须保留，否则整片成绩会错位一列");
    }

    /** 三种行分隔符都要认：Windows、Unix/mac 复制、以及老式 mac 的单独 CR。 */
    private static void allThreeRowSeparatorsAreUnderstood() {
        List<List<String>> expected = List.of(List.of("80"), List.of("90"));
        require(expected, GradeClipboardParser.parse("80\r\n90"), "\\r\\n 必须分行");
        require(expected, GradeClipboardParser.parse("80\n90"), "\\n 必须分行");
        require(expected, GradeClipboardParser.parse("80\r90"), "\\r 必须分行");
        require(List.of(List.of("80", "90"), List.of("91", "92")),
                GradeClipboardParser.parse("80\t90\r\n91\t92"), "\\r\\n 分行的块必须按列切开");
    }

    /** 末尾换行只是“最后一行结束了”，不能变成“再清空一行”。 */
    private static void trailingNewlinesDoNotClearAnExtraRow() {
        List<List<String>> expected = List.of(List.of("80"), List.of("90"));
        require(expected, GradeClipboardParser.parse("80\n90\n"), "末尾一个换行必须被忽略");
        require(expected, GradeClipboardParser.parse("80\n90\n\n"), "末尾多个换行必须被忽略");
        require(expected, GradeClipboardParser.parse("80\r\n90\r\n"), "末尾的 \\r\\n 必须被忽略");
        require(List.of(List.of("80")), GradeClipboardParser.parse("80\n"),
                "单值加换行仍然只有一行");
    }

    /** 中间的空行是用户真的想清空那一行，必须保留。 */
    private static void blankMiddleRowStillClearsThatRow() {
        require(List.of(List.of("80"), List.of(""), List.of("93")),
                GradeClipboardParser.parse("80\r\n\r\n93"), "中间的空行必须保留");
        require(List.of(List.of("80"), List.of("", ""), List.of("93")),
                GradeClipboardParser.parse("80\n\t\n93"), "中间的空行按制表符切出两个空格子");
    }

    /** 空单元格要留在块里：它的语义是“把目标格清空”，不是“跳过这一格”。 */
    private static void blankCellsAreKeptSoTheyCanClearTheTarget() {
        require(List.of(List.of("80", "", "70")), GradeClipboardParser.parse("80\t\t70"),
                "空格子必须保留");
    }

    /** 空剪贴板 / 纯换行：什么都没有，调用方据此直接返回。 */
    private static void emptyTextProducesNothing() {
        require(GradeClipboardParser.parse(null).isEmpty(), "null 必须解析成空块");
        require(GradeClipboardParser.parse("").isEmpty(), "空字符串必须解析成空块");
        require(GradeClipboardParser.parse("\n").isEmpty(), "纯换行必须解析成空块");
        require(GradeClipboardParser.parse("\r\n").isEmpty(), "纯 \\r\\n 必须解析成空块");

        require(GradeClipboardParser.isEmpty(List.of()), "空块必须被判定为空");
        require(GradeClipboardParser.isEmpty(GradeClipboardParser.parse("\t\n")),
                "只有空白单元格的块必须被判定为空");
        require(!GradeClipboardParser.isEmpty(List.of(List.of("80"), List.of(""))),
                "含有内容的块不是空块");
    }

    private static void require(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + "，实际 " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
