# Schedule Adjustment Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Approve or reject multi-week temporary schedule changes without mutating the published base plan, publish a student notice atomically, and show original and adjusted timetable positions together.

**Architecture:** Submitted requests contain one proposed arrangement and multiple immutable original-occurrence targets. Approval expands targets into ACTIVE adjustments in one transaction; student schedule queries overlay adjustments on the published plan and return two display entries per adjusted occurrence.

**Tech Stack:** Java, JavaFX/FXML, Gson, TCP, JDBC/MySQL 8, existing schedule layout and notice UI.

**Spec:** `docs/superpowers/specs/2026-09-12-admin-course-management-design.md`

## Global Constraints

- Complete catalog, scheduling, and enrollment plans first.
- Teacher request-entry UI is out of scope; tests/seed create already-submitted PENDING requests.
- A request has one new weekday/period/resource set and one or more target weeks.
- Approval is all-or-nothing and never updates schedule_plan, course_schedule_rule, course_schedule_rule_week, or original course_occurrence rows.
- Existing dirty ScheduleController, ScheduleLayout, ScheduleEntryView, and CourseQueryDAO content must be reread immediately before each patch and preserved.
- Original adjusted slots are display-only and do not occupy conflict resources; target slots are effective.

---

### Task 1: Define Adjustment Approval and Student Display Contracts

**Files:**
- Create: `VCampusCommon/src/dto/course/admin/approval/ApprovalStatusDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/AdjustmentRequestSummaryDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/AdjustmentTargetDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/AdjustmentRequestDetailDTO.java`
- Create: `VCampusCommon/src/dto/course/admin/approval/ApprovalDecisionRequestDTO.java`
- Create: `VCampusCommon/src/dto/course/ScheduleDisplayKindDTO.java`
- Modify: `VCampusCommon/src/dto/course/ScheduleEntryDTO.java`
- Create: `VCampusCommon/test/dto/course/admin/AdjustmentApprovalDtoJsonTest.java`
- Modify: `VCampusCommon/test/dto/course/CourseDtoJsonTest.java`
- Modify: `VCampusClient/src/model/course/ScheduleEntryView.java`
- Modify: `VCampusClient/src/service/AdminCourseService.java`

**Interfaces:**
- Produces: adjustment list/detail/decision DTOs and backward-compatible student schedule display fields.

- [ ] **Step 1: Write failing DTO tests**

Round-trip multiple targets, proposed resources, UTC times, status, force reason and exact BIGINT strings. Verify existing ScheduleEntryDTO constructor remains usable and defaults to NORMAL.

- [ ] **Step 2: Implement exact approval signatures**

```java
AdjustmentRequestSummaryDTO(String requestId, String courseName,
        String offeringCode, String applicantUid, String applicantName,
        int targetWeekCount, String status, String submittedAt)
AdjustmentTargetDTO(String originalOccurrenceId, int week,
        String originalStartAt, String originalEndAt, String originalTeacher,
        String originalAssistant, String originalClassroom)
AdjustmentRequestDetailDTO(String requestId, String offeringId,
        String applicantUid, String reason, ApprovalStatusDTO status,
        int version, int newDayOfWeek, int newStartPeriod, int newEndPeriod,
        ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
        ScheduleResourceDTO newClassroom, List<AdjustmentTargetDTO> targets,
        List<ScheduleConflictDTO> conflicts, String submittedAt,
        String reviewedBy, String reviewedAt, String reviewComment)
ApprovalDecisionRequestDTO(String operationId, String requestId,
        int expectedVersion, boolean approved, boolean force,
        String overrideReason, String reviewComment)
```

Extend ScheduleEntryDTO/View with displayKind, adjustmentId, originalScheduleText, adjustedScheduleText and adjustmentReason. Keep the old constructor delegating to the new one with NORMAL and null adjustment fields.

- [ ] **Step 3: Add AdminCourseService methods**

```java
CompletableFuture<List<AdjustmentRequestSummaryDTO>> listAdjustmentRequests(
        ApprovalStatusDTO status, int page, int size);
CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId);
CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>> reviewAdjustmentRequest(
        ApprovalDecisionRequestDTO request);
```

- [ ] **Step 4: Run DTO/model regressions and commit**

Run both DTO tests and CourseModelTest. Commit with `feat: define schedule adjustment contracts`.

### Task 2: Implement Adjustment Query and Approval Transaction

**Files:**
- Create: `VCampusServer/src/dao/ScheduleAdjustmentDAO.java`
- Create: `VCampusServer/src/service/ScheduleAdjustmentApprovalService.java`
- Create: `VCampusServer/test/service/ScheduleAdjustmentApprovalMySqlTest.java`

**Interfaces:**
- Consumes: CourseConflictService and V004 adjustment/notice/audit tables.
- Produces: list/detail/review services.

- [ ] **Step 1: Write guarded MySQL tests**

Seed one two-week PENDING request. Assert ordinary approval creates two adjustments and one PUBLISHED RESCHEDULED notice, leaves original rows byte-for-byte unchanged, and changes request to APPROVED. Add rejection, blocking conflict, overridable conflict without/with reason, stale version, repeated operationId, concurrent approval and injected-failure rollback cases.

- [ ] **Step 2: Implement DAO reads and locks**

List by status with PENDING default. Detail returns target snapshots and fresh conflicts. Approval locks request then all original occurrence IDs in ascending order. Recheck that each occurrence still belongs to the request's offering and current published plan.

- [ ] **Step 3: Implement effective adjustment expansion**

For each target week, map proposed weekday/periods through teaching_calendar_date and period definitions to UTC start/end. Use CourseConflictService after excluding all request target originals. Reject BLOCKING; require force/reason for OVERRIDABLE.

- [ ] **Step 4: Implement atomic approval/rejection**

Approved: insert one ACTIVE adjustment per target, update request/reviewer fields, insert one PUBLISHED `course_notice` with adjustment_request_id and a deterministic summary of weeks/original/new arrangement, then save operation result and commit. Rejected: require reviewComment, update request and operation log only. Never partially approve targets.

- [ ] **Step 5: Run tests and commit**

Run ScheduleAdjustmentApprovalMySqlTest and ScheduleManagementMySqlTest. Commit with `feat: approve temporary schedule adjustments`.

### Task 3: Expose Adjustment Approval in Handler and Admin Client

**Files:**
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java`
- Create: `VCampusServer/test/handler/ScheduleAdjustmentApprovalHandlerTest.java`
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java`
- Modify: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`
- Create: `VCampusClient/test/service/MockAdjustmentApprovalServiceTest.java`

**Interfaces:**
- Produces response keys `adjustmentRequests`, `adjustmentRequest`, and `result`.

- [ ] **Step 1: Write failing permission, parsing and mapping tests**

Test default PENDING filter, invalid status/page/ID, token-derived admin UID, teacher/student FORBIDDEN, rejected-without-comment BAD_REQUEST, force-without-reason BAD_REQUEST, conflict propagation and typed list/detail mapping.

- [ ] **Step 2: Add Handler actions and Socket mapping**

Use TypeToken for lists and typed Gson conversion for decision DTOs. Do not accept applicant/reviewer identity from the request.

- [ ] **Step 3: Add deterministic mock workflow**

Seed PENDING, APPROVED and REJECTED requests. Review updates copies, rejects double decisions and produces one mock notice/adjustment state for later UI tests.

- [ ] **Step 4: Run tests and commit**

Commit with `feat: expose schedule adjustment approvals`.

### Task 4: Overlay Adjustments in Student Schedule Queries

**Files:**
- Modify: `VCampusServer/src/dao/CourseQueryDAO.java`
- Modify: `VCampusServer/src/service/CourseQueryService.java`
- Modify: `VCampusServer/test/dao/CourseQueryMappingTest.java`
- Modify: `VCampusServer/test/service/CourseQueryMySqlTest.java`
- Modify: `VCampusClient/src/service/SocketCourseService.java`
- Modify: `VCampusClient/src/service/MockCourseService.java`
- Modify: `VCampusClient/test/service/MockCourseScheduleTest.java`
- Modify: `VCampusClient/test/service/SocketCourseServiceTest.java`

**Interfaces:**
- Consumes: ACTIVE adjustment rows.
- Produces: NORMAL or paired ADJUSTED_ORIGINAL/ADJUSTED_TARGET student entries.

- [ ] **Step 1: Reread dirty files and write regression tests before editing**

Capture current diffs, then add tests asserting an adjusted week returns exactly two entries with the same adjustmentId, an unadjusted week returns one NORMAL entry, original/target text is correct, and existing optimized meeting aggregation remains intact.

- [ ] **Step 2: Implement effective query mapping**

Query enrolled base occurrences for the selected current plan and week. LEFT JOIN ACTIVE adjustment. Emit NORMAL when absent; when present, map one display-only original and one effective target. Notice queries remain enrollment-scoped and return the published linked notice.

- [ ] **Step 3: Map new fields on client and mock**

Preserve the old DTO constructor and all existing tests. SocketCourseService maps the enum explicitly; unknown display kind fails clearly rather than silently treating it as NORMAL.

- [ ] **Step 4: Run query/service regressions and commit**

Run CourseQueryMappingTest, CourseQueryMySqlTest, SocketCourseServiceTest and MockCourseScheduleTest. Verify the commit includes the user's prior CourseQueryDAO edits rather than replacing them only if those edits have been intentionally committed first; otherwise keep this task's patch isolated and ask the user to resolve overlap.

### Task 5: Build Approval UI and Dual-Position Timetable Rendering

**Files:**
- Create: `VCampusClient/src/controller/AdminApprovalController.java`
- Create: `VCampusClient/src/controller/AdjustmentApprovalDialogController.java`
- Create: `VCampusClient/src/resources/fxml/AdminApprovalView.fxml`
- Create: `VCampusClient/src/resources/fxml/AdjustmentApprovalDialog.fxml`
- Modify: `VCampusClient/src/controller/AdminCourseManagementController.java`
- Modify: `VCampusClient/src/resources/fxml/AdminCourseManagementView.fxml`
- Modify: `VCampusClient/src/controller/ScheduleController.java`
- Modify: `VCampusClient/src/resources/css/style.css`
- Create: `VCampusClient/test/controller/AdminApprovalControllerTest.java`
- Create: `VCampusClient/test/controller/AdjustmentApprovalDialogControllerTest.java`
- Modify: `VCampusClient/test/controller/ScheduleControllerTest.java`
- Modify: `VCampusClient/test/controller/ScheduleLayoutTest.java`
- Modify: `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`
- Modify: `VCampusClient/test/ui/CourseUiSmokeTest.java`

**Interfaces:**
- Produces: administrator adjustment tab and student paired schedule blocks.

- [ ] **Step 1: Write controller/layout tests**

Verify default PENDING, status filters, stale list/detail suppression, reject reason, force reason, post-review refresh, original grey block at old coordinates, target block at new coordinates, overlap lanes based only on actual target occupancy, and detail text from either block.

- [ ] **Step 2: Build approval FXML**

AdminApprovalView owns approval tabs and common status filter. Initially wire adjustment content; grade content remains an explicit empty placeholder until the grade plan. Detail dialog shows side-by-side original/new rows and all target weeks.

- [ ] **Step 3: Implement dual rendering without discarding current schedule edits**

Reread ScheduleController/ScheduleLayout immediately before patching. Render ADJUSTED_ORIGINAL with a grey `course-adjusted-original` style and header badge; render ADJUSTED_TARGET with `course-adjusted-target`. Layout collision lanes use target plus NORMAL entries; original placeholders are visual overlays and do not force an extra lane for resource semantics.

- [ ] **Step 4: Run visual verification**

Capture administrator pending/detail/conflict states and student normal/adjusted weeks. Inspect both 860 x 580 shells for clipping and label visibility.

- [ ] **Step 5: Commit UI changes**

Commit with `feat: show approved schedule adjustments`.

### Task 6: Verify Adjustment Approval End to End

**Files:**
- Create: `VCampusServer/test/integration/ScheduleAdjustmentSocketEndToEndTest.java`
- Modify: `VCampusServer/src/resources/seed-course-test.sql`

**Interfaces:**
- Produces: real admin approval to student timetable/notice proof.

- [ ] **Step 1: Implement guarded E2E flow**

Insert a PENDING two-week request, approve over TCP, assert two adjustments/one notice/request status, verify base plan checksum unchanged, then query the enrolled student: adjusted week returns original+target and notice; neighboring week remains NORMAL. Replay approval and test a second administrator loses the concurrent state race.

- [ ] **Step 2: Run full schedule/admin/student regressions and commit**

Run migration, scheduling, approval, query, socket, ScheduleController/Layout and both UI smoke suites; run `git diff --check`. Commit with `test: verify schedule adjustment approval end to end`.
