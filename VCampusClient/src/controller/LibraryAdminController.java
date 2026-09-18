package controller;

import service.LibraryClientService;
import entity.Book;
import enums.BookStatus;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import util.AlertUtil;

import java.util.concurrent.CompletionException;

public class LibraryAdminController {
    @FXML private void handleCirculation() {
        new LibraryCirculationController().show(0);
        handleAdminSearch();
    }
    @FXML private void handleAdminRecords() {
        Dialog<Void> dialog = new Dialog<>();
        // Management windows must remain minimizable; modal dialogs disable this on Windows.
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setTitle("图书馆业务名单（管理员）");
        dialog.getDialogPane().getStyleClass().add("library-root");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/resources/css/library.css").toExternalForm());
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ComboBox<String> kind = new ComboBox<>();
        kind.getItems().addAll("借阅名单", "罚款缴费名单", "挂失名单", "预约名单");
        kind.getSelectionModel().selectFirst();
        TextField filter = new TextField();
        filter.setPromptText("按账号、姓名、书名或状态筛选");
        TableView<java.util.Map<String,String>> table = new TableView<>();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        javafx.collections.ObservableList<java.util.Map<String,String>> rows = javafx.collections.FXCollections.observableArrayList();
        javafx.collections.transformation.FilteredList<java.util.Map<String,String>> filtered = new javafx.collections.transformation.FilteredList<>(rows);
        table.setItems(filtered);
        filter.textProperty().addListener((o,oldValue,text) -> filtered.setPredicate(row -> row.values().stream()
                .anyMatch(value -> value != null && value.toLowerCase(java.util.Locale.ROOT).contains(text.trim().toLowerCase(java.util.Locale.ROOT)))));
        Button refresh = new Button("刷新名单");
        long[] version = {0};
        Runnable load = () -> {
            long requestVersion = ++version[0];
            rows.clear(); table.getColumns().clear(); table.setPlaceholder(new Label("正在加载..."));
            String[] keys = {"borrow", "fine", "loss", "reservation"};
            service.getAdminRecords(keys[kind.getSelectionModel().getSelectedIndex()]).whenComplete((records,error) -> Platform.runLater(() -> {
                if (requestVersion != version[0]) return;
                table.setPlaceholder(new Label("暂无匹配记录"));
                if(error != null) { showError(error); return; }
                if(!records.isEmpty()) for(String key : records.get(0).keySet()) {
                    TableColumn<java.util.Map<String,String>,String> col = new TableColumn<>(key);
                    col.setPrefWidth(key.equals("书名") || key.equals("原因") ? 230 : 140);
                    col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().get(key)));
                    table.getColumns().add(col);
                }
                rows.setAll(records);
            }));
        };
        kind.setOnAction(e -> load.run()); refresh.setOnAction(e -> load.run());
        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(10,kind,filter,refresh);
        javafx.scene.layout.HBox.setHgrow(filter, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(12,toolbar,table);
        javafx.scene.layout.VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        root.setPrefSize(950,540);
        dialog.getDialogPane().setContent(root);
        load.run(); dialog.showAndWait();
        version[0]++;
    }
    private final LibraryClientService service = LibraryClientService.getInstance();

    @FXML private TextField adminSearchField;
    @FXML private TableView<Book> adminBookTable;
    @FXML private TableColumn<Book, Number> adminIdColumn;
    @FXML private TableColumn<Book, String> adminNameColumn;
    @FXML private TableColumn<Book, String> adminAuthorColumn;
    @FXML private TableColumn<Book, String> adminIsbnColumn;
    @FXML private TableColumn<Book, String> adminStatusColumn;
    @FXML private TableColumn<Book, String> adminCategoryColumn;
    @FXML private ComboBox<String> categoryCombo;
    private ComboBox<String> categoryFilter;
    private final javafx.collections.ObservableList<Book> searchResults = javafx.collections.FXCollections.observableArrayList();
    private final javafx.collections.transformation.FilteredList<Book> filteredBooks = new javafx.collections.transformation.FilteredList<>(searchResults);
    private long searchVersion;
    @FXML private TextField idField;
    @FXML private TextField isbnField;
    @FXML private TextField nameField;
    @FXML private TextField authorField;
    @FXML private TextField publisherField;
    @FXML private TextField priceField;
    @FXML private TextField quantityField;

    @FXML public void initialize() {
        categoryFilter = util.LibraryCatalogTable.configure(adminBookTable,adminCategoryColumn,adminStatusColumn);
        javafx.collections.transformation.SortedList<Book> sortedBooks = new javafx.collections.transformation.SortedList<>(filteredBooks);
        sortedBooks.comparatorProperty().bind(adminBookTable.comparatorProperty());
        adminBookTable.setItems(sortedBooks);
        categoryFilter.valueProperty().addListener((o,oldValue,value) -> applyCategoryFilter());
        categoryCombo.getItems().setAll(Book.CATEGORIES);
        categoryCombo.setValue("其他");
        adminIdColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getId()));
        adminNameColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getName()));
        adminAuthorColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAuthor()));
        adminIsbnColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getIsbn()));
        quantityField.setText("1");
        adminBookTable.getSelectionModel().selectedItemProperty().addListener((o, oldValue, book) -> fillForm(book));
        handleAdminSearch();
    }

    @FXML private void handleAdminSearch() {
        long version = ++searchVersion;
        service.searchBooks(adminSearchField.getText()).whenComplete((books, error) -> Platform.runLater(() -> {
            if (version != searchVersion) return;
            if (error != null) showError(error); else { searchResults.setAll(books); applyCategoryFilter(); }
        }));
    }

    private void applyCategoryFilter() {
        String category = categoryFilter.getValue();
        filteredBooks.setPredicate(book -> category == null
                || util.LibraryCatalogTable.ALL_CATEGORIES.equals(category) || category.equals(book.getCategory()));
        adminBookTable.setPlaceholder(new Label("没有符合当前关键词和类别的图书"));
    }

    @FXML private void handleAddBook() {
        Book book = readForm(false);
        if (book != null) run(service.addBook(book), "图书上架成功，已入库" + book.getTotalCopies() + "册");
    }

    @FXML private void handleUpdateBook() {
        Book book = readForm(true);
        if (book != null) run(service.updateBook(book), "图书信息修改成功");
    }

    @FXML private void handleRemoveBook() {
        Book book = adminBookTable.getSelectionModel().getSelectedItem();
        if (book == null) { AlertUtil.showWarning("提示", "请选择要下架的图书"); return; }
        run(service.removeBook(book.getId()), "图书下架成功");
    }

    @FXML private void handleClearForm() {
        adminBookTable.getSelectionModel().clearSelection();
        fillForm(null);
    }

    private Book readForm(boolean requireId) {
        try {
            int id = requireId ? Integer.parseInt(idField.getText()) : 0;
            if (isbnField.getText().isBlank() || nameField.getText().isBlank() || authorField.getText().isBlank()) {
                AlertUtil.showWarning("提示", "ISBN、书名和作者不能为空"); return null;
            }
            Book book = new Book(id, isbnField.getText().trim(), nameField.getText().trim(), authorField.getText().trim(),
                    publisherField.getText().trim(), BookStatus.AVAILABLE.getCode());
            if (!requireId) {
                int count = Integer.parseInt(quantityField.getText().trim());
                Book.validateInitialCopies(count);
                book.setTotalCopies(count);
            }
            book.setCategory(categoryCombo.getValue());
            if (!priceField.getText().isBlank()) {
                java.math.BigDecimal price=new java.math.BigDecimal(priceField.getText().trim());
                if(price.signum()<=0||price.scale()>2||price.compareTo(new java.math.BigDecimal("99999999.99"))>0) {
                    AlertUtil.showWarning("提示","书价须大于0，最多两位小数且不超过99999999.99元");return null;
                }
                book.setPrice(price);
            }
            return book;
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("提示", "图书编号、书价或上架数量格式不正确，数量须为1至1000之间的整数"); return null;
        } catch (IllegalArgumentException e) {
            AlertUtil.showWarning("提示", e.getMessage()); return null;
        }
    }

    private void fillForm(Book book) {
        idField.setText(book == null ? "" : String.valueOf(book.getId()));
        isbnField.setText(book == null ? "" : book.getIsbn());
        nameField.setText(book == null ? "" : book.getName());
        authorField.setText(book == null ? "" : book.getAuthor());
        publisherField.setText(book == null ? "" : book.getPublisher());
        categoryCombo.setValue(book == null ? "其他" : book.getCategory());
        priceField.setText(book==null||book.getPrice()==null?"":book.getPrice().toPlainString());
        quantityField.setText("1");
    }

    private void run(java.util.concurrent.CompletableFuture<Void> future, String success) {
        future.whenComplete((v, error) -> Platform.runLater(() -> {
            if (error != null) showError(error);
            else { AlertUtil.showInfo("操作成功", success); handleAdminSearch(); handleClearForm(); }
        }));
    }

    private void showError(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        AlertUtil.showError("操作失败", cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }
}
