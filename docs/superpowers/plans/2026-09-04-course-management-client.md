# Course Management Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build a complete student-facing JavaFX course management demo covering course selection, timetable, grades, and training plan.

**Architecture:** A shared CourseManagementView shell owns top-level navigation and includes four focused FXML subviews. Controllers depend on an asynchronous CourseService contract; MockCourseService is the only implementation in this iteration and owns all mutable demo state.

**Tech Stack:** Java 25, JavaFX FXML/Controls, CSS, CompletableFuture, framework-free Java assertion tests

**Spec:** docs/superpowers/specs/2026-09-04-course-management-client-design.md

## Global Constraints

- Keep the existing fixed 860 x 580 application window.
- Implement only the student client; do not modify VCampusServer or database migrations.
- Demo state resets when the client process exits.
- Every new CSS selector starts with course-.
- Use #587558, #fdd000, and #151E49 as the main green, gold, and navy colors.
- Keep CourseService asynchronous so a future SocketCourseService can replace the mock without rewriting controllers.
- All JavaFX node updates occur on the JavaFX Application Thread.
- Backend eligibility and concurrency rules remain explicitly out of scope.

---

### Task 1: Immutable Course Display Models

**Files:**
- Create: VCampusClient/src/model/course/SelectionStatus.java
- Create: VCampusClient/src/model/course/CourseOfferingView.java
- Create: VCampusClient/src/model/course/ScheduleEntryView.java
- Create: VCampusClient/src/model/course/CourseNoticeView.java
- Create: VCampusClient/src/model/course/GradeRecordView.java
- Create: VCampusClient/src/model/course/GradeSummaryView.java
- Create: VCampusClient/src/model/course/TrainingPlanCourseView.java
- Create: VCampusClient/src/model/course/TrainingPlanGroupView.java
- Test: VCampusClient/test/model/course/CourseModelTest.java

**Interfaces:**
- Consumes: Java collections only.
- Produces: Immutable model classes with constructor-initialized fields and getters; CourseOfferingView.withSelectionStatus(SelectionStatus).

- [ ] **Step 1: Write the failing model test**

~~~java
package model.course;

public final class CourseModelTest {
    public static void main(String[] args) {
        CourseOfferingView original = new CourseOfferingView(
                1001L, "CS203", "数据结构", "必修", 4.0, 64,
                "张老师", "周二 3-4节", "教四-201",
                "线性表、树和图", "程序设计基础",
                96, 120, SelectionStatus.AVAILABLE);
        CourseOfferingView planned = original.withSelectionStatus(SelectionStatus.PLANNED);
        require(original.getSelectionStatus() == SelectionStatus.AVAILABLE, "original must stay immutable");
        require(planned.getSelectionStatus() == SelectionStatus.PLANNED, "copy must contain new status");
        require(planned.getOfferingId() == 1001L, "copy must preserve identity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

- [ ] **Step 2: Compile the test and verify it fails**

Run in PowerShell:

~~~powershell
$sources = Get-ChildItem VCampusClient/test/model/course -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
~~~

Expected: compilation fails because CourseOfferingView and SelectionStatus do not exist.

- [ ] **Step 3: Implement the immutable models**

Use these exact enum values:

~~~java
public enum SelectionStatus {
    AVAILABLE, PLANNED, WAITLISTED, ENROLLED
}
~~~

Use these exact constructor signatures and getter names:

~~~java
CourseOfferingView(long offeringId, String courseCode, String courseName,
        String courseType, double credit, int creditHours, String teacher,
        String schedule, String location, String description,
        String prerequisites, int enrolledCount, int capacity,
        SelectionStatus selectionStatus)

ScheduleEntryView(long offeringId, String term, String courseCode,
        String courseName, String teacher, String location, int dayOfWeek,
        int startPeriod, int periodCount, int startWeek, int endWeek)

CourseNoticeView(String term, int week, String title, String content)

GradeRecordView(String term, String courseCode, String courseName,
        double credit, double score, double gradePoint, Double dailyScore,
        Double midtermScore, Double experimentScore, Double finalScore)

GradeSummaryView(String term, double termGpa, double termAverage,
        double cumulativeAverage, double cumulativeGpa,
        List<GradeRecordView> records)

TrainingPlanCourseView(String courseCode, String courseName,
        double credit, String completionStatus)

TrainingPlanGroupView(String name, double requiredCredits,
        double earnedCredits, List<TrainingPlanCourseView> courses)
~~~

Each getter is named get plus the field name in UpperCamelCase, for example getOfferingId(), getTermGpa(), and getCourses(). CourseOfferingView.withSelectionStatus(SelectionStatus) returns a new instance. ScheduleEntryView adds isActiveInWeek(int week). GradeSummaryView and TrainingPlanGroupView store unmodifiable defensive copies of their lists.

- [ ] **Step 4: Compile and run the model test**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/test/model/course -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes model.course.CourseModelTest
~~~

Expected: process exits with code 0 and no assertion error.

- [ ] **Step 5: Commit the model layer**

~~~powershell
git add VCampusClient/src/model/course VCampusClient/test/model/course
git commit -m "feat: add course client view models"
~~~

### Task 2: Mock Course Service State Machine

**Files:**
- Create: VCampusClient/src/service/CourseService.java
- Create: VCampusClient/src/service/MockCourseService.java
- Create: VCampusClient/src/service/CourseServices.java
- Test: VCampusClient/test/service/MockCourseServiceTest.java

**Interfaces:**
- Consumes: model.course display models from Task 1.
- Produces: CourseService asynchronous API and CourseServices.current().

- [ ] **Step 1: Write the failing service test**

~~~java
package service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import model.course.CourseOfferingView;
import model.course.SelectionStatus;

public final class MockCourseServiceTest {
    public static void main(String[] args) throws Exception {
        testInitialStates();
        testConfirmPlanRoutesByCapacity();
        testIllegalTransition();
        testReturnedListIsUnmodifiable();
    }

    private static void testInitialStates() throws Exception {
        MockCourseService service = new MockCourseService();
        List<CourseOfferingView> courses = service.loadOfferings().get();
        require(courses.size() == 6, "six deterministic courses expected");
        require(statusOf(courses, 1002L) == SelectionStatus.PLANNED, "OS starts in plan");
        require(statusOf(courses, 1003L) == SelectionStatus.WAITLISTED, "HCI starts waitlisted");
    }

    private static void testConfirmPlanRoutesByCapacity() throws Exception {
        MockCourseService service = new MockCourseService();
        service.addToPlan(1001L).get();
        service.confirmPlan().get();
        List<CourseOfferingView> courses = service.loadOfferings().get();
        require(statusOf(courses, 1001L) == SelectionStatus.ENROLLED, "course with space enrolls");
        require(statusOf(courses, 1002L) == SelectionStatus.WAITLISTED, "full course waitlists");
    }

    private static void testIllegalTransition() throws Exception {
        MockCourseService service = new MockCourseService();
        try {
            service.dropCourse(1001L).get();
            throw new AssertionError("dropping an available course must fail");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof IllegalStateException, "state error expected");
        }
    }

    private static void testReturnedListIsUnmodifiable() throws Exception {
        List<CourseOfferingView> courses = new MockCourseService().loadOfferings().get();
        try {
            courses.clear();
            throw new AssertionError("service result must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static SelectionStatus statusOf(List<CourseOfferingView> courses, long id) {
        return courses.stream().filter(c -> c.getOfferingId() == id).findFirst().orElseThrow().getSelectionStatus();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

- [ ] **Step 2: Compile and verify the test fails**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
~~~

Expected: compilation fails because CourseService and MockCourseService do not exist.

- [ ] **Step 3: Define the service contract**

~~~java
public interface CourseService {
    CompletableFuture<List<CourseOfferingView>> loadOfferings();
    CompletableFuture<CourseOfferingView> addToPlan(long offeringId);
    CompletableFuture<CourseOfferingView> removeFromPlan(long offeringId);
    CompletableFuture<List<CourseOfferingView>> confirmPlan();
    CompletableFuture<CourseOfferingView> joinWaitlist(long offeringId);
    CompletableFuture<CourseOfferingView> leaveWaitlist(long offeringId);
    CompletableFuture<CourseOfferingView> dropCourse(long offeringId);
    CompletableFuture<List<ScheduleEntryView>> loadSchedule(String term, int week);
    CompletableFuture<List<CourseNoticeView>> loadNotices(String term, int week);
    CompletableFuture<GradeSummaryView> loadGrades(String term);
    CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
}
~~~

- [ ] **Step 4: Implement deterministic data and transitions**

Create six offerings with IDs and initial states:

~~~text
1001 数据结构             AVAILABLE   96/120
1002 操作系统             PLANNED    100/100
1003 人机交互             WAITLISTED  60/60
1004 音乐鉴赏             AVAILABLE   47/80
1005 离散数学             ENROLLED    86/120
1006 计算机网络           ENROLLED    79/100
~~~

All mutating methods are synchronized, validate the current state, replace the immutable offering in a LinkedHashMap, and return completed futures. Invalid transitions return futures completed exceptionally with IllegalStateException. confirmPlan moves every PLANNED course to ENROLLED when enrolledCount is less than capacity, otherwise to WAITLISTED.

CourseServices uses:

~~~java
private static final CourseService CURRENT = new MockCourseService();

public static CourseService current() {
    return CURRENT;
}
~~~

- [ ] **Step 5: Compile and run the service test**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseServiceTest
~~~

Expected: process exits with code 0.

- [ ] **Step 6: Commit the service layer**

~~~powershell
git add VCampusClient/src/service VCampusClient/test/service
git commit -m "feat: add mock course service"
~~~

### Task 3: Course Module Shell and Main Navigation

**Files:**
- Create: VCampusClient/src/resources/fxml/CourseManagementView.fxml
- Create: VCampusClient/src/controller/CourseManagementController.java
- Create: VCampusClient/src/resources/fxml/CourseSelectionView.fxml
- Create: VCampusClient/src/resources/fxml/ScheduleView.fxml
- Create: VCampusClient/src/resources/fxml/GradeView.fxml
- Create: VCampusClient/src/resources/fxml/TrainingPlanView.fxml
- Create: VCampusClient/src/controller/CourseSelectionController.java
- Create: VCampusClient/src/controller/ScheduleController.java
- Create: VCampusClient/src/controller/GradeController.java
- Create: VCampusClient/src/controller/TrainingPlanController.java
- Modify: VCampusClient/src/controller/MainController.java
- Modify: VCampusClient/src/resources/css/style.css

**Interfaces:**
- Consumes: ClientMain.switchScene(String).
- Produces: a shell that exposes four included content nodes and refreshes their controllers.

- [ ] **Step 1: Change the main menu route**

Replace the current notice in openCourseSelection with:

~~~java
ClientMain.switchScene("/resources/fxml/CourseManagementView.fxml");
~~~

- [ ] **Step 2: Create the shell FXML**

Use a BorderPane with a 64px top bar. The top bar contains a back Button, the title, a spacer, and four ToggleButtons with exact fx:id values:

~~~xml
<ToggleButton fx:id="selectionNavButton" onAction="#showSelection" selected="true" text="选课"/>
<ToggleButton fx:id="scheduleNavButton" onAction="#showSchedule" text="课表"/>
<ToggleButton fx:id="gradeNavButton" onAction="#showGrades" text="成绩"/>
<ToggleButton fx:id="planNavButton" onAction="#showTrainingPlan" text="培养方案"/>
~~~

The center StackPane includes CourseSelectionView.fxml, ScheduleView.fxml, GradeView.fxml, and TrainingPlanView.fxml using fx:id values selectionPage, schedulePage, gradePage, and planPage. Only selectionPage starts visible and managed.

- [ ] **Step 3: Create loadable child-page skeletons**

Each child FXML initially contains a StackPane with its final fx:controller and one Label naming the page. Each child controller initially has this complete public contract:

~~~java
public final class CourseSelectionController {
    public void refresh() {
    }
}
~~~

Create the equivalent ScheduleController, GradeController, and TrainingPlanController. These are valid incremental page skeletons, not test-only files; Tasks 4-7 replace their empty content with the final implementations.

- [ ] **Step 4: Implement shell navigation**

CourseManagementController injects each included node, its generated child-controller field such as selectionPageController, and each navigation button. Each handler calls one helper:

~~~java
private void activate(Node page, ToggleButton button, Runnable refreshAction) {
    setPageState(selectionPage, page == selectionPage);
    setPageState(schedulePage, page == schedulePage);
    setPageState(gradePage, page == gradePage);
    setPageState(planPage, page == planPage);
    selectionNavButton.setSelected(button == selectionNavButton);
    scheduleNavButton.setSelected(button == scheduleNavButton);
    gradeNavButton.setSelected(button == gradeNavButton);
    planNavButton.setSelected(button == planNavButton);
    refreshAction.run();
}
~~~

handleBack calls ClientMain.switchScene("/resources/fxml/MainView.fxml").

- [ ] **Step 5: Add shell CSS**

Add course-shell, course-topbar, course-title, course-nav-button, course-nav-button:selected, and course-content styles. The selected nav uses a 3px #fdd000 bottom border and #fef9df background.

- [ ] **Step 6: Compile production sources**

~~~powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
~~~

Expected: compilation succeeds with the four loadable child-page skeletons.

- [ ] **Step 7: Commit shell navigation**

~~~powershell
git add VCampusClient/src/resources/fxml/CourseManagementView.fxml VCampusClient/src/resources/fxml/CourseSelectionView.fxml VCampusClient/src/resources/fxml/ScheduleView.fxml VCampusClient/src/resources/fxml/GradeView.fxml VCampusClient/src/resources/fxml/TrainingPlanView.fxml VCampusClient/src/controller/CourseManagementController.java VCampusClient/src/controller/CourseSelectionController.java VCampusClient/src/controller/ScheduleController.java VCampusClient/src/controller/GradeController.java VCampusClient/src/controller/TrainingPlanController.java VCampusClient/src/controller/MainController.java VCampusClient/src/resources/css/style.css
git commit -m "feat: add course management shell"
~~~

### Task 4: Course Selection Page

**Files:**
- Modify: VCampusClient/src/resources/fxml/CourseSelectionView.fxml
- Modify: VCampusClient/src/controller/CourseSelectionController.java
- Modify: VCampusClient/src/resources/css/style.css
- Test: VCampusClient/test/controller/CourseSelectionControllerTest.java

**Interfaces:**
- Consumes: CourseServices.current(), CourseOfferingView, SelectionStatus.
- Produces: public void refresh() for the shell.

- [ ] **Step 1: Write a controller filtering test**

Expose one package-private pure method:

~~~java
List<CourseOfferingView> filterCourses(
        List<CourseOfferingView> source,
        SelectionStatus status,
        String keyword,
        String courseType)
~~~

Create this test in package controller:

~~~java
package controller;

import java.util.List;
import model.course.CourseOfferingView;
import model.course.SelectionStatus;
import service.MockCourseService;

public final class CourseSelectionControllerTest {
    public static void main(String[] args) throws Exception {
        CourseSelectionController controller = new CourseSelectionController();
        List<CourseOfferingView> source = new MockCourseService().loadOfferings().get();
        require(controller.filterCourses(source, null, "数据", "全部").size() == 1,
                "keyword must match 数据结构 only");
        require(controller.filterCourses(source, null, "", "必修").stream()
                        .allMatch(course -> "必修".equals(course.getCourseType())),
                "type filter must contain required courses only");
        List<CourseOfferingView> planned =
                controller.filterCourses(source, SelectionStatus.PLANNED, "", "全部");
        require(planned.size() == 1 && "操作系统".equals(planned.get(0).getCourseName()),
                "planned tab must contain 操作系统 only");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

- [ ] **Step 2: Run the test and verify it fails**

~~~powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test/controller -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
~~~

Expected: compilation fails because filterCourses is missing.

- [ ] **Step 3: Create the selection FXML**

Use a BorderPane. The top contains a TextField searchField, ComboBox typeFilter, refresh Button, and four ToggleButtons. The center contains a ScrollPane whose content is VBox courseList. The right contains counts requiredCountLabel, electiveCountLabel, generalCountLabel. Add confirmPlanButton to the PLAN tab action area.

- [ ] **Step 4: Implement loading, filtering, and course rows**

refresh calls loadOfferings and keeps the selected tab and filter values. renderCourses builds one bounded course-row VBox per offering. The collapsed header is an HBox; the expandable detail VBox is managed only while visible. Action buttons call the exact CourseService transition matching the current SelectionStatus.

Use these state labels:

~~~java
AVAILABLE -> "可选"
PLANNED -> "计划中"
WAITLISTED -> "候补中"
ENROLLED -> "已选"
~~~

Use AlertUtil.showConfirm before confirmPlan, leaveWaitlist, and dropCourse, and continue only when it returns ButtonType.OK. Disable the action button until the future completes, then call refresh.

- [ ] **Step 5: Add selection CSS**

Add course-toolbar, course-subnav-button, course-row, course-row-header, course-row-details, course-status-available, course-status-planned, course-status-waitlisted, course-status-enrolled, course-summary-rail, and course-empty-state. Keep row radius at 6px.

- [ ] **Step 6: Run filtering and service tests**

~~~powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test/model/course,VCampusClient/test/service,VCampusClient/test/controller -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
java --module-path VCampusClient/lib --add-modules javafx.controls,javafx.fxml -cp ".codex-tmp/classes;VCampusClient/lib/gson-2.13.2.jar" controller.CourseSelectionControllerTest
java -cp .codex-tmp/classes service.MockCourseServiceTest
~~~

Expected: both CourseSelectionControllerTest and MockCourseServiceTest exit with code 0.

- [ ] **Step 7: Commit the selection page**

~~~powershell
git add VCampusClient/src/resources/fxml/CourseSelectionView.fxml VCampusClient/src/controller/CourseSelectionController.java VCampusClient/src/resources/css/style.css VCampusClient/test/controller/CourseSelectionControllerTest.java
git commit -m "feat: build student course selection view"
~~~

### Task 5: Weekly Schedule Page

**Files:**
- Modify: VCampusClient/src/resources/fxml/ScheduleView.fxml
- Modify: VCampusClient/src/controller/ScheduleController.java
- Modify: VCampusClient/src/resources/css/style.css
- Test: VCampusClient/test/service/MockCourseScheduleTest.java

**Interfaces:**
- Consumes: CourseService.loadSchedule(term, week) and loadNotices(term, week).
- Produces: public void refresh().

- [ ] **Step 1: Write the failing schedule test**

~~~java
package service;

import java.util.List;
import model.course.CourseNoticeView;
import model.course.ScheduleEntryView;

public final class MockCourseScheduleTest {
    public static void main(String[] args) throws Exception {
        MockCourseService service = new MockCourseService();
        List<ScheduleEntryView> initial = service.loadSchedule("2026-2027 秋学期", 3).get();
        require(hasOffering(initial, 1005L) && hasOffering(initial, 1006L),
                "initial enrolled courses must appear");
        service.addToPlan(1001L).get();
        service.confirmPlan().get();
        require(hasOffering(service.loadSchedule("2026-2027 秋学期", 3).get(), 1001L),
                "new enrollment must appear");
        service.dropCourse(1005L).get();
        require(!hasOffering(service.loadSchedule("2026-2027 秋学期", 3).get(), 1005L),
                "dropped course must disappear");
        List<CourseNoticeView> notices =
                service.loadNotices("2026-2027 秋学期", 13).get();
        require(notices.stream().anyMatch(n -> n.getTitle().contains("数据结构")),
                "week 13 adjustment notice expected");
    }

    private static boolean hasOffering(List<ScheduleEntryView> entries, long id) {
        return entries.stream().anyMatch(entry -> entry.getOfferingId() == id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

- [ ] **Step 2: Run the test and verify it fails**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseScheduleTest
~~~

Expected: the test fails because schedule templates and notices are not populated.

- [ ] **Step 3: Implement schedule data in MockCourseService**

Store schedule templates keyed by offeringId. loadSchedule filters templates by term, week range, and current ENROLLED state. loadNotices filters by term and week.

- [ ] **Step 4: Create FXML and controller**

ScheduleView uses a top filter HBox, a center GridPane scheduleGrid, and a right VBox noticeList. The grid has one time-label column and five weekday columns. Controller rebuilds rows for periods 1-10, adds course blocks using dayOfWeek/startPeriod/periodCount, and uses GridPane.setRowSpan. Clicking a course block opens an information alert with the course details.

- [ ] **Step 5: Add schedule CSS**

Add course-schedule-grid, course-schedule-header, course-period-label, course-class-block, course-class-title, course-class-meta, course-notice-rail, and course-notice-item.

- [ ] **Step 6: Run schedule and full service tests**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseScheduleTest
java -cp .codex-tmp/classes service.MockCourseServiceTest
~~~

Expected: MockCourseScheduleTest and MockCourseServiceTest both pass.

- [ ] **Step 7: Commit the schedule page**

~~~powershell
git add VCampusClient/src/resources/fxml/ScheduleView.fxml VCampusClient/src/controller/ScheduleController.java VCampusClient/src/service/MockCourseService.java VCampusClient/src/resources/css/style.css VCampusClient/test/service/MockCourseScheduleTest.java
git commit -m "feat: add weekly course schedule"
~~~

### Task 6: Grade Query Page

**Files:**
- Modify: VCampusClient/src/resources/fxml/GradeView.fxml
- Modify: VCampusClient/src/controller/GradeController.java
- Modify: VCampusClient/src/resources/css/style.css
- Test: VCampusClient/test/service/MockCourseGradeTest.java

**Interfaces:**
- Consumes: CourseService.loadGrades(term).
- Produces: public void refresh().

- [ ] **Step 1: Write the failing grade test**

~~~java
package service;

import model.course.GradeRecordView;
import model.course.GradeSummaryView;

public final class MockCourseGradeTest {
    public static void main(String[] args) throws Exception {
        GradeSummaryView summary =
                new MockCourseService().loadGrades("2026-2027 秋学期").get();
        require(summary.getRecords().size() == 2, "two grade records expected");
        require(summary.getTermGpa() == 3.85, "term GPA must be deterministic");
        require(summary.getTermAverage() == 91.5, "term average must be deterministic");
        GradeRecordView record = summary.getRecords().stream()
                .filter(item -> item.getExperimentScore() == null)
                .findFirst().orElseThrow();
        require(record.getExperimentScore() == null, "missing component remains null");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

The UI snapshot in Task 8 verifies that the controller renders the null component as "--".

- [ ] **Step 2: Run the test and verify it fails**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseGradeTest
~~~

Expected: the test fails because deterministic grade data is not populated.

- [ ] **Step 3: Add deterministic grade data**

Return records for 程序设计基础 and 高等数学 with complete total scores and nullable component scores. Unknown terms return a zero-valued summary with an empty records list.

- [ ] **Step 4: Create FXML and controller**

GradeView contains the term ComboBox, four metric labels, a TableView with course/code/credit/score/GPA columns, and a fixed-width details VBox. Selecting a row updates daily, midterm, experiment, final, total, and GPA labels. An empty term shows the standard course-empty-state.

- [ ] **Step 5: Add grade CSS**

Add course-metrics, course-metric, course-metric-value, course-grade-table, course-grade-detail, and course-score-value.

- [ ] **Step 6: Run grade and compile checks**

~~~powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseGradeTest
~~~

Expected: MockCourseGradeTest passes and all production Java sources compile.

- [ ] **Step 7: Commit the grade page**

~~~powershell
git add VCampusClient/src/resources/fxml/GradeView.fxml VCampusClient/src/controller/GradeController.java VCampusClient/src/service/MockCourseService.java VCampusClient/src/resources/css/style.css VCampusClient/test/service/MockCourseGradeTest.java
git commit -m "feat: add student grade query view"
~~~

### Task 7: Training Plan Page

**Files:**
- Modify: VCampusClient/src/resources/fxml/TrainingPlanView.fxml
- Modify: VCampusClient/src/controller/TrainingPlanController.java
- Modify: VCampusClient/src/resources/css/style.css
- Test: VCampusClient/test/service/MockTrainingPlanTest.java

**Interfaces:**
- Consumes: CourseService.loadTrainingPlan().
- Produces: public void refresh().

- [ ] **Step 1: Write the failing training-plan test**

~~~java
package service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import model.course.TrainingPlanGroupView;

public final class MockTrainingPlanTest {
    public static void main(String[] args) throws Exception {
        List<TrainingPlanGroupView> groups =
                new MockCourseService().loadTrainingPlan().get();
        Set<String> names = groups.stream()
                .map(TrainingPlanGroupView::getName)
                .collect(Collectors.toSet());
        require(groups.size() == 4, "four plan groups expected");
        require(names.containsAll(Set.of("必修课程", "限选课程", "选修课程", "通选课程")),
                "all plan categories expected");
        require(groups.stream().allMatch(group ->
                        group.getEarnedCredits() <= group.getRequiredCredits()),
                "earned credits cannot exceed required credits");
        require(groups.stream().allMatch(group ->
                        group.getEarnedCredits() == group.getCourses().stream()
                                .filter(course -> "已修".equals(course.getCompletionStatus()))
                                .mapToDouble(course -> course.getCredit()).sum()),
                "group totals must equal completed course credits");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
~~~

- [ ] **Step 2: Run the test and verify it fails**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockTrainingPlanTest
~~~

Expected: the test fails because mock training-plan data is absent.

- [ ] **Step 3: Add deterministic training-plan data**

Each group contains at least two TrainingPlanCourseView rows with code, name, credit, and one of 已修, 在修, 未修. Group earned credits equal the sum of 已修 course credits.

- [ ] **Step 4: Create FXML and controller**

TrainingPlanView contains overall earned/required labels, a ProgressBar, and a ScrollPane with VBox planGroupList. Controller renders one flat group section with a heading, group progress, and course rows. It does not expose edit controls.

- [ ] **Step 5: Add training-plan CSS**

Add course-plan-summary, course-progress-bar, course-plan-group, course-plan-group-title, course-plan-row, course-plan-complete, course-plan-current, and course-plan-pending.

- [ ] **Step 6: Run training-plan and full service tests**

~~~powershell
$sources = Get-ChildItem VCampusClient/src/model/course,VCampusClient/src/service,VCampusClient/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes service.MockCourseServiceTest
java -cp .codex-tmp/classes service.MockCourseScheduleTest
java -cp .codex-tmp/classes service.MockCourseGradeTest
java -cp .codex-tmp/classes service.MockTrainingPlanTest
~~~

Expected: MockTrainingPlanTest and every earlier service test pass.

- [ ] **Step 7: Commit the training-plan page**

~~~powershell
git add VCampusClient/src/resources/fxml/TrainingPlanView.fxml VCampusClient/src/controller/TrainingPlanController.java VCampusClient/src/service/MockCourseService.java VCampusClient/src/resources/css/style.css VCampusClient/test/service/MockTrainingPlanTest.java
git commit -m "feat: add student training plan view"
~~~

### Task 8: FXML Smoke Test and Visual Verification

**Files:**
- Create: VCampusClient/test/ui/CourseUiSmokeTest.java
- Modify: VCampusClient/src/resources/fxml/CourseManagementView.fxml
- Modify: VCampusClient/src/resources/css/style.css

**Interfaces:**
- Consumes: complete shell, four subviews, all controllers, and local JavaFX runtime.
- Produces: load verification and PNG snapshots under .codex-tmp/course-ui-snapshots.

- [ ] **Step 1: Write the FXML smoke test**

~~~java
package ui;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class CourseUiSmokeTest extends Application {
    private static final String[] FILES = {
            "selection.png", "schedule.png", "grades.png", "training-plan.png"
    };
    private static final String[] NAV_IDS = {
            null, "#scheduleNavButton", "#gradeNavButton", "#planNavButton"
    };
    private Parent root;
    private int pageIndex;

    @Override
    public void start(Stage stage) throws Exception {
        root = FXMLLoader.load(getClass().getResource(
                "/resources/fxml/CourseManagementView.fxml"));
        stage.setScene(new Scene(root, 860, 580));
        stage.setResizable(false);
        stage.show();
        captureAfterPulse();
    }

    private void captureAfterPulse() {
        PauseTransition pause = new PauseTransition(Duration.millis(180));
        pause.setOnFinished(event -> {
            try {
                Path output = Path.of(".codex-tmp", "course-ui-snapshots");
                Files.createDirectories(output);
                WritableImage image = root.snapshot(null, null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        output.resolve(FILES[pageIndex]).toFile());
                pageIndex++;
                if (pageIndex == FILES.length) {
                    Platform.exit();
                    return;
                }
                ((ButtonBase) root.lookup(NAV_IDS[pageIndex])).fire();
                captureAfterPulse();
            } catch (Exception exception) {
                exception.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
        pause.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
~~~

Any missing FXML field or handler fails the process before snapshots complete.

- [ ] **Step 2: Compile all production and test sources**

~~~powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
~~~

Expected: compilation succeeds with no errors.

- [ ] **Step 3: Run all framework-free tests**

~~~powershell
java -cp .codex-tmp/classes model.course.CourseModelTest
java -cp .codex-tmp/classes service.MockCourseServiceTest
java -cp .codex-tmp/classes service.MockCourseScheduleTest
java -cp .codex-tmp/classes service.MockCourseGradeTest
java -cp .codex-tmp/classes service.MockTrainingPlanTest
~~~

Expected: every process exits with code 0.

- [ ] **Step 4: Parse all FXML as XML**

~~~powershell
Get-ChildItem VCampusClient/src/resources/fxml -Filter *.fxml | ForEach-Object { [xml](Get-Content -Raw -LiteralPath $_.FullName) | Out-Null }
~~~

Expected: command exits with code 0.

- [ ] **Step 5: Run the JavaFX smoke test**

~~~powershell
java --module-path VCampusClient/lib --add-modules javafx.controls,javafx.fxml,javafx.swing -cp ".codex-tmp/classes;VCampusClient/src;VCampusClient/lib/gson-2.13.2.jar" ui.CourseUiSmokeTest
~~~

Expected: four PNG files are created under .codex-tmp/course-ui-snapshots and the process exits.

- [ ] **Step 6: Inspect and polish every snapshot**

Inspect each PNG at original resolution. Fix all clipped text, overlapping controls, inconsistent spacing, unreadable status colors, missing styles, and scroll regions that exceed 860 x 580. Re-run Steps 2-5 after every visual change.

- [ ] **Step 7: Verify the final diff**

~~~powershell
git diff --check
git status --short
~~~

Expected: no whitespace errors; only planned client files and pre-existing user changes are present.

- [ ] **Step 8: Commit verification support and final polish**

~~~powershell
git add VCampusClient/test/ui/CourseUiSmokeTest.java VCampusClient/src/resources/fxml/CourseManagementView.fxml VCampusClient/src/resources/css/style.css
git commit -m "test: verify course management client UI"
~~~
