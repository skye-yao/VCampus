# Administrator Course Scheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let administrators edit multi-slot teaching arrangements in draft plans, inspect conflicts, force overridable warnings with a reason, and publish an authoritative schedule safely.

**Architecture:** `course_schedule_arrangement` is the UI and service aggregate; existing rules, weeks, occurrences, and bookings remain its normalized execution records. A centralized conflict service evaluates both previews and final transactions, while publication atomically advances the teaching calendar's current plan pointer.

**Tech Stack:** Java, JavaFX/FXML, Gson, TCP Message protocol, JDBC/MySQL 8, framework-free tests, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-12-admin-course-management-design.md`

## Global Constraints

- Complete `2026-09-12-admin-course-catalog.md` first.
- Only DRAFT plans are editable; published plans and their rules/occurrences are immutable.
- One arrangement owns one teacher, optional assistant, one classroom, one shared contiguous week range, and one or more slots.
- Same-offering overlap and invalid calendar/period ranges are BLOCKING; teacher, assistant, classroom, and classroom-capacity conflicts are OVERRIDABLE.
- A preview never authorizes a write; every mutation recalculates conflicts inside its transaction.
- Preserve existing student schedule queries and precomputed `course_offering_conflict` semantics.

---

### Task 1: Define Scheduling DTO and Client Interfaces

**Files:**
- Create: `VCampusCommon/src/dto/course/admin/schedule/ScheduleSlotDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/ScheduleResourceDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/ScheduleArrangementDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/SchedulePlanDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/schedule/SaveArrangementRequestDTO.java`
- Create: `VCampusCommon/test/dto/course/admin/AdminScheduleDtoJsonTest.java`
- Create: `VCampusClient/src/model/course/admin/ScheduleArrangementView.java`
- Create: `VCampusClient/src/model/course/admin/SchedulePlanView.java`
- Modify: `VCampusClient/src/service/AdminCourseService.java`

**Interfaces:**
- Produces: exact schedule request/response shapes used by all later tasks.

- [ ] **Step 1: Write failing JSON and interface tests**

Round-trip two slots and weeks 5-10, nullable assistant, decimal-string IDs, version, BLOCKING/OVERRIDABLE severity, and defensive lists.

- [ ] **Step 2: Implement immutable DTOs with these signatures**

```java
ScheduleSlotDTO(int dayOfWeek, int startPeriod, int endPeriod)
ScheduleResourceDTO(String resourceId, String businessId, String name,
        String resourceType, int capacity)
ScheduleArrangementDTO(String arrangementId, String planId, String offeringId,
        ScheduleResourceDTO teacher, ScheduleResourceDTO assistant,
        ScheduleResourceDTO classroom, List<ScheduleSlotDTO> slots,
        int startWeek, int endWeek, String status, int version)
SchedulePlanDTO(String planId, String name, int revision, String status,
        boolean current, List<ScheduleConflictDTO> conflicts)
SaveArrangementRequestDTO(String operationId, String arrangementId,
        int expectedVersion, String planId, String offeringId,
        String teacherUid, String assistantUid, String classroomId,
        List<ScheduleSlotDTO> slots, int startWeek, int endWeek,
        boolean force, String overrideReason)
```

Add these methods:

```java
CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(String type, String query);
CompletableFuture<SchedulePlanDTO> loadSchedulePlan(int academicYear, int semester);
CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
        String planId, String offeringId);
CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
        SaveArrangementRequestDTO request);
CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
        SaveArrangementRequestDTO request);
CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
        String arrangementId, int expectedVersion, String operationId);
CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
        String planId, int expectedRevision, String operationId,
        boolean force, String overrideReason);
```

- [ ] **Step 3: Run DTO tests and commit**

Compile Common/Client sources, run `AdminScheduleDtoJsonTest`, then commit the listed files with `feat: define administrator scheduling contracts`.

### Task 2: Build Arrangement Persistence and Conflict Detection

**Files:**
- Create: `VCampusServer/src/dao/AdminScheduleDAO.java`
- Create: `VCampusServer/src/dao/AdminScheduleConflictDAO.java`
- Create: `VCampusServer/src/service/CourseConflictService.java`
- Create: `VCampusServer/src/service/ScheduleManagementService.java`
- Create: `VCampusServer/test/service/CourseConflictMySqlTest.java`
- Create: `VCampusServer/test/service/ScheduleManagementMySqlTest.java`

**Interfaces:**
- Consumes: V004 arrangement schema and scheduling DTOs.
- Produces: resource queries, arrangement snapshots, preview, save/delete, and publish services.

- [ ] **Step 1: Write conflict fixtures and failing tests**

Create one fixture for each conflict type. Assert:

```java
require(type(result, "OFFERING_OVERLAP").getSeverity() == BLOCKING, "self overlap");
require(type(result, "TEACHER_OVERLAP").getSeverity() == OVERRIDABLE, "teacher");
require(type(result, "ASSISTANT_OVERLAP").getSeverity() == OVERRIDABLE, "assistant");
require(type(result, "CLASSROOM_OVERLAP").getSeverity() == OVERRIDABLE, "room");
require(type(result, "CLASSROOM_CAPACITY").getSeverity() == OVERRIDABLE, "capacity");
```

Also test boundary-touching slots do not overlap, editing excludes the arrangement's own occurrences, and an ACTIVE temporary adjustment replaces its original occurrence for effective conflict checks.

- [ ] **Step 2: Implement focused SQL reads**

`AdminScheduleDAO` owns plans, resources, arrangements, rules, weeks, occurrence generation and bookings. `AdminScheduleConflictDAO` returns overlapping effective occurrences using `start_at < candidate_end AND end_at > candidate_start`, excluding replaced originals and including ACTIVE adjustments.

- [ ] **Step 3: Implement structural validation before SQL writes**

Reject empty slots, weekday outside 1-7, start/end outside the teaching day's period definitions, start greater than end, weeks outside the teaching calendar, duplicate slots, missing/archived offering, missing people/classroom, and overlapping slots within the same arrangement.

- [ ] **Step 4: Implement `saveArrangement` transaction**

Follow spec section 8.3 exactly. Expand each slot across the shared week range, derive UTC timestamps from teaching calendar dates and period definitions, replace only draft aggregate rows, recreate bookings, recalculate conflicts, reject BLOCKING always, reject OVERRIDABLE unless force and nonblank reason, store response in `admin_course_operation_log`, then commit.

- [ ] **Step 5: Implement delete and publish**

Delete only from DRAFT; rely on cascade within the locked aggregate. Publication locks plan and calendar, verifies revision, performs a full-plan check, sets READY only after successful validation, then sets PUBLISHED and updates `current_schedule_plan_id` in the same transaction. Reject switching while an OPEN selection window points elsewhere.

- [ ] **Step 6: Run guarded MySQL tests and commit**

```powershell
java -cp ".codex-tmp/classes;VCampusServer/lib/*" service.CourseConflictMySqlTest
java -cp ".codex-tmp/classes;VCampusServer/lib/*" service.ScheduleManagementMySqlTest
```

Commit with `feat: manage draft course schedules`.

### Task 3: Expose Scheduling Actions Over TCP

**Files:**
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java`
- Create: `VCampusServer/test/handler/AdminScheduleHandlerTest.java`
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java`
- Modify: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`
- Create: `VCampusClient/test/service/MockAdminScheduleServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: every schedule action from AdminCourseActions.

- [ ] **Step 1: Write failing Handler and fake-transport cases**

Assert exact response keys: `resources`, `plan`, `arrangements`, `conflicts`, and `result`. Verify malformed decimal IDs, invalid UUID, empty force reason, missing token and non-admin role map to the expected MessageCode.

- [ ] **Step 2: Add Handler dispatch and client mapping**

Deserialize `SaveArrangementRequestDTO` from `data.request`. Never use a raw cast from LinkedTreeMap. Client requests use module `courseAdmin`, attach the Session token, and convert response keys with Gson TypeToken.

- [ ] **Step 3: Implement deterministic mock behavior**

The mock contains one draft plan, one two-slot arrangement, resources, a teacher warning case and a blocking self-overlap case. Force saves require a reason and increment version.

- [ ] **Step 4: Run all Handler/service regressions and commit**

Run AdminScheduleHandlerTest, AdminCourseHandlerTest, SocketAdminCourseServiceTest, MockAdminScheduleServiceTest, and existing CourseHandler tests. Commit with `feat: expose administrator scheduling APIs`.

### Task 4: Build the Scheduling Dialog

**Files:**
- Create: `VCampusClient/src/controller/ScheduleArrangementDialogController.java`
- Create: `VCampusClient/src/controller/ScheduleSlotEditor.java`
- Create: `VCampusClient/src/resources/fxml/ScheduleArrangementDialog.fxml`
- Modify: `VCampusClient/src/controller/AdminCourseCatalogController.java`
- Modify: `VCampusClient/src/resources/css/style.css`
- Create: `VCampusClient/test/controller/ScheduleArrangementDialogControllerTest.java`
- Modify: `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`

**Interfaces:**
- Consumes: schedule methods on AdminCourseService.
- Produces: existing-arrangement list and add/edit/delete/publish UI.

- [ ] **Step 1: Write controller tests**

Test multiple slot rows, shared weeks, local validation, preview generation protection, BLOCKING-only return path, OVERRIDABLE reason requirement, distinct operationId after changing force intent, button disable/restore, delete confirmation and publish confirmation.

- [ ] **Step 2: Implement fixed FXML and dynamic slot editors**

FXML owns teacher/assistant/classroom selectors, week fields, arrangement list and action buttons. `ScheduleSlotEditor` owns one weekday/start/end row and exposes `ScheduleSlotDTO value()` plus validation text. Do not put server state in JavaFX Nodes.

- [ ] **Step 3: Wire the teaching-class `排课` action**

Open the dialog with offering and active draft plan IDs. Load resources and arrangements asynchronously. Render each arrangement as one card with all slots and the shared week range.

- [ ] **Step 4: Implement conflict UX**

Show conflicts grouped by severity. BLOCKING removes the force button. OVERRIDABLE enables “填写原因并保存”; trim reason and reject empty input locally. After any write, reload the authoritative arrangement list and course-row schedule status.

- [ ] **Step 5: Compile, run controller tests, capture screenshots and commit**

Run full Client compile/FXML parse/AdminCourseUiSmokeTest; inspect normal, blocking-conflict, overridable-conflict and two-slot states. Commit with `feat: build administrator scheduling dialog`.

### Task 5: Verify Scheduling End to End

**Files:**
- Create: `VCampusServer/test/integration/AdminScheduleSocketEndToEndTest.java`
- Modify: `VCampusServer/src/resources/seed-course-test.sql`

**Interfaces:**
- Produces: real TCP/MySQL proof that a published arrangement reaches the existing student schedule.

- [ ] **Step 1: Write the guarded E2E scenario**

Authenticate as admin, save a two-slot week-5-to-10 arrangement, verify replay idempotency, verify stale version conflict, publish it, then authenticate as an enrolled student and assert `loadSchedule` returns both slots only in applicable weeks. Assert non-admin scheduling writes are FORBIDDEN.

- [ ] **Step 2: Run complete schedule regressions**

Run V004 migration, conflict/service, Handler, E2E, existing CourseQueryMySqlTest, CourseSelectionMySqlTest, course socket E2E, ScheduleControllerTest, ScheduleLayoutTest and UI smoke.

- [ ] **Step 3: Commit fixture/test changes**

Commit with `test: verify administrator scheduling end to end`.
