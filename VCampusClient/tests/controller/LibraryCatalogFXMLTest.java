package controller;

import entity.Book;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import util.LibraryCatalogTable;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real FXML/CSS layout regression check, using memory-only book results.
 * No Stage is shown and no service requests or database connections are made.
 * Supply an optional output directory for reader/admin PNGs at 860 and 1280 pixels.
 */
public class LibraryCatalogFXMLTest {
    private static final List<String> CATEGORIES = List.of(
            "计算机", "文学", "艺术", "历史", "经济管理", "哲学", "自然科学", "其他");

    public static void main(String[] args) throws Exception {
        checkStructure("LibraryView.fxml", "bookCategoryColumn", "bookStatusColumn");
        checkStructure("LibraryAdminView.fxml", "adminCategoryColumn", "adminStatusColumn");
        Path output = Path.of(args.length == 0 ? "build-check/categories-ui/screenshots" : args[0]);
        Files.createDirectories(output);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                for (int width : new int[]{860, 1280}) checkLayout(width, output);
                done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
            }
        });
        try {
            done.get(45, TimeUnit.SECONDS);
            System.out.println("LibraryCatalogFXMLTest PASS: FXML, category filtering/sorting, "
                    + "inventory/header text fit and 860/1280px offscreen snapshots");
        } finally {
            Platform.exit();
        }
    }

    private static void checkStructure(String name, String categoryId, String inventoryId) throws Exception {
        try (InputStream input = LibraryCatalogFXMLTest.class.getResourceAsStream("/resources/fxml/" + name)) {
            if (input == null) throw new AssertionError("Missing " + name);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            requireId(document, categoryId);
            Element inventory = requireId(document, inventoryId);
            require("可借 / 馆藏".equals(inventory.getAttribute("text")), "Inventory header missing in " + name);
            require(Double.parseDouble(inventory.getAttribute("minWidth")) >= 132, "Inventory column too narrow");
            if (name.equals("LibraryView.fxml")) {
                require("#handleSearch".equals(requireId(document,"searchField").getAttribute("onAction")),
                        "Search field must retain Enter search");
                LibraryController.class.getDeclaredMethod("handleShowAll");
            }
        }
    }

    private static void checkLayout(int width, Path output) throws Exception {
        FXMLLoader loader = new FXMLLoader(LibraryCatalogFXMLTest.class.getResource("/resources/fxml/LibraryView.fxml"));
        loader.setControllerFactory(type -> {
            if (type == LibraryController.class) return new OfflineReader();
            if (type == LibraryAdminController.class) return new OfflineAdmin();
            throw new AssertionError("Unexpected controller: " + type);
        });
        Parent root = loader.load();
        new Scene(root, width, 780);
        ((Region) root).resize(width, 780);
        OfflineReader controller = loader.getController();
        TableView<Book> table = field(controller, LibraryController.class, "bookTable");
        ComboBox<String> filter = field(controller, LibraryController.class, "categoryFilter");
        ObservableList<Book> results = field(controller, LibraryController.class, "searchResults");
        List<Book> books = sampleBooks();
        results.setAll(books);
        require(filter.getItems().equals(combinedCategories()), "Category options differ from supported labels");

        filter.setValue("计算机");
        require(table.getItems().size() == 4 && table.getItems().stream().allMatch(b -> b.getCategory().equals("计算机")),
                "Selecting 计算机 must show only matching books");
        results.setAll(List.of(books.get(0), books.get(1), books.get(8)));
        require(table.getItems().size() == 2, "Category must also filter newly fetched keyword results");
        filter.setValue("艺术");
        require(table.getItems().isEmpty(), "Unmatched category should leave no rows");
        filter.setValue(LibraryCatalogTable.ALL_CATEGORIES);
        require(table.getItems().size() == 3, "All categories must retain the current keyword result set");
        results.setAll(books);
        TableColumn<Book,String> nameColumn = field(controller, LibraryController.class, "bookNameColumn");
        nameColumn.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(nameColumn);
        table.sort();
        filter.setValue("计算机");
        require(table.getItems().size() == 4, "Filtering must work with table sorting active");
        table.getSortOrder().clear();
        filter.setValue(LibraryCatalogTable.ALL_CATEGORIES);
        layout(root);
        checkTable(table, field(controller, LibraryController.class, "bookStatusColumn"));
        save(root, output.resolve("reader-" + width + ".png"));
        checkFilterText(root, filter, "经济管理");

        TabPane tabs = (TabPane) loader.getNamespace().get("libraryTabs");
        Tab adminTab = (Tab) loader.getNamespace().get("adminTab");
        tabs.getSelectionModel().select(adminTab);
        layout(root);
        @SuppressWarnings("unchecked")
        TableView<Book> admin = (TableView<Book>) root.lookup("#adminBookTable");
        require(admin != null, "Included admin table must load");
        admin.getItems().setAll(books);
        layout(root);
        @SuppressWarnings("unchecked")
        TableColumn<Book,String> inventory = (TableColumn<Book,String>) admin.getColumns().stream()
                .filter(column -> "adminStatusColumn".equals(column.getId())).findFirst().orElseThrow();
        checkTable(admin, inventory);
        save(root, output.resolve("admin-" + width + ".png"));
    }

    private static void checkTable(TableView<Book> table, TableColumn<Book,String> inventory) {
        require(inventory.getWidth() >= 132, "Actual inventory column width is too narrow: " + inventory.getWidth());
        boolean header = false;
        for (Node node : table.lookupAll(".column-header .label")) {
            if (node instanceof Label label && "可借 / 馆藏".equals(label.getText())) {
                requireRenderedText(label, "可借 / 馆藏");
                header = true;
            }
        }
        require(header, "Inventory header not rendered");
        int checkedCells = 0;
        for (Node node : table.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?,?> cell && cell.getTableColumn() == inventory && !cell.isEmpty()) {
                requireRenderedText(cell, cell.getText());
                require(cell.localToScene(cell.getBoundsInLocal()).getMaxX()
                                <= table.localToScene(table.getBoundsInLocal()).getMaxX(),
                        "Inventory is horizontally clipped at this window width");
                checkedCells++;
            }
        }
        require(checkedCells > 0, "No inventory cells rendered");
        require(table.lookupAll(".category-badge").stream().anyMatch(Node::isVisible), "Category badges not rendered");
        System.out.printf("table=%s width=%.0f inventory=%.0f checkedRows=%d%n",
                table.getId(), table.getWidth(), inventory.getWidth(), checkedCells);
    }

    private static void checkFilterText(Parent root, ComboBox<String> filter, String category) {
        filter.setValue(category);
        layout(root);
        filter.applyCss();
        filter.layout();
        // ComboBox skin also contains a hidden measurement cell with empty text.
        Labeled buttonCell = filter.lookupAll(".list-cell").stream().filter(Node::isVisible)
                .map(node -> (Labeled) node).filter(label -> category.equals(label.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("Selected category not rendered"));
        requireRenderedText(buttonCell, category);
        filter.setValue(LibraryCatalogTable.ALL_CATEGORIES);
        layout(root);
    }

    private static void requireRenderedText(Labeled label, String expected) {
        require(label != null, "Missing label for " + expected);
        Text text = (Text) label.lookup(".text");
        require(text != null && expected.equals(text.getText()), "Rendered text truncated: "
                + expected + " -> " + (text == null ? "<missing>" : text.getText()));
        double available = label.getWidth() - label.getInsets().getLeft() - label.getInsets().getRight();
        require(text.getLayoutBounds().getWidth() <= available + 1,
                "Text exceeds cell content width: " + expected + " / available=" + available);
    }

    private static void layout(Parent root) { root.applyCss(); root.layout(); }
    private static void save(Parent root, Path path) throws Exception {
        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",path.toFile());
    }

    private static List<Book> sampleBooks() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Book book = new Book(i+1,"978730000000"+i,CATEGORIES.get(i % 8)+"示例图书 " +(i+1),"图书作者","出版社",0);
            book.setCategory(CATEGORIES.get(i % 8));
            book.setTotalCopies(10);
            book.setAvailableCopies(i % 3 == 0 ? 10 : 9);
            books.add(book);
        }
        return books;
    }

    private static List<String> combinedCategories() {
        List<String> categories = new ArrayList<>();
        categories.add(LibraryCatalogTable.ALL_CATEGORIES);
        categories.addAll(CATEGORIES);
        return categories;
    }

    public static final class OfflineReader extends LibraryController {
        @Override public void initialize() {
            try {
                Method configure = LibraryController.class.getDeclaredMethod("configureTables");
                configure.setAccessible(true);
                configure.invoke(this);
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        }
    }

    /** Only the shared table presentation is exercised for admin; service/form actions are excluded. */
    public static final class OfflineAdmin extends LibraryAdminController {
        @Override public void initialize() {
            try {
                TableView<Book> table = field(this,LibraryAdminController.class,"adminBookTable");
                LibraryCatalogTable.configure(table,field(this,LibraryAdminController.class,"adminCategoryColumn"),
                        field(this,LibraryAdminController.class,"adminStatusColumn"));
                TableColumn<Book,Number> id = field(this,LibraryAdminController.class,"adminIdColumn");
                TableColumn<Book,String> name = field(this,LibraryAdminController.class,"adminNameColumn");
                TableColumn<Book,String> author = field(this,LibraryAdminController.class,"adminAuthorColumn");
                TableColumn<Book,String> isbn = field(this,LibraryAdminController.class,"adminIsbnColumn");
                id.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getId()));
                name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getName()));
                author.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAuthor()));
                isbn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getIsbn()));
                ComboBox<String> categories = field(this,LibraryAdminController.class,"categoryCombo");
                categories.getItems().setAll(CATEGORIES);
                categories.setValue("其他");
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object instance, Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(instance);
    }

    private static Element requireId(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        throw new AssertionError("FXML missing " + id);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
