package util;

import entity.Book;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.*;

/** 图书检索和管理共用的类别标签、表头筛选和馆藏显示。 */
public final class LibraryCatalogTable {
    public static final String ALL_CATEGORIES = "全部类别";
    private LibraryCatalogTable() {}

    public static ComboBox<String> configure(TableView<Book> table,
            TableColumn<Book,String> category, TableColumn<Book,String> inventory) {
        // 小窗口允许横向滚动，不能通过压缩关键列来隐藏类别和馆藏数量。
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("catalog-table");
        category.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getCategory()));
        category.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();
            { badge.getStyleClass().add("category-badge"); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value,empty);
                setText(null);
                badge.setText(value);
                setGraphic(empty || value == null ? null : badge);
            }
        });
        ComboBox<String> filter = new ComboBox<>();
        filter.getItems().add(ALL_CATEGORIES);
        filter.getItems().addAll(Book.CATEGORIES);
        filter.setValue(ALL_CATEGORIES);
        filter.setMinWidth(64);
        filter.setPrefWidth(64);
        filter.setMaxWidth(64);
        filter.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText("类别");
                setGraphic(null);
            }
        });
        filter.getStyleClass().add("category-filter");
        filter.setAccessibleText("按图书类别筛选");
        filter.setTooltip(new Tooltip("选择类别筛选当前检索结果"));
        category.setText(null);
        category.setGraphic(filter);
        category.setSortable(false);
        category.setMinWidth(144);
        category.setPrefWidth(144);
        inventory.setText("可借 / 馆藏");
        inventory.setMinWidth(132);
        inventory.setPrefWidth(132);
        inventory.setMaxWidth(180);
        inventory.setStyle("-fx-alignment: CENTER-LEFT;");
        inventory.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().getAvailableCopies()+" / "+c.getValue().getTotalCopies()+" 册"));
        return filter;
    }
}
