# Administrator Course Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the administrator entry, `courseAdmin` protocol foundation, V004 schema, and complete course/offering create, read, update, archive, restore, cancel, and guarded-delete workflow.

**Architecture:** A role-aware main-menu route opens a separate JavaFX administrator shell. `SocketAdminCourseService` sends typed DTOs through the existing `SocketClient` to a dedicated `AdminCourseHandler`; focused catalog/offering services use optimistic versions, an administrator operation log, and transactional DAOs.

**Tech Stack:** Java 17+, JavaFX/FXML, Gson 2.13.2, JSON-line TCP `Message`, JDBC, MySQL 8, framework-free Java `main` tests, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-12-admin-course-management-design.md`

## Global Constraints

- Work only in `D:/JavaProject/VCampus/.worktrees/course-management-client` on `feature/course-management-client`.
- Preserve the pre-existing uncommitted GradeController, ScheduleController, ScheduleLayout, ScheduleEntryView, CourseQueryDAO, and seed-file changes.
- The administrator protocol module is exactly `courseAdmin`; the existing student `course` module remains compatible.
- IDs backed by BIGINT cross JSON as decimal strings; protocol times are ISO-8601 UTC strings.
- Every administrator write receives a UUID `operationId`; mutable course and offering requests also receive `expectedVersion`.
- Do not trust sender, uid, or role from request data; resolve administrator UID from the server Session token.
- Use direct `javac`/`java` commands; do not introduce Gradle or Maven.

---

### Task 1: Add the Complete V004 Schema Contract

**Files:**
- Create: `VCampusServer/src/resources/migrations/V004_admin_course_management.sql`
- Create: `VCampusServer/test/database/AdminCourseMigrationContractTest.java`
- Modify: `VCampusServer/test/database/CourseMigrationMySqlTest.java`
- Modify: `VCampusServer/test/integration/CourseModuleSocketEndToEndTest.java`

**Interfaces:**
- Consumes: V001 `course`, `course_offering`, `enrollment`, `grade`; V002 schedule tables; V003 notices.
- Produces: the exact V004 tables and columns from spec section 7 for every later plan.

- [ ] **Step 1: Write the failing static migration contract**

Create a framework-free test that reads V004 and checks all schema anchors:

```java
package database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AdminCourseMigrationContractTest {
    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(
                "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"),
                StandardCharsets.UTF_8);
        for (String token : List.of(
                "ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'",
                "ADD COLUMN `version` INT NOT NULL DEFAULT 1",
                "DROP CHECK `chk_course_offering_enrolled_count`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_arrangement`",
                "ADD COLUMN `arrangement_id` BIGINT",
                "ADD COLUMN `current_schedule_plan_id` BIGINT",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_request`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_target`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment`",
                "CREATE TABLE IF NOT EXISTS `grade_submission`",
                "CREATE TABLE IF NOT EXISTS `grade_submission_item`",
                "CREATE TABLE IF NOT EXISTS `admin_course_operation_log`")) {
            require(sql.contains(token), "missing V004 contract: " + token);
        }
        require(sql.contains("CHECK (`status` IN ('ACTIVE', 'ARCHIVED'))"),
                "course archive states required");
        require(sql.contains("CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED'))"),
                "approval states required");
        require(sql.contains("UNIQUE KEY `uk_active_adjustment_occurrence`"),
                "one effective adjustment per occurrence required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

- [ ] **Step 2: Run the contract and verify it fails because V004 is absent**

```powershell
$sources = Get-ChildItem VCampusServer/test/database/AdminCourseMigrationContractTest.java | ForEach-Object FullName
javac -encoding UTF-8 -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes database.AdminCourseMigrationContractTest
```

Expected: FAIL reading the missing V004 file.

- [ ] **Step 3: Create V004 in dependency order**

Implement these statements in this exact order:

```sql
ALTER TABLE `course`
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN `version` INT NOT NULL DEFAULT 1,
    ADD COLUMN `archived_by` VARCHAR(32) NULL,
    ADD COLUMN `archived_at` DATETIME(6) NULL,
    ADD CONSTRAINT `fk_course_archived_by` FOREIGN KEY (`archived_by`)
        REFERENCES `tbl_user` (`UID`) ON DELETE RESTRICT ON UPDATE CASCADE,
    ADD CONSTRAINT `chk_course_status` CHECK (`status` IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT `chk_course_version` CHECK (`version` > 0);

ALTER TABLE `course_offering`
    DROP CHECK `chk_course_offering_enrolled_count`,
    ADD COLUMN `version` INT NOT NULL DEFAULT 1,
    ADD COLUMN `created_by` VARCHAR(32) NULL,
    ADD COLUMN `cancelled_by` VARCHAR(32) NULL,
    ADD COLUMN `cancelled_at` DATETIME(6) NULL,
    ADD CONSTRAINT `chk_course_offering_enrolled_nonnegative` CHECK (`enrolled_count` >= 0);
```

Then add schedule-plan audit columns, `teaching_calendar.current_schedule_plan_id`, `course_schedule_arrangement`, nullable/backfilled/non-null `course_schedule_rule.arrangement_id`, the three adjustment tables, the two grade-submission tables, `course_notice.adjustment_request_id`, and `admin_course_operation_log` with every column, FK, index, check, and unique key specified in spec sections 7.2-7.6. Create referenced tables before adding notice/current-plan foreign keys. For legacy rule backfill, insert one arrangement per existing rule and map by `arrangement_id = rule.id`; nullable teacher/classroom values are permitted only for migrated legacy rows, while Service validation rejects publishing incomplete active arrangements.

Enforce one active adjustment per original occurrence without blocking cancelled history by adding this generated key to `course_schedule_adjustment`:

```sql
`active_original_occurrence_id` BIGINT GENERATED ALWAYS AS
    (CASE WHEN `status` = 'ACTIVE' THEN `original_occurrence_id` ELSE NULL END) STORED,
UNIQUE KEY `uk_active_adjustment_occurrence` (`active_original_occurrence_id`)
```

- [ ] **Step 4: Make live migration tests apply V004**

Add immediately after V003 in both reset helpers:

```java
applyScript(connection, root.resolve(
        "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
```

Extend the MySQL test to assert course/offering version defaults, the relaxed over-capacity constraint, all new tables, and the arrangement FK.

- [ ] **Step 5: Run static and live-safe checks**

```powershell
java -cp .codex-tmp/classes database.AdminCourseMigrationContractTest
git diff --check -- VCampusServer/src/resources/migrations/V004_admin_course_management.sql VCampusServer/test/database/AdminCourseMigrationContractTest.java
```

Expected: contract passes. Run the live MySQL test only against the guarded `virtual_campus_course_test` database; if MySQL is unavailable, record it as unverified.

- [ ] **Step 6: Commit the schema foundation**

```powershell
git add VCampusServer/src/resources/migrations/V004_admin_course_management.sql VCampusServer/test/database/AdminCourseMigrationContractTest.java VCampusServer/test/database/CourseMigrationMySqlTest.java VCampusServer/test/integration/CourseModuleSocketEndToEndTest.java
git commit -m "feat: add administrator course schema"
```

### Task 2: Add Catalog and Operation DTOs

**Files:**
- Create: `VCampusCommon/src/dto/course/admin/AdminCourseActions.java`
- Create: `VCampusCommon/src/dto/course/admin/catalog/AdminCourseDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/catalog/AdminOfferingDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/catalog/CourseEditorRequestDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/catalog/OfferingEditorRequestDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/ScheduleConflictDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/ScheduleConflictSeverityDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/result/AdminOperationResultDTO.java`
- Create: `VCampusCommon/test/dto/course/admin/AdminCatalogDtoJsonTest.java`

**Interfaces:**
- Produces: immutable DTOs with defensive list copies and exact action constants.

- [ ] **Step 1: Write the failing DTO round-trip test**

Test BIGINT preservation with `"9007199254740993"`, nullable assistant UID, version preservation, defensive conflict lists, and every action from spec section 6.2. Define the complete action set now so later plans never mutate this protocol registry.

- [ ] **Step 2: Compile and confirm the DTO test fails**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test/dto/course/admin -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar" -d .codex-tmp/classes $sources
```

Expected: missing administrator DTO packages.

- [ ] **Step 3: Implement exact catalog contracts**

Use these constructor fields:

```java
AdminCourseDTO(String courseId, String courseCode, String courseName,
        String courseType, double credit, int creditHours, String description,
        String prerequisites, boolean allowCrossMajor, boolean finalExam,
        String status, int offeringCount, int version)

AdminOfferingDTO(String offeringId, String offeringCode, String courseId,
        int academicYear, int semester, int capacity, int enrolledCount,
        String status, String teacherUid, String teacherName,
        String assistantUid, String assistantName, String scheduleStatus, int version)

CourseEditorRequestDTO(String operationId, String courseId, int expectedVersion,
        String courseCode, String courseName, String courseType, double credit,
        int creditHours, String description, String prerequisites,
        boolean allowCrossMajor, boolean finalExam)

OfferingEditorRequestDTO(String operationId, String offeringId, int expectedVersion,
        String courseId, String offeringCode, int academicYear, int semester,
        int capacity, String teacherUid, String assistantUid, int status)

ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
        String subjectId, String relatedOfferingId, int week,
        int dayOfWeek, int startPeriod, int endPeriod, String message)

AdminOperationResultDTO<T>(String operationId, String outcomeCode, String message,
        T entity, List<ScheduleConflictDTO> conflicts)
```

Do not expose mutable lists. Validate domain values in Services, not DTO constructors, so Gson can deserialize consistently.

- [ ] **Step 4: Run DTO tests and commit**

```powershell
java -cp ".codex-tmp/classes;VCampusClient/lib/gson-2.13.2.jar" dto.course.admin.AdminCatalogDtoJsonTest
git add VCampusCommon/src/dto/course/admin VCampusCommon/test/dto/course/admin
git commit -m "feat: define administrator course contracts"
```

### Task 3: Implement Transactional Catalog and Offering Services

**Files:**
- Create: `VCampusServer/src/dao/AdminCourseOperationDAO.java`
- Create: `VCampusServer/src/dao/AdminCourseCatalogDAO.java`
- Create: `VCampusServer/src/dao/AdminOfferingDAO.java`
- Create: `VCampusServer/src/service/AdminCourseCatalogService.java`
- Create: `VCampusServer/src/service/AdminOfferingService.java`
- Create: `VCampusServer/test/service/AdminCourseCatalogMySqlTest.java`
- Create: `VCampusServer/test/service/AdminOfferingMySqlTest.java`

**Interfaces:**
- Produces: list/create/update/archive/restore courses and list/create/update/cancel/delete offerings.

- [ ] **Step 1: Write guarded MySQL tests first**

Tests must refuse databases other than `virtual_campus_course_test`, then verify:

```java
AdminCourseDTO created = catalog.create(adminUid,
        new CourseEditorRequestDTO(operationId, null, 0, "CS999", "测试课程",
                "选修", 2.0, 32, "说明", "无"));
require(created.getVersion() == 1, "new course version");
AdminCourseDTO updated = catalog.update(adminUid,
        requestWithName(created, "测试课程二", UUID.randomUUID().toString()));
require(updated.getVersion() == 2, "update increments version");
requireConflict(() -> catalog.update(adminUid, staleRequest(created)));
require(catalog.archive(adminUid, created.getCourseId(), 2,
        UUID.randomUUID().toString()).getStatus().equals("ARCHIVED"), "archive");
```

Offering tests cover immutable course/term after enrollment, cancellation instead of delete, empty draft physical delete, and replaying the same operationId without a second row/count change.

- [ ] **Step 2: Run and confirm missing services fail compilation**

Compile Common and the two new tests with `VCampusServer/lib/*`.

- [ ] **Step 3: Implement focused DAOs**

`AdminCourseCatalogDAO` owns catalog SQL and optimistic updates. `AdminOfferingDAO` owns offering/staff SQL and association-existence checks. `AdminCourseOperationDAO` mirrors `CourseOperationDAO` digest/replay behavior but stores the administrator request and conflict snapshots in `admin_course_operation_log`.

Required update shape:

```sql
UPDATE course
SET course_name=?, credit=?, credit_hours=?, course_type=?, description=?,
    prerequisites=?, version=version+1
WHERE course_id=? AND version=? AND status='ACTIVE'
```

If affected rows are zero, distinguish missing row from stale version/status and throw a Service `ConflictException` with the latest entity.

- [ ] **Step 4: Implement service transactions**

Each mutation opens a DBUtil connection, sets READ_COMMITTED and autoCommit false, checks/replays operationId, locks the aggregate, applies rules, writes the operation result, commits, rolls back on every failure, and restores autoCommit. Course archive rejects active offerings. Offering delete rejects any schedule, enrollment, grade, request, or operation dependency; cancellation updates status to 4.

- [ ] **Step 5: Run MySQL tests**

```powershell
java -cp ".codex-tmp/classes;VCampusServer/lib/*" service.AdminCourseCatalogMySqlTest
java -cp ".codex-tmp/classes;VCampusServer/lib/*" service.AdminOfferingMySqlTest
```

Expected: both pass on the guarded test database.

- [ ] **Step 6: Commit server catalog services**

```powershell
git add VCampusServer/src/dao/AdminCourseOperationDAO.java VCampusServer/src/dao/AdminCourseCatalogDAO.java VCampusServer/src/dao/AdminOfferingDAO.java VCampusServer/src/service/AdminCourseCatalogService.java VCampusServer/src/service/AdminOfferingService.java VCampusServer/test/service/AdminCourseCatalogMySqlTest.java VCampusServer/test/service/AdminOfferingMySqlTest.java
git commit -m "feat: manage courses and offerings transactionally"
```

### Task 4: Route and Authorize `courseAdmin`

**Files:**
- Create: `VCampusServer/src/handler/AdminCourseHandler.java`
- Modify: `VCampusServer/src/network/MessageDispatcher.java`
- Modify: `VCampusServer/src/main/ServerMain.java`
- Create: `VCampusServer/test/handler/AdminCourseHandlerTest.java`
- Modify: `VCampusServer/test/handler/CourseHandlerTest.java`

**Interfaces:**
- Consumes: Task 2 DTOs and Task 3 Services.
- Produces: authenticated `courseAdmin` query/mutation responses.

- [ ] **Step 1: Write the Handler permission and routing test**

Create sessions for administrator, teacher, and student. Assert missing/invalid token is UNAUTHORIZED, teacher/student are FORBIDDEN, spoofed sender/data UID is ignored, administrator UID reaches the fake Service, unknown actions are BAD_REQUEST, and MessageDispatcher routes `courseAdmin` while preserving request UID.

- [ ] **Step 2: Verify the test fails before routing exists**

Run the new test and expect unknown-module BAD_REQUEST.

- [ ] **Step 3: Implement Handler and dispatcher injection**

Add constructors that preserve existing tests:

```java
public MessageDispatcher() {
    this(new CourseHandler(), new AdminCourseHandler());
}

public MessageDispatcher(CourseHandler courseHandler) {
    this(courseHandler, new AdminCourseHandler());
}

public MessageDispatcher(CourseHandler courseHandler,
        AdminCourseHandler adminCourseHandler) { /* assign non-null defaults */ }
```

Route `courseAdmin` case-insensitively. `AdminCourseHandler` uses typed Gson conversion for request payloads and maps IllegalArgumentException, NotFoundException, ConflictException, and DatabaseException to existing MessageCode values without leaking SQL.

- [ ] **Step 4: Run handler regression tests and commit**

```powershell
java -cp ".codex-tmp/classes;VCampusServer/lib/*" handler.AdminCourseHandlerTest
java -cp ".codex-tmp/classes;VCampusServer/lib/*" handler.CourseHandlerTest
java -cp ".codex-tmp/classes;VCampusServer/lib/*" handler.CourseMutationHandlerTest
git add VCampusServer/src/handler/AdminCourseHandler.java VCampusServer/src/network/MessageDispatcher.java VCampusServer/src/main/ServerMain.java VCampusServer/test/handler/AdminCourseHandlerTest.java VCampusServer/test/handler/CourseHandlerTest.java
git commit -m "feat: route administrator course requests"
```

### Task 5: Add the Administrator Client Service

**Files:**
- Create: `VCampusClient/src/model/course/admin/AdminCourseView.java`
- Create: `VCampusClient/src/model/course/admin/AdminOfferingView.java`
- Create: `VCampusClient/src/model/course/admin/AdminOperationResultView.java`
- Create: `VCampusClient/src/service/AdminCourseService.java`
- Create: `VCampusClient/src/service/AdminCourseServices.java`
- Create: `VCampusClient/src/service/AdminCourseTransport.java`
- Create: `VCampusClient/src/service/SocketAdminCourseTransport.java`
- Create: `VCampusClient/src/service/SocketAdminCourseService.java`
- Create: `VCampusClient/src/service/MockAdminCourseService.java`
- Create: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`
- Create: `VCampusClient/test/service/MockAdminCourseServiceTest.java`

**Interfaces:**
- Produces: CompletableFuture catalog/offering methods matching all Task 2 actions.

- [ ] **Step 1: Define and test the interface**

```java
public interface AdminCourseService {
    CompletableFuture<List<AdminCourseView>> listCourses(String query, String status);
    CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(CourseEditorRequestDTO request);
    CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(CourseEditorRequestDTO request);
    CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId);
    CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId);
    CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId);
    CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(OfferingEditorRequestDTO request);
    CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(OfferingEditorRequestDTO request);
    CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
            String offeringId, int expectedVersion, String operationId);
    CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
            String offeringId, int expectedVersion, String operationId);
}
```

Fake-transport tests assert module/action/token and map a 9007199254740993 ID exactly. Mock tests assert deterministic create/update/archive/restore and offering restrictions.

- [ ] **Step 2: Implement transport and mapping**

`SocketAdminCourseTransport.send()` delegates to `SocketClient.getInstance().sendAsync()`. `SocketAdminCourseService` puts one typed DTO under `request`, attaches the current ClientSession token, maps `result` through Gson, and converts every callback without touching JavaFX controls.

- [ ] **Step 3: Run tests and commit**

```powershell
java -cp ".codex-tmp/classes;VCampusClient/lib/gson-2.13.2.jar" service.SocketAdminCourseServiceTest
java -cp .codex-tmp/classes service.MockAdminCourseServiceTest
git add VCampusClient/src/model/course/admin VCampusClient/src/service/AdminCourseService.java VCampusClient/src/service/AdminCourseServices.java VCampusClient/src/service/AdminCourseTransport.java VCampusClient/src/service/SocketAdminCourseTransport.java VCampusClient/src/service/SocketAdminCourseService.java VCampusClient/src/service/MockAdminCourseService.java VCampusClient/test/service/SocketAdminCourseServiceTest.java VCampusClient/test/service/MockAdminCourseServiceTest.java
git commit -m "feat: add administrator course client service"
```

### Task 6: Build the Administrator Shell and Catalog UI

**Files:**
- Modify: `VCampusClient/src/controller/MainController.java`
- Modify: `VCampusClient/src/resources/fxml/MainView.fxml`
- Create: `VCampusClient/src/controller/AdminCourseManagementController.java`
- Create: `VCampusClient/src/controller/AdminCourseCatalogController.java`
- Create: `VCampusClient/src/controller/CourseEditorDialogController.java`
- Create: `VCampusClient/src/controller/OfferingEditorDialogController.java`
- Create: `VCampusClient/src/resources/fxml/AdminCourseManagementView.fxml`
- Create: `VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml`
- Create: `VCampusClient/src/resources/fxml/CourseEditorDialog.fxml`
- Create: `VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml`
- Modify: `VCampusClient/src/resources/css/style.css`
- Create: `VCampusClient/test/controller/AdminCourseCatalogControllerTest.java`
- Create: `VCampusClient/test/controller/MainControllerRoleRoutingTest.java`
- Create: `VCampusClient/test/ui/AdminCourseUiPreview.java`
- Create: `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`

**Interfaces:**
- Consumes: Task 5 AdminCourseService.
- Produces: role-based entry and complete course/offering UI.

- [ ] **Step 1: Write controller tests for routing and stale responses**

Use an injected fake Service and `Runnable::run` FX executor. Verify query/status preservation, older list generations ignored, write buttons re-enabled after failure, and version conflicts trigger refresh rather than local overwrite. `MainControllerRoleRoutingTest` separately verifies student, administrator and teacher routing without opening a real Stage.

- [ ] **Step 2: Implement role-aware entry**

Give the existing course-card title an `fx:id`. In MainController, set its text from `ClientSession.getRole()` and route exactly:

```java
if ("管理员".equals(ClientSession.getInstance().getRole())) {
    ClientMain.switchScene("/resources/fxml/AdminCourseManagementView.fxml");
} else if ("学生".equals(ClientSession.getInstance().getRole())) {
    ClientMain.switchScene("/resources/fxml/CourseManagementView.fxml");
} else {
    AlertUtil.showInfo("系统提示", "教师端教务功能暂未开放");
}
```

- [ ] **Step 3: Build FXML skeletons and dynamic rows**

FXML owns stable toolbar, navigation, list containers, editor fields, loading and empty labels. `AdminCourseCatalogController` dynamically creates course rows and expandable offering rows. Use a MenuButton for low-frequency delete actions. Add course-admin-prefixed CSS only.

- [ ] **Step 4: Wire safe mutations**

Generate a new UUID per distinct write intent; reuse it only for transport retry of the identical request. Require confirmation for archive, cancel and physical delete. After success reload the authoritative list; after 409 show “数据已被其他管理员修改” and refresh.

- [ ] **Step 5: Run UI verification**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
Get-ChildItem VCampusClient/src/resources/fxml -Filter *.fxml | ForEach-Object { [xml](Get-Content -Raw -LiteralPath $_.FullName) | Out-Null }
java --module-path VCampusClient/lib --add-modules javafx.controls,javafx.fxml,javafx.swing -cp ".codex-tmp/classes;VCampusClient/src;VCampusClient/lib/gson-2.13.2.jar" ui.AdminCourseUiSmokeTest
```

Inspect generated 860 x 580 screenshots for clipped columns, scroll behavior, expanded offerings, dialogs, destructive confirmations and empty/error states.

- [ ] **Step 6: Commit the UI**

```powershell
git add VCampusClient/src/controller/MainController.java VCampusClient/src/resources/fxml/MainView.fxml VCampusClient/src/controller/AdminCourseManagementController.java VCampusClient/src/controller/AdminCourseCatalogController.java VCampusClient/src/controller/CourseEditorDialogController.java VCampusClient/src/controller/OfferingEditorDialogController.java VCampusClient/src/resources/fxml/AdminCourseManagementView.fxml VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml VCampusClient/src/resources/fxml/CourseEditorDialog.fxml VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml VCampusClient/src/resources/css/style.css VCampusClient/test/controller/AdminCourseCatalogControllerTest.java VCampusClient/test/controller/MainControllerRoleRoutingTest.java VCampusClient/test/ui/AdminCourseUiPreview.java VCampusClient/test/ui/AdminCourseUiSmokeTest.java
git commit -m "feat: build administrator course catalog UI"
```

### Task 7: Verify the Catalog Vertical Slice

**Files:**
- Create: `VCampusServer/test/integration/AdminCourseCatalogSocketEndToEndTest.java`
- Modify: `VCampusServer/src/resources/seed-course-test.sql`

**Interfaces:**
- Consumes: Tasks 1-6.
- Produces: authenticated administrator catalog proof over real TCP/MySQL.

- [ ] **Step 1: Add an isolated administrator fixture and E2E test**

Seed an ADMIN user in the guarded test database. Connect through a real socket, authenticate, create/update/archive/restore one course, create/update/cancel one offering, replay one operationId, and assert a student token receives FORBIDDEN for the same module.

- [ ] **Step 2: Run all relevant regression checks**

Run DTO, Handler, catalog/offering MySQL, new E2E, existing `CourseModuleSocketEndToEndTest`, client service, controller and FXML smoke tests. Run `git diff --check` and verify `git status --short` contains no unexpected files.

- [ ] **Step 3: Commit verification support**

```powershell
git add VCampusServer/test/integration/AdminCourseCatalogSocketEndToEndTest.java VCampusServer/src/resources/seed-course-test.sql
git commit -m "test: verify administrator catalog end to end"
```
