# Course Management System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved two-level JavaFX course-selection client, authenticated TCP server APIs, transactional MySQL selection and FIFO waitlist behavior, and reliable course push notifications.

**Architecture:** Shared immutable DTOs cross the existing JSON-line `Message` protocol with `BIGINT` IDs encoded as decimal strings. The JavaFX client renders courses and offerings separately and delegates all authoritative decisions to authenticated server services; MySQL transactions enforce idempotency, capacity and conflict rules, while an outbox-backed connection registry pushes waitlist events and clients always reconcile with snapshots.

**Tech Stack:** Java 25, JavaFX 25/26 FXML and Controls, Gson 2.13.2, MySQL 8, Connector/J, TCP JSON lines, `CompletableFuture`, framework-free Java `main` tests

**Spec:** `docs/superpowers/specs/2026-09-09-course-management-server-design.md`

## Global Constraints

- Work only in `D:\JavaProject\VCampus\.worktrees\course-management-client` on `feature/course-management-client`.
- Treat `feature/courses` as read-only reference material; use `git show` and never switch, reset, commit, or write to that branch.
- Commits through `ed8f3bb` are completed prerequisites. Do not reimplement the shared DTO baseline or Socket request-lifecycle fixes blindly; revise them in place where this plan requires a new contract.
- Preserve the user's current edits in `CourseSelectionController.java` and `CourseSelectionView.fxml`: course types are `全部/必修/限选/选修/通选`, the search listener comment remains, and the button text remains `搜索` unless a reviewed UI requirement supersedes only the surrounding behavior.
- Do not commit `.codex-tmp`, screenshots, generated classes, logs, or `VCampusServer/src/resources/db.properties`. Never print database credentials.
- Keep `tbl_user.UID` and every user foreign key as `VARCHAR(32)`. Encode course, offering, event and other database `BIGINT` IDs as decimal strings in JSON.
- Use UTC for server/database timestamps and ISO-8601 strings on the wire. The five-minute offer deadline starts from server `offered_at`.
- Derive the student UID from `SessionManager.getSession(token)` and require the student role. Never trust request `sender`, `data.uid`, or a client-supplied role.
- Only first-come-first-served selection is implemented. Do not add lottery actions, statuses, tables or UI.
- All business mutations carry a client-generated UUID `operationId`; idempotency is scoped by authenticated UID and operation ID.
- Use the lock order: student academic-profile row, affected offering rows sorted by ID, then enrollment/plan/waitlist rows. Recheck idempotency after acquiring the student lock.
- Preserve `enrolled_count + active OFFERED reservations <= capacity`. Existing FIFO waiters cannot be bypassed by ordinary selection.
- Keep `MockCourseService` and `CourseUiPreview` serverless. Normal application execution installs the Socket-backed service.
- Use direct `javac`/`java` and the repository JARs; do not introduce Gradle, Maven, a web API, or a new test framework.

---

### Task 1: Replace the Shared Course Wire Contract

**Files:**
- Create: `VCampusCommon/src/dto/course/CourseDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseTeacherDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseMeetingDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseSelectionItemDTO.java`
- Create: `VCampusCommon/src/dto/course/CoursePlanSnapshotDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseMutationResultDTO.java`
- Create: `VCampusCommon/src/dto/course/CoursePushEventDTO.java`
- Create: `VCampusCommon/src/dto/course/CoursePushEventTypeDTO.java`
- Modify: `VCampusCommon/src/dto/course/CourseActions.java`
- Modify: `VCampusCommon/src/dto/course/CourseTermDTO.java`
- Modify: `VCampusCommon/src/dto/course/CourseOfferingDTO.java`
- Modify: `VCampusCommon/src/dto/course/SelectionStateDTO.java`
- Delete: `VCampusCommon/src/dto/course/PlanConfirmationDTO.java`
- Delete: `VCampusCommon/src/dto/course/PlanConfirmationItemDTO.java`
- Test: `VCampusCommon/test/dto/course/CourseDtoJsonTest.java`

**Interfaces:**
- Consumes: Gson serialization and existing `protocol.Message.data` conversion.
- Produces: immutable course/offering/snapshot/mutation/push DTOs and the final course action constants used by all later tasks.

- [ ] **Step 1: Rewrite the DTO contract test so the old flattened contract fails**

The test must assert all of the following:

```java
require(SelectionStateDTO.values().length == 6, "six selection states required");
require("9007199254740993".equals(copy.getOfferingId()), "BIGINT must remain exact");
require(copy.getTeachers().size() == 2, "multiple teachers must survive JSON");
require(copy.getMeetings().size() == 2, "multiple meetings must survive JSON");
require("2026-09-10T02:05:00Z".equals(copy.getExpiresAt()), "deadline required");
require(snapshot.getPlanItems().get(0).getCourse().getCourseId()
        .equals(snapshot.getPlanItems().get(0).getOffering().getCourseId()),
        "course/offering relation must stay explicit");
```

Also assert every collection is a defensive unmodifiable copy, `WAITLIST_OFFERED` round-trips `offeredAt/expiresAt`, and `CourseActions` contains the exact actions from the spec with no `CONFIRM_PLAN`, `LEAVE_WAITLIST`, or `DROP_COURSE` constants.

Construct a `CourseMutationResultDTO` and assert its affected `item`, explicit `finalState`, `outcomeCode`, `message`, and full `CoursePlanSnapshotDTO` all survive JSON independently; the snapshot is not a replacement for the direct mutation fields.

- [ ] **Step 2: Compile the test and verify RED**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test/dto/course -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar" -d .codex-tmp/task1-red $sources
```

Expected: compilation fails because the new DTOs and six-state API do not exist.

- [ ] **Step 3: Implement immutable DTOs with exact fields**

Use these contracts:

```java
enum SelectionStateDTO { AVAILABLE, PLANNED, FULL, WAITLISTED, WAITLIST_OFFERED, ENROLLED }

CourseTermDTO(int academicYear, int semester, String displayName)
CourseDTO(String courseId, String courseCode, String courseName, String courseType,
          double credit, int creditHours, String description, String prerequisites)
CourseTeacherDTO(String uid, String displayName)
CourseMeetingDTO(int dayOfWeek, int startPeriod, int endPeriod,
                 int startWeek, int endWeek, String weekPattern, String location,
                 String startsAtUtc, String endsAtUtc)
CourseOfferingDTO(String offeringId, String courseId,
                  List<CourseTeacherDTO> teachers, List<CourseMeetingDTO> meetings,
                  int enrolledCount, int capacity, SelectionStateDTO selectionState,
                  String failureReason, String offeredAt, String expiresAt)
CourseSelectionItemDTO(CourseDTO course, CourseOfferingDTO offering)
CoursePlanSnapshotDTO(CourseTermDTO term,
                      List<CourseSelectionItemDTO> planItems,
                      List<CourseSelectionItemDTO> waitlistItems,
                      List<CourseSelectionItemDTO> enrolledItems)
CourseMutationResultDTO(String operationId, CourseSelectionItemDTO item,
                        SelectionStateDTO finalState, String outcomeCode, String message,
                        CoursePlanSnapshotDTO snapshot)
CoursePushEventDTO(String eventId, CoursePushEventTypeDTO eventType,
                   CourseTermDTO term, String offeringId, String occurredAt,
                   String expiresAt, String message)
```

`CoursePushEventTypeDTO` values are `WAITLIST_OFFERED`, `WAITLIST_AUTO_ENROLLED`, `WAITLIST_OFFER_EXPIRED`, `WAITLIST_OFFER_ABANDONED`. Nullable timestamps and failure reason remain `null`, not empty strings.

- [ ] **Step 4: Replace the action constants exactly**

```java
LIST_TERMS, LIST_COURSES, LIST_COURSE_OFFERINGS, LOAD_SELECTION_SNAPSHOT,
ADD_TO_PLAN, REMOVE_FROM_PLAN, SELECT_OFFERING, JOIN_WAITLIST,
CANCEL_WAITLIST, RESOLVE_WAITLIST_OFFER, DROP_OFFERING, ACK_COURSE_EVENT,
LOAD_SCHEDULE, LOAD_NOTICES, LOAD_GRADES, LOAD_TRAINING_PLAN, SELECTION_EVENT
```

The values use lower camel case, for example `LIST_COURSE_OFFERINGS = "listCourseOfferings"`, `ACK_COURSE_EVENT = "ackCourseEvent"`, and `SELECTION_EVENT = "selectionEvent"`.

- [ ] **Step 5: Run common tests and commit**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar" -d .codex-tmp/task1-green $sources
java -cp ".codex-tmp/task1-green;VCampusClient/lib/gson-2.13.2.jar" dto.course.CourseDtoJsonTest
java -cp ".codex-tmp/task1-green;VCampusClient/lib/gson-2.13.2.jar" protocol.MessageUidTest
git add VCampusCommon/src/dto/course VCampusCommon/test/dto/course/CourseDtoJsonTest.java
git commit -m "refactor: split course and offering DTOs"
```

Expected: both tests exit with code 0; deleted batch-confirmation DTOs are included in the commit.

### Task 2: Build the Two-Level Mock-Backed Selection Client

**Files:**
- Create: `VCampusClient/src/model/course/CourseView.java`
- Create: `VCampusClient/src/model/course/CourseTeacherView.java`
- Create: `VCampusClient/src/model/course/CourseMeetingView.java`
- Create: `VCampusClient/src/model/course/CourseSelectionItemView.java`
- Create: `VCampusClient/src/model/course/CoursePlanSnapshotView.java`
- Create: `VCampusClient/src/model/course/CourseMutationResultView.java`
- Create: `VCampusClient/src/model/course/CourseTermView.java`
- Create: `VCampusClient/src/model/course/CoursePushEventView.java`
- Create: `VCampusClient/src/model/course/CoursePushEventType.java`
- Create: `VCampusClient/src/model/course/WaitlistDecision.java`
- Create: `VCampusClient/src/service/CoursePushListener.java`
- Create: `VCampusClient/src/service/CourseSubscription.java`
- Modify: `VCampusClient/src/model/course/CourseOfferingView.java`
- Modify: `VCampusClient/src/model/course/SelectionStatus.java`
- Modify: `VCampusClient/src/service/CourseService.java`
- Modify: `VCampusClient/src/service/MockCourseService.java`
- Modify: `VCampusClient/src/controller/CourseSelectionController.java`
- Modify: `VCampusClient/src/resources/fxml/CourseSelectionView.fxml`
- Modify: `VCampusClient/src/resources/css/style.css`
- Test: `VCampusClient/test/model/course/CourseModelTest.java`
- Test: `VCampusClient/test/service/MockCourseServiceTest.java`
- Modify: `VCampusClient/test/service/MockCourseScheduleTest.java`
- Test: `VCampusClient/test/controller/CourseSelectionControllerTest.java`
- Modify: `VCampusClient/test/controller/GradeControllerTest.java`
- Modify: `VCampusClient/test/controller/TrainingPlanControllerTest.java`
- Test: `VCampusClient/test/ui/CourseUiSmokeTest.java`

**Interfaces:**
- Consumes: Task 1 DTO semantics, but remains independent of Socket and MySQL.
- Produces: the final `CourseService` selection contract, two-level JavaFX behavior, six-state mock transitions, and model types used by the real client service.

- [ ] **Step 1: Write failing model, service and controller tests**

Cover these observable cases:

```java
require(service.loadCourses(term).join().size() >= 2, "course catalog required");
require(service.loadCourseOfferings(term, courseId).join().size() >= 2,
        "one course must expose multiple offerings");
require(service.addToPlan(term, offeringA, op1).join().getSnapshot()
        .find(offeringA).getStatus() == SelectionStatus.PLANNED, "plan transition");
require(service.selectOffering(term, fullOffering, op2).join().getSnapshot()
        .find(fullOffering).getStatus() == SelectionStatus.FULL, "full is not auto-waitlist");
require(service.joinWaitlist(term, fullOffering, op3).join().getSnapshot()
        .find(fullOffering).getStatus() == SelectionStatus.WAITLISTED, "explicit waitlist");
```

Controller tests must verify: “全部” renders one outer row per `CourseView`; expanding one row calls `loadCourseOfferings` once and renders multiple child rows; “计划” includes `PLANNED/FULL/WAITLISTED/WAITLIST_OFFERED`; “候补” includes the latter two; “已选” includes only `ENROLLED`; no batch-confirm button exists; and two clicks for the same offering start only one mutation while every rendered copy of that offering is disabled.

- [ ] **Step 2: Compile and verify RED**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test/model/course,VCampusClient/test/service,VCampusClient/test/controller -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/task2-red $sources
```

Expected: compilation fails on the new model and service methods.

- [ ] **Step 3: Implement the final selection service signatures**

```java
CompletableFuture<List<CourseTermView>> loadTerms();
CompletableFuture<List<CourseView>> loadCourses(CourseTermView term);
CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(CourseTermView term, long courseId);
CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(CourseTermView term);
CompletableFuture<CourseMutationResultView> addToPlan(CourseTermView term, long offeringId, String operationId);
CompletableFuture<CourseMutationResultView> removeFromPlan(CourseTermView term, long offeringId, String operationId);
CompletableFuture<CourseMutationResultView> selectOffering(CourseTermView term, long offeringId, String operationId);
CompletableFuture<CourseMutationResultView> joinWaitlist(CourseTermView term, long offeringId, String operationId);
CompletableFuture<CourseMutationResultView> cancelWaitlist(CourseTermView term, long offeringId, String operationId);
CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(CourseTermView term, long offeringId,
        String operationId, WaitlistDecision decision);
CompletableFuture<CourseMutationResultView> dropOffering(CourseTermView term, long offeringId, String operationId);
```

Keep the existing schedule/notice/grade/training-plan signatures unchanged in this task so the untouched auxiliary Controllers continue to compile; Task 8 migrates those methods and callers to `CourseTermView` together. `CourseTermView.toString()` returns the display name. All view collections are immutable. `CourseOfferingView` contains only offering-owned data and references `courseId`; `CourseSelectionItemView` combines it with a `CourseView` for non-“全部” tabs.

Use `int academicYear`, `int semester`, and `String displayName` in `CourseTermView`. Course/offering/event IDs convert to `long` only in client view models. `CourseMeetingView` retains structured periods/weeks/location and parses optional UTC boundaries to `Instant`. `CoursePushEventType` mirrors the four shared event values without exposing DTO enums to Controllers.

`CourseSelectionItemView.getStatus()` delegates to its offering. `CoursePlanSnapshotView.find(long offeringId)` returns the matching item across plan, waitlist and enrolled lists or `null`. `CourseMutationResultView` exposes the affected item, explicit final state, outcome code, message and complete `CoursePlanSnapshotView`. Include these event hooks in the interface now so Mock and Socket implementations share one final contract:

```java
CompletableFuture<Void> ackCourseEvent(String eventId);
CourseSubscription subscribe(CoursePushListener listener);

interface CoursePushListener {
    void onCourseEvent(CoursePushEventView event);
}

interface CourseSubscription extends AutoCloseable {
    @Override void close();
}
```

- [ ] **Step 4: Implement deterministic Mock behavior**

Use at least two courses with two offerings each. Preserve unrelated offerings when one is selected. Generate six visible states, an `OFFERED` item with a fixed future UTC deadline for tests, and operation-result snapshots. Duplicate `uid` is irrelevant in Mock, but duplicate operation ID must return the previously stored result without repeating count changes. Mock ACK returns a completed future and Mock subscriptions can be closed without opening a Socket.

- [ ] **Step 5: Refactor the selection Controller and FXML**

Remove `confirmPlanButton` and every `confirmPlan` method. Add `ComboBox<CourseTermView> termFilter`. Keep one `Set<Long> pendingOfferingIds`, generate `UUID.randomUUID().toString()` immediately before each business mutation, and clear the ID only after success/failure reconciliation.

For “全部”, create a stable course row with an expand icon button and an initially empty child container; first expansion invokes `loadCourseOfferings(term, courseId)`, later expansion reuses the loaded list until refresh or term change. For the other tabs, render `CourseSelectionItemView` directly from `loadSelectionSnapshot` using the exact state/button rules in the spec. A timeout-like failure must call `loadSelectionSnapshot` before re-enabling the offering.

Use these exact actions: in “全部”, `AVAILABLE -> 加入计划`, `PLANNED/FULL -> 移除计划`, `WAITLISTED -> 候补中` disabled, `WAITLIST_OFFERED -> 待处理` disabled, `ENROLLED -> 已选` disabled. In “计划”, show `PLANNED/FULL/WAITLISTED/WAITLIST_OFFERED`; the right actions are `选择/候补/取消候补/处理`, and only `PLANNED/FULL` have a separate left remove action. “候补” shows `WAITLISTED/WAITLIST_OFFERED` with `取消候补/处理`; “已选” shows `ENROLLED` with `退选`.

- [ ] **Step 6: Apply scoped CSS and validate the 860 x 580 layout**

Use existing course-prefixed selectors. Keep cards at 8 px radius or less, ensure nested teaching-class rows are visually subordinate without being cards inside cards, reserve fixed widths for status/actions, and preserve the user's course-type labels and `搜索` text.

- [ ] **Step 7: Update all remaining CourseService test doubles**

Before compiling all tests, update the `ControlledCourseService` implementations in `GradeControllerTest` and `TrainingPlanControllerTest` to implement the new selection methods with completed or explicitly failed futures while retaining their existing grade/training behavior. Replace `MockCourseScheduleTest` calls to removed batch methods with `addToPlan(..., operationId)` followed by `selectOffering(..., operationId)`. No test source may retain `loadOfferings`, `confirmPlan`, `leaveWaitlist`, or `dropCourse`.

- [ ] **Step 8: Run client tests, FXML parse and preview smoke**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/task2-green $sources
java -cp ".codex-tmp/task2-green;VCampusClient/src;VCampusClient/lib/*" model.course.CourseModelTest
java -cp ".codex-tmp/task2-green;VCampusClient/src;VCampusClient/lib/*" service.MockCourseServiceTest
java -cp ".codex-tmp/task2-green;VCampusClient/src;VCampusClient/lib/*" controller.CourseSelectionControllerTest
Get-ChildItem VCampusClient/src/resources/fxml -Filter *.fxml | ForEach-Object { [xml](Get-Content -Raw -LiteralPath $_.FullName) | Out-Null }
java --module-path .codex-tmp/openjfx-25.0.4-win/lib --add-modules javafx.controls,javafx.fxml,javafx.swing -cp ".codex-tmp/task2-green;VCampusClient/src;VCampusClient/lib/gson-2.13.2.jar" ui.CourseUiPreview --smoke
```

Expected: tests and smoke exit 0 and FXML parsing emits no error.

- [ ] **Step 9: Commit the complete mock-backed selection UI**

```powershell
git add VCampusClient/src/model/course VCampusClient/src/service/CourseService.java VCampusClient/src/service/MockCourseService.java VCampusClient/src/service/CoursePushListener.java VCampusClient/src/service/CourseSubscription.java VCampusClient/src/controller/CourseSelectionController.java VCampusClient/src/resources/fxml/CourseSelectionView.fxml VCampusClient/src/resources/css/style.css VCampusClient/test/model/course/CourseModelTest.java VCampusClient/test/service/MockCourseServiceTest.java VCampusClient/test/service/MockCourseScheduleTest.java VCampusClient/test/controller/CourseSelectionControllerTest.java VCampusClient/test/controller/GradeControllerTest.java VCampusClient/test/controller/TrainingPlanControllerTest.java VCampusClient/test/ui/CourseUiSmokeTest.java
git commit -m "feat: add two-level course selection client"
```

### Task 3: Create Authoritative Course Migrations and Test Seed

**Files:**
- Create: `VCampusServer/src/resources/migrations/V001_create_course_tables.sql`
- Create: `VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql`
- Create: `VCampusServer/src/resources/migrations/V003_extend_course_management.sql`
- Create: `VCampusServer/src/resources/seed-course-test.sql`
- Modify: `VCampusServer/src/resources/db.properties.example`
- Modify: `VCampusServer/src/util/DBUtil.java`
- Modify: `VCampusServer/test/database/CourseMigrationContractTest.java`
- Create: `VCampusServer/test/database/CourseMigrationMySqlTest.java`

**Interfaces:**
- Consumes: current `tbl_user.UID VARCHAR(32)` and selected structures from read-only `feature/courses` SQL.
- Produces: one non-overlapping migration chain and deterministic `virtual_campus_course_test` data for all server tests.

- [ ] **Step 1: Adapt the existing uncommitted contract test to the approved schema**

Assert exact ownership: V001 creates `course`, `course_major`, `course_year`, `course_offering`, `course_offering_teacher`, `enrollment`, `grade`; V002 creates schedule/calendar/resource/conflict tables; V003 creates `major`, `student_academic_profile`, `course_selection_window`, `course_plan_item`, `course_waitlist`, `training_plan`, `training_plan_group`, `training_plan_course`, `course_notice`, `course_operation_log`, `course_event_outbox`.

Add string checks for:

```text
VARCHAR(32) user foreign keys
UNIQUE (offering_id, course_id, academic_year, semester)
UNIQUE (uid, offering_id) on enrollment
UNIQUE (uid, academic_year, semester, active_course_id)
UNIQUE (uid, offering_id) on plan and waitlist
enrollment status CHECK permits only 2/3
waitlist status CHECK WAITING/OFFERED/CANCELLED/EXPIRED/ENROLLED
operation PRIMARY KEY (uid, operation_id)
outbox index (uid, acked_at, event_id)
selection window schedule_plan_id foreign key
all queue/deadline/operation/outbox timestamps at microsecond precision
```

- [ ] **Step 2: Run the contract test and verify RED**

```powershell
javac -encoding UTF-8 -d .codex-tmp/task3-red VCampusServer/test/database/CourseMigrationContractTest.java
java -cp .codex-tmp/task3-red database.CourseMigrationContractTest
```

Expected: failure reports missing migration files or missing approved tables.

- [ ] **Step 3: Build V001 and V002 from the reference without copying incompatibilities**

Use `git show feature/courses:<path>` only. Remove duplicated schedule tables from V001, use `VARCHAR(32)` teacher/student UID, keep course/offering IDs as `BIGINT`, remove lottery policy from the active scope, and add the offering composite unique key required by enrollment. Because `major` is created in V003, V001 creates `course_major.major_id` and its indexes without that foreign key; V003 adds the foreign key only after creating `major`. V002 owns all calendar, schedule, classroom, booking and `course_offering_conflict` definitions exactly once.

- [ ] **Step 4: Implement V003 state and support tables**

Use `DATETIME(6)` columns for `queue_time`, `offered_at`, `expires_at`, operation and outbox timestamps. `enrollment.active_course_id` is generated as `CASE WHEN status = 2 THEN course_id ELSE NULL END`; its unique key includes UID, academic year and semester, and its status check permits only `2 = ENROLLED` and `3 = DROPPED`. `course_selection_window.schedule_plan_id` is non-null when selection is open. Plan state is only `PLANNED/FULL`; waitlist state uses the five approved values.

Change `db.properties.example` to `serverTimezone=UTC`. Make `DBUtil.getConnection()` execute `SET time_zone = '+00:00'` on every newly opened connection before returning it; close and fail the connection if that setup fails. The live test must assert `SELECT @@session.time_zone` returns `+00:00`. Business code still uses `Instant`/`Clock` rather than relying on the JVM default zone.

- [ ] **Step 5: Add deterministic seed and guarded live MySQL test**

Seed at least two students, two courses, multiple offerings for one course, two teachers, multi-meeting schedule rows, one published schedule plan/conflict matrix, open selection/drop windows, grades, training plan and notices. Give the two application test accounts a documented test-only password hash generated by the existing `PasswordUtil`; this is not the local MySQL password and must never be reused outside the test schema. `CourseMigrationMySqlTest` must abort unless the JDBC URL database name is exactly `virtual_campus_course_test`; it applies the authoritative `tbl_user` definition and V001-V003 in order inside that dedicated schema and verifies every generated constraint using real inserts.

- [ ] **Step 6: Run static and live migration tests**

Compile with `VCampusServer/lib/mysql-connector-j-9.3.0.jar`. Load ignored local `db.properties` without printing it. Apply only to `virtual_campus_course_test`; never drop or alter `virtual_campus`.

Expected: static contract passes; MySQL test proves same-term same-course duplicate enrollment is rejected, cross-term enrollment is accepted, alphanumeric UID foreign keys work, enrollment statuses `1` and `4` are rejected, and every connection uses UTC. The cross-table capacity/reservation invariant is tested after transactional services exist in Task 6, not claimed as a migration-only constraint.

- [ ] **Step 7: Commit migrations and tests**

```powershell
git add VCampusServer/src/resources/migrations VCampusServer/src/resources/seed-course-test.sql VCampusServer/src/resources/db.properties.example VCampusServer/src/util/DBUtil.java VCampusServer/test/database/CourseMigrationContractTest.java VCampusServer/test/database/CourseMigrationMySqlTest.java
git commit -m "feat: add course management migrations"
```

Do not stage `db.properties` or `.codex-tmp`.

### Task 4: Add Authenticated Course Read APIs

**Files:**
- Create: `VCampusServer/src/dao/CourseQueryDAO.java`
- Create: `VCampusServer/src/dao/CourseScheduleDAO.java`
- Create: `VCampusServer/src/dao/CourseAcademicDAO.java`
- Create: `VCampusServer/src/service/CourseQueryService.java`
- Create: `VCampusServer/src/handler/CourseHandler.java`
- Modify: `VCampusServer/src/network/MessageDispatcher.java`
- Test: `VCampusServer/test/dao/CourseQueryMappingTest.java`
- Test: `VCampusServer/test/handler/CourseHandlerTest.java`
- Test: `VCampusServer/test/service/CourseQueryMySqlTest.java`

**Interfaces:**
- Consumes: Task 1 DTOs/actions, Task 3 schema/seed, `SessionManager`, `DBUtil` and existing `Message` response conventions.
- Produces: authenticated implementations of every read action and query services reused by mutation responses.

- [ ] **Step 1: Write failing mapping and Handler tests**

Use `CachedRowSet` or deterministic test rows to verify SQL nulls remain null, every `BIGINT` becomes a decimal string, two teachers and two meetings are grouped under one offering, and snapshots use this priority:

```text
ENROLLED > WAITLIST_OFFERED > WAITLISTED > PLANNED/FULL > AVAILABLE
```

Handler tests must assert `UNAUTHORIZED` for missing/invalid token, `FORBIDDEN` for a non-student role, `BAD_REQUEST` for malformed term/ID, `NOT_FOUND` for an unknown course, and that request `sender`/`data.uid` cannot change the student UID passed to the service.

- [ ] **Step 2: Compile and verify RED**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusServer/src,VCampusServer/test/dao,VCampusServer/test/handler -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusServer/lib/*" -d .codex-tmp/task4-red $sources
```

Expected: compilation fails because course DAOs, service and Handler are absent.

- [ ] **Step 3: Implement focused DAO query methods**

```java
List<CourseTermDTO> listTerms(Connection connection, String studentUid);
List<CourseDTO> listCourses(Connection connection, String studentUid, int academicYear, int semester);
List<CourseOfferingDTO> listCourseOfferings(Connection connection, String studentUid,
        int academicYear, int semester, long courseId);
CoursePlanSnapshotDTO loadSelectionSnapshot(Connection connection, String studentUid,
        int academicYear, int semester);
List<ScheduleEntryDTO> loadSchedule(Connection connection, String studentUid,
        int academicYear, int semester, int week);
List<CourseNoticeDTO> loadNotices(Connection connection, String studentUid,
        int academicYear, int semester, int week);
GradeSummaryDTO loadGrades(Connection connection, String studentUid,
        int academicYear, int semester);
List<TrainingPlanGroupDTO> loadTrainingPlan(Connection connection, String studentUid);
```

DAO methods do no authentication and do not close caller-owned connections. Catalog visibility uses student major/cohort and offering/window status. Schedule/conflict reads use `course_selection_window.schedule_plan_id`; missing or unpublished plans are errors, not empty conflict sets. Grades include only published rows and preserve nullable component scores.

- [ ] **Step 4: Implement CourseQueryService and CourseHandler**

`CourseQueryService` opens read connections and delegates to DAOs. `CourseHandler.handle(Message)` resolves `request.token`, obtains `UserSession.username`, validates role `学生`, parses decimal-string IDs, and maps read actions. Response data keys are exactly `terms`, `courses`, `offerings`, `snapshot`, `schedule`, `notices`, `grades`, and `trainingPlan`.

- [ ] **Step 5: Register only the `course` module in server MessageDispatcher**

Keep the existing `user` route unchanged. Unknown module/action returns `BAD_REQUEST`; Handler failures map to stable codes and never include SQL or stack traces.

- [ ] **Step 6: Run mapping, handler and live query tests**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusServer/src,VCampusServer/test/dao,VCampusServer/test/handler,VCampusServer/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusServer/lib/*" -d .codex-tmp/task4-green $sources
java -cp ".codex-tmp/task4-green;VCampusServer/lib/*" dao.CourseQueryMappingTest
java -cp ".codex-tmp/task4-green;VCampusServer/lib/*" handler.CourseHandlerTest
java -cp ".codex-tmp/task4-green;VCampusServer/src;VCampusServer/lib/*" service.CourseQueryMySqlTest
```

Expected: all tests exit 0 against the dedicated test database.

- [ ] **Step 7: Commit authenticated reads**

```powershell
git add VCampusServer/src/dao/CourseQueryDAO.java VCampusServer/src/dao/CourseScheduleDAO.java VCampusServer/src/dao/CourseAcademicDAO.java VCampusServer/src/service/CourseQueryService.java VCampusServer/src/handler/CourseHandler.java VCampusServer/src/network/MessageDispatcher.java VCampusServer/test/dao VCampusServer/test/handler VCampusServer/test/service/CourseQueryMySqlTest.java
git commit -m "feat: add authenticated course read APIs"
```

### Task 5: Implement Idempotent Plan, Select and Drop Transactions

**Files:**
- Create: `VCampusServer/src/dao/CourseSelectionDAO.java`
- Create: `VCampusServer/src/dao/CourseOperationDAO.java`
- Create: `VCampusServer/src/service/CourseSelectionService.java`
- Create: `VCampusServer/src/service/WaitlistAdvanceTrigger.java`
- Modify: `VCampusServer/src/handler/CourseHandler.java`
- Test: `VCampusServer/test/service/CourseSelectionMySqlTest.java`
- Test: `VCampusServer/test/handler/CourseMutationHandlerTest.java`

**Interfaces:**
- Consumes: Task 3 constraints and Task 4 query snapshot support.
- Produces: transactional `addToPlan`, `removeFromPlan`, `selectOffering`, `dropOffering`, idempotency replay, and the post-commit trigger later implemented by the waitlist service.

- [ ] **Step 1: Write the failing transaction test**

Reset only seeded course test rows before each case. Assert:

```text
AVAILABLE -> PLANNED -> AVAILABLE
PLANNED with capacity -> ENROLLED and current count + 1
PLANNED at capacity -> FULL with no waitlist row
add/remove plan and FULL outcomes create no enrollment row of any status
PLANNED when WAITING exists -> FULL even if count has a transient vacancy
same-course or schedule conflict -> remains PLANNED with stable reason
drop before deadline -> DROPPED, count - 1, no restored plan
drop after deadline -> CONFLICT with no mutation
same UID + same operationId -> identical stored business result and one count change
same UID + same operationId + different request -> BAD_REQUEST/operation conflict
same UUID under two different UIDs -> independent operations
```

- [ ] **Step 2: Compile and verify RED**

Compile all common/server sources plus `CourseSelectionMySqlTest`; expect missing service/DAO symbols.

- [ ] **Step 3: Implement DAO lock and state primitives**

Methods accept caller-owned `Connection` and include:

```java
void lockStudentProfile(Connection c, String uid);
List<Long> lockOfferingsAscending(Connection c, Collection<Long> offeringIds);
boolean hasActiveWaiters(Connection c, long offeringId);
List<Long> findEnrolledCourseAndTimeConflicts(Connection c, String uid,
        int academicYear, int semester, long offeringId, long schedulePlanId);
void upsertPlan(Connection c, String uid, long offeringId, String state, String reason);
void deletePlan(Connection c, String uid, long offeringId);
void enroll(Connection c, String uid, long offeringId);
void drop(Connection c, String uid, long offeringId, Instant now);
void changeEnrolledCount(Connection c, long offeringId, int delta);
```

All count changes verify the affected row count and capacity invariant. Conflicts query both normalized sides of `course_offering_conflict` for the selection window's plan.

- [ ] **Step 4: Implement operation replay inside the student lock**

`CourseOperationDAO` performs an optional fast read by `(uid, operationId)`, then a mandatory second read after `lockStudentProfile`. It canonicalizes action, term, offering ID and decision into the request hash. Stable success/conflict results are inserted in the same transaction as the business state and snapshot. Replayed business payload is wrapped in a fresh `Message` by the Handler so the transport request UID remains current.

- [ ] **Step 5: Implement service transactions and post-commit trigger**

Each public method validates UUID syntax, opens one connection, sets auto-commit false, locks in the global order, mutates state, records operation result, commits, then invokes `WaitlistAdvanceTrigger.offeringFreed(offeringId)` only after a successful drop commit. Roll back on every exception and restore auto-commit before closing.

```java
CourseMutationResultDTO addToPlan(String uid, CourseTermDTO term, long offeringId, String operationId);
CourseMutationResultDTO removeFromPlan(String uid, CourseTermDTO term, long offeringId, String operationId);
CourseMutationResultDTO selectOffering(String uid, CourseTermDTO term, long offeringId, String operationId);
CourseMutationResultDTO dropOffering(String uid, CourseTermDTO term, long offeringId, String operationId);
```

- [ ] **Step 6: Map mutation actions and exact error codes in CourseHandler**

Business state/window/capacity/conflict failures return `CONFLICT`; malformed UUID/IDs return `BAD_REQUEST`; missing objects return `NOT_FOUND`; database failures return `ERROR`. Every response includes the original request UID.

- [ ] **Step 7: Run sequential and concurrent MySQL tests**

Add a two-thread barrier test where different operation IDs for the same student race, and a capacity test where two students race for the final seat. Expected: one final-seat winner, no count drift, and the loser ends in `FULL` rather than an implicit waitlist.

- [ ] **Step 8: Commit core mutations**

```powershell
git add VCampusServer/src/dao/CourseSelectionDAO.java VCampusServer/src/dao/CourseOperationDAO.java VCampusServer/src/service/CourseSelectionService.java VCampusServer/src/service/WaitlistAdvanceTrigger.java VCampusServer/src/handler/CourseHandler.java VCampusServer/test/service/CourseSelectionMySqlTest.java VCampusServer/test/handler/CourseMutationHandlerTest.java
git commit -m "feat: add idempotent course selection transactions"
```

### Task 6: Implement FIFO Waitlist, Five-Minute Offers and Outbox

**Files:**
- Create: `VCampusServer/src/dao/CourseWaitlistDAO.java`
- Create: `VCampusServer/src/dao/CourseEventOutboxDAO.java`
- Create: `VCampusServer/src/service/CourseWaitlistService.java`
- Create: `VCampusServer/src/service/CourseWaitlistScheduler.java`
- Modify: `VCampusServer/src/handler/CourseHandler.java`
- Test: `VCampusServer/test/service/CourseWaitlistMySqlTest.java`
- Test: `VCampusServer/test/service/CourseWaitlistSchedulerTest.java`

**Interfaces:**
- Consumes: Task 5 lock/idempotency primitives and Task 3 waitlist/outbox tables.
- Produces: explicit waitlist mutations, FIFO promotion, five-minute conflict offers, expiry recovery and transactional push events.

- [ ] **Step 1: Write failing waitlist and scheduler tests using an injected Clock**

Cover these cases with fixed UTC instants:

```text
join with vacancy/no queue/no conflict -> ENROLLED
join with vacancy/conflict -> OFFERED with expires_at = offered_at + 5 minutes
join when any WAITING row exists -> WAITING at queue tail
WAITING and OFFERED transitions create no enrollment row until actual enrollment succeeds
cancel WAITING -> CANCELLED and plan FULL
rejoin after cancel -> same row may be reused but queue_time moves behind existing waiters
freed seat promotes FIFO head with no conflict automatically
conflicting FIFO head receives OFFERED and reserves exactly one seat
FIFO head that lost major/cohort eligibility is cancelled with a reason and the next eligible waiter receives the seat
FIFO head for a closed/cancelled offering or mismatched term is never promoted
ACCEPT before deadline drops every conflicting enrollment and enrolls offered class atomically
ABANDON and timeout restore plan FULL, release reservation and advance next waiter
ACCEPT at or after expires_at fails and cannot consume the released seat
server-start scan expires overdue offers and resumes queue only while selection window is open
window close expires WAITING but permits pre-existing OFFERED until its own deadline
```

- [ ] **Step 2: Compile and verify RED**

Compile common/server sources plus the two waitlist tests; expect missing waitlist service/scheduler symbols.

- [ ] **Step 3: Implement waitlist/outbox DAO primitives**

Queue head order is `queue_time, waitlist_id`. The advance algorithm may read a candidate UID without a lock, but its transaction must lock that student's profile first, then the offering, then recheck that the candidate is still the active head. It then revalidates selection-window state, offering state/term, student role/profile, major/cohort visibility and schedule-plan validity. An invalid head becomes `CANCELLED`, its plan remains `FULL` with a stable reason, and the loop continues to the next candidate without reserving capacity. Outbox insert happens in the same transaction as `OFFERED`, `ENROLLED`, `EXPIRED`, or user-visible `CANCELLED` state changes.

- [ ] **Step 4: Implement the three user mutations**

```java
CourseMutationResultDTO joinWaitlist(String uid, CourseTermDTO term,
        long offeringId, String operationId);
CourseMutationResultDTO cancelWaitlist(String uid, CourseTermDTO term,
        long offeringId, String operationId);
CourseMutationResultDTO resolveWaitlistOffer(String uid, CourseTermDTO term,
        long offeringId, String operationId, String decision);
```

`joinWaitlist` checks existing active waiters before direct enrollment. `ACCEPT` locks every offered/conflicting teaching class in ascending ID order and schedules advancement for each dropped class after commit. `ABANDON` and timeout schedule advancement of the offered class after commit.

- [ ] **Step 5: Implement scheduler lifecycle logic without Socket coupling**

`CourseWaitlistService` implements the Task 5 `WaitlistAdvanceTrigger`. `CourseWaitlistScheduler` uses one daemon `ScheduledExecutorService`. On start it scans overdue offers immediately, then polls overdue offers and open-window vacancy work at a bounded interval. It exposes deterministic package-visible `runOnce(Instant now)` for tests, catches/logs one job failure without terminating the scheduler, and has idempotent `start()`/`close()`.

- [ ] **Step 6: Map waitlist actions in CourseHandler and run tests**

Run Handler tests plus live waitlist/scheduler tests. Assert every outbox row contains the correct authenticated UID, event type, offering ID, UTC timestamps and optional deadline. Add concurrent join/promotion attempts and query after every barrier to prove `enrolled_count + active unexpired OFFERED <= capacity`; this is a service invariant, not a migration-only claim. Re-run Task 5 final-seat tests with offered reservations present.

- [ ] **Step 7: Commit waitlist behavior**

```powershell
git add VCampusServer/src/dao/CourseWaitlistDAO.java VCampusServer/src/dao/CourseEventOutboxDAO.java VCampusServer/src/service/CourseWaitlistService.java VCampusServer/src/service/CourseWaitlistScheduler.java VCampusServer/src/handler/CourseHandler.java VCampusServer/test/service/CourseWaitlistMySqlTest.java VCampusServer/test/service/CourseWaitlistSchedulerTest.java
git commit -m "feat: add FIFO course waitlist"
```

### Task 7: Add Server Push Connections and At-Least-Once Event Delivery

**Files:**
- Create: `VCampusServer/src/network/ClientConnection.java`
- Create: `VCampusServer/src/network/OnlineConnectionRegistry.java`
- Create: `VCampusServer/src/service/CourseEventDispatcher.java`
- Modify: `VCampusServer/src/network/ClientHandler.java`
- Modify: `VCampusServer/src/network/Server.java`
- Modify: `VCampusServer/src/handler/CourseHandler.java`
- Modify: `VCampusServer/src/main/ServerMain.java`
- Test: `VCampusServer/test/network/ClientConnectionTest.java`
- Test: `VCampusServer/test/network/OnlineConnectionRegistryTest.java`
- Test: `VCampusServer/test/service/CourseEventDispatcherTest.java`
- Test: `VCampusServer/test/main/ServerMainTimeZoneTest.java`

**Interfaces:**
- Consumes: Task 6 outbox DAO/events and existing Socket server lifecycle.
- Produces: UID-to-connections registry, one serialized writer for responses and pushes, event ACK/replay, and scheduler/dispatcher startup wiring.

- [ ] **Step 1: Write failing concurrency and delivery tests**

`ClientConnectionTest` must send responses and pushes concurrently through one connection into a captured writer, parse every output line with Gson, and assert no interleaving. Registry tests bind two connections to one UID, remove one without removing the other, reject stale unbind calls, and clear all bindings on close.

Outbox dispatcher tests assert:

```text
all current connections receive an unacked event
zero connections leaves the event unacked
a failed writer does not prevent delivery to another connection
ACK by the authenticated UID sets acked_at
ACK by another UID cannot consume the event
unacked event is delivered again and client eventId can deduplicate it
one event failure does not terminate later dispatch cycles
```

- [ ] **Step 2: Compile and verify RED**

Compile common/server/network/service tests; expect missing `ClientConnection`, registry and dispatcher.

- [ ] **Step 3: Implement one connection abstraction and registry**

```java
final class ClientConnection implements AutoCloseable {
    void send(Message message) throws IOException;
    boolean isOpen();
}

final class OnlineConnectionRegistry {
    void bind(String uid, ClientConnection connection);
    void unbind(String uid, ClientConnection connection);
    List<ClientConnection> snapshot(String uid);
}
```

`ClientConnection.send` serializes with the shared Gson and synchronizes all writes on one private lock. The registry stores concurrent sets, never raw writers, and returns snapshots so network I/O occurs outside registry locks.

- [ ] **Step 4: Refactor ClientHandler to use ClientConnection**

All ordinary responses call `connection.send(response)`. Bind after a successful login response by resolving `response.token` through `SessionManager`, or after any request carrying a currently valid token. On logout or disconnect, unbind the exact connection. Never bind using request sender.

- [ ] **Step 5: Implement CourseEventDispatcher and ACK**

The dispatcher polls unacked rows in event-ID order, builds `MessageType.PUSH` with `module="course"`, `action="selectionEvent"`, and sends to every connection snapshot. It records attempts but only `ackCourseEvent` from the event's authenticated UID sets `acked_at`. Use bounded batches and an injected executor/clock for tests; start/close is idempotent.

- [ ] **Step 6: Wire server lifecycle**

`ServerMain` first sets the server JVM default zone to UTC, then creates one registry, one event dispatcher and one waitlist scheduler. `Server` passes the shared registry and Handler dependencies into every `ClientHandler`. Start both background services after DB configuration succeeds and close them during server shutdown. Tests must not use static background executors. `ServerMainTimeZoneTest` saves/restores the test process default and asserts the startup configuration selects `UTC`.

- [ ] **Step 7: Run tests and commit**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusServer/src,VCampusServer/test/network,VCampusServer/test/service -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusServer/lib/*" -d .codex-tmp/task7-green $sources
java -cp ".codex-tmp/task7-green;VCampusServer/lib/*" network.ClientConnectionTest
java -cp ".codex-tmp/task7-green;VCampusServer/lib/*" network.OnlineConnectionRegistryTest
java -cp ".codex-tmp/task7-green;VCampusServer/lib/*" service.CourseEventDispatcherTest
java -cp ".codex-tmp/task7-green;VCampusServer/lib/*" main.ServerMainTimeZoneTest
git add VCampusServer/src/network/ClientConnection.java VCampusServer/src/network/OnlineConnectionRegistry.java VCampusServer/src/network/ClientHandler.java VCampusServer/src/network/Server.java VCampusServer/src/service/CourseEventDispatcher.java VCampusServer/src/handler/CourseHandler.java VCampusServer/src/main/ServerMain.java VCampusServer/test/network VCampusServer/test/service/CourseEventDispatcherTest.java VCampusServer/test/main/ServerMainTimeZoneTest.java
git commit -m "feat: push course events over socket"
```

### Task 8: Connect the Real Client Service and Push Coordinator

**Files:**
- Create: `VCampusClient/src/service/CourseTransport.java`
- Create: `VCampusClient/src/service/SocketCourseTransport.java`
- Create: `VCampusClient/src/service/SocketCourseService.java`
- Create: `VCampusClient/src/service/CoursePushCoordinator.java`
- Modify: `VCampusClient/src/model/course/CoursePushEventView.java`
- Modify: `VCampusClient/src/service/CoursePushListener.java`
- Modify: `VCampusClient/src/service/CourseSubscription.java`
- Create: `VCampusClient/src/network/PushListenerRegistry.java`
- Modify: `VCampusClient/src/service/CourseServices.java`
- Modify: `VCampusClient/src/network/MessageDispatcher.java`
- Modify: `VCampusClient/src/network/SocketClient.java`
- Modify: `VCampusClient/src/controller/CourseSelectionController.java`
- Modify: `VCampusClient/src/controller/ScheduleController.java`
- Modify: `VCampusClient/src/controller/GradeController.java`
- Modify: `VCampusClient/src/controller/TrainingPlanController.java`
- Modify: `VCampusClient/test/ui/CourseUiPreview.java`
- Test: `VCampusClient/test/service/SocketCourseServiceTest.java`
- Test: `VCampusClient/test/service/CoursePushCoordinatorTest.java`
- Test: `VCampusClient/test/network/MessageDispatcherTest.java`
- Test: `VCampusClient/test/controller/CourseSelectionControllerTest.java`
- Create: `VCampusClient/test/controller/ScheduleControllerTest.java`
- Test: `VCampusClient/test/controller/ScheduleLayoutTest.java`
- Test: `VCampusClient/test/controller/GradeControllerTest.java`
- Test: `VCampusClient/test/controller/TrainingPlanControllerTest.java`
- Test: `VCampusClient/test/service/MockCourseScheduleTest.java`
- Test: `VCampusClient/test/service/MockCourseGradeTest.java`
- Test: `VCampusClient/test/service/MockTrainingPlanTest.java`

**Interfaces:**
- Consumes: final `CourseService` from Task 2, shared DTOs from Task 1 and server protocol from Tasks 4-7.
- Produces: real Socket-backed selection/read operations, push listeners, reconnection reconciliation, and server-driven terms across all course pages.

- [ ] **Step 1: Write failing transport and DTO mapping tests**

Use a fake `CourseTransport` that captures outgoing messages and returns completed responses. Assert exact module/action/data keys, decimal-string course/offering/event IDs, UUID propagation, `TypeToken` conversion from `Message.data`, nullable grade fields, structured teacher/meeting mapping, six states, mutation snapshots and stable non-success exceptions.

For every business mutation assert the caller-provided operation ID appears unchanged:

```java
service.selectOffering(term, 9007199254740993L, operationId).join();
require(operationId.equals(transport.lastRequest.getData("operationId")), "operationId required");
require("9007199254740993".equals(transport.lastRequest.getData("offeringId")),
        "offering ID must be decimal text");
```

- [ ] **Step 2: Write failing push/connection tests**

Extend client `MessageDispatcherTest` to register multiple listeners by module/action, safely remove one, dispatch PUSH without touching pending response futures, and tolerate a listener exception. Register a listener before replacing the connection dispatcher, then dispatch a push through the replacement and prove the listener still runs. `CoursePushCoordinatorTest` must use server `expiresAt` and cover: duplicate arrival while ACK is pending is coalesced; failed ACK is retried on redelivery; user notification is shown only after ACK and only once; failed snapshot refresh is retried locally after ACK; and completion is remembered only after both ACK and refresh succeed. Add a Socket reconnect-listener test proving a new connection generation triggers one authoritative refresh and stale generations cannot trigger it.

- [ ] **Step 3: Implement transport and SocketCourseService mappings**

Use this transport boundary so tests can simulate responses, pushes and reconnects without a real singleton Socket:

```java
interface CourseTransport {
    CompletableFuture<Message> send(Message request);
    CourseSubscription subscribePush(String module, String action, Consumer<Message> listener);
    CourseSubscription subscribeReconnect(Runnable listener);
}
```

`SocketCourseTransport.send` delegates to `SocketClient.sendAsync`; its subscriptions delegate to `MessageDispatcher` and Socket connection-generation listeners. `SocketCourseService` builds `MessageType.REQUEST` messages, validates `MessageCode.SUCCESS`, converts response maps through Gson target types, and maps immutable DTOs to views. Do not perform database eligibility, conflict or capacity logic on the client.

- [ ] **Step 4: Implement push listener and reconnect APIs**

`SocketClient` owns one persistent `PushListenerRegistry` that is not replaced on reconnect; every connection-generation `MessageDispatcher` receives that same registry while keeping its own pending-request map. The registry uses thread-safe listener collections and invokes listeners on the `MessageReceiver` thread without holding pending-request locks. `SocketClient` also exposes a persistent connection-generation listener registry invoked after a successful new connection, not for a redundant `connect()` call.

`CoursePushCoordinator` tracks each event as `ackPending/ackDone`, `refreshPending/refreshDone`, and `notificationShown`. It submits ACK first; only ACK success may show the notification and start the authoritative refresh. ACK failure leaves the event retryable on redelivery. Refresh failure after a successful ACK is retried by an injected scheduler with capped backoff until success, term replacement, or disposal, because the server will stop replaying an ACKed event. Duplicate deliveries coalesce in-flight work and never repeat the notification. Only events with both ACK and refresh complete enter a bounded completed-event cache. All UI callbacks go through the supplied JavaFX executor, and remaining time always uses server `expiresAt`.

- [ ] **Step 5: Install service implementations explicitly**

`CourseServices` stores a volatile current service, defaults normal execution to `SocketCourseService`, and provides `install(CourseService)`/`resetToSocket()`. `CourseUiPreview` installs a new `MockCourseService` before loading FXML. Tests install/restore explicitly so they never open a network connection.

- [ ] **Step 6: Update all controllers to server-driven CourseTermView**

Change the retained auxiliary service methods to `loadSchedule(CourseTermView, int)`, `loadNotices(CourseTermView, int)`, and `loadGrades(CourseTermView)`, then update Mock, Socket and all callers/tests in the same step. Selection, schedule and grade pages load terms, select the first result, and use independent generation counters so stale futures cannot replace newer term/content state. Training-plan loading remains student-based without a term parameter. Selection Controller creates one push coordinator, refreshes snapshot on course events/reconnect, and disposes listeners when its view is detached or replaced.

- [ ] **Step 7: Run client network, service, controller and preview tests**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/task8-green $sources
java -cp ".codex-tmp/task8-green;VCampusClient/src;VCampusClient/lib/*" network.MessageDispatcherTest
java -cp ".codex-tmp/task8-green;VCampusClient/src;VCampusClient/lib/*" service.SocketCourseServiceTest
java -cp ".codex-tmp/task8-green;VCampusClient/src;VCampusClient/lib/*" service.CoursePushCoordinatorTest
java --module-path .codex-tmp/openjfx-25.0.4-win/lib --add-modules javafx.controls,javafx.fxml,javafx.swing -cp ".codex-tmp/task8-green;VCampusClient/src;VCampusClient/lib/gson-2.13.2.jar" ui.CourseUiPreview --smoke
```

Run every controller and mock-service test as separate `java` processes. Expected: all exit 0 and preview makes no server connection.

- [ ] **Step 8: Commit real client integration**

```powershell
git add VCampusClient/src/service VCampusClient/src/network VCampusClient/src/model/course/CoursePushEventView.java VCampusClient/src/controller VCampusClient/test/service VCampusClient/test/network VCampusClient/test/controller VCampusClient/test/ui/CourseUiPreview.java
git commit -m "feat: connect course client to socket service"
```

### Task 9: Verify the Complete MySQL, Socket and JavaFX Workflow

**Files:**
- Create: `VCampusServer/test/integration/CourseModuleSocketEndToEndTest.java`
- Modify only when a failing verification proves it necessary: files introduced or changed by Tasks 1-8

**Interfaces:**
- Consumes: complete migration, server, Socket client/service, push and JavaFX flows.
- Produces: executable end-to-end evidence and final reviewed branch state.

- [ ] **Step 1: Write a live Socket end-to-end test**

The test aborts unless the JDBC database name is exactly `virtual_campus_course_test`. It resets/reseeds only that schema, starts `Server` on an available loopback port, authenticates a seeded test student, and uses the real JSON-line transport for:

```text
listTerms -> listCourses -> listCourseOfferings -> addToPlan -> selectOffering
full select -> FULL -> joinWaitlist -> WAITLISTED
drop by another student -> WAITLIST_OFFERED or AUTO_ENROLLED push
ACK push -> resolve offer where applicable -> loadSelectionSnapshot
loadSchedule -> loadNotices -> loadGrades -> loadTrainingPlan
```

Assert each persisted state directly through a separate JDBC connection after the protocol response. Include a disconnected waitlist recipient, advance/expire while offline, reconnect, and prove the snapshot restores the authoritative state/deadline even if the push is replayed.

- [ ] **Step 2: Run every framework-free test in clean output directories**

Compile all production and test sources with JavaFX, Gson and both server Connector/J dependencies resolved without duplicate-driver ambiguity. Discover classes containing `public static void main` under all three test roots and run them individually, excluding only helpers explicitly lacking a main entry.

Expected: every process exits 0. Record command, exit code and elapsed time in the task report rather than claiming a single compilation proves runtime behavior.

- [ ] **Step 3: Run database invariants under contention**

Repeat final-seat and duplicate-operation tests for at least 100 synchronized iterations. After each iteration query:

```sql
enrolled_count >= 0
enrolled_count + active_unexpired_offers <= capacity
COUNT(active enrollment per uid/term/course) <= 1
COUNT(operation result per uid/operation_id) = 1
COUNT(enrollment where status NOT IN (2,3)) = 0
```

Expected: zero invariant violations and zero deadlocks left unhandled. A retry may handle a detected MySQL deadlock only at the service boundary with the same operation ID and bounded attempts.

- [ ] **Step 4: Parse FXML and capture all four course pages at 860 x 580**

Run `CourseUiSmokeTest` in mock mode to generate selection, schedule, grade and training-plan screenshots. Inspect the actual images at original resolution for blank content, clipping, overlap, text overflow and layout shifts. Specifically expand a course with two teaching classes and capture “全部”, then capture `FULL`, `WAITLISTED`, `WAITLIST_OFFERED` and `ENROLLED` states in their relevant tabs.

- [ ] **Step 5: Run final static checks**

```powershell
Get-ChildItem VCampusClient/src/resources/fxml -Filter *.fxml | ForEach-Object { [xml](Get-Content -Raw -LiteralPath $_.FullName) | Out-Null }
rg -n "confirmPlan|PlanConfirmation|等待抽签|LOTTERY|LEAVE_WAITLIST|DROP_COURSE|status[[:space:]]+IN[[:space:]]*\\(1,[[:space:]]*2,[[:space:]]*3,[[:space:]]*4\\)" VCampusCommon/src VCampusClient/src VCampusServer/src
git diff --check
git status --short
git check-ignore VCampusServer/src/resources/db.properties .codex-tmp
```

Expected: no obsolete production contract, no whitespace errors, local secrets/build artifacts ignored, and no unrelated user work lost.

- [ ] **Step 6: Request a fresh whole-branch review**

Use a new `gpt-5.6-sol` high-effort reviewer. Review commits after `ed8f3bb` for authentication bypass, UID/BIGINT mismatch, transaction scope, lock-order reversal, queue jumping, capacity/reservation drift, idempotency replay errors, outbox loss/duplication, Socket write races, JavaFX thread misuse, listener leaks and missing tests. Resolve every confirmed P0/P1 issue and rerun Steps 2-5.

- [ ] **Step 7: Commit the end-to-end test and verified fixes**

```powershell
git add VCampusServer/test/integration/CourseModuleSocketEndToEndTest.java
git commit -m "test: verify course management end to end"
```

Do not create an empty commit. Final reporting must distinguish design review, compilation, unit tests, MySQL tests, real Socket tests and JavaFX screenshot inspection.
