# Grade Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let administrators inspect immutable teaching-class grade submissions, approve them into the published student grade projection, reject them with reasons, and preserve every resubmission/correction version.

**Architecture:** `grade_submission` and its immutable items are the approval facts; the existing `grade` table remains the current published projection consumed by students. Approval locks one batch, validates the complete student/score set, updates the projection and status atomically, and exposes typed summary/detail views through `courseAdmin`.

**Tech Stack:** Java, JavaFX/FXML, Gson, TCP, JDBC/MySQL 8, existing student grade query UI, framework-free tests.

**Spec:** `docs/superpowers/specs/2026-09-12-admin-course-management-design.md`

## Global Constraints

- Complete the first four plans before this plan.
- Teacher grade-entry/submission UI is out of scope; guarded fixtures create submitted versions.
- A submission is immutable after PENDING creation and is approved/rejected as a whole.
- Approval immediately publishes every item; rejection never changes current `grade` rows.
- Corrections and post-rejection retries create a higher version, never overwrite old submission items.
- Students continue querying only `grade.is_published=1` through the existing `course` module.

---

### Task 1: Define Grade Approval DTOs and Service Methods

**Files:**
- Create: `VCampusCommon/src/dto/course/admin/approval/GradeSubmissionSummaryDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/GradeSubmissionItemDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/GradeDistributionBucketDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/GradeSubmissionDetailDTO.java`
- Create: `VCampusCommon/test/dto/course/admin/GradeApprovalDtoJsonTest.java`
- Modify: `VCampusClient/src/service/AdminCourseService.java`

**Interfaces:**
- Produces: grade list/detail/decision contracts.

- [ ] **Step 1: Write failing JSON tests**

Round-trip nullable component scores without converting them to zero, decimal-string IDs, statistics, distribution buckets, status/reviewer fields and immutable item lists.

- [ ] **Step 2: Implement exact DTO signatures**

```java
GradeSubmissionSummaryDTO(String submissionId, String offeringId,
        String courseName, String offeringCode, int version,
        String teacherUid, String teacherName, int studentCount,
        double average, double highest, double lowest, int failCount,
        ApprovalStatusDTO status, String submittedAt)
GradeSubmissionItemDTO(String enrollmentId, String studentUid,
        String studentName, Double dailyScore, Double midtermScore,
        Double experimentScore, Double finaltermScore, Double score,
        Integer gradeLevel, Double gradePoint)
GradeDistributionBucketDTO(String label, int count)
GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
        List<GradeDistributionBucketDTO> distribution,
        List<GradeSubmissionItemDTO> items, String reviewedBy,
        String reviewedAt, String reviewComment)
```

Reuse `ApprovalDecisionRequestDTO`. Add:

```java
CompletableFuture<List<GradeSubmissionSummaryDTO>> listGradeSubmissions(
        ApprovalStatusDTO status, int page, int size);
CompletableFuture<GradeSubmissionDetailDTO> getGradeSubmission(String submissionId);
CompletableFuture<AdminOperationResultView<GradeSubmissionDetailDTO>> reviewGradeSubmission(
        ApprovalDecisionRequestDTO request);
```

- [ ] **Step 3: Run DTO tests and commit**

Compile Common and run GradeApprovalDtoJsonTest plus existing Grade DTO tests. Commit with `feat: define grade approval contracts`.

### Task 2: Implement Grade Submission Queries and Approval Transaction

**Files:**
- Create: `VCampusServer/src/dao/GradeApprovalDAO.java`
- Create: `VCampusServer/src/service/GradeApprovalService.java`
- Create: `VCampusServer/test/service/GradeApprovalMySqlTest.java`

**Interfaces:**
- Consumes: V004 grade submission and operation-log tables.
- Produces: list/detail/review behavior and current grade projection.

- [ ] **Step 1: Write guarded MySQL tests first**

Seed version 1 PENDING with complete enrollment coverage. Test summary statistics, nullable components, distribution counts, approval projection, immediate student visibility, rejection leaves projection unchanged, version 2 correction supersedes current grade only after approval, old items remain unchanged, duplicate operation replay, stale version, concurrent administrators and rollback after an injected mid-projection failure.

- [ ] **Step 2: Implement DAO reads and validation queries**

List by status with PENDING default and stable `(submitted_at DESC, submission_id DESC)` pagination. Detail orders students by UID. Lock submission and items, then compare item enrollment IDs to the offering's eligible active enrollment set captured at submission. Validate each score in 0-100, grade point in 0-5, total/student counts and stored statistics.

- [ ] **Step 3: Implement approval/rejection**

Rejected decisions require nonblank reviewComment and update only submission review fields plus operation log. Approved decisions upsert every `grade` row by enrollment_id, assign all nullable components exactly, set `is_published=1` and one transaction timestamp, then set submission APPROVED and commit. Do not unpublish older projections before all new rows are valid.

Required projection shape:

```sql
INSERT INTO grade (enrollment_id, daily_score, midterm_score,
    finalterm_score, experiment_score, score, grade_level,
    grade_point, is_published, publish_time)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
ON DUPLICATE KEY UPDATE
    daily_score=VALUES(daily_score), midterm_score=VALUES(midterm_score),
    finalterm_score=VALUES(finalterm_score),
    experiment_score=VALUES(experiment_score), score=VALUES(score),
    grade_level=VALUES(grade_level), grade_point=VALUES(grade_point),
    is_published=1, publish_time=VALUES(publish_time);
```

- [ ] **Step 4: Run MySQL tests and commit**

Run GradeApprovalMySqlTest and CourseQueryMySqlTest. Commit with `feat: approve and publish grade submissions`.

### Task 3: Expose Grade Approval Through Handler and Client Service

**Files:**
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java`
- Create: `VCampusServer/test/handler/GradeApprovalHandlerTest.java`
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java`
- Modify: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`
- Create: `VCampusClient/test/service/MockGradeApprovalServiceTest.java`

**Interfaces:**
- Produces response keys `gradeSubmissions`, `gradeSubmission`, and `result`.

- [ ] **Step 1: Write failing Handler and client mapping tests**

Verify PENDING default, page/status parsing, exact item IDs, nullable scores, distribution mapping, token-derived reviewer, reject-without-comment BAD_REQUEST, teacher/student FORBIDDEN, already-final CONFLICT and sanitized database ERROR.

- [ ] **Step 2: Add Handler cases and Socket mapping**

Use TypeToken for summary lists and typed Gson conversion for detail/decision. Both approve and reject use `reviewGradeSubmission`; `approved` inside ApprovalDecisionRequestDTO selects the transition.

- [ ] **Step 3: Add deterministic mock versions**

Seed PENDING v1, APPROVED and REJECTED batches. Approving updates a mock published-grade projection; rejecting does not. Add v2 correction and assert v1 snapshots remain unchanged.

- [ ] **Step 4: Run tests and commit**

Run GradeApprovalHandlerTest, AdminCourseHandlerTest, SocketAdminCourseServiceTest and MockGradeApprovalServiceTest. Commit with `feat: expose grade approval APIs`.

### Task 4: Build the Grade Approval Tab and Detail Dialog

**Files:**
- Create: `VCampusClient/src/controller/GradeApprovalController.java`
- Create: `VCampusClient/src/controller/GradeApprovalDialogController.java`
- Create: `VCampusClient/src/resources/fxml/GradeApprovalView.fxml`
- Create: `VCampusClient/src/resources/fxml/GradeApprovalDialog.fxml`
- Modify: `VCampusClient/src/controller/AdminApprovalController.java`
- Modify: `VCampusClient/src/resources/fxml/AdminApprovalView.fxml`
- Modify: `VCampusClient/src/resources/css/style.css`
- Create: `VCampusClient/test/controller/GradeApprovalControllerTest.java`
- Create: `VCampusClient/test/controller/GradeApprovalDialogControllerTest.java`
- Modify: `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`

**Interfaces:**
- Consumes: Task 3 AdminCourseService methods.
- Produces: complete grade-approval list, statistics, item table and decision UX.

- [ ] **Step 1: Write controller tests**

Verify default PENDING, status filter persistence, stale response suppression, open-detail loading, `--` for null component scores, approve confirmation, reject reason requirement, no double submission, post-decision refresh and display of reviewer/time/comment for completed requests.

- [ ] **Step 2: Build grade list/detail FXML**

List rows display course, offering, teacher, student count, submitted time, average and status. Detail contains metric cards for average/high/low/fail count, distribution rows, a student score TableView and decision buttons. Completed batches render read-only.

- [ ] **Step 3: Wire the second approval tab**

AdminApprovalController switches between included adjustment and grade pages while preserving the shared status filter. Each child receives the current filter and refreshes only when active.

- [ ] **Step 4: Implement safe decision flow**

Approve requires confirmation. Reject opens a text-area prompt and trims the reason. Generate one operationId after the decision payload is final; reuse only for identical transport retry. On success reload list/detail from the server.

- [ ] **Step 5: Run UI verification and commit**

Compile, parse FXML, run controller tests and AdminCourseUiSmokeTest. Inspect pending, approved, rejected, null-score, long-name and distribution states. Commit with `feat: build grade approval interface`.

### Task 5: Verify Grade Approval End to End

**Files:**
- Create: `VCampusServer/test/integration/GradeApprovalSocketEndToEndTest.java`
- Modify: `VCampusServer/src/resources/seed-course-test.sql`

**Interfaces:**
- Produces: real TCP/MySQL approval-to-student-grade proof.

- [ ] **Step 1: Implement guarded E2E flow**

Insert one PENDING batch while its grade rows are unpublished. Verify student `loadGrades` cannot see it. Approve as administrator over TCP; verify submission/reviewer fields, every projection row and student visibility. Create and reject v2; verify student values remain v1. Create and approve v3 correction; verify student values switch to v3 and v1/v2/v3 snapshots remain queryable. Replay operationId and race a second administrator.

- [ ] **Step 2: Run the final complete suite**

Run V001-V004 migration, every admin DTO/Handler/MySQL/socket test, existing student course selection/waitlist/push/query tests, all Client service/controller tests, both UI smoke suites, XML parsing and `git diff --check`.

- [ ] **Step 3: Record evidence boundaries**

Report compile, static migration, real MySQL, real TCP and JavaFX screenshot results separately. If MySQL or GUI is unavailable, do not call those layers verified.

- [ ] **Step 4: Commit final verification support**

Commit with `test: verify grade approval end to end`.
