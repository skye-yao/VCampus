# Administrator Offering Enrollment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow administrators to search students, preview risks, force permitted additions, and safely remove students from teaching classes while preserving history, counts, grades, and waitlist behavior.

**Architecture:** Read-only preview and authoritative mutation share one risk engine, but mutations re-evaluate under locks. `AdminEnrollmentService` restores or drops existing enrollment rows transactionally, logs forced decisions, and invokes the existing waitlist trigger only after commit.

**Tech Stack:** Java, JavaFX/FXML, Gson, TCP, JDBC/MySQL 8, framework-free tests.

**Spec:** `docs/superpowers/specs/2026-09-12-admin-course-management-design.md`

## Global Constraints

- Complete catalog and scheduling plans first.
- Capacity, schedule, and prerequisite findings are OVERRIDABLE; duplicate active enrollment for the same course and grade-workflow removal restrictions are BLOCKING.
- Never physically delete enrollment history.
- `enrolled_count` changes in the same transaction as enrollment state.
- Same operationId plus same digest replays the stored result; different digest returns CONFLICT.
- Waitlist advancement is triggered only after a successful removal commit.

---

### Task 1: Define Enrollment DTOs and Service Methods

**Files:**
- Create: `VCampusCommon/src/dto/course/admin/enrollment/StudentSearchResultDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/enrollment/OfferingStudentDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/enrollment/AdminEnrollmentPreviewDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/enrollment/AdminEnrollmentRequestDTO.java`
- Create: `VCampusCommon/test/dto/course/admin/AdminEnrollmentDtoJsonTest.java`
- Create: `VCampusClient/src/model/course/admin/StudentSearchResultView.java`
- Create: `VCampusClient/src/model/course/admin/OfferingStudentView.java`
- Modify: `VCampusClient/src/service/AdminCourseService.java`

**Interfaces:**
- Produces: typed search/list/preview/add/remove contracts.

- [ ] **Step 1: Write JSON tests for exact IDs and immutable risks**

Use these signatures:

```java
StudentSearchResultDTO(String uid, String name, String major,
        int cohortYear, String academicStatus)
OfferingStudentDTO(String enrollmentId, String uid, String name,
        String major, int cohortYear, String enrollmentStatus,
        boolean removable, String blockedReason)
AdminEnrollmentPreviewDTO(String offeringId, String studentUid,
        List<ScheduleConflictDTO> risks)
AdminEnrollmentRequestDTO(String operationId, String offeringId,
        String studentUid, boolean force, String overrideReason)
```

Add Service methods:

```java
CompletableFuture<List<StudentSearchResultView>> searchStudents(String query, int page, int size);
CompletableFuture<List<OfferingStudentView>> listOfferingStudents(
        String offeringId, String query, int page, int size);
CompletableFuture<AdminEnrollmentPreviewDTO> previewAdminEnrollment(
        String offeringId, String studentUid);
CompletableFuture<AdminOperationResultView<OfferingStudentView>> addStudentToOffering(
        AdminEnrollmentRequestDTO request);
CompletableFuture<AdminOperationResultView<OfferingStudentView>> removeStudentFromOffering(
        AdminEnrollmentRequestDTO request);
```

- [ ] **Step 2: Compile/run the new DTO test and commit**

Expected first compile failure, then passing Gson round-trip, nullable blockedReason, decimal enrollment ID and unmodifiable risks. Commit with `feat: define administrator enrollment contracts`.

### Task 2: Implement Risk Queries and Transactional Mutations

**Files:**
- Create: `VCampusServer/src/dao/AdminEnrollmentDAO.java`
- Create: `VCampusServer/src/service/AdminEnrollmentRiskService.java`
- Create: `VCampusServer/src/service/AdminEnrollmentService.java`
- Create: `VCampusServer/test/service/AdminEnrollmentMySqlTest.java`

**Interfaces:**
- Consumes: AdminCourseOperationDAO, CourseConflictService, existing WaitlistAdvanceTrigger.
- Produces: search, list, preview, add and remove behavior.

- [ ] **Step 1: Write guarded MySQL scenarios**

Cover exact UID/name search, pagination, full offering, schedule overlap, unmet prerequisite, same-course active enrollment, reactivating status=3, identical replay, force without reason, over-capacity count, removal with pending/published grades, successful removal and rollback after injected failure.

- [ ] **Step 2: Implement DAO SQL**

Search only users with student role and active academic profiles. Lock student profile, offering, and current enrollment. Reuse the existing enrollment row by updating status/drop_time/select_time. Query pending/approved grade submissions and published grade before allowing removal.

Required state updates:

```sql
UPDATE enrollment
SET status=2, select_time=?, drop_time=NULL
WHERE enrollment_id=? AND status=3;

UPDATE enrollment
SET status=3, drop_time=?
WHERE enrollment_id=? AND status=2;

UPDATE course_offering
SET enrolled_count=enrolled_count+?
WHERE offering_id=? AND enrolled_count+? >= 0;
```

- [ ] **Step 3: Implement one shared risk calculation**

Return `CAPACITY`, `STUDENT_SCHEDULE`, and `PREREQUISITE` as OVERRIDABLE. Return `SAME_COURSE_ACTIVE`, `INVALID_STUDENT_STATUS`, `CANCELLED_OFFERING`, and `GRADE_WORKFLOW_LOCKED` as BLOCKING. Preview and mutation call the same method; mutation calls it after locks.

- [ ] **Step 4: Implement add/remove transaction and after-commit trigger**

Validate UUID and reason, replay operation log, lock in stable order, apply state/count, persist result, commit. Only after removal commits call `waitlistTrigger.offeringFreed(offeringId)`; log trigger failure without rolling back the completed removal.

- [ ] **Step 5: Run tests and commit**

Run `AdminEnrollmentMySqlTest` plus existing CourseSelectionMySqlTest and CourseWaitlistMySqlTest. Commit with `feat: manage teaching class students`.

### Task 3: Expose Enrollment Actions Through Handler and Client Service

**Files:**
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java`
- Create: `VCampusServer/test/handler/AdminEnrollmentHandlerTest.java`
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java`
- Modify: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`
- Create: `VCampusClient/test/service/MockAdminEnrollmentServiceTest.java`

**Interfaces:**
- Produces response keys `students`, `offeringStudents`, `preview`, and `result`.

- [ ] **Step 1: Write failing action/mapping tests**

Assert token-derived administrator UID, page bounds, exact decimal IDs, typed preview risks, BAD_REQUEST for blank search/invalid UUID, FORBIDDEN for student/teacher, and safe ERROR messages.

- [ ] **Step 2: Add Handler cases and client Gson mapping**

Use `data.request` for mutation DTOs and explicit scalar parsing for query filters. Search query is trimmed; page starts at 1; size is limited to 1-100.

- [ ] **Step 3: Extend deterministic mock data**

Include normal, schedule-warning, full-capacity and grade-locked students. Mock add/remove must mutate copied state, enforce force reasons and preserve enrollment history semantics.

- [ ] **Step 4: Run tests and commit**

Commit with `feat: expose administrator enrollment APIs` after Handler and client service regressions pass.

### Task 4: Build Add and Remove Student Dialogs

**Files:**
- Create: `VCampusClient/src/controller/AddOfferingStudentDialogController.java`
- Create: `VCampusClient/src/controller/RemoveOfferingStudentDialogController.java`
- Create: `VCampusClient/src/resources/fxml/AddOfferingStudentDialog.fxml`
- Create: `VCampusClient/src/resources/fxml/RemoveOfferingStudentDialog.fxml`
- Modify: `VCampusClient/src/controller/AdminCourseCatalogController.java`
- Modify: `VCampusClient/src/resources/css/style.css`
- Create: `VCampusClient/test/controller/AddOfferingStudentDialogControllerTest.java`
- Create: `VCampusClient/test/controller/RemoveOfferingStudentDialogControllerTest.java`
- Modify: `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`

**Interfaces:**
- Consumes: Task 3 AdminCourseService methods.
- Produces: searchable add/remove workflows from each offering row.

- [ ] **Step 1: Write controller tests**

Verify debounced/explicit search ignores stale results, add always previews first, BLOCKING removes confirmation, OVERRIDABLE requires nonblank reason, cancel sends no mutation, double click sends once, removal displays blockedReason and confirmation text includes student/offering.

- [ ] **Step 2: Build FXML and controllers**

Add dialog contains query, search button, result table and risk panel. Remove dialog contains query filter and current-student table. Keep pagination controls visible when total exceeds page size. Use static FXML skeletons and dynamic row/result content only.

- [ ] **Step 3: Wire offering-row actions**

`添加学生` opens add dialog. `更多 -> 删除学生` opens remove dialog. On successful close, refresh the expanded offering row so current/capacity and student state are authoritative.

- [ ] **Step 4: Run UI tests and visual verification**

Compile all Client sources, parse FXML, run both controller tests and AdminCourseUiSmokeTest. Inspect normal, warning, blocked, empty, long-name and over-capacity screenshots.

- [ ] **Step 5: Commit UI changes**

Commit with `feat: add administrator student management dialogs`.

### Task 5: Verify Enrollment End to End

**Files:**
- Create: `VCampusServer/test/integration/AdminEnrollmentSocketEndToEndTest.java`
- Modify: `VCampusServer/src/resources/seed-course-test.sql`

**Interfaces:**
- Produces: real TCP/MySQL proof including waitlist integration.

- [ ] **Step 1: Implement guarded E2E flow**

Authenticate admin; search a student; preview a full offering; verify non-force add is rejected; force add with reason; replay operationId; verify count exceeds capacity exactly once; verify grade-locked removal is rejected; remove an eligible student; verify status=3, count decrement, audit row and waitlist advancement.

- [ ] **Step 2: Run complete regression suite and commit**

Run AdminEnrollment tests, course selection/waitlist MySQL and scheduler tests, course/admin socket E2E, client service/controller/FXML tests, and `git diff --check`. Commit with `test: verify administrator enrollment end to end`.
