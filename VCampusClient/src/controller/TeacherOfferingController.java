package controller;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import dto.course.CourseTermDTO;
import dto.course.teacher.TeacherOfferingDTO;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教学班列表页（设计 §5.1）：学期、课程/教学班搜索、分页列表，点击一行进入四 Tab 详情。
 *
 * <p>行数据直接绑定 {@link TeacherOfferingDTO}：不新建平行的视图模型，也不把 JavaFX 状态塞进 DTO。
 * 学期与搜索文本保存在控制器字段里，工作台来回切换子页时不重置它们，只重新加载当前页；页面被
 * {@link #unload()} 卸下后，在途请求的响应一律丢弃（generation + active 双重判定），因此过期结果
 * 永远不会覆盖新结果，也不会写进已经不可见的界面。
 *
 * <p>所有节点都可能为 {@code null}：控制器测试按仓库约定在无工具包、无 FXML 节点的环境下运行。
 */
public final class TeacherOfferingController {
    /** 列表分页大小；名单与列表共用的固定页长。 */
    static final int PAGE_SIZE = 20;
    static final String LOAD_FAILURE_TEXT = "教学班加载失败，请重试";
    static final String DETAIL_LABEL = "详情";

    private static final String STATUS_NOT_OPEN = "NOT_OPEN";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private Consumer<String> onShowOffering = offeringId -> { };
    private List<CourseTermDTO> terms = List.of();
    private CourseTermDTO term;
    private String query = "";
    private int page = 1;
    private int loadedPage = 1;
    private long totalCount;
    private List<TeacherOfferingDTO> offerings = List.of();
    private boolean termsLoaded;
    private boolean loading;
    private boolean active;
    private boolean syncingFilters;
    private String errorText;
    private long listGeneration;

    @FXML private ComboBox<String> termFilter;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Label summaryLabel;
    @FXML private TableView<TeacherOfferingDTO> offeringTable;
    @FXML private TableColumn<TeacherOfferingDTO, String> nameColumn;
    @FXML private TableColumn<TeacherOfferingDTO, String> codeColumn;
    @FXML private TableColumn<TeacherOfferingDTO, String> creditColumn;
    @FXML private TableColumn<TeacherOfferingDTO, String> countColumn;
    @FXML private TableColumn<TeacherOfferingDTO, String> statusColumn;
    @FXML private TableColumn<TeacherOfferingDTO, String> actionColumn;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private Label pageLabel;
    @FXML private Button previousPageButton;
    @FXML private Button nextPageButton;

    public TeacherOfferingController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherOfferingController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        bindColumn(nameColumn, 0);
        bindColumn(codeColumn, 1);
        bindColumn(creditColumn, 2);
        bindColumn(countColumn, 3);
        bindColumn(statusColumn, 4);
        if (actionColumn != null) actionColumn.setCellFactory(column -> detailsCell());
        if (termFilter != null) {
            termFilter.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, previous, next) ->
                            selectTerm(next == null ? -1 : next.intValue()));
        }
        if (searchField != null) {
            searchField.textProperty().addListener((observable, previous, next) -> {
                if (!syncingFilters) applyFilters(next);
            });
        }
        render();
    }

    /** 工作台切换到本页时调用；学期与搜索文本保持不变，只重新加载当前页。 */
    void activate() {
        active = true;
        if (!termsLoaded) {
            loadTerms();
            return;
        }
        loadPage(page);
    }

    /** 工作台离开本页时调用：保留界面状态，但不再接受任何在途响应。 */
    void unload() {
        active = false;
        listGeneration++;
    }

    @FXML
    void handleNextPage(Event event) {
        goToPage(page + 1);
    }

    @FXML
    void handlePreviousPage(Event event) {
        goToPage(page - 1);
    }

    @FXML
    void refresh() {
        if (!termsLoaded) {
            activate();
            return;
        }
        loadPage(page);
    }

    void applyFilters(String nextQuery) {
        query = nextQuery == null ? "" : nextQuery;
        syncingFilters = true;
        try {
            if (searchField != null && !query.equals(searchField.getText())) {
                searchField.setText(query);
            }
        } finally {
            syncingFilters = false;
        }
        goToPage(1);
    }

    /** 切换学期：重新从第一页加载，搜索文本保持。 */
    void selectTerm(int index) {
        if (syncingFilters || index < 0 || index >= terms.size()) return;
        CourseTermDTO next = terms.get(index);
        if (next == term) return;
        term = next;
        goToPage(1);
    }

    void goToPage(int nextPage) {
        loadPage(Math.max(1, nextPage));
    }

    /** 打开某个教学班的详情；由工作台接住并切换子页。 */
    void showOffering(TeacherOfferingDTO offering) {
        if (offering == null || offering.getOfferingId() == null) return;
        onShowOffering.accept(offering.getOfferingId());
    }

    void setOnShowOffering(Consumer<String> onShowOffering) {
        this.onShowOffering = onShowOffering == null ? offeringId -> { } : onShowOffering;
    }

    // ------------------------------------------------------------------ 加载

    private void loadTerms() {
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listTerms().whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(generation)) return;
            loading = false;
            if (failure != null) {
                errorText = LOAD_FAILURE_TEXT;
                render();
                return;
            }
            terms = List.copyOf(loaded == null ? List.of() : loaded);
            termsLoaded = true;
            term = terms.isEmpty() ? null : terms.get(0);
            page = 1;
            loadedPage = 1;
            renderTerms();
            if (term == null) {
                offerings = List.of();
                totalCount = 0;
                errorText = null;
                render();
                return;
            }
            loadPage(1);
        }));
    }

    private void loadPage(int nextPage) {
        if (term == null) {
            render();
            return;
        }
        page = Math.max(1, nextPage);
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listOfferings(term.getAcademicYear(), term.getSemester(), blankToNull(query), page,
                        PAGE_SIZE)
                .whenComplete((result, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrent(generation)) return;
                    loading = false;
                    if (failure != null) {
                        // 保留上一页已显示的数据与页码，只提示可以重试。
                        page = loadedPage;
                        errorText = LOAD_FAILURE_TEXT;
                        render();
                        return;
                    }
                    errorText = null;
                    offerings = List.copyOf(result == null ? List.of() : result.getItems());
                    totalCount = result == null ? 0 : result.getTotalCount();
                    loadedPage = page;
                    render();
                }));
    }

    /** 当前 generation 且页面仍处于激活状态时才允许写界面。 */
    private boolean isCurrent(long generation) {
        return active && generation == listGeneration;
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        boolean hasError = errorText != null;
        setActive(loadingLabel, loading);
        setActive(emptyLabel, !loading && !hasError && offerings.isEmpty());
        if (errorLabel != null) {
            errorLabel.setText(hasError ? errorText : "");
        }
        setActive(errorLabel, hasError);
        setActive(errorRetryButton, hasError);
        if (summaryLabel != null) summaryLabel.setText(summaryText());
        if (pageLabel != null) pageLabel.setText(pageText());
        if (previousPageButton != null) previousPageButton.setDisable(!hasPreviousPage());
        if (nextPageButton != null) nextPageButton.setDisable(!hasNextPage());
        if (offeringTable != null) offeringTable.getItems().setAll(offerings);
    }

    private void renderTerms() {
        if (termFilter == null) return;
        syncingFilters = true;
        try {
            termFilter.getItems().setAll(
                    terms.stream().map(CourseTermDTO::getDisplayName).toList());
            termFilter.getSelectionModel().select(term == null ? -1 : terms.indexOf(term));
        } finally {
            syncingFilters = false;
        }
    }

    private TableCell<TeacherOfferingDTO, String> detailsCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                // 每次更新都新建按钮：回收的空单元格不会留下上一行的按钮节点。
                setGraphic(empty ? null : createDetailsButton());
            }

            private Button createDetailsButton() {
                Button button = new Button(DETAIL_LABEL);
                button.getStyleClass().add("teacher-course-row-detail-button");
                button.setOnAction(event -> {
                    int row = getIndex();
                    List<TeacherOfferingDTO> items = getTableView().getItems();
                    if (row >= 0 && row < items.size()) showOffering(items.get(row));
                });
                return button;
            }
        };
    }

    private void bindColumn(TableColumn<TeacherOfferingDTO, String> column, int index) {
        if (column == null) return;
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue() == null ? "" : offeringCells(cell.getValue()).get(index)));
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------------ 纯文本

    /** 一行教学班的单元格文本，顺序与 FXML 的列顺序一一对应。 */
    static List<String> offeringCells(TeacherOfferingDTO offering) {
        return List.of(
                orDash(offering.getOfferingName()),
                orDash(offering.getCourseCode()),
                creditText(offering.getCredit()),
                offering.getEnrolledCount() + "/" + offering.getCapacity(),
                offeringStatusText(offering.getStatus()));
    }

    static String offeringStatusText(String status) {
        if (STATUS_NOT_OPEN.equals(status)) return "未开放";
        if (STATUS_OPEN.equals(status)) return "开放中";
        if (STATUS_STOPPED.equals(status)) return "已停止";
        if (STATUS_CANCELLED.equals(status)) return "已取消";
        return status == null ? "未知" : status;
    }

    static String creditText(double credit) {
        return String.format(Locale.ROOT, "%.1f", credit);
    }

    private String summaryText() {
        return "共 " + totalCount + " 个教学班";
    }

    private String pageText() {
        if (totalCount <= 0) return "共 0 条";
        return "第 " + page + "/" + totalPages() + " 页　共 " + totalCount + " 条";
    }

    // -------------------------------------------------------------- 测试访问器

    List<TeacherOfferingDTO> offerings() {
        return offerings;
    }

    CourseTermDTO term() {
        return term;
    }

    String query() {
        return query;
    }

    int page() {
        return page;
    }

    int totalPages() {
        return totalCount <= 0 ? 1 : (int) ((totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    long totalCount() {
        return totalCount;
    }

    boolean loading() {
        return loading;
    }

    boolean active() {
        return active;
    }

    String errorText() {
        return errorText;
    }

    boolean hasPreviousPage() {
        return !loading && page > 1;
    }

    boolean hasNextPage() {
        return !loading && page < totalPages();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
