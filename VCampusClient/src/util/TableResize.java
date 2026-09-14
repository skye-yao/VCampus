package util;

import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * 让表格列宽随窗口大小自适应。
 *
 * <p>JavaFX 默认的列宽策略下，窗口变大时列宽保持不变，表格右侧会留出空白。
 * 这里统一改为“所有列按比例填满表格宽度”的策略，窗口拉伸或缩小时列宽同步调整；
 * 同时给过窄的列设置一个可读的最小宽度，避免窗口很小时文字被压得看不清。
 */
public final class TableResize {

    /** 列在窗口变窄时的最小宽度。 */
    private static final double MIN_COLUMN_WIDTH = 56;

    private TableResize() { }

    /** 让传入的表格填满可用宽度，并随窗口大小重新分配列宽。 */
    public static void fillWidth(TableView<?>... tables) {
        for (TableView<?> table : tables) {
            if (table == null) continue;
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
            for (TableColumn<?, ?> column : table.getColumns()) {
                if (column.getMinWidth() < MIN_COLUMN_WIDTH && column.getMaxWidth() > MIN_COLUMN_WIDTH) {
                    column.setMinWidth(MIN_COLUMN_WIDTH);
                }
            }
        }
    }
}
