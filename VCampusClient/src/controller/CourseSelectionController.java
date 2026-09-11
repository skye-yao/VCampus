package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.course.CourseMeetingView;
import model.course.CourseMutationResultView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseSelectionItemView;
import model.course.CourseTeacherView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.SelectionStatus;
import model.course.WaitlistDecision;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class CourseSelectionController {
    enum SelectionTab {
        ALL, PLAN, WAITLIST, ENROLLED
    }

    private final CourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private final Set<Long> pendingOfferingIds = new HashSet<>();
    private final Map<Long, CourseTermView> pendingOfferingTerms = new HashMap<>();
    private final Set<Long> reconciliationPendingOfferingIds = new HashSet<>();
    private final Set<Long> failedOfferingCourseIds = new HashSet<>();
    private final Map<Long, List<Consumer<Boolean>>> renderedOfferingActions =
            new HashMap<>();
    private final Map<Long, List<CourseOfferingView>> offeringCache = new HashMap<>();
    private final Map<Long, CompletableFuture<List<CourseOfferingView>>> offeringLoads =
            new HashMap<>();
    private List<CourseView> courses = Collections.emptyList();
    private CoursePlanSnapshotView snapshot;
    private CourseTermView currentTerm;
    private SelectionTab selectedTab = SelectionTab.ALL;
    private long termLoadGeneration;
    private long courseLoadGeneration;
    private long snapshotLoadGeneration;
    private long offeringCacheGeneration;

    @FXML private TextField searchField;
    @FXML private ComboBox<CourseTermView> termFilter;
    @FXML private ComboBox<String> typeFilter;
    @FXML private ToggleButton allTabButton;
    @FXML private ToggleButton planTabButton;
    @FXML private ToggleButton waitlistTabButton;
    @FXML private ToggleButton enrolledTabButton;
    @FXML private VBox courseList;
    @FXML private Label requiredCountLabel;
    @FXML private Label electiveCountLabel;
    @FXML private Label generalCountLabel;

    public CourseSelectionController() {
        this(CourseServices.current(), AlertUtil::showConfirm, AlertUtil::showError,
                CourseSelectionController::runOnFxThread);
    }

    CourseSelectionController(CourseService service,
            BiFunction<String, String, ButtonType> confirmation,
            BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor) {
        this.service = service;
        this.confirmation = confirmation;
        this.errorReporter = errorReporter;
        this.fxExecutor = fxExecutor;
    }

    @FXML
    public void initialize() {
        typeFilter.getItems().addAll("全部", "必修", "限选", "选修", "通选");
        typeFilter.setValue("全部");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> renderCourses()); // change listener
        typeFilter.valueProperty().addListener((observable, oldValue, newValue) -> renderCourses());
        termFilter.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(currentTerm)) {
                selectTerm(newValue);
            }
        });
        selectTab(SelectionTab.ALL);
        loadTerms();
    }

    @FXML
    public void refresh() {
        fxExecutor.accept(() -> {
            invalidateOfferingCache();
            loadCurrentTerm();
        });
    }

    List<CourseView> filterCourses(List<CourseView> source,
            String keyword, String courseType) {
        String normalizedKeyword = normalizeKeyword(keyword);
        boolean allTypes = isAllTypes(courseType);
        List<CourseView> filtered = new ArrayList<>();
        for (CourseView course : source) {
            boolean keywordMatches = normalizedKeyword.isEmpty()
                    || course.getCourseName().toLowerCase(Locale.ROOT)
                            .contains(normalizedKeyword)
                    || course.getCourseCode().toLowerCase(Locale.ROOT)
                            .contains(normalizedKeyword);
            boolean typeMatches = allTypes || courseType.equals(course.getCourseType());
            if (keywordMatches && typeMatches) filtered.add(course);
        }
        return List.copyOf(filtered);
    }

    List<CourseSelectionItemView> filterSelectionItems(
            CoursePlanSnapshotView source, SelectionTab tab,
            String keyword, String courseType) {
        if (source == null) return List.of();
        Map<Long, CourseSelectionItemView> candidates = new LinkedHashMap<>();
        if (tab == SelectionTab.PLAN) {
            addItems(candidates, source.getPlanItems());
            addItems(candidates, source.getWaitlistItems());
        } else if (tab == SelectionTab.WAITLIST) {
            addItems(candidates, source.getWaitlistItems());
        } else if (tab == SelectionTab.ENROLLED) {
            addItems(candidates, source.getEnrolledItems());
        }

        String normalizedKeyword = normalizeKeyword(keyword);
        boolean allTypes = isAllTypes(courseType);
        List<CourseSelectionItemView> filtered = new ArrayList<>();
        for (CourseSelectionItemView item : candidates.values()) {
            CourseView course = item.getCourse();
            boolean keywordMatches = normalizedKeyword.isEmpty()
                    || course.getCourseName().toLowerCase(Locale.ROOT)
                            .contains(normalizedKeyword)
                    || course.getCourseCode().toLowerCase(Locale.ROOT)
                            .contains(normalizedKeyword);
            boolean typeMatches = allTypes || courseType.equals(course.getCourseType());
            if (keywordMatches && typeMatches) filtered.add(item);
        }
        return List.copyOf(filtered);
    }

    void requestCourses(CourseTermView term, Consumer<List<CourseView>> onLoaded,
            Consumer<Throwable> onError) {
        long generation = ++courseLoadGeneration;
        service.loadCourses(term).whenComplete((loaded, error) ->
                fxExecutor.accept(() -> {
                    if (generation != courseLoadGeneration) return;
                    if (error != null) {
                        onError.accept(error);
                    } else {
                        onLoaded.accept(List.copyOf(loaded));
                    }
                }));
    }

    void requestCourseOfferings(CourseTermView term, long courseId,
            Consumer<List<CourseOfferingView>> onLoaded,
            Consumer<Throwable> onError) {
        List<CourseOfferingView> cached = offeringCache.get(courseId);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }

        long generation = offeringCacheGeneration;
        CompletableFuture<List<CourseOfferingView>> load = offeringLoads.get(courseId);
        if (load == null) {
            load = service.loadCourseOfferings(term, courseId);
            offeringLoads.put(courseId, load);
        }
        load.whenComplete((loaded, error) -> fxExecutor.accept(() -> {
            if (generation != offeringCacheGeneration) return;
            offeringLoads.remove(courseId);
            if (error != null) {
                failedOfferingCourseIds.add(courseId);
                onError.accept(error);
                return;
            }
            List<CourseOfferingView> immutable = List.copyOf(loaded);
            failedOfferingCourseIds.remove(courseId);
            offeringCache.put(courseId, immutable);
            onLoaded.accept(immutable);
        }));
    }

    boolean shouldRequestOfferings(long courseId, boolean expanded,
            boolean containerEmpty) {
        return expanded && (containerEmpty || failedOfferingCourseIds.remove(courseId));
    }

    void registerOfferingAction(long offeringId, Consumer<Boolean> setDisabled) {
        renderedOfferingActions
                .computeIfAbsent(offeringId, ignored -> new ArrayList<>())
                .add(setDisabled);
        setDisabled.accept(pendingOfferingIds.contains(offeringId));
    }

    void executeMutation(CourseTermView term, long offeringId, Runnable rerender,
            Function<String, CompletableFuture<CourseMutationResultView>> mutation) {
        if (!pendingOfferingIds.add(offeringId)) {
            updateOfferingActionState(offeringId);
            return;
        }
        pendingOfferingTerms.put(offeringId, term);
        updateOfferingActionState(offeringId);

        CompletableFuture<CourseMutationResultView> future;
        try {
            String operationId = UUID.randomUUID().toString();
            future = mutation.apply(operationId);
        } catch (RuntimeException error) {
            reconcileFailure(term, offeringId, rerender, error);
            return;
        }

        future.whenComplete((result, error) -> fxExecutor.accept(() -> {
            if (currentTerm != null && !term.equals(currentTerm)) {
                finishMutation(offeringId, rerender);
                return;
            }
            reconcileMutation(term, offeringId, rerender, error);
        }));
    }

    @FXML
    private void showAllCourses() {
        selectTab(SelectionTab.ALL);
    }

    @FXML
    private void showPlannedCourses() {
        selectTab(SelectionTab.PLAN);
    }

    @FXML
    private void showWaitlistedCourses() {
        selectTab(SelectionTab.WAITLIST);
    }

    @FXML
    private void showEnrolledCourses() {
        selectTab(SelectionTab.ENROLLED);
    }

    private void loadTerms() {
        long generation = ++termLoadGeneration;
        service.loadTerms().whenComplete((terms, error) -> fxExecutor.accept(() -> {
            if (generation != termLoadGeneration) return;
            if (error != null) {
                errorReporter.accept("加载失败", errorMessage(error));
                return;
            }
            termFilter.getItems().setAll(terms);
            if (terms.isEmpty()) {
                currentTerm = null;
                courses = List.of();
                snapshot = null;
                renderCourses();
            } else {
                termFilter.setValue(terms.get(0));
            }
        }));
    }

    private void selectTerm(CourseTermView term) {
        currentTerm = term;
        courses = List.of();
        snapshot = new CoursePlanSnapshotView(term, List.of(), List.of(), List.of());
        invalidateOfferingCache();
        loadCurrentTerm();
    }

    private void loadCurrentTerm() {
        if (currentTerm == null) return;
        CourseTermView term = currentTerm;
        requestCourses(term, loaded -> {
            if (!term.equals(currentTerm)) return;
            courses = loaded;
            updateSummary();
            renderCourses();
        }, error -> {
            courses = List.of();
            updateSummary();
            renderCourses();
            errorReporter.accept("课程加载失败", errorMessage(error));
        });
        requestSnapshot(term, loaded -> {
            acceptAuthoritativeSnapshot(term, loaded);
            renderCourses();
        }, error -> {
            if (snapshot == null || !term.equals(snapshot.getTerm())) {
                snapshot = new CoursePlanSnapshotView(term, List.of(), List.of(), List.of());
            }
            renderCourses();
            errorReporter.accept("选课状态加载失败", errorMessage(error));
        });
    }

    private void requestSnapshot(CourseTermView term,
            Consumer<CoursePlanSnapshotView> onLoaded,
            Consumer<Throwable> onError) {
        long generation = ++snapshotLoadGeneration;
        service.loadSelectionSnapshot(term).whenComplete((loaded, error) ->
                fxExecutor.accept(() -> {
                    if (generation != snapshotLoadGeneration) return;
                    if (error != null) onError.accept(error);
                    else onLoaded.accept(loaded);
                }));
    }

    private void selectTab(SelectionTab tab) {
        selectedTab = tab;
        if (allTabButton != null) allTabButton.setSelected(tab == SelectionTab.ALL);
        if (planTabButton != null) planTabButton.setSelected(tab == SelectionTab.PLAN);
        if (waitlistTabButton != null) {
            waitlistTabButton.setSelected(tab == SelectionTab.WAITLIST);
        }
        if (enrolledTabButton != null) {
            enrolledTabButton.setSelected(tab == SelectionTab.ENROLLED);
        }
        if (courseList != null) renderCourses();
    }

    private void renderCourses() {
        if (courseList == null || searchField == null || typeFilter == null) return;
        renderedOfferingActions.clear();
        courseList.getChildren().clear();

        if (selectedTab == SelectionTab.ALL) {
            List<CourseView> filtered = filterCourses(
                    courses, searchField.getText(), typeFilter.getValue());
            if (filtered.isEmpty()) {
                addEmptyState("没有符合条件的课程");
                return;
            }
            for (CourseView course : filtered) {
                courseList.getChildren().add(createCourseRow(course));
            }
            return;
        }

        List<CourseSelectionItemView> filtered = filterSelectionItems(
                snapshot, selectedTab, searchField.getText(), typeFilter.getValue());
        if (filtered.isEmpty()) {
            addEmptyState(emptyStateText());
            return;
        }
        for (CourseSelectionItemView item : filtered) {
            courseList.getChildren().add(createSelectionRow(item));
        }
    }

    private VBox createCourseRow(CourseView course) {
        VBox row = new VBox();
        row.getStyleClass().add("course-row");
        VBox offeringList = new VBox(6.0);
        offeringList.getStyleClass().add("course-offering-list");
        offeringList.setVisible(false);
        offeringList.setManaged(false);

        Button expandButton = new Button("+");
        expandButton.getStyleClass().add("course-expand-button");
        expandButton.setOnAction(event -> {
            boolean expanded = !offeringList.isVisible();
            offeringList.setVisible(expanded);
            offeringList.setManaged(expanded);
            expandButton.setText(expanded ? "-" : "+");
            if (shouldRequestOfferings(course.getCourseId(), expanded,
                    offeringList.getChildren().isEmpty())) {
                Label loading = styledLabel("正在加载教学班...", "course-empty-state");
                offeringList.getChildren().add(loading);
                requestCourseOfferings(currentTerm, course.getCourseId(), loaded -> {
                    offeringList.getChildren().clear();
                    if (loaded.isEmpty()) {
                        offeringList.getChildren().add(styledLabel(
                                "暂无可用教学班", "course-empty-state"));
                    } else {
                        for (CourseOfferingView offering : loaded) {
                            offeringList.getChildren().add(
                                    createOfferingRow(course, offering, true));
                        }
                    }
                }, error -> {
                    offeringList.getChildren().setAll(styledLabel(
                            "教学班加载失败，请重试", "course-empty-state"));
                    errorReporter.accept("教学班加载失败", errorMessage(error));
                });
            }
        });

        VBox titleBlock = new VBox(2.0,
                styledLabel(course.getCourseName(), "course-row-title"),
                styledLabel(course.getCourseCode(), "course-row-code"));
        titleBlock.getStyleClass().add("course-row-title-block");
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        HBox header = new HBox(10.0,
                expandButton, titleBlock,
                styledLabel(course.getCourseType(), "course-row-meta"),
                styledLabel(course.getCredit() + " 学分", "course-row-meta"),
                styledLabel(course.getCreditHours() + " 学时", "course-row-meta"));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("course-row-header");
        row.getChildren().addAll(header, offeringList);
        return row;
    }

    private VBox createSelectionRow(CourseSelectionItemView item) {
        VBox row = createOfferingRow(item.getCourse(), item.getOffering(), false);
        row.getStyleClass().add("course-selection-item");
        return row;
    }

    private VBox createOfferingRow(CourseView course, CourseOfferingView offering,
            boolean nested) {
        VBox row = new VBox(5.0);
        row.getStyleClass().add(nested ? "course-offering-row" : "course-row");

        VBox titleBlock = new VBox(1.0,
                styledLabel(course.getCourseName(), "course-row-title"),
                styledLabel(course.getCourseCode() + " · 教学班 "
                        + offering.getOfferingId(), "course-row-code"));
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        Label status = styledLabel(statusText(offering.getSelectionStatus()),
                statusStyle(offering.getSelectionStatus()));
        status.getStyleClass().add("course-status-label");
        HBox header = new HBox(9.0,
                titleBlock,
                styledLabel(teacherText(offering), "course-row-meta"),
                styledLabel(offering.getEnrolledCount() + "/"
                        + offering.getCapacity(), "course-capacity"),
                status);
        header.setAlignment(Pos.CENTER_LEFT);

        Label meeting = styledLabel(meetingText(offering), "course-detail-text");
        meeting.setWrapText(true);
        HBox actions = createActions(course, offering);
        VBox.setVgrow(actions, Priority.NEVER);
        row.getChildren().addAll(header, meeting, actions);
        return row;
    }

    private HBox createActions(CourseView course, CourseOfferingView offering) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(7.0);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("course-row-actions");
        actions.getChildren().add(spacer);

        SelectionStatus status = offering.getSelectionStatus();
        if (selectedTab == SelectionTab.ALL) {
            if (status == SelectionStatus.AVAILABLE) {
                actions.getChildren().add(actionButton(offering, "加入计划", false,
                        operationId -> service.addToPlan(
                                currentTerm, offering.getOfferingId(), operationId)));
            } else if (status == SelectionStatus.PLANNED
                    || status == SelectionStatus.FULL) {
                actions.getChildren().add(actionButton(offering, "移除计划", false,
                        operationId -> service.removeFromPlan(
                                currentTerm, offering.getOfferingId(), operationId)));
            } else {
                actions.getChildren().add(disabledAction(offering,
                        status == SelectionStatus.WAITLISTED ? "候补中"
                                : status == SelectionStatus.WAITLIST_OFFERED
                                        ? "待处理" : "已选"));
            }
            return actions;
        }

        if (selectedTab == SelectionTab.PLAN
                && (status == SelectionStatus.PLANNED || status == SelectionStatus.FULL)) {
            Button remove = actionButton(offering, "移除计划", false,
                    operationId -> service.removeFromPlan(
                            currentTerm, offering.getOfferingId(), operationId));
            remove.getStyleClass().add("course-row-action-secondary");
            actions.getChildren().add(remove);
        }

        if (status == SelectionStatus.PLANNED) {
            actions.getChildren().add(actionButton(offering, "选择", false,
                    operationId -> service.selectOffering(
                            currentTerm, offering.getOfferingId(), operationId)));
        } else if (status == SelectionStatus.FULL) {
            actions.getChildren().add(actionButton(offering, "候补", false,
                    operationId -> service.joinWaitlist(
                            currentTerm, offering.getOfferingId(), operationId)));
        } else if (status == SelectionStatus.WAITLISTED) {
            actions.getChildren().add(confirmAction(course, offering, "取消候补",
                    "确认取消“" + course.getCourseName() + "”的候补？",
                    operationId -> service.cancelWaitlist(
                            currentTerm, offering.getOfferingId(), operationId)));
        } else if (status == SelectionStatus.WAITLIST_OFFERED) {
            Button button = new Button("处理");
            button.getStyleClass().add("course-row-action");
            registerOfferingAction(offering.getOfferingId(), button::setDisable);
            button.setOnAction(event -> {
                ButtonType choice = confirmation.apply("处理候补席位",
                        "确定接受“" + course.getCourseName()
                                + "”的候补席位；取消则放弃该席位。");
                WaitlistDecision decision = waitlistDecision(choice);
                if (decision != null) {
                    executeMutation(currentTerm, offering.getOfferingId(),
                            this::renderCourses,
                            operationId -> service.resolveWaitlistOffer(
                                    currentTerm, offering.getOfferingId(), operationId,
                                    decision));
                }
            });
            actions.getChildren().add(button);
        } else if (status == SelectionStatus.ENROLLED) {
            actions.getChildren().add(confirmAction(course, offering, "退选",
                    "确认退选“" + course.getCourseName() + "”？",
                    operationId -> service.dropOffering(
                            currentTerm, offering.getOfferingId(), operationId)));
        }
        return actions;
    }

    private Button confirmAction(CourseView course, CourseOfferingView offering,
            String text, String message,
            Function<String, CompletableFuture<CourseMutationResultView>> mutation) {
        Button button = new Button(text);
        button.getStyleClass().add("course-row-action");
        registerOfferingAction(offering.getOfferingId(), button::setDisable);
        button.setOnAction(event -> {
            if (confirmation.apply(text, message) == ButtonType.OK) {
                executeMutation(currentTerm, offering.getOfferingId(),
                        this::renderCourses, mutation);
            }
        });
        return button;
    }

    private Button actionButton(CourseOfferingView offering, String text,
            boolean disabled,
            Function<String, CompletableFuture<CourseMutationResultView>> mutation) {
        Button button = new Button(text);
        button.getStyleClass().add("course-row-action");
        registerOfferingAction(offering.getOfferingId(), button::setDisable);
        if (disabled) button.setDisable(true);
        button.setOnAction(event -> executeMutation(
                currentTerm, offering.getOfferingId(), this::renderCourses, mutation));
        return button;
    }

    private Button disabledAction(CourseOfferingView offering, String text) {
        Button button = new Button(text);
        button.getStyleClass().add("course-row-action");
        button.setDisable(true);
        registerOfferingAction(offering.getOfferingId(), ignored -> button.setDisable(true));
        return button;
    }

    static WaitlistDecision waitlistDecision(ButtonType choice) {
        if (choice == ButtonType.OK) return WaitlistDecision.ACCEPT;
        if (choice == ButtonType.CANCEL) return WaitlistDecision.ABANDON;
        return null;
    }

    private void reconcileFailure(CourseTermView term, long offeringId,
            Runnable rerender, Throwable mutationError) {
        reconcileMutation(term, offeringId, rerender, mutationError);
    }

    private void reconcileMutation(CourseTermView term, long offeringId,
            Runnable rerender, Throwable mutationError) {
        reconciliationPendingOfferingIds.add(offeringId);
        requestSnapshot(term, loaded -> {
            acceptAuthoritativeSnapshot(term, loaded);
            invalidateOfferingCache();
            rerender.run();
            if (mutationError != null) {
                errorReporter.accept("操作失败", errorMessage(mutationError));
            }
        }, refreshError -> {
            invalidateOfferingCache();
            rerender.run();
            String message = mutationError == null
                    ? "操作结果尚未确认"
                    : errorMessage(mutationError);
            message += "；状态刷新失败：" + errorMessage(refreshError);
            errorReporter.accept("状态待确认", message);
        });
    }

    void acceptAuthoritativeSnapshot(CourseTermView term,
            CoursePlanSnapshotView loaded) {
        if (currentTerm == null || term.equals(currentTerm)) {
            snapshot = loaded;
        }
        List<Long> reconciled = new ArrayList<>();
        for (Long offeringId : reconciliationPendingOfferingIds) {
            if (term.equals(pendingOfferingTerms.get(offeringId))) {
                reconciled.add(offeringId);
            }
        }
        for (Long offeringId : reconciled) {
            finishMutation(offeringId, () -> { });
        }
    }

    private void finishMutation(long offeringId, Runnable rerender) {
        pendingOfferingIds.remove(offeringId);
        pendingOfferingTerms.remove(offeringId);
        reconciliationPendingOfferingIds.remove(offeringId);
        updateOfferingActionState(offeringId);
        rerender.run();
    }

    private void updateOfferingActionState(long offeringId) {
        boolean disabled = pendingOfferingIds.contains(offeringId);
        for (Consumer<Boolean> action :
                renderedOfferingActions.getOrDefault(offeringId, List.of())) {
            action.accept(disabled);
        }
    }

    private void invalidateOfferingCache() {
        offeringCache.clear();
        offeringLoads.clear();
        offeringCacheGeneration++;
    }

    private void updateSummary() {
        if (requiredCountLabel == null) return;
        long required = courses.stream()
                .filter(course -> "必修".equals(course.getCourseType())).count();
        long general = courses.stream()
                .filter(course -> "通选".equals(course.getCourseType())).count();
        long elective = courses.size() - required - general;
        requiredCountLabel.setText(required + " 门");
        electiveCountLabel.setText(elective + " 门");
        generalCountLabel.setText(general + " 门");
    }

    private void addEmptyState(String text) {
        Label emptyState = styledLabel(text, "course-empty-state");
        emptyState.setMaxWidth(Double.MAX_VALUE);
        courseList.getChildren().add(emptyState);
    }

    private String emptyStateText() {
        if (selectedTab == SelectionTab.PLAN) return "计划中暂无课程";
        if (selectedTab == SelectionTab.WAITLIST) return "候补中暂无课程";
        if (selectedTab == SelectionTab.ENROLLED) return "当前暂无已选课程";
        return "没有符合条件的课程";
    }

    private static void addItems(Map<Long, CourseSelectionItemView> target,
            List<CourseSelectionItemView> items) {
        for (CourseSelectionItemView item : items) {
            target.put(item.getOffering().getOfferingId(), item);
        }
    }

    private static String teacherText(CourseOfferingView offering) {
        List<String> names = new ArrayList<>();
        for (CourseTeacherView teacher : offering.getTeachers()) {
            names.add(teacher.getDisplayName());
        }
        return names.isEmpty() ? "教师待定" : String.join("、", names);
    }

    private static String meetingText(CourseOfferingView offering) {
        List<String> values = new ArrayList<>();
        for (CourseMeetingView meeting : offering.getMeetings()) {
            values.add("周" + chineseDay(meeting.getDayOfWeek()) + " "
                    + meeting.getStartPeriod() + "-" + meeting.getEndPeriod()
                    + "节 · " + meeting.getStartWeek() + "-"
                    + meeting.getEndWeek() + "周 · " + meeting.getLocation());
        }
        return values.isEmpty() ? "时间地点待定" : String.join("；", values);
    }

    private static String chineseDay(int day) {
        String[] values = {"", "一", "二", "三", "四", "五", "六", "日"};
        return day >= 1 && day <= 7 ? values[day] : String.valueOf(day);
    }

    private static String statusText(SelectionStatus status) {
        switch (status) {
            case AVAILABLE: return "可选";
            case PLANNED: return "计划中";
            case FULL: return "已满";
            case WAITLISTED: return "候补中";
            case WAITLIST_OFFERED: return "待处理";
            case ENROLLED: return "已选";
            default: throw new IllegalArgumentException("Unknown status: " + status);
        }
    }

    private static String statusStyle(SelectionStatus status) {
        return "course-status-" + status.name().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAllTypes(String courseType) {
        return courseType == null || courseType.isEmpty() || "全部".equals(courseType);
    }

    private static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
