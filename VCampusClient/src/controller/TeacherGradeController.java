package controller;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import dto.course.CourseTermDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
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
import model.course.teacher.GradeBookEditorModel;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 成绩录入的教学班列表页（设计 §5.4）：按学期列出本人任课的教学班、成绩状态与录入进度，
 * 点击一行打开该班的成绩编辑表。
 *
 * <p>列表数据直接绑定 {@link TeacherGradeOfferingDTO}，不新建平行的视图模型；学期与页码保存在
 * 控制器字段里，工作台来回切换子页时不重置，只重新加载当前页。页面被 {@link #unload()} 卸下后
 * 在途响应一律丢弃（generation + active 双重判定），因此过期结果不会覆盖新结果。
 *
 * <p>本页没有可编辑状态，因此不注册离开守卫：只有真正持有未保存内容的页面才需要拦截关闭。
 * 所有节点都可能为 {@code null}，控制器测试在无工具包、无 FXML 节点的环境下运行。
 */
public final class TeacherGradeController {
    /** 与教学班列表、名单一致的固定页长。 */
    static final int PAGE_SIZE = 20;
    static final String LOAD_FAILURE_TEXT = "成绩列表加载失败，请重试";
    static final String ENTRY_LABEL = "录入成绩";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private Consumer<String> onOpenGradeBook = offeringId -> { };
    private List<CourseTermDTO> terms = List.of();
    private CourseTermDTO term;
    private int page = 1;
    private int loadedPage = 1;
    private long totalCount;
    private List<TeacherGradeOfferingDTO> offerings = List.of();
    private boolean termsLoaded;
    private boolean loading;
    private boolean active;
    private boolean syncingFilters;
    private String errorText;
    private long listGeneration;

    @FXML private ComboBox<String> gradeTermFilter;
    @FXML private Button gradeRefreshButton;
    @FXML private Label gradeSummaryLabel;
    @FXML private TableView<TeacherGradeOfferingDTO> gradeOfferingTable;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeOfferingColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeCourseCodeColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeCountColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeStateColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeEnteredColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeMissingColumn;
    @FXML private TableColumn<TeacherGradeOfferingDTO, String> gradeActionColumn;
    @FXML private Label gradeLoadingLabel;
    @FXML private Label gradeEmptyLabel;
    @FXML private Label gradeErrorLabel;
    @FXML private Button gradeErrorRetryButton;
    @FXML private Label gradePageLabel;
    @FXML private Button gradePreviousPageButton;
    @FXML private Button gradeNextPageButton;

    public TeacherGradeController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherGradeController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        bindColumn(gradeOfferingColumn, 0);
        bindColumn(gradeCourseCodeColumn, 1);
        bindColumn(gradeCountColumn, 2);
        bindColumn(gradeStateColumn, 3);
        bindColumn(gradeEnteredColumn, 4);
        bindColumn(gradeMissingColumn, 5);
        if (gradeActionColumn != null) gradeActionColumn.setCellFactory(column -> entryCell());
        if (gradeTermFilter != null) {
            gradeTermFilter.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, previous, next) ->
                            selectTerm(next == null ? -1 : next.intValue()));
        }
        render();
    }

    /** 工作台切换到本页时调用；学期与页码保持不变，只重新加载当前页。 */
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
    void refresh(Event event) {
        refresh();
    }

    void refresh() {
        if (!termsLoaded) {
            activate();
            return;
        }
        loadPage(page);
    }

    /** 切换学期：重新从第一页加载。 */
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

    /** 打开某个教学班的成绩表；由工作台接住并切换子页。 */
    void openGradeBook(TeacherGradeOfferingDTO offering) {
        if (offering == null || offering.getOffering() == null
                || offering.getOffering().getOfferingId() == null) {
            return;
        }
        onOpenGradeBook.accept(offering.getOffering().getOfferingId());
    }

    void setOnOpenGradeBook(Consumer<String> onOpenGradeBook) {
        this.onOpenGradeBook = onOpenGradeBook == null ? offeringId -> { } : onOpenGradeBook;
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
        service.listGradeOfferings(term.getAcademicYear(), term.getSemester(), page, PAGE_SIZE)
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
        setActive(gradeLoadingLabel, loading);
        setActive(gradeEmptyLabel, !loading && !hasError && offerings.isEmpty());
        if (gradeErrorLabel != null) gradeErrorLabel.setText(hasError ? errorText : "");
        setActive(gradeErrorLabel, hasError);
        setActive(gradeErrorRetryButton, hasError);
        if (gradeSummaryLabel != null) gradeSummaryLabel.setText("共 " + totalCount + " 个教学班");
        if (gradePageLabel != null) gradePageLabel.setText(pageText());
        if (gradePreviousPageButton != null) gradePreviousPageButton.setDisable(!hasPreviousPage());
        if (gradeNextPageButton != null) gradeNextPageButton.setDisable(!hasNextPage());
        if (gradeOfferingTable != null) gradeOfferingTable.getItems().setAll(offerings);
    }

    private void renderTerms() {
        if (gradeTermFilter == null) return;
        syncingFilters = true;
        try {
            gradeTermFilter.getItems().setAll(
                    terms.stream().map(CourseTermDTO::getDisplayName).toList());
            gradeTermFilter.getSelectionModel().select(term == null ? -1 : terms.indexOf(term));
        } finally {
            syncingFilters = false;
        }
    }

    private TableCell<TeacherGradeOfferingDTO, String> entryCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                // 每次更新都新建按钮：回收的空单元格不会留下上一行的按钮节点。
                setGraphic(empty ? null : createEntryButton());
            }

            private Button createEntryButton() {
                Button button = new Button(ENTRY_LABEL);
                button.getStyleClass().add("teacher-course-row-detail-button");
                button.setOnAction(event -> {
                    int row = getIndex();
                    List<TeacherGradeOfferingDTO> items = getTableView().getItems();
                    if (row >= 0 && row < items.size()) openGradeBook(items.get(row));
                });
                return button;
            }
        };
    }

    private void bindColumn(TableColumn<TeacherGradeOfferingDTO, String> column, int index) {
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

    /** 一行教学班的单元格文本，顺序与 FXML 的列顺序一一对应（最后一列是操作按钮）。 */
    static List<String> offeringCells(TeacherGradeOfferingDTO offering) {
        TeacherOfferingDTO summary = offering.getOffering();
        return List.of(
                summary == null ? "—" : orDash(summary.getOfferingName()),
                summary == null ? "—" : orDash(summary.getCourseCode()),
                summary == null ? "—" : summary.getEnrolledCount() + "/" + summary.getCapacity(),
                stateText(offering.getState()),
                offering.getEnteredCount() + " 人",
                offering.getMissingCount() + " 人",
                ENTRY_LABEL);
    }

    /** 成绩状态与服务端同一套取值：DRAFT/PENDING/APPROVED/REJECTED。 */
    static String stateText(String state) {
        return GradeBookEditorModel.stateLabel(state);
    }

    private String pageText() {
        if (totalCount <= 0) return "共 0 条";
        return "第 " + page + "/" + totalPages() + " 页　共 " + totalCount + " 条";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    // -------------------------------------------------------------- 测试访问器

    List<TeacherGradeOfferingDTO> offerings() {
        return offerings;
    }

    CourseTermDTO term() {
        return term;
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
}
