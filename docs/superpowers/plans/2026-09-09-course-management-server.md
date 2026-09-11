# Course Management Server Integration Implementation Plan

> **Superseded:** Do not execute this plan after commit `d178c9c`. The approved replacement is `docs/superpowers/plans/2026-09-10-course-management-system.md`; this file remains only as historical context for completed prerequisite Tasks 1-3.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the completed JavaFX course-management client to authenticated Socket server APIs backed by transactional MySQL course data while preserving the standalone mock preview.

**Architecture:** Shared course DTOs cross the existing JSON-line `Message` protocol. `SocketCourseService` maps those DTOs to client view models, while `CourseHandler` authenticates requests and delegates queries and transactional mutations to focused server services and DAOs. Database migrations are selectively ported from `feature/courses` into this branch and extended additively.

**Tech Stack:** Java 25, JavaFX 25/26 FXML and Controls, Gson 2.13.2, MySQL 8, JDBC, CompletableFuture, framework-free Java assertion tests

**Spec:** `docs/superpowers/specs/2026-09-09-course-management-server-design.md`

## Global Constraints

- Work only in `D:\JavaProject\VCampus\.worktrees\course-management-client` on `feature/course-management-client`.
- Treat `feature/courses` and commit `d71ef18` as read-only reference material; do not switch, reset, commit, or write to that branch.
- Do not commit `db.properties`, the local MySQL password, generated classes, screenshots, logs, or `.codex-tmp`.
- Derive the student UID from `SessionManager.getSession(token)`, never from request `sender` or `data.uid`.
- Encode every database `BIGINT` identifier as a decimal string in JSON DTOs; keep account UID values as `VARCHAR(32)` strings.
- Execute capacity and enrollment state changes in one JDBC transaction with `SELECT ... FOR UPDATE`.
- Keep front-end filtering display-only; enforce term, role, major, cohort, window, capacity, and schedule rules on the server.
- Keep `MockCourseService` working for tests and `CourseUiPreview`; normal application execution uses `SocketCourseService`.
- Preserve the existing 860 x 580 client layout and existing unrelated working-tree changes.
- Use framework-free `main` tests and the repository's checked-in JARs; do not add a build framework or dependency.

---

### Task 1: Preserve the Completed Client Baseline

**Files:**
- Modify: `VCampusClient/src/controller/CourseManagementController.java`
- Modify: `VCampusClient/src/resources/fxml/CourseManagementView.fxml`
- Modify: `VCampusClient/src/resources/fxml/CourseSelectionView.fxml`
- Create: `VCampusClient/test/ui/CourseUiPreview.java`
- Create: `VCampusClient/test/ui/CourseUiPreviewTest.java`

**Interfaces:**
- Consumes: the existing `CourseManagementView.fxml` and `MockCourseService`.
- Produces: a no-server preview entry and a clean committed baseline for later client changes.

- [ ] **Step 1: Inspect the existing dirty diff without rewriting it**

Run:

```powershell
git diff -- VCampusClient/src/controller/CourseManagementController.java VCampusClient/src/resources/fxml/CourseManagementView.fxml VCampusClient/src/resources/fxml/CourseSelectionView.fxml
Get-Content -Raw VCampusClient/test/ui/CourseUiPreview.java
Get-Content -Raw VCampusClient/test/ui/CourseUiPreviewTest.java
```

Expected: navigation clears all page/toggle states before activating one page, the title is `教务管理系统`, and the preview loads course FXML directly without `SocketClient`.

- [ ] **Step 2: Remove only the known trailing whitespace in CourseSelectionView.fxml**

Change the closing top `VBox` line from `<\/VBox>    ` to `<\/VBox>`. Do not alter the title, top inset, or navigation logic.

- [ ] **Step 3: Compile and run the preview smoke entry**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusClient/src,VCampusClient/test/ui -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/*" -d .codex-tmp/classes $sources
java --module-path .codex-tmp/openjfx-25.0.4-win/lib --add-modules javafx.controls,javafx.fxml,javafx.swing -cp ".codex-tmp/classes;VCampusClient/src;VCampusClient/lib/gson-2.13.2.jar" ui.CourseUiPreview --smoke
```

Expected: compilation succeeds and the smoke process exits with code 0 without attempting a server connection.

- [ ] **Step 4: Commit only the verified baseline files**

```powershell
git add VCampusClient/src/controller/CourseManagementController.java VCampusClient/src/resources/fxml/CourseManagementView.fxml VCampusClient/src/resources/fxml/CourseSelectionView.fxml VCampusClient/test/ui/CourseUiPreview.java VCampusClient/test/ui/CourseUiPreviewTest.java
git commit -m "test: add standalone course UI preview"
```

### Task 2: Shared Course Protocol DTOs

**Files:**
- Create: `VCampusCommon/src/dto/course/CourseActions.java`
- Create: `VCampusCommon/src/dto/course/CourseTermDTO.java`
- Create: `VCampusCommon/src/dto/course/SelectionStateDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseOfferingDTO.java`
- Create: `VCampusCommon/src/dto/course/ScheduleEntryDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseNoticeDTO.java`
- Create: `VCampusCommon/src/dto/course/GradeRecordDTO.java`
- Create: `VCampusCommon/src/dto/course/GradeSummaryDTO.java`
- Create: `VCampusCommon/src/dto/course/TrainingPlanCourseDTO.java`
- Create: `VCampusCommon/src/dto/course/TrainingPlanGroupDTO.java`
- Create: `VCampusCommon/src/dto/course/PlanConfirmationItemDTO.java`
- Create: `VCampusCommon/src/dto/course/PlanConfirmationDTO.java`
- Test: `VCampusCommon/test/dto/course/CourseDtoJsonTest.java`

**Interfaces:**
- Consumes: Gson JSON serialization and the existing `protocol.Message.data` map.
- Produces: `CourseActions` string constants and immutable DTOs shared by client and server.

- [ ] **Step 1: Write the failing DTO JSON test**

Create a test that constructs an offering with ID `9007199254740993`, serializes it through Gson, deserializes it with `CourseOfferingDTO.class`, and asserts exact string equality. It must also serialize and deserialize a `List<CourseTermDTO>` using `new TypeToken<List<CourseTermDTO>>() {}.getType()`.

```java
CourseOfferingDTO source = new CourseOfferingDTO(
        "9007199254740993", "CS203", "数据结构", "必修", 4.0, 64,
        "张老师", "周二 3-4节", "教四-201", "线性表、树和图", "程序设计基础",
        96, 120, SelectionStateDTO.AVAILABLE);
CourseOfferingDTO copy = gson.fromJson(gson.toJson(source), CourseOfferingDTO.class);
require(source.getOfferingId().equals(copy.getOfferingId()), "BIGINT ID must stay exact");
```

- [ ] **Step 2: Compile and verify the DTO test fails**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test/dto/course -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar" -d .codex-tmp/classes $sources
```

Expected: compilation fails because `dto.course` types do not exist.

- [ ] **Step 3: Implement protocol constants and DTOs**

Use exactly these actions:

```java
public static final String LIST_TERMS = "listTerms";
public static final String LIST_OFFERINGS = "listOfferings";
public static final String ADD_TO_PLAN = "addToPlan";
public static final String REMOVE_FROM_PLAN = "removeFromPlan";
public static final String CONFIRM_PLAN = "confirmPlan";
public static final String JOIN_WAITLIST = "joinWaitlist";
public static final String LEAVE_WAITLIST = "leaveWaitlist";
public static final String DROP_COURSE = "dropCourse";
public static final String LOAD_SCHEDULE = "loadSchedule";
public static final String LOAD_NOTICES = "loadNotices";
public static final String LOAD_GRADES = "loadGrades";
public static final String LOAD_TRAINING_PLAN = "loadTrainingPlan";
```

Use `SelectionStateDTO { AVAILABLE, PLANNED, WAITLISTED, ENROLLED }`. `CourseTermDTO` contains `academicYear`, `semester`, and `displayName`. DTO collection fields must be defensive unmodifiable copies. Grade component fields use nullable `Double`; all IDs use `String`.

`PlanConfirmationItemDTO` contains `offeringId`, `courseName`, `selectionState`, `successful`, and `reason`. `PlanConfirmationDTO` contains an immutable list named `items` plus a refreshed immutable `offerings` list.

Use these remaining DTO field contracts:

```text
ScheduleEntryDTO: offeringId, term, courseCode, courseName, teacher, location,
                  dayOfWeek, startPeriod, periodCount, startWeek, endWeek
CourseNoticeDTO: noticeId, offeringId, term, week, noticeType, title, content
GradeRecordDTO: term, courseCode, courseName, credit, score, gradePoint,
                dailyScore, midtermScore, experimentScore, finalScore
GradeSummaryDTO: term, termGpa, termAverage, cumulativeAverage, cumulativeGpa, records
TrainingPlanCourseDTO: courseCode, courseName, credit, completionStatus
TrainingPlanGroupDTO: name, requiredCredits, earnedCredits, courses
```

- [ ] **Step 4: Run the DTO JSON test**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test/dto/course -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar" -d .codex-tmp/classes $sources
java -cp ".codex-tmp/classes;VCampusClient/lib/gson-2.13.2.jar" dto.course.CourseDtoJsonTest
```

Expected: process exits with code 0.

- [ ] **Step 5: Commit shared protocol DTOs**

```powershell
git add VCampusCommon/src/dto/course VCampusCommon/test/dto/course
git commit -m "feat: add shared course protocol DTOs"
```

### Task 3: Request Correlation and Socket Reliability

**Files:**
- Modify: `VCampusCommon/src/protocol/Message.java`
- Modify: `VCampusClient/src/network/MessageDispatcher.java`
- Modify: `VCampusClient/src/network/MessageReceiver.java`
- Modify: `VCampusClient/src/network/SocketClient.java`
- Test: `VCampusCommon/test/protocol/MessageUidTest.java`
- Test: `VCampusClient/test/network/MessageDispatcherTest.java`

**Interfaces:**
- Consumes: existing JSON-line Socket protocol.
- Produces: collision-free request IDs, timeout cleanup, serialized writes, and connection-generation isolation.

- [ ] **Step 1: Write failing correlation tests**

`MessageUidTest` creates 20,000 `Message` instances in parallel and asserts every UID is non-null and unique. `MessageDispatcherTest` verifies that duplicate registration is rejected and that `failPending(uid, cause)` completes and removes only the matching future.

```java
boolean first = dispatcher.registerPendingRequest(42L, futureA);
boolean duplicate = dispatcher.registerPendingRequest(42L, futureB);
require(first && !duplicate, "duplicate UID must not replace an existing future");
dispatcher.failPending(42L, new TimeoutException("request timed out"));
require(futureA.isCompletedExceptionally(), "registered future must fail");
require(!futureB.isDone(), "unregistered duplicate must remain untouched");
```

- [ ] **Step 2: Run tests and verify failure**

Compile common and client network sources with Gson. Expected: tests fail because UID generation uses milliseconds and dispatcher methods do not expose safe registration/failure behavior.

- [ ] **Step 3: Implement monotonic IDs and dispatcher cleanup**

Initialize a static `AtomicLong` from `System.currentTimeMillis()` and assign UIDs with `incrementAndGet()`. Change `registerPendingRequest` to return `pendingRequests.putIfAbsent(uid, future) == null`. Add `failPending(Long uid, Throwable cause)` that removes and exceptionally completes one pending future.

- [ ] **Step 4: Add request timeout and connection isolation**

`SocketClient.sendAsync` schedules a 10-second timeout on a daemon `ScheduledExecutorService`; completion cancels that timeout. Guard `writer.println` and `writer.checkError` with a dedicated `sendLock`.

Create a new `MessageDispatcher` for every successful connection and give that exact instance to its `MessageReceiver`. When reconnecting, fail only the previous dispatcher's pending futures before replacing it. Do not automatically retry writes.

- [ ] **Step 5: Run the network tests and existing login compile check**

```powershell
$sources = Get-ChildItem VCampusCommon/src,VCampusCommon/test/protocol,VCampusClient/src/network,VCampusClient/src/session,VCampusClient/test/network -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -cp "VCampusClient/lib/gson-2.13.2.jar;VCampusCommon/src" -d .codex-tmp/classes $sources
java -cp .codex-tmp/classes protocol.MessageUidTest
java -cp .codex-tmp/classes network.MessageDispatcherTest
```

Expected: both tests pass.

- [ ] **Step 6: Commit network hardening**

```powershell
git add VCampusCommon/src/protocol/Message.java VCampusClient/src/network VCampusCommon/test/protocol/MessageUidTest.java VCampusClient/test/network/MessageDispatcherTest.java
git commit -m "fix: harden socket request correlation"
```

### Task 4: Course Database Migrations

**Files:**
- Create: `VCampusServer/src/resources/migrations/V001_create_course_tables.sql`
- Create: `VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql`
- Create: `VCampusServer/src/resources/migrations/V003_extend_course_management.sql`
- Create: `VCampusServer/src/resources/seed-course-integration.sql`
- Test: `VCampusServer/test/database/CourseMigrationContractTest.java`

**Interfaces:**
- Consumes: the current branch's `tbl_user(UID VARCHAR(32))` from `VCampusServer/src/resources/init.sql` and read-only SQL from `feature/courses`.
- Produces: one non-overlapping migration sequence supporting all approved student APIs.

- [ ] **Step 1: Write the failing migration contract test**

The test reads all three migration files and asserts:

```java
require(countCreate(v001, "course_offering") == 1, "V001 owns course_offering");
require(countCreate(v001, "schedule_plan") == 0, "V001 must not own schedule tables");
require(countCreate(v002, "schedule_plan") == 1, "V002 owns schedule tables");
require(v003.contains("CREATE TABLE IF NOT EXISTS `course_waitlist`"), "waitlist table required");
require(v003.contains("CREATE TABLE IF NOT EXISTS `training_plan`"), "training plan required");
require(v003.contains("CREATE TABLE IF NOT EXISTS `course_notice`"), "notice table required");
```

Resolve resource paths from the repository root so the test runs without copying SQL into the class output.

- [ ] **Step 2: Verify the contract test fails because migrations are absent**

Compile and run `database.CourseMigrationContractTest`. Expected: failure naming `V001_create_course_tables.sql` as missing.

- [ ] **Step 3: Selectively port V001 and V002**

Read, but do not check out, the reference files:

```powershell
git show feature/courses:VCampusServer/src/resources/migrations/v001_create_course_table.sql
git show feature/courses:VCampusServer/src/resources/migrations/V002_create_table2.sql
```

Create `V001_create_course_tables.sql` with only the reference definitions from `course` through `grade`. Preserve `tbl_user.UID` as the current branch's `VARCHAR(32)` contract, so `course_offering_teacher.uid` and `enrollment.uid` must also be `VARCHAR(32)`; do not copy the reference branch's incompatible `BIGINT` UID change. Create `V002_create_schedule_tables.sql` with exactly one definition of each schedule table from `teaching_calendar` through `course_offering_conflict`. Use `ON UPDATE RESTRICT` for the two offering-conflict foreign keys so both creation paths are deterministic.

- [ ] **Step 4: Implement V003 additive schema**

Create the approved tables with foreign keys and query indexes. Required unique/index contracts are:

```sql
UNIQUE KEY `uk_major_code` (`major_code`)
UNIQUE KEY `uk_student_academic_profile_uid` (`uid`)
UNIQUE KEY `uk_selection_window_term` (`academic_year`, `semester`)
UNIQUE KEY `uk_course_waitlist_uid_offering` (`uid`, `offering_id`)
KEY `idx_course_waitlist_queue` (`offering_id`, `active`, `queue_time`)
UNIQUE KEY `uk_training_plan_identity` (`major_id`, `cohort_year`, `version`)
UNIQUE KEY `uk_training_plan_group_name` (`plan_id`, `group_name`)
PRIMARY KEY (`group_id`, `course_id`)
KEY `idx_course_notice_offering_week` (`offering_id`, `week_no`, `status`)
KEY `idx_course_major_major` (`major_id`, `course_id`)
KEY `idx_course_year_year` (`year`, `course_id`)
```

Use these exact columns and meanings:

```text
major: major_id INT AUTO_INCREMENT PK, major_code VARCHAR(32), major_name VARCHAR(100)
student_academic_profile: uid VARCHAR(32) PK, major_id INT FK, enrollment_year INT
course_selection_window: window_id BIGINT AUTO_INCREMENT PK, academic_year INT,
    semester TINYINT, plan_start_at DATETIME, plan_end_at DATETIME,
    confirm_start_at DATETIME, confirm_end_at DATETIME, drop_deadline DATETIME,
    status TINYINT (1-DRAFT, 2-OPEN, 3-CLOSED)
course_waitlist: waitlist_id BIGINT AUTO_INCREMENT PK, offering_id BIGINT FK,
    uid VARCHAR(32) FK, queue_time DATETIME, active TINYINT
training_plan: plan_id BIGINT AUTO_INCREMENT PK, major_id INT FK,
    cohort_year INT, version INT, status VARCHAR(20)
training_plan_group: group_id BIGINT AUTO_INCREMENT PK, plan_id BIGINT FK,
    group_name VARCHAR(100), required_credits DECIMAL(5,2), sort_order INT
training_plan_course: group_id BIGINT FK, course_id BIGINT FK,
    recommended_term TINYINT, requirement_type VARCHAR(20)
course_notice: notice_id BIGINT AUTO_INCREMENT PK, offering_id BIGINT FK,
    occurrence_id BIGINT NULL FK, week_no SMALLINT, notice_type VARCHAR(20),
    title VARCHAR(200), content TEXT, status VARCHAR(20), published_at DATETIME,
    created_by VARCHAR(32) FK
```

Add nullable `academic_year INT` and `semester TINYINT` columns to `teaching_calendar`, back them with `uk_teaching_calendar_term_version(academic_year, semester, version)`, and validate a non-null semester as `1..3`. Existing calendars remain valid but are ignored by term queries until assigned a term. This gives schedule and conflict queries a real relationship instead of parsing calendar names.

Use checks for active/published booleans `0/1`, training-plan/notice status values `DRAFT` and `PUBLISHED`, and notice type values `GENERAL`, `CANCELLED`, `RESCHEDULED`. Every FK to `tbl_user.UID` uses `VARCHAR(32)`, matching the current branch and its alphanumeric accounts. Do not add a `course_major.major_id` foreign key in this migration because existing installations may contain locally assigned major IDs; enforce that relationship after those IDs are migrated into the new dictionary.

- [ ] **Step 5: Add deterministic integration seed data**

Seed one student profile, one major, two terms, at least four courses, four offerings covering available/full/lottery/enrolled states, published schedule data, one notice, two published grades, and one published training plan. Use only numeric UIDs compatible with `BIGINT`.

- [ ] **Step 6: Apply migrations to an isolated MySQL schema**

Create only `virtual_campus_course_test`, apply the authoritative `tbl_user` definition followed by V001, V002, V003, and the integration seed, and abort on the first SQL error. Verify `SHOW TABLES` contains the core, schedule, waitlist, notice, and training-plan tables. Never apply destructive setup to `virtual_campus`.

- [ ] **Step 7: Run the migration contract test and SQL static checks**

```powershell
javac -encoding UTF-8 -d .codex-tmp/classes VCampusServer/test/database/CourseMigrationContractTest.java
java -cp .codex-tmp/classes database.CourseMigrationContractTest
git diff --check -- VCampusServer/src/resources
```

Expected: contract test passes and no whitespace error is reported.

- [ ] **Step 8: Commit migrations**

```powershell
git add VCampusServer/src/resources/migrations VCampusServer/src/resources/seed-course-integration.sql VCampusServer/test/database/CourseMigrationContractTest.java
git commit -m "feat: add course management database migrations"
```

### Task 5: Authenticated Server Query APIs

**Files:**
- Create: `VCampusServer/src/dao/CourseQueryDAO.java`
- Create: `VCampusServer/src/dao/GradeDAO.java`
- Create: `VCampusServer/src/dao/TrainingPlanDAO.java`
- Create: `VCampusServer/src/service/CourseApplicationService.java`
- Create: `VCampusServer/src/handler/CourseHandler.java`
- Modify: `VCampusServer/src/network/MessageDispatcher.java`
- Test: `VCampusServer/test/handler/CourseHandlerTest.java`
- Test: `VCampusServer/test/service/CourseQueryMappingTest.java`

**Interfaces:**
- Consumes: Task 2 DTOs, existing `SessionManager`, `DBUtil`, and Task 4 schema.
- Produces: authenticated read actions for terms, offerings, schedule, notices, grades, and training plans.

- [ ] **Step 1: Write failing handler authentication tests**

Use constructor injection for a non-final `CourseApplicationService` so a package-local test subclass can count calls without touching MySQL. `CourseHandler()` constructs the JDBC-backed service; `CourseHandler(CourseApplicationService)` is package-visible for tests. Test no token -> `UNAUTHORIZED`, teacher session -> `FORBIDDEN`, malformed term/week -> `BAD_REQUEST`, and valid student session -> service called with the UID from the session rather than request sender.

```java
UserSession student = SessionManager.getInstance().createSession("20240001", "学生");
Message request = new Message(MessageType.REQUEST, "course", CourseActions.LIST_TERMS);
request.setToken(student.getToken());
request.setSender("99999999");
Message response = handler.handle(request);
require("20240001".equals(service.lastStudentUid), "session UID must win over sender");
```

- [ ] **Step 2: Verify handler tests fail before implementation**

Compile common and server sources. Expected: failure because `CourseHandler` and course service do not exist.

- [ ] **Step 3: Implement query DAOs with explicit mappings**

`CourseQueryDAO` exposes `listTerms`, `listOfferings`, `loadSchedule`, and `loadNotices`. Join `course_offering`, `course`, teacher user rows, published schedule rules/occurrences, classrooms, current enrollment, and active waitlist records. Aggregate multiple teacher names deterministically and map state precedence as `ENROLLED`, `WAITLISTED`, `PLANNED`, then `AVAILABLE`.

`GradeDAO.loadGrades` joins `grade -> enrollment -> course_offering -> course`, filters `is_published = 1`, preserves nullable score components with `ResultSet.wasNull()`, and computes term/cumulative weighted averages from published results.

`TrainingPlanDAO.loadTrainingPlan` selects the newest published plan matching `student_academic_profile.major_id` and `enrollment_year`, then derives course status as `已修`, `在修`, or `未修`. Count credits as earned only for published passing grades.

- [ ] **Step 4: Implement CourseApplicationService read methods**

Use these public signatures:

```java
List<CourseTermDTO> listTerms(String studentUid) throws BusinessException, DatabaseException;
List<CourseOfferingDTO> listOfferings(String studentUid, int academicYear, int semester) throws BusinessException, DatabaseException;
List<ScheduleEntryDTO> loadSchedule(String studentUid, int academicYear, int semester, int week) throws BusinessException, DatabaseException;
List<CourseNoticeDTO> loadNotices(String studentUid, int academicYear, int semester, int week) throws BusinessException, DatabaseException;
GradeSummaryDTO loadGrades(String studentUid, int academicYear, int semester) throws BusinessException, DatabaseException;
List<TrainingPlanGroupDTO> loadTrainingPlan(String studentUid) throws BusinessException, DatabaseException;
```

Validate `academicYear > 0`, semester `1..3`, and week `1..30` before DAO calls.

- [ ] **Step 5: Implement CourseHandler and register the module**

Authenticate once at the start of `handle`, parse numeric map values via `Number` or decimal strings, switch on exact `CourseActions` constants, and return DTO values under stable keys: `terms`, `offerings`, `schedule`, `notices`, `grades`, `trainingPlan`. Add `courseHandler` to the server `MessageDispatcher` and dispatch when module equals `course` ignoring case.

- [ ] **Step 6: Run handler and query mapping tests**

`CourseQueryMappingTest` uses `CachedRowSet` fixtures to verify null grade components and exact BIGINT string mapping without a database. Run both test mains; expected exit code is 0.

- [ ] **Step 7: Commit server read APIs**

```powershell
git add VCampusServer/src/dao/CourseQueryDAO.java VCampusServer/src/dao/GradeDAO.java VCampusServer/src/dao/TrainingPlanDAO.java VCampusServer/src/service/CourseApplicationService.java VCampusServer/src/handler/CourseHandler.java VCampusServer/src/network/MessageDispatcher.java VCampusServer/test/handler VCampusServer/test/service
git commit -m "feat: add course query server APIs"
```

### Task 6: Transactional Enrollment and Waitlist APIs

**Files:**
- Create: `VCampusServer/src/dao/EnrollmentDAO.java`
- Modify: `VCampusServer/src/service/CourseApplicationService.java`
- Modify: `VCampusServer/src/handler/CourseHandler.java`
- Test: `VCampusServer/test/service/CourseSelectionIntegrationTest.java`

**Interfaces:**
- Consumes: authenticated student UID, `CourseTermDTO`, course schema, and `PlanConfirmationDTO`.
- Produces: all six selection mutations with transactional capacity and waitlist behavior.

- [ ] **Step 1: Write the database integration test cases first**

Against a dedicated `virtual_campus_course_test` schema, test:

```text
add available offering -> PLANNED
repeat add -> remains one PLANNED row
remove planned offering -> AVAILABLE
confirm course with capacity -> ENROLLED and enrolled_count + 1
confirm full course -> WAITLISTED with one active queue row
confirm lottery course -> PLANNED with reason 等待抽签
schedule conflict -> remains PLANNED with conflict reason
drop before deadline -> DROPPED and first eligible waiter promoted
drop after deadline -> CONFLICT and no data changed
```

The test must abort rather than create or drop anything unless the JDBC URL database name is exactly `virtual_campus_course_test`.

- [ ] **Step 2: Run the integration test and verify implementation is missing**

Compile with MySQL Connector/J. Expected: compilation fails because mutation methods and `EnrollmentDAO` are absent.

- [ ] **Step 3: Implement connection-aware EnrollmentDAO operations**

DAO methods accept a caller-owned `Connection` and never close it. Include operations to lock an offering, read the current enrollment/waitlist state, validate student profile and selection window, query `course_offering_conflict`, upsert/delete a plan, activate/deactivate waitlist rows, update counts, mark a drop, and fetch the queue head ordered by `queue_time, uid`.

- [ ] **Step 4: Implement service transactions**

Add these public methods to `CourseApplicationService`:

```java
CourseOfferingDTO addToPlan(String studentUid, int academicYear, int semester, long offeringId)
        throws BusinessException, DatabaseException;
CourseOfferingDTO removeFromPlan(String studentUid, int academicYear, int semester, long offeringId)
        throws BusinessException, DatabaseException;
PlanConfirmationDTO confirmPlan(String studentUid, int academicYear, int semester)
        throws BusinessException, DatabaseException;
CourseOfferingDTO joinWaitlist(String studentUid, int academicYear, int semester, long offeringId)
        throws BusinessException, DatabaseException;
CourseOfferingDTO leaveWaitlist(String studentUid, int academicYear, int semester, long offeringId)
        throws BusinessException, DatabaseException;
CourseOfferingDTO dropCourse(String studentUid, int academicYear, int semester, long offeringId)
        throws BusinessException, DatabaseException;
```

For each mutation:

```java
try (Connection connection = DBUtil.getConnection()) {
    connection.setAutoCommit(false);
    try {
        // Lock, validate, mutate, and update counters.
        connection.commit();
    } catch (Exception exception) {
        connection.rollback();
        throw exception;
    }
}
```

Sort planned offering IDs before `confirmPlan` locks them. Return one `PlanConfirmationItemDTO` per planned course and a refreshed offering snapshot. Promotion skips invalid candidates and deactivates only stale queue entries; it stops after promoting one eligible student.

- [ ] **Step 5: Map mutations in CourseHandler**

Parse `offeringId` from a decimal string. Map missing resources to `NOT_FOUND`, eligibility/window/state conflicts to `CONFLICT`, invalid format to `BAD_REQUEST`, and SQL failures to `ERROR`. Never include SQL text in `response.message`.

- [ ] **Step 6: Run integration and handler tests**

Run `CourseSelectionIntegrationTest` with the ignored local DB configuration and rerun `CourseHandlerTest`. Expected: every state transition and rollback assertion passes.

- [ ] **Step 7: Commit transactional selection**

```powershell
git add VCampusServer/src/dao/EnrollmentDAO.java VCampusServer/src/service/CourseApplicationService.java VCampusServer/src/handler/CourseHandler.java VCampusServer/test/service/CourseSelectionIntegrationTest.java
git commit -m "feat: add transactional course selection"
```

### Task 7: Real Client Course Service

**Files:**
- Create: `VCampusClient/src/model/course/CourseTermView.java`
- Create: `VCampusClient/src/model/course/PlanConfirmationItemView.java`
- Create: `VCampusClient/src/model/course/PlanConfirmationView.java`
- Create: `VCampusClient/src/service/CourseTransport.java`
- Create: `VCampusClient/src/service/SocketCourseTransport.java`
- Create: `VCampusClient/src/service/SocketCourseService.java`
- Modify: `VCampusClient/src/service/CourseService.java`
- Modify: `VCampusClient/src/service/CourseServices.java`
- Modify: `VCampusClient/src/service/MockCourseService.java`
- Modify: `VCampusClient/test/ui/CourseUiPreview.java`
- Test: `VCampusClient/test/service/SocketCourseServiceTest.java`
- Test: `VCampusClient/test/service/MockCourseServiceTest.java`
- Test: `VCampusClient/test/service/MockCourseScheduleTest.java`
- Test: `VCampusClient/test/service/MockCourseGradeTest.java`
- Test: `VCampusClient/test/service/MockTrainingPlanTest.java`

**Interfaces:**
- Consumes: Task 2 DTOs and `SocketClient.sendAsync`.
- Produces: a real asynchronous `CourseService` plus explicit mock installation for previews/tests.

- [ ] **Step 1: Write failing SocketCourseService mapping tests**

Use a fake `CourseTransport` that captures the outgoing `Message` and returns completed responses. Assert module/action, term fields, decimal-string offering IDs, non-success exception mapping, nullable grade fields, and DTO-to-view conversion.

```java
service.addToPlan(term, 9007199254740993L).get();
require("9007199254740993".equals(transport.lastRequest.getData("offeringId")),
        "offering ID must be sent as a decimal string");
require("course".equals(transport.lastRequest.getModule()), "course module required");
```

- [ ] **Step 2: Update the CourseService contract**

Use these signatures:

```java
CompletableFuture<List<CourseTermView>> loadTerms();
CompletableFuture<List<CourseOfferingView>> loadOfferings(CourseTermView term);
CompletableFuture<CourseOfferingView> addToPlan(CourseTermView term, long offeringId);
CompletableFuture<CourseOfferingView> removeFromPlan(CourseTermView term, long offeringId);
CompletableFuture<PlanConfirmationView> confirmPlan(CourseTermView term);
CompletableFuture<CourseOfferingView> joinWaitlist(CourseTermView term, long offeringId);
CompletableFuture<CourseOfferingView> leaveWaitlist(CourseTermView term, long offeringId);
CompletableFuture<CourseOfferingView> dropCourse(CourseTermView term, long offeringId);
CompletableFuture<List<ScheduleEntryView>> loadSchedule(CourseTermView term, int week);
CompletableFuture<List<CourseNoticeView>> loadNotices(CourseTermView term, int week);
CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term);
CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan();
```

`CourseTermView.toString()` returns its display name so JavaFX ComboBox cells need no custom renderer.

- [ ] **Step 3: Implement transport and DTO mapping**

`SocketCourseTransport.send` delegates to the singleton `SocketClient`. `SocketCourseService` creates `MessageType.REQUEST` messages, validates `MessageCode.SUCCESS`, converts response map values back through Gson/`TypeToken`, and maps DTO values to immutable client views. Server business failures complete futures exceptionally with a stable client exception message.

- [ ] **Step 4: Make service selection explicit**

`CourseServices` holds a volatile current implementation initialized to `new SocketCourseService(new SocketCourseTransport())`. Add `install(CourseService)` and `resetToSocket()` methods. `CourseUiPreview` installs `new MockCourseService()` before FXML loading. Tests construct/inject services directly or restore the provider after use.

- [ ] **Step 5: Update MockCourseService for terms and confirmation details**

Return deterministic `CourseTermView` values, accept term arguments, and return `PlanConfirmationView` items for enrolled, waitlisted, and pending-lottery outcomes. Preserve all existing mock state-transition assertions.

- [ ] **Step 6: Run socket mapping and all mock tests**

Compile common/client production and service tests, then run all five mock tests plus `SocketCourseServiceTest`. Expected: all exit with code 0 and preview smoke still makes no connection.

- [ ] **Step 7: Commit real client service**

```powershell
git add VCampusClient/src/model/course VCampusClient/src/service VCampusClient/test/service VCampusClient/test/ui/CourseUiPreview.java
git commit -m "feat: connect course client service to socket API"
```

### Task 8: Dynamic Terms and Confirmation Results in JavaFX

**Files:**
- Modify: `VCampusClient/src/resources/fxml/CourseSelectionView.fxml`
- Modify: `VCampusClient/src/controller/CourseSelectionController.java`
- Modify: `VCampusClient/src/controller/ScheduleController.java`
- Modify: `VCampusClient/src/controller/GradeController.java`
- Modify: `VCampusClient/src/resources/css/style.css`
- Test: `VCampusClient/test/controller/CourseSelectionControllerTest.java`
- Test: `VCampusClient/test/controller/ScheduleControllerTest.java`
- Test: `VCampusClient/test/controller/GradeControllerTest.java`

**Interfaces:**
- Consumes: Task 7 `CourseService` signatures and `CourseTermView`.
- Produces: server-driven term controls and per-course confirmation feedback without layout regression.

- [ ] **Step 1: Update controller tests before production code**

Test that each controller loads terms, selects the first returned term, ignores stale asynchronous term/data responses, and avoids data requests when no term exists. Extend selection tests so `PlanConfirmationView` renders counts for enrolled, waitlisted, pending lottery, and failed/conflicting courses.

- [ ] **Step 2: Add the selection term control**

Add `ComboBox<CourseTermView> termFilter` to the existing selection toolbar with a stable width. On term change, clear stale offering data and load offerings for the selected term.

- [ ] **Step 3: Remove hard-coded term constants**

`ScheduleController` and `GradeController` call `loadTerms()` during initialization/refresh and populate `ComboBox<CourseTermView>`. Keep independent generation counters for term loads and content loads so an older completion cannot replace newer UI state.

- [ ] **Step 4: Render batch confirmation feedback**

After `confirmPlan`, update offerings from the returned refreshed snapshot and show one summary dialog. Group outcomes by final state and list failed course names with reasons. Keep pending lottery items in the plan tab and label their row action/status as `等待抽签` without adding a fifth top-level tab.

- [ ] **Step 5: Run controller, service, FXML, and preview tests**

Compile all production and tests; run the controller tests, all service tests, XML-parse every FXML file, and run `CourseUiPreview --smoke`. Expected: no hard-coded term remains in the three production controllers and every test exits with code 0.

- [ ] **Step 6: Capture and inspect all four pages**

Regenerate `selection.png`, `schedule.png`, `grades.png`, and `training-plan.png` at 860 x 580. Inspect at original resolution for clipping, overlap, stable toolbar height, readable result text, and nonblank page content. Apply only course-prefixed CSS changes required by observed defects.

- [ ] **Step 7: Commit dynamic client UI**

```powershell
git add VCampusClient/src/resources/fxml/CourseSelectionView.fxml VCampusClient/src/controller/CourseSelectionController.java VCampusClient/src/controller/ScheduleController.java VCampusClient/src/controller/GradeController.java VCampusClient/src/resources/css/style.css VCampusClient/test/controller
git commit -m "feat: load course terms from server"
```

### Task 9: MySQL and End-to-End Verification

**Files:**
- Create locally but do not commit: `VCampusServer/src/resources/db.properties`
- Create: `VCampusServer/test/integration/CourseModuleEndToEndTest.java`
- Modify only if failures require it: files introduced in Tasks 2-8

**Interfaces:**
- Consumes: complete migrations, server dispatcher, session manager, DAOs, and client DTO contract.
- Produces: evidence that the real database-backed protocol path works with local MySQL.

- [ ] **Step 1: Configure local ignored database credentials**

Create `VCampusServer/src/resources/db.properties` from the example using `root` and the password supplied by the user outside the repository. Confirm `git check-ignore` reports the file as ignored. Never print or stage its contents.

- [ ] **Step 2: Create and migrate the dedicated test schema**

Use MySQL root credentials to create only `virtual_campus_course_test`, then apply the authoritative user table definition, V001, V002, V003, and integration seed in order. Abort on the first SQL error. Do not drop or alter `virtual_campus` during testing.

- [ ] **Step 3: Write and run the end-to-end dispatcher test**

Create a student session with `SessionManager.createSession("20240001", "学生")`. Send `course` Messages through the server `MessageDispatcher` for `listTerms`, `listOfferings`, `addToPlan`, `confirmPlan`, `loadSchedule`, `loadGrades`, and `loadTrainingPlan`. Assert `SUCCESS`, exact response keys, and expected persisted states. This exercises authentication, handler, application service, DAO, SQL, and DTO serialization without bypassing the protocol dispatcher.

- [ ] **Step 4: Run the complete test suite**

Compile all `VCampusCommon`, `VCampusClient`, and `VCampusServer` production/test sources with JavaFX, Gson, and MySQL Connector/J. Run every framework-free test discovered under all three `test` directories. Expected: every process exits with code 0.

- [ ] **Step 5: Run final UI and static verification**

```powershell
Get-ChildItem VCampusClient/src/resources/fxml -Filter *.fxml | ForEach-Object { [xml](Get-Content -Raw -LiteralPath $_.FullName) | Out-Null }
git diff --check
git status --short
```

Run the four-page screenshot smoke test and inspect each image. Expected: FXML parses, screenshots are nonblank at 860 x 580, no whitespace errors exist, and `db.properties`/`.codex-tmp` are not staged.

- [ ] **Step 6: Request a fresh code review agent**

Ask a new `gpt-5.6-sol` high-effort reviewer to inspect the complete branch for authentication bypass, BIGINT precision, transaction boundaries, lock order, counter drift, result mapping, resource leaks, and missing test coverage. Resolve every confirmed P0/P1 issue and rerun Steps 4-5.

- [ ] **Step 7: Commit final integration test or verified fixes**

```powershell
git add VCampusServer/test/integration/CourseModuleEndToEndTest.java
git commit -m "test: verify course module end to end"
```

Do not create an empty commit when the integration test was already committed with a preceding fix.
