package controller;

import service.LibraryClientService;
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
    @FXML private Button previewButton;
    @FXML private Button downloadButton;
    @FXML private Label ebookStatus;
    private boolean ebookBusy;

    private void updateEbookButtons() {
        previewButton.setDisable(ebookBusy || selectedBook == null);
        downloadButton.setDisable(ebookBusy || selectedBook == null);
    }

    @FXML private void handlePreviewBook() { loadEbook(true); }
    @FXML private void handleDownloadBook() { loadEbook(false); }

    private void loadEbook(boolean preview) {
        if (ebookBusy || !requireSelectedBook()) return;
        Book book = selectedBook;
        java.io.File destination;
        if (!preview) {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("保存电子书");
            chooser.setInitialFileName("book-" + book.getId() + ".pdf");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF 电子书", "*.pdf"));
            destination = chooser.showSaveDialog(bookTable.getScene().getWindow());
            if (destination == null) return;
        } else destination = null;
        ebookBusy = true;
        updateEbookButtons();
        ebookStatus.setText("正在获取《" + book.getName() + "》…");
        service.getBookFile(book.getId()).thenApplyAsync(bytes -> {
            if (destination != null) {
                java.nio.file.Path temporary = null;
                try {
                    var target = destination.toPath().toAbsolutePath();
                    temporary = java.nio.file.Files.createTempFile(target.getParent(), ".ebook-", ".part");
                    java.nio.file.Files.write(temporary, bytes);
                    java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException e) { throw new CompletionException(e); }
                finally {
                    if (temporary != null) try { java.nio.file.Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { }
                }
            }
            return bytes;
        }).whenComplete((bytes, error) -> Platform.runLater(() -> {
            ebookBusy = false;
            updateEbookButtons();
            if (error != null) {
                ebookStatus.setText("电子书获取失败");
                showError(preview ? "预览失败" : "下载失败", error);
            } else if (preview) {
                ebookStatus.setText("已打开《" + book.getName() + "》");
                util.pdf.BookPreview.show(bookTable.getScene().getWindow(), book.getName(), bytes);
            } else {
                ebookStatus.setText("下载完成");
                AlertUtil.showInfo("下载完成", "已保存至：" + destination.getAbsolutePath());
            }
        }));
    }

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
    private long lossNoticeVersion;
    private long myLibraryVersion;

    @FXML private void refreshLossNotices() {
        long version = ++lossNoticeVersion;
        service.getPublicLossNotices().whenComplete((notices, error) -> Platform.runLater(() -> {
            if (version != lossNoticeVersion) return;
            if (error != null) showError("加载挂失公告失败", error);
            else lossNoticeList.getItems().setAll(notices);
        }));
    }

    @FXML private Tab adminTab;
    @FXML private TabPane libraryTabs;
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
    @FXML private Label fineSummaryLabel;
    @FXML private Label fineDetailLabel;
    @FXML private Button adminRefundButton;
    private boolean finePaymentBusy;
    @FXML private void handleAdminRefund() { new LibraryCirculationController().show(2); refreshMyLibrary(); }

    @FXML
    public void initialize() {
        updateEbookButtons();
        configureTables();
        boolean administrator="管理员".equals(ClientSession.getInstance().getRole());
        adminRefundButton.setVisible(administrator);adminRefundButton.setManaged(administrator);
        fineTable.getSelectionModel().selectedItemProperty().addListener((obs,old,fine)->showFine(fine));
        fineTable.getItems().addListener((javafx.collections.ListChangeListener<FineRecord>) change->{
            java.math.BigDecimal total=java.math.BigDecimal.ZERO;
            for(FineRecord fine:fineTable.getItems())if(fine.getStatus()==0)total=total.add(fine.getAmount());
            fineSummaryLabel.setText("待结算费用：¥ "+total.setScale(2));
        });
        lossNoticeList.setPlaceholder(new Label("暂无挂失公告，点击刷新获取最新信息"));
        lossNoticeList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(vo.LostBookNotice notice, boolean empty) {
                super.updateItem(notice, empty);
                setText(empty || notice == null ? null : "《" + notice.getName() + "》  编号：" + notice.getBookId()
                        + "  作者：" + notice.getAuthor() + "\n挂失时间：" + notice.getLossTime().replace('T', ' ')
                        + "\n如有发现，请交至图书馆服务台。");
            }
        });
        boolean canManageLibrary = ("管理员".equals(ClientSession.getInstance().getRole())
                || "ADMIN".equalsIgnoreCase(ClientSession.getInstance().getRole()))
                && ClientSession.getInstance().hasLibraryPermission();
        if (adminTab != null && !canManageLibrary) {
            adminTab.setDisable(true);
        }
        bookTable.getSelectionModel().selectedItemProperty().addListener((obs, oldBook, newBook) -> showBook(newBook));
        libraryTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != adminTab) {
                handleSearch();
                refreshMyLibrary();
            }
        });
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
                ? (c.getValue().getStatus()==2?"挂失中 / 逾期":"挂失中") : borrowStatus(c.getValue().getStatus())));

        historyBookColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getBookId()));
        historyBorrowTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getBorrowTime())));
        historyReturnTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getReturnTime())));
        historyStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().isLossReported()
                ? (c.getValue().getStatus()==2?"挂失中 / 逾期":"挂失中") : borrowStatus(c.getValue().getStatus())));

        reservationBookColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getBookId()));
        reservationTimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(time(c.getValue().getReserveTime())));
        reservationStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(reservationStatus(c.getValue().getStatus())));

        fineIdColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getId()));
        fineAmountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAmount() + " 元"));
        fineReasonColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getReason()));
        fineStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getStatus()==0 && c.getValue().getAmount().signum()==0
                ? "无需缴费" : c.getValue().getRefundedAmount().signum()>0?"已缴费 / 已退款":fineStatus(c.getValue().getStatus())));

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
        updateEbookButtons();
        if (!ebookBusy) ebookStatus.setText("电子书支持 PDF 格式");
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
            else AlertUtil.showInfo("预约成功", "请在预约后12小时内到图书馆找管理员办理借书，超时将自动取消预约。");
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
        long version = ++myLibraryVersion;
        service.searchBooks("").whenComplete((books, error) -> Platform.runLater(() -> {
            if (version != myLibraryVersion) return;
            if (error != null) { showError("加载书名失败", error); return; }
            bookNames.clear();
            for (Book book : books) bookNames.put(book.getId(), book.getName());
            currentBorrowTable.refresh(); historyTable.refresh(); reservationTable.refresh();
        }));
        refreshLossNotices();
        service.getCurrentBorrow().whenComplete((v, e) -> updateTable(currentBorrowTable, v, e, "当前借阅", version));
        service.getBorrowHistory().whenComplete((v, e) -> updateTable(historyTable, v, e, "借阅历史", version));
        service.getReservations().whenComplete((v, e) -> updateTable(reservationTable, v, e, "预约记录", version));
        service.getFineRecords().whenComplete((v, e) -> updateTable(fineTable, v, e, "罚款记录", version));
    }

    @FXML private void handleReportLoss() {
        BorrowRecord record = currentBorrowTable.getSelectionModel().getSelectedItem();
        if (record == null) { AlertUtil.showWarning("提示", "请在当前借阅中选择一本图书"); return; }
        runAction(service.reportLoss(record.getBookId()), "图书挂失成功", () -> { refreshMyLibrary(); handleSearch(); });
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
        runAction(service.cancelLoss(record.getBookId()), "已解除挂失", () -> { refreshMyLibrary(); handleSearch(); });
    }

    @FXML private void handlePayFine() {
        if(finePaymentBusy)return;
        FineRecord fine = fineTable.getSelectionModel().getSelectedItem();
        if (fine == null) { AlertUtil.showWarning("提示", "请选择一条罚款记录"); return; }
        if (fine.getStatus() == FineStatus.PAID.getCode()) { AlertUtil.showInfo("提示", "该罚款已经缴纳"); return; }
        if(!fine.isPayable()) {AlertUtil.showInfo("暂不能缴费","无需缴费，或借阅尚未结束。普通逾期请还书后结算；挂失赔偿需管理员先录入书价。");return;}
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("校园银行支付");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        PasswordField password=new PasswordField();password.setPromptText("6位校园银行支付密码");
        Label amount=new Label("确认扣款 ¥ "+fine.getAmount()+"\n"+fine.getReason()+
                (fine.getLossAmount().signum()>0?"\n赔偿缴清后结束本次借阅；以后找回可联系管理员退款。":""));
        amount.setWrapText(true);
        javafx.scene.layout.VBox content=new javafx.scene.layout.VBox(12,amount,password);content.setPrefWidth(410);
        content.setPadding(new javafx.geometry.Insets(16));dialog.getDialogPane().setContent(content);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        finePaymentBusy=true;
        service.payFine(fine.getId(),password.getText(),fine.getAmount()).whenComplete((v,error)->Platform.runLater(()->{
            finePaymentBusy=false;
            if(error!=null)showError("缴费失败",error);else AlertUtil.showInfo("缴费成功","校园银行已扣款，可在银行交易流水中查看。");
            refreshMyLibrary();handleSearch();
        }));
        password.clear();
    }
    private void showFine(FineRecord fine) {
        if(fine==null) {fineDetailLabel.setText("选择一条记录查看费用明细并办理缴费。");return;}
        fineDetailLabel.setText("账单 #"+fine.getId()+"  |  逾期费 ¥"+fine.getOverdueAmount()+"  |  赔偿价 ¥"+fine.getLossAmount()
                +"\n实付 ¥"+fine.getPaidAmount()+"  |  已退款 ¥"+fine.getRefundedAmount()
                +"\n"+fine.getReason());
    }

    private void loadReviews(int bookId) {
        reviewList.setPlaceholder(new Label("正在加载评价..."));
        service.getBookReviews(bookId).whenComplete((reviews, error) -> Platform.runLater(() -> {
            if (selectedBook == null || selectedBook.getId() != bookId) return;
            if (error != null) showError("加载评价失败", error);
            else reviewList.getItems().setAll(reviews);
        }));
    }

    private <T> void updateTable(TableView<T> table, List<T> values, Throwable error, String name, long version) {
        Platform.runLater(() -> {
            if (version != myLibraryVersion) return;
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
