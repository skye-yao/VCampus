package controller;

import java.util.Objects;

import dto.course.teacher.GradeComponentCodeDTO;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import model.course.teacher.GradeBookEditorModel.Row;
import model.course.teacher.GradeBookNavigator;

/**
 * 一个成绩格子的“Excel 式”编辑器：单击即编辑、输入即写入、方向键即导航、离开即保存、异常才打断。
 *
 * <p>与 {@code TextFieldTableCell} 的区别是它管自己的 {@link TextField}（而不是走 JavaFX 的
 * startEdit/commitEdit 生命周期），因此：
 * <ul>
 *   <li><b>单击进入编辑</b>：单元格上的 MOUSE_CLICKED 直接请求打开编辑器，不需要双击。</li>
 *   <li><b>输入即生效</b>：文本每变一次就写进 {@link model.course.teacher.GradeBookEditorModel}
 *       （{@link TeacherGradeBookController#liveScoreEdit}），所以总评/绩点/行错误与非法标红
 *       在敲键的同时就更新，不存在“还要按一次回车确认”的那一步。</li>
 *   <li><b>打开即全选</b>：下一次敲键直接覆盖原值，不必先删干净。</li>
 *   <li><b>关闭即保存</b>：失焦、点别的格子、点页面别处都只是把编辑器收起来——值早就写进去了，
 *       因此绝不回滚、绝不二次确认。</li>
 * </ul>
 *
 * <p>键盘分工（{@code ←}/{@code →} 的取舍见 {@link GradeBookNavigator#shouldLeaveCell}）：
 * {@code Enter} 下移一格、{@code Tab}/{@code Shift+Tab} 右/左移一格、{@code ↑}/{@code ↓} 上下移动、
 * {@code Esc} 撤销本次修改、{@code Ctrl+C}/{@code Ctrl+V} 复制与批量粘贴。所有按键都用
 * <b>事件过滤器</b>处理：过滤器在控件自己的行为（光标移动、回车提交、Tab 焦点切换）之前运行，
 * 因此 {@code Tab} 不会把焦点挪出表格，{@code Enter} 也不会发出多余的 ActionEvent。
 *
 * <p>非法成绩只在本格标红并带 {@link Tooltip} 说明原因，绝不弹窗——连续性录入不能被对话框打断；
 * 真正挡住 保存/提交 的仍然是模型里的 {@code saveBlockReason()}/{@code submitBlockReason()}。
 */
final class ScoreEditCell extends TableCell<Row, String> {

    private final TeacherGradeBookController owner;
    private final TableColumn<Row, String> column;
    private final GradeComponentCodeDTO code;
    /** 每格一个编辑器实例：虚拟化复用时跟着单元格走，不需要在开合之间重新构造。 */
    private final TextField editor = new TextField();
    private final Tooltip errorTooltip = new Tooltip();

    private boolean editing;
    private int editingRow = -1;
    /** 正在用程序回写编辑器文本（打开/撤销/粘贴）时忽略文本监听器，避免把回写当成用户输入。 */
    private boolean syncing;
    /** 本次编辑开始时的原文：{@code Esc} 要恢复的就是它。 */
    private String revertText = "";

    ScoreEditCell(TeacherGradeBookController owner, TableColumn<Row, String> column,
            GradeComponentCodeDTO code) {
        this.owner = Objects.requireNonNull(owner, "Owner is required");
        this.column = Objects.requireNonNull(column, "Column is required");
        this.code = Objects.requireNonNull(code, "Component code is required");
        editor.getStyleClass().add("teacher-course-grade-editor");
        editor.setMaxWidth(Double.MAX_VALUE);
        editor.prefWidthProperty().bind(widthProperty().subtract(12.0));
        editor.textProperty().addListener((observable, previous, next) -> onEditorTextChanged(next));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::onEditorKeyPressed);
        // 失焦 = 完成本次输入：值已经实时写进模型，这里只把编辑器收起来（不回滚、不确认）。
        editor.focusedProperty().addListener((observable, previous, focused) -> {
            if (!focused && editing) endEdit(false);
        });
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && rowOrNull() != null) {
                owner.requestCellEdit(getIndex(), column);
            }
        });
    }

    TableColumn<Row, String> column() {
        return column;
    }

    GradeComponentCodeDTO code() {
        return code;
    }

    boolean editorOpen() {
        return editing;
    }

    /** 事件目标是不是本格的编辑器（含编辑器内部的皮肤节点）：是的话键盘交给编辑器自己处理。 */
    boolean owns(javafx.scene.Node node) {
        for (javafx.scene.Node current = node; current != null; current = current.getParent()) {
            if (current == editor) return true;
        }
        return false;
    }

    /**
     * 打开编辑器。{@code seedText} 为 null 表示“打开并全选原值”（单击/方向键走到这一格），
     * 非 null 表示用户直接敲了一个字符（选中即输入，该字符直接覆盖原值）。
     */
    void beginEdit(String seedText) {
        Row row = rowOrNull();
        if (row == null || !owner.isColumnEditable(code)) return;
        editingRow = getIndex();
        revertText = row.cell(code).text();
        editing = true;
        syncing = true;
        try {
            editor.setText(seedText == null ? revertText : seedText);
        } finally {
            syncing = false;
        }
        setText(null);
        setGraphic(editor);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        applyStyles();
        // 焦点只有等这一轮布局结束才落得上去；落上去之后再把全选/光标补一次。
        owner.runLater(() -> {
            if (!editing) return;
            editor.requestFocus();
            if (seedText == null) {
                editor.selectAll();
            } else {
                editor.positionCaret(editor.getLength());
            }
        });
    }

    /** 用户在本格已经打开的情况下又敲了一个字符（例如焦点还没落进输入框就被键入）。 */
    void typeIn(String text) {
        if (!editing || text == null || text.isEmpty()) return;
        int caret = editor.getCaretPosition();
        editor.replaceSelection(text);
        editor.positionCaret(Math.min(caret + text.length(), editor.getLength()));
    }

    /** 关闭编辑器并重新显示模型里的原文（值不会被改动）。 */
    void endEdit(boolean refocusTable) {
        if (!editing) return;
        editing = false;
        editingRow = -1;
        syncing = true;
        try {
            editor.setText("");
        } finally {
            syncing = false;
        }
        setGraphic(null);
        setContentDisplay(ContentDisplay.TEXT_ONLY);
        setText(currentRawText());
        applyStyles();
        owner.editorClosed(this);
        if (refocusTable) owner.focusTable();
    }

    @Override
    public void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        // 被虚拟化流程复用到别的行时，先给上一行的编辑收尾，别把输入框留在错的行上。
        if (editing && (empty || getIndex() != editingRow)) endEdit(false);
        if (!editing) {
            setGraphic(null);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
            setText(empty ? null : item == null ? "" : item);
        }
        applyStyles();
    }

    // ------------------------------------------------------------------ 输入

    private void onEditorTextChanged(String next) {
        if (syncing) return;
        Row row = rowOrNull();
        if (row == null) return;
        owner.liveScoreEdit(row, code, next);
        applyStyles();
    }

    private void onEditorKeyPressed(KeyEvent event) {
        Row row = rowOrNull();
        if (row == null) return;
        switch (event.getCode()) {
            case ESCAPE -> {
                event.consume();
                revertAndClose();
            }
            case ENTER -> {
                event.consume();
                owner.navigateFrom(getIndex(), code, GradeBookNavigator.Move.DOWN);
            }
            case TAB -> {
                event.consume();
                owner.navigateFrom(getIndex(), code, event.isShiftDown()
                        ? GradeBookNavigator.Move.PREVIOUS : GradeBookNavigator.Move.NEXT);
            }
            case UP -> {
                event.consume();
                owner.navigateFrom(getIndex(), code, GradeBookNavigator.Move.UP);
            }
            case DOWN -> {
                event.consume();
                owner.navigateFrom(getIndex(), code, GradeBookNavigator.Move.DOWN);
            }
            case LEFT -> {
                if (GradeBookNavigator.shouldLeaveCell(editor.getCaretPosition(),
                        editor.getSelection().getLength(), editor.getLength(), false)) {
                    event.consume();
                    owner.navigateFrom(getIndex(), code, GradeBookNavigator.Move.LEFT);
                }
            }
            case RIGHT -> {
                if (GradeBookNavigator.shouldLeaveCell(editor.getCaretPosition(),
                        editor.getSelection().getLength(), editor.getLength(), true)) {
                    event.consume();
                    owner.navigateFrom(getIndex(), code, GradeBookNavigator.Move.RIGHT);
                }
            }
            case C -> {
                if (event.isShortcutDown()) {
                    event.consume();
                    owner.copyScoreText(row, code);
                }
            }
            case V -> {
                if (event.isShortcutDown()) {
                    event.consume();
                    owner.pasteScoreBlock(row, code, clipboardText());
                }
            }
            default -> {
            }
        }
    }

    /** 撤销本次修改：把模型里的原文改回编辑开始时的样子，然后收起编辑器。 */
    private void revertAndClose() {
        Row row = rowOrNull();
        if (row != null) owner.revertScore(row, code, revertText);
        endEdit(true);
    }

    /**
     * 模型里的原文变了之后重画这一格：批量粘贴会改到起点之外的格子，而那些格子收不到输入事件，
     * 也不会走 {@link #updateItem}（表格没有重建）。重建整张表代价太大、还会拆掉正在输入的编辑器，
     * 所以这里就地重画——正开着编辑器的那一格跳过，它由编辑器自己显示内容。
     */
    void refreshFromModel() {
        if (editing) return;
        setText(currentRawText());
        applyStyles();
    }

    // ------------------------------------------------------------------ 样式

    /** 一格的外观只有三态：禁用列（灰）、非法值（红）、正常。禁用优先于标红。 */
    private void applyStyles() {
        getStyleClass().remove(DISABLED_CELL_CLASS);
        getStyleClass().remove(ERROR_CELL_CLASS);
        Row row = rowOrNull();
        String error = null;
        if (row != null && owner.model() != null) {
            if (!owner.model().column(code).enabled()) {
                getStyleClass().add(DISABLED_CELL_CLASS);
            } else {
                error = row.cell(code).error();
                if (error != null) getStyleClass().add(ERROR_CELL_CLASS);
            }
        }
        if (error == null) {
            setTooltip(null);
            editor.setTooltip(null);
        } else {
            errorTooltip.setText(error);
            setTooltip(errorTooltip);
            editor.setTooltip(errorTooltip);
        }
    }

    private String currentRawText() {
        Row row = rowOrNull();
        return row == null ? "" : row.cell(code).text();
    }

    private Row rowOrNull() {
        if (getIndex() < 0 || getTableRow() == null || getTableRow().isEmpty()) return null;
        return getTableRow().getItem();
    }

    private static String clipboardText() {
        String text = Clipboard.getSystemClipboard().getString();
        return text == null ? "" : text;
    }

    static final String DISABLED_CELL_CLASS = "teacher-course-grade-cell-disabled";
    static final String ERROR_CELL_CLASS = "teacher-course-grade-cell-error";
}
