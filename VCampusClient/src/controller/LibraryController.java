package controller;

import Service.LibraryClientService;
import app.ClientMain;
import entity.Book;
import entity.BookReview;
import entity.BorrowRecord;
import entity.FineRecord;
import entity.Reservation;
import enums.BookStatus;
import enums.BorrowStatus;
import enums.FineStatus;
import enums.ReservationStatus;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import session.ClientSession;
import util.AlertUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletionException;

public class LibraryController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final LibraryClientService service = LibraryClientService.getInstance();
    private Book selectedBook;
    private final java.util.Map<Integer,String> bookNames = new java.util.HashMap<>();
    private long searchVersion;

    @FXML private void handleShowAll() {
        searchField.clear();
        bookTable.getSelectionModel().clearSelection();
        showBook(null);
        reviewInput.clear();
        handleSearch();
    }

    private <T> void addBookNameColumn(TableView<T> table, java.util.function.ToIntFunction<T> getId) {
        TableColumn<T,String> name = new TableColumn<>("书名");
        name.setPrefWidth(180);
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(bookNames.getOrDefault(getId.applyAsInt(c.getValue()), "未知图书")));
        table.getColumns().add(1, name);
    }
    @FXML private ListView<vo.LostBookNotice> lossNoticeList;

    @FXML private void refreshLossNotices() {
        service.getPublicLossNotices().whenComplete((notices, error) -> Platform.runLater(() -> {
            if (error != null) showError("加载挂失公告失败", error);
            else lossNoticeList.getItems().setAll(notices);
        }));
    }

    @FXML private Tab adminTab;
    @FXML private TextField searchField;
    @FXML private TableView<Book> bookTable;
    @FXML private TableColumn<Book, Number> bookIdColumn;
    @FXML private TableColumn<Book, String> bookNameColumn;
    @FXML private TableColumn<Book, String> bookAuthorColumn;
    @FXML private TableColumn<Book, String> bookIsbnColumn;
    @FXML private TableColumn<Book, String> bookStatusColumn;
    @FXML private Label detailLabel;
    @FXML private TextArea expandedDetail;
    @FXML private Hyperlink detailToggle;
    private boolean detailExpanded;

    @FXML private void handleToggleDetail() {
        if (selectedBook == null) return;
        setDetailExpanded(!detailExpanded);
    }

    private void setDetailExpanded(boolean expanded) {
        detailExpanded = expanded;
        expandedDetail.setVisible(expanded);
        expandedDetail.setManaged(expanded);
        detailLabel.setVisible(!expanded);
        detailLabel.setManaged(!expanded);
        detailToggle.setText(expanded ? "收起详情" : "展开详情");
        detailToggle.setVisited(false);
        if (expanded) {
            expandedDetail.positionCaret(0);
            expandedDetail.setScrollTop(0);
        }
    }
    @FXML private TextArea reviewInput;
    @FXML private ListView<BookReview> reviewList;

    @FXML private TableView<BorrowRecord> currentBorrowTable;
    @FXML private TableColumn<BorrowRecord, Number> currentBookColumn;
    @FXML private TableColumn<BorrowRecord, String> currentBorrowTimeColumn;
    @FXML private TableColumn<BorrowRecord, String> currentDueTimeColumn;
    @FXML private TableColumn<BorrowRecord, String> currentStatusColumn;

    @FXML private TableView<BorrowRecord> historyTable;
    @FXML private TableColumn<BorrowRecord, Number> historyBookColumn;
    @FXML private TableColumn<BorrowRecord, String> historyBorrowTimeColumn;
    @FXML private TableColumn<BorrowRecord, String> historyReturnTimeColumn;
    @FXML private TableColumn<BorrowRecord, String> historyStatusColumn;

    @FXML private TableView<Reservation> reservationTable;
    @FXML private TableColumn<Reservation, Number> reservationBookColumn;
    @FXML private TableColumn<Reservation, String> reservationTimeColumn;
    @FXML private TableColumn<Reservation, String> reservationStatusColumn;

    @FXML private TableView<FineRecord> fineTable;
    @FXML private TableColumn<FineRecord, Number> fineIdColumn;
    @FXML private TableColumn<FineRecord, String> fineAmountColumn;
    @FXML private TableColumn<FineRecord, String> fineReasonColumn;
    @FXML private TableColumn<FineRecord, String> fineStatusColumn;

    @FXML
    public void initialize() {
        configureTables();
        lossNoticeList.setPlaceholder(new Label("暂无挂失公告，点击刷新获取最新信息"));
        lossNoticeList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(vo.LostBookNotice notice, boolean empty) {
                super.updateItem(notice, empty);
                setText(empty || notice == null ? null : "《" + notice.getName() + "》  编号：" + notice.getBookId()
                        + "  作者：" + notice.getAuthor() + "\n挂失时间：" + notice.getLossTime().replace('T', ' ')
                        + "\n如有发现，请交至图书馆服务台。");
            }
        });
        if (adminTab != null && !"管理员".equals(ClientSession.getInstance().getRole())) {
            adminTab.setDisable(true);
        }
        bookTable.getSelectionModel().selectedItemProperty().addListener((obs, oldBook, newBook) -> showBook(newBook));
        handleSearch();
        refreshMyLibrary();
    }

    private void configureTables() {
        addBookNameColumn(currentBorrowTable, BorrowRecord::getBookId);
        addBookNameColumn(historyTable, BorrowRecord::getBookId);
        addBookNameColumn(reservationTable, Reservation::getBookId);
        // 列宽策略是 Callback 对象，不能在 FXML 中直接写常量名称字符串。
        bookTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        currentBorrowTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        reservationTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        fineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        bookIdColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getId()));
        bookNameColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getName()));
        bookAuthorColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAuthor()));
        bookIsbnColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getIsbn()));
        bookStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(bookStatus(c.getValue().getStatus())));

        currentBookColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getBookId()));
        currentBorrowTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getBorrowTime())));
        currentDueTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getDueTime())));
        currentStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().isLossReported()
                ? "挂失中" : borrowStatus(c.getValue().getStatus())));

        historyBookColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getBookId()));
        historyBorrowTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getBorrowTime())));
        historyReturnTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getReturnTime())));
        historyStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(borrowStatus(c.getValue().getStatus())));

        reservationBookColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getBookId()));
        reservationTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getReserveTime())));
        reservationStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(reservationStatus(c.getValue().getStatus())));

        fineIdColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getId()));
        fineAmountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAmount() + " 元"));
        fineReasonColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getReason()));
        fineStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(fineStatus(c.getValue().getStatus())));

        reviewList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(BookReview review, boolean empty) {
                super.updateItem(review, empty);
                setText(empty || review == null ? null : review.getUserId() + "  " + time(review.getCreateTime()) + "\n" + review.getContent());
            }
        });
    }

    @FXML private void handleBack() { ClientMain.switchScene("/resources/fxml/MainView.fxml"); }

    @FXML private void handleSearch() {
        long version = ++searchVersion;
        setBooksLoading(true);
        service.searchBooks(searchField == null ? "" : searchField.getText())
                .whenComplete((books, error) -> Platform.runLater(() -> {
                    if (version != searchVersion) return;
                    setBooksLoading(false);
                    if (error != null) showError("查询图书失败", error);
                    else bookTable.getItems().setAll(books);
                }));
    }

    private void showBook(Book book) {
        selectedBook = book;
        setDetailExpanded(false);
        detailToggle.setVisible(book != null);
        detailToggle.setManaged(book != null);
        expandedDetail.clear();
        if (book == null) {
            detailLabel.setText("请选择一本图书查看详情");
            reviewList.getItems().clear();
            return;
        }
        detailLabel.setText(String.format("《%s》  作者：%s  出版社：%s  ISBN：%s  状态：%s",
                book.getName(), book.getAuthor(), book.getPublisher(), book.getIsbn(), bookStatus(book.getStatus())));
        expandedDetail.setText(String.format("书名：%s%n作者：%s%n出版社：%s%nISBN：%s%n图书编号：%d%n状态：%s",
                book.getName(), book.getAuthor(), book.getPublisher() == null ? "暂无" : book.getPublisher(),
                book.getIsbn(), book.getId(), bookStatus(book.getStatus())));
        loadReviews(book.getId());
    }

    @FXML private void handleReserve() {
        if (!requireSelectedBook()) return;
        if (selectedBook.getStatus() != BookStatus.AVAILABLE.getCode()) {
            AlertUtil.showWarning("暂不可预约", "该书当前状态为：" + bookStatus(selectedBook.getStatus()));
            return;
        }
        service.reserveBook(selectedBook.getId()).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) showError("预约失败", error);
            else AlertUtil.showInfo("预约成功", "预约记录已保存");
            // 无论成功或失败都刷新，处理列表打开后被其他读者预约的情况。
            handleSearch();
            refreshMyLibrary();
        }));
    }

    @FXML private void handleAddReview() {
        if (!requireSelectedBook()) return;
        String content = reviewInput.getText() == null ? "" : reviewInput.getText().trim();
        if (content.isEmpty()) { AlertUtil.showWarning("提示", "请输入评价内容"); return; }
        runAction(service.addBookReview(selectedBook.getId(), content), "评价发表成功", () -> {
            reviewInput.clear(); loadReviews(selectedBook.getId());
        });
    }

    @FXML private void handleDeleteReview() {
        BookReview review = reviewList.getSelectionModel().getSelectedItem();
        if (review == null) { AlertUtil.showWarning("提示", "请选择要删除的评价"); return; }
        runAction(service.deleteBookReview(review.getId()), "评价已删除", () -> loadReviews(selectedBook.getId()));
    }

    @FXML private void refreshMyLibrary() {
        service.searchBooks("").whenComplete((books, error) -> Platform.runLater(() -> {
            if (error != null) { showError("加载书名失败", error); return; }
            bookNames.clear();
            for (Book book : books) bookNames.put(book.getId(), book.getName());
            currentBorrowTable.refresh(); historyTable.refresh(); reservationTable.refresh();
        }));
        refreshLossNotices();
        service.getCurrentBorrow().whenComplete((v, e) -> updateTable(currentBorrowTable, v, e, "当前借阅"));
        service.getBorrowHistory().whenComplete((v, e) -> updateTable(historyTable, v, e, "借阅历史"));
        service.getReservations().whenComplete((v, e) -> updateTable(reservationTable, v, e, "预约记录"));
        service.getFineRecords().whenComplete((v, e) -> updateTable(fineTable, v, e, "罚款记录"));
    }

    @FXML private void handleReportLoss() {
        BorrowRecord record = currentBorrowTable.getSelectionModel().getSelectedItem();
        if (record == null) { AlertUtil.showWarning("提示", "请在当前借阅中选择一本图书"); return; }
        runAction(service.reportLoss(record.getBookId()), "图书挂失成功", this::refreshMyLibrary);
    }

    @FXML private void handleCancelReservation() {
        Reservation reservation = reservationTable.getSelectionModel().getSelectedItem();
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVING.getCode()) {
            AlertUtil.showWarning("提示", "请选择一条预约中的记录"); return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "确定取消这条预约吗？", ButtonType.OK, ButtonType.CANCEL);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        runAction(service.cancelReservation(reservation.getId()), "预约已取消", () -> {
            refreshMyLibrary(); handleSearch();
        });
    }

    @FXML private void handleCancelLoss() {
        BorrowRecord record = currentBorrowTable.getSelectionModel().getSelectedItem();
        if (record == null) { AlertUtil.showWarning("提示", "请在当前借阅中选择一本图书"); return; }
        runAction(service.cancelLoss(record.getBookId()), "已解除挂失", this::refreshMyLibrary);
    }

    @FXML private void handlePayFine() {
        FineRecord fine = fineTable.getSelectionModel().getSelectedItem();
        if (fine == null) { AlertUtil.showWarning("提示", "请选择一条罚款记录"); return; }
        if (fine.getStatus() == FineStatus.PAID.getCode()) { AlertUtil.showInfo("提示", "该罚款已经缴纳"); return; }
        runAction(service.payFine(fine.getId()), "缴费成功", this::refreshMyLibrary);
    }

    private void loadReviews(int bookId) {
        reviewList.setPlaceholder(new Label("正在加载评价..."));
        service.getBookReviews(bookId).whenComplete((reviews, error) -> Platform.runLater(() -> {
            if (selectedBook == null || selectedBook.getId() != bookId) return;
            if (error != null) showError("加载评价失败", error);
            else reviewList.getItems().setAll(reviews);
        }));
    }

    private <T> void updateTable(TableView<T> table, List<T> values, Throwable error, String name) {
        Platform.runLater(() -> {
            if (error != null) showError("加载" + name + "失败", error);
            else table.getItems().setAll(values);
        });
    }

    private void runAction(java.util.concurrent.CompletableFuture<Void> future, String success, Runnable after) {
        future.whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) showError("操作失败", error);
            else { AlertUtil.showInfo("操作成功", success); if (after != null) after.run(); }
        }));
    }

    private boolean requireSelectedBook() {
        if (selectedBook != null) return true;
        AlertUtil.showWarning("提示", "请先选择一本图书");
        return false;
    }

    private void setBooksLoading(boolean loading) {
        bookTable.setPlaceholder(new Label(loading ? "正在查询..." : "没有找到图书"));
    }

    private void showError(String title, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getClass() == RuntimeException.class) && cause.getCause() != null) cause = cause.getCause();
        AlertUtil.showError(title, cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }

    private static String time(LocalDateTime value) { return value == null ? "—" : TIME_FORMAT.format(value); }
    private static String bookStatus(int code) { try { return BookStatus.fromCode(code).getDescription(); } catch (Exception e) { return "未知(" + code + ")"; } }
    private static String borrowStatus(int code) { try { return BorrowStatus.fromCode(code).getDescription(); } catch (Exception e) { return "未知(" + code + ")"; } }
    private static String reservationStatus(int code) { try { return ReservationStatus.fromCode(code).getDescription(); } catch (Exception e) { return "未知(" + code + ")"; } }
    private static String fineStatus(int code) { try { return FineStatus.fromCode(code).getDescription(); } catch (Exception e) { return "未知(" + code + ")"; } }
}
