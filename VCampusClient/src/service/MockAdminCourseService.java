package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import model.course.admin.StudentSearchResultView;
import protocol.MessageCode;
import service.SocketAdminCourseService.AdminCourseServiceException;

/**
 * 无服务器预览用的确定性课程管理实现。
 *
 * 仅供单一线程（预览界面线程）调用，内部状态不做并发保护。
 */
public final class MockAdminCourseService implements AdminCourseService {
    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String PLAN_ID = "7001";
    private static final String PLAN_NAME = "2026-2027 学年第一学期排课方案";
    private static final int PLAN_YEAR = 2026;
    private static final int PLAN_SEMESTER = 1;
    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String TEACHER_RESOURCE = "teacher";
    private static final String CLASSROOM_RESOURCE = "classroom";

    private final Map<String, AdminCourseView> courses = new LinkedHashMap<>();
    private final Map<String, AdminOfferingView> offerings = new LinkedHashMap<>();
    private final Map<String, ScheduleArrangementView> arrangements = new LinkedHashMap<>();
    private final Map<String, AdminOperationResultView<?>> operationResults =
            new LinkedHashMap<>();
    private final Map<String, EnrollmentStudent> students = new LinkedHashMap<>();
    private final Map<String, EnrollmentState> enrollmentHistory = new LinkedHashMap<>();
    private final Map<String, EnrollmentOperation> enrollmentOperations = new LinkedHashMap<>();
    private final Map<String, AdjustmentRequestDetailDTO> adjustmentRequests = new LinkedHashMap<>();
    private final Map<String, AdjustmentReviewStored> adjustmentReviews = new LinkedHashMap<>();
    private final Map<String, AdjustmentRecord> adjustmentRecordings = new LinkedHashMap<>();
    private final Map<String, AdjustmentNotice> adjustmentNoticeRecordings = new LinkedHashMap<>();
    private final Map<String, GradeSubmissionDetailDTO> gradeSubmissions = new LinkedHashMap<>();
    private final Map<String, GradeReviewStored> gradeReviews = new LinkedHashMap<>();
    /** The current published-grade projection the mock owns, keyed by enrollment id. */
    private final Map<String, Double> publishedGrades = new LinkedHashMap<>();

    private long nextCourseId = 401;
    private long nextOfferingId = 4001;
    private long nextArrangementId = 9002;
    private long nextEnrollmentId = 50031;
    private long nextAdjustmentId = 9101;
    private long nextNoticeId = 8101;
    private PlanState plan = new PlanState(PLAN_ID, PLAN_NAME, PLAN_YEAR, PLAN_SEMESTER, 1,
            DRAFT, false);

    public MockAdminCourseService() {
        addCourse(new AdminCourseView("101", "CS203", "数据结构", "必修", 4.0, 64,
                "线性表、树和图", "程序设计基础", true, true, ACTIVE, 1, 1));
        addCourse(new AdminCourseView("201", "CS301", "操作系统", "必修", 3.5, 56,
                "进程、内存与文件系统", "数据结构", false, true, ACTIVE, 1, 1));
        addCourse(new AdminCourseView("301", "CS352", "人机交互", "限选", 2.0, 32,
                "交互设计与可用性评估", "无", true, false, ARCHIVED, 0, 1));

        addOffering(new AdminOfferingView("1001", "OFF-1001", "101", 2026, 1, 120, 30,
                "OPEN", "T1001", "张老师", null, null, "SCHEDULED", 1));
        addOffering(new AdminOfferingView("2001", "OFF-2001", "201", 2026, 1, 100, 0,
                "CANCELLED", "T2001", "李老师", null, null, "SCHEDULED", 1));
        addOffering(new AdminOfferingView("2002", "OFF-2002", "201", 2026, 1, 100, 0,
                "NOT_OPEN", "T2003", "王老师", null, null, "UNSCHEDULED", 1));
        addOffering(new AdminOfferingView("3001", "OFF-3001", "301", 2026, 1, 80, 0,
                "CANCELLED", "T3001", "赵老师", null, null, "SCHEDULED", 1));

        addArrangement(new ScheduleArrangementView("9001", PLAN_ID, "1001",
                new ScheduleResourceDTO("8001", "T1001", "张老师", TEACHER_RESOURCE, 0),
                null,
                new ScheduleResourceDTO("8101", "3001", "A-101", CLASSROOM_RESOURCE, 120),
                List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                1, 16, DRAFT, 1));
        seedEnrollmentStudents();
        seedAdjustmentRequests();
        seedGradeSubmissions();
    }

    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
        String statusFilter = blankToNull(status);
        String queryFilter = blankToNull(query);
        List<AdminCourseView> result = new ArrayList<>();
        for (AdminCourseView course : courses.values()) {
            if (statusFilter != null && !statusFilter.equals(course.getStatus())) continue;
            if (queryFilter != null && !matches(course.getCourseCode(), queryFilter)
                    && !matches(course.getCourseName(), queryFilter)) {
                continue;
            }
            result.add(course);
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
            CourseEditorRequestDTO request) {
        AdminOperationResultView<AdminCourseView> replay =
                replay(request == null ? null : request.getOperationId());
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireCourseRequest(request);
            if (findByCode(request.getCourseCode()) != null) {
                throw conflict("课程代码已存在");
            }
            String courseId = Long.toString(nextCourseId++);
            AdminCourseView course = new AdminCourseView(courseId, request.getCourseCode(),
                    request.getCourseName(), request.getCourseType(), request.getCredit(),
                    request.getCreditHours(), blankToNull(request.getDescription()),
                    blankToNull(request.getPrerequisites()), request.isAllowCrossMajor(),
                    request.isFinalExam(), ACTIVE, 0, 1);
            addCourse(course);
            return CompletableFuture.completedFuture(
                    remember(request.getOperationId(), "课程已创建", course));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
            CourseEditorRequestDTO request) {
        AdminOperationResultView<AdminCourseView> replay =
                replay(request == null ? null : request.getOperationId());
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireCourseRequest(request);
            AdminCourseView current = requireCourse(request.getCourseId());
            if (!request.getCourseCode().equals(current.getCourseCode())) {
                throw badRequest("课程代码创建后不可修改");
            }
            requireVersion(current.getVersion(), request.getExpectedVersion());
            AdminCourseView updated = new AdminCourseView(current.getCourseId(),
                    current.getCourseCode(), request.getCourseName(), request.getCourseType(),
                    request.getCredit(), request.getCreditHours(),
                    blankToNull(request.getDescription()),
                    blankToNull(request.getPrerequisites()), request.isAllowCrossMajor(),
                    request.isFinalExam(), current.getStatus(), current.getOfferingCount(),
                    current.getVersion() + 1);
            addCourse(updated);
            return CompletableFuture.completedFuture(
                    remember(request.getOperationId(), "课程已更新", updated));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId) {
        AdminOperationResultView<AdminCourseView> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            AdminCourseView current = requireCourse(courseId);
            requireVersion(current.getVersion(), expectedVersion);
            for (AdminOfferingView offering : offerings.values()) {
                if (offering.getCourseId().equals(current.getCourseId())
                        && !"CANCELLED".equals(offering.getStatus())) {
                    throw conflict("课程仍有未取消的教学班，无法归档");
                }
            }
            AdminCourseView archived = withCourseStatus(current, ARCHIVED);
            addCourse(archived);
            return CompletableFuture.completedFuture(
                    remember(operationId, "课程已归档", archived));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId) {
        AdminOperationResultView<AdminCourseView> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            AdminCourseView current = requireCourse(courseId);
            requireVersion(current.getVersion(), expectedVersion);
            AdminCourseView restored = withCourseStatus(current, ACTIVE);
            addCourse(restored);
            return CompletableFuture.completedFuture(
                    remember(operationId, "课程已恢复", restored));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
        List<AdminOfferingView> result = new ArrayList<>();
        for (AdminOfferingView offering : offerings.values()) {
            if (offering.getCourseId().equals(courseId)) result.add(offering);
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
            OfferingEditorRequestDTO request) {
        AdminOperationResultView<AdminOfferingView> replay =
                replay(request == null ? null : request.getOperationId());
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOfferingRequest(request);
            AdminCourseView course = requireCourse(request.getCourseId());
            if (!ACTIVE.equals(course.getStatus())) {
                throw conflict("课程已归档，无法开设教学班");
            }
            String status = statusName(request.getStatus(), 2);
            String offeringId = Long.toString(nextOfferingId++);
            AdminOfferingView offering = new AdminOfferingView(offeringId,
                    request.getOfferingCode(), course.getCourseId(), request.getAcademicYear(),
                    request.getSemester(), request.getCapacity(), 0, status,
                    request.getTeacherUid(), request.getTeacherUid(),
                    blankToNull(request.getAssistantUid()),
                    blankToNull(request.getAssistantUid()), "UNSCHEDULED", 1);
            addOffering(offering);
            bumpOfferingCount(course.getCourseId(), 1);
            return CompletableFuture.completedFuture(
                    remember(request.getOperationId(), "教学班已创建", offering));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
            OfferingEditorRequestDTO request) {
        AdminOperationResultView<AdminOfferingView> replay =
                replay(request == null ? null : request.getOperationId());
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOfferingRequest(request);
            AdminOfferingView current = requireOffering(request.getOfferingId());
            requireVersion(current.getVersion(), request.getExpectedVersion());
            String status = statusName(request.getStatus(), 3);
            AdminOfferingView updated = new AdminOfferingView(current.getOfferingId(),
                    request.getOfferingCode(), current.getCourseId(), request.getAcademicYear(),
                    request.getSemester(), request.getCapacity(), current.getEnrolledCount(),
                    status, request.getTeacherUid(), request.getTeacherUid(),
                    blankToNull(request.getAssistantUid()),
                    blankToNull(request.getAssistantUid()), current.getScheduleStatus(),
                    current.getVersion() + 1);
            addOffering(updated);
            return CompletableFuture.completedFuture(
                    remember(request.getOperationId(), "教学班已更新", updated));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
            String offeringId, int expectedVersion, String operationId) {
        AdminOperationResultView<AdminOfferingView> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            AdminOfferingView current = requireOffering(offeringId);
            if ("CANCELLED".equals(current.getStatus())) {
                throw conflict("教学班已取消");
            }
            requireVersion(current.getVersion(), expectedVersion);
            AdminOfferingView cancelled = withOfferingStatus(current, "CANCELLED");
            addOffering(cancelled);
            bumpOfferingCount(current.getCourseId(), -1);
            return CompletableFuture.completedFuture(
                    remember(operationId, "教学班已取消", cancelled));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
            String offeringId, int expectedVersion, String operationId) {
        AdminOperationResultView<Void> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            AdminOfferingView current = requireOffering(offeringId);
            if (!"NOT_OPEN".equals(current.getStatus())
                    || !"UNSCHEDULED".equals(current.getScheduleStatus())
                    || current.getEnrolledCount() != 0) {
                throw conflict("只有未开放的教学班可以删除");
            }
            requireVersion(current.getVersion(), expectedVersion);
            if (enrollmentHistory.values().stream()
                    .anyMatch(state -> current.getOfferingId().equals(state.offeringId()))) {
                throw conflict("教学班已有选课历史，无法删除");
            }
            offerings.remove(current.getOfferingId());
            bumpOfferingCount(current.getCourseId(), -1);
            AdminOperationResultView<Void> result =
                    new AdminOperationResultView<>(operationId, "OK", "教学班已删除", null);
            operationResults.put(operationId, result);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
            String type, String query) {
        String typeFilter = blankToNull(type);
        if (typeFilter != null && !TEACHER_RESOURCE.equals(typeFilter)
                && !CLASSROOM_RESOURCE.equals(typeFilter)) {
            return failed(badRequest("未知的排课资源类型"));
        }
        String queryFilter = blankToNull(query);
        List<ScheduleResourceDTO> result = new ArrayList<>();
        if (typeFilter == null || TEACHER_RESOURCE.equals(typeFilter)) {
            collectResources(result, teacherResources(), queryFilter);
        }
        if (typeFilter == null || CLASSROOM_RESOURCE.equals(typeFilter)) {
            collectResources(result, classroomResources(), queryFilter);
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    @Override
    public CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> searchStudentsPage(
            String query, int page, int size) {
        try {
            String filter = query == null ? "" : query.trim();
            if (filter.isEmpty()) throw badRequest("搜索条件不能为空");
            List<StudentSearchResultView> found = new ArrayList<>();
            for (EnrollmentStudent profile : students.values()) {
                StudentSearchResultView student = profile.view();
                if (student.getUid().equals(filter) || matches(student.getName(), filter)) found.add(student);
            }
            found.sort(Comparator.comparing(StudentSearchResultView::getUid));
            return CompletableFuture.completedFuture(enrollmentPage(found, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> listOfferingStudentsPage(
            String offeringId, String query, int page, int size) {
        try {
            String id = enrollmentOfferingId(offeringId);
            requireOffering(id);
            String filter = query == null ? "" : query.trim();
            List<OfferingStudentView> found = new ArrayList<>();
            for (EnrollmentState state : enrollmentHistory.values()) {
                if (!state.offeringId().equals(id) || !state.enrolled()) continue;
                OfferingStudentView row = enrollmentView(state);
                if (filter.isEmpty() || row.getUid().equals(filter) || matches(row.getName(), filter)) found.add(row);
            }
            found.sort(Comparator.comparing(OfferingStudentView::getUid));
            return CompletableFuture.completedFuture(enrollmentPage(found, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminEnrollmentPreviewDTO> previewAdminEnrollment(String offeringId, String studentUid) {
        try {
            AdminOfferingView offering = requireOffering(enrollmentOfferingId(offeringId));
            EnrollmentStudent student = requireEnrollmentStudent(enrollmentStudentUid(studentUid));
            EnrollmentState current = enrollmentHistory.get(enrollmentKey(offeringId, student.view().getUid()));
            return CompletableFuture.completedFuture(new AdminEnrollmentPreviewDTO(offeringId,
                    student.view().getUid(), enrollmentRisks(student, offering, current, false)));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<OfferingStudentView>> addStudentToOffering(
            AdminEnrollmentRequestDTO request) {
        return mutateEnrollment(request, false);
    }

    @Override
    public CompletableFuture<AdminOperationResultView<OfferingStudentView>> removeStudentFromOffering(
            AdminEnrollmentRequestDTO request) {
        return mutateEnrollment(request, true);
    }

    private CompletableFuture<AdminOperationResultView<OfferingStudentView>> mutateEnrollment(
            AdminEnrollmentRequestDTO raw, boolean removal) {
        try {
            AdminEnrollmentRequestDTO request = enrollmentRequest(raw);
            String action = removal ? AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING
                    : AdminCourseActions.ADD_STUDENT_TO_OFFERING;
            EnrollmentIntent intent = new EnrollmentIntent(action, request.getOfferingId(), request.getStudentUid(),
                    request.isForce(), request.getOverrideReason());
            EnrollmentOperation stored = enrollmentOperations.get(request.getOperationId());
            if (stored != null) {
                if (!stored.intent().equals(intent)) throw conflict("operationId 已用于不同的业务请求");
                return CompletableFuture.completedFuture(stored.result());
            }
            AdminOfferingView offering = requireOffering(request.getOfferingId());
            EnrollmentStudent student = requireEnrollmentStudent(request.getStudentUid());
            String key = enrollmentKey(offering.getOfferingId(), request.getStudentUid());
            EnrollmentState current = enrollmentHistory.get(key);
            List<ScheduleConflictDTO> risks = enrollmentRisks(student, offering, current, removal);
            OfferingStudentView latest = current == null ? null : enrollmentView(current);
            if (risks.stream().anyMatch(r -> r.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING)
                    || (!request.isForce() && !risks.isEmpty())) {
                throw new AdminCourseServiceException(MessageCode.CONFLICT,
                        "当前条件不允许直接执行，请查看风险信息", latest, risks);
            }
            EnrollmentState updated;
            String message;
            if (removal) {
                if (current == null || !current.enrolled()) {
                    throw new AdminCourseServiceException(MessageCode.CONFLICT,
                            "该学生当前未选中此教学班", latest, risks);
                }
                updated = new EnrollmentState(current.enrollmentId(), current.offeringId(), current.studentUid(),
                        false, current.gradeLocked());
                changeEnrollmentCount(offering, -1);
                message = "已从教学班移除学生";
            } else if (current != null && current.enrolled()) {
                updated = current;
                message = "该学生已在教学班中";
            } else {
                updated = new EnrollmentState(current == null ? Long.toString(nextEnrollmentId++) : current.enrollmentId(),
                        offering.getOfferingId(), request.getStudentUid(), true, current != null && current.gradeLocked());
                changeEnrollmentCount(offering, 1);
                message = "已将学生加入教学班";
            }
            enrollmentHistory.put(key, updated);
            AdminOperationResultView<OfferingStudentView> result = new AdminOperationResultView<>(
                    request.getOperationId(), "OK", message, enrollmentView(updated));
            enrollmentOperations.put(request.getOperationId(), new EnrollmentOperation(intent, result));
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    private List<ScheduleConflictDTO> enrollmentRisks(EnrollmentStudent student, AdminOfferingView offering,
            EnrollmentState current, boolean removal) {
        List<ScheduleConflictDTO> risks = new ArrayList<>();
        String uid = student.view().getUid();
        AdminCourseView course = requireCourse(offering.getCourseId());
        if (!ACTIVE.equals(student.view().getAcademicStatus())) {
            enrollmentRisk(risks, "INVALID_STUDENT_STATUS", ScheduleConflictSeverityDTO.BLOCKING,
                    uid, offering.getOfferingId(), "学生角色或学籍状态不可用");
        }
        if ("CANCELLED".equals(offering.getStatus()) || !ACTIVE.equals(course.getStatus())) {
            enrollmentRisk(risks, "CANCELLED_OFFERING", ScheduleConflictSeverityDTO.BLOCKING,
                    uid, offering.getOfferingId(), "CANCELLED".equals(offering.getStatus()) ? "教学班已取消" : "课程已归档");
        }
        if (removal) {
            if (current != null && current.gradeLocked()) {
                enrollmentRisk(risks, "GRADE_WORKFLOW_LOCKED", ScheduleConflictSeverityDTO.BLOCKING,
                        uid, offering.getOfferingId(), "该学生已进入成绩审批或已有发布成绩，不能移除");
            }
            return List.copyOf(risks);
        }
        if ((current != null && current.enrolled()) || !ACTIVE.equals(student.view().getAcademicStatus())) {
            return List.copyOf(risks);
        }
        if (offering.getEnrolledCount() >= offering.getCapacity()) {
            enrollmentRisk(risks, "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                    uid, offering.getOfferingId(), "教学班人数已达到容量");
        }
        for (EnrollmentState state : enrollmentHistory.values()) {
            if (!state.enrolled() || !uid.equals(state.studentUid()) || state.offeringId().equals(offering.getOfferingId())) continue;
            AdminOfferingView other = offerings.get(state.offeringId());
            if (other != null && other.getCourseId().equals(offering.getCourseId())
                    && other.getAcademicYear() == offering.getAcademicYear() && other.getSemester() == offering.getSemester()) {
                enrollmentRisk(risks, "SAME_COURSE_ACTIVE", ScheduleConflictSeverityDTO.BLOCKING,
                        uid, other.getOfferingId(), "该学生在同一学期已选中本课程的其他教学班");
            }
        }
        for (ScheduleArrangementView arrangement : arrangements.values()) {
            if (!arrangement.getOfferingId().equals(offering.getOfferingId())) continue;
            ScheduleSlotDTO overlap = overlappingSlot(arrangement.getSlots(), student.busySlots());
            if (overlap != null) {
                risks.add(new ScheduleConflictDTO("STUDENT_SCHEDULE", ScheduleConflictSeverityDTO.OVERRIDABLE,
                        uid, null, arrangement.getStartWeek(), overlap.getDayOfWeek(), overlap.getStartPeriod(),
                        overlap.getEndPeriod(), "与该学生已选课程的上课时间冲突"));
                break;
            }
        }
        String prerequisites = course.getPrerequisites();
        if (prerequisites != null) {
            for (String part : prerequisites.split("[,;，；\\r\\n]+")) {
                String required = part.trim();
                if (required.isEmpty() || "无".equals(required) || "none".equalsIgnoreCase(required)) continue;
                if (!student.passedPrerequisites().contains(required)) {
                    enrollmentRisk(risks, "PREREQUISITE", ScheduleConflictSeverityDTO.OVERRIDABLE,
                            uid, offering.getOfferingId(), "需要管理员确认先修要求：" + required);
                }
            }
        }
        return List.copyOf(risks);
    }

    private static void enrollmentRisk(List<ScheduleConflictDTO> risks, String type,
            ScheduleConflictSeverityDTO severity, String uid, String offeringId, String message) {
        risks.add(new ScheduleConflictDTO(type, severity, uid, offeringId, 0, 0, 0, 0, message));
    }

    private OfferingStudentView enrollmentView(EnrollmentState state) {
        StudentSearchResultView student = students.get(state.studentUid()).view();
        AdminOfferingView offering = requireOffering(state.offeringId());
        String blocked = !state.enrolled() ? "该学生当前已退课"
                : !ACTIVE.equals(student.getAcademicStatus()) ? "学生学籍状态不可用"
                : "CANCELLED".equals(offering.getStatus()) ? "教学班已取消"
                : !ACTIVE.equals(requireCourse(offering.getCourseId()).getStatus()) ? "课程已归档"
                : state.gradeLocked() ? "该学生已进入成绩审批或已有发布成绩，不能移除" : null;
        return new OfferingStudentView(state.enrollmentId(), student.getUid(), student.getName(), student.getMajor(),
                student.getCohortYear(), state.enrolled() ? "ENROLLED" : "DROPPED", blocked == null, blocked);
    }

    private void changeEnrollmentCount(AdminOfferingView offering, int delta) {
        addOffering(new AdminOfferingView(offering.getOfferingId(), offering.getOfferingCode(), offering.getCourseId(),
                offering.getAcademicYear(), offering.getSemester(), offering.getCapacity(), offering.getEnrolledCount() + delta,
                offering.getStatus(), offering.getTeacherUid(), offering.getTeacherName(), offering.getAssistantUid(),
                offering.getAssistantName(), offering.getScheduleStatus(), offering.getVersion()));
    }

    private void seedEnrollmentStudents() {
        for (int index = 1; index <= 34; index++) {
            String uid = Integer.toString(20240000 + index);
            String name = switch (index) {
                case 1 -> "张明";
                case 2 -> "李静";
                case 31 -> "陈晨";
                case 32 -> "王晓雨";
                case 33 -> "刘洋";
                case 34 -> "休学学生";
                default -> String.format(Locale.ROOT, "示例同学%02d", index);
            };
            students.put(uid, new EnrollmentStudent(new StudentSearchResultView(uid, name, "软件工程", 2024,
                    index == 34 ? "SUSPENDED" : ACTIVE),
                    index == 32 ? List.of(new ScheduleSlotDTO(1, 1, 2)) : List.of(),
                    index == 33 ? Set.of() : Set.of("程序设计基础", "数据结构", "CS203")));
            if (index <= 30) {
                enrollmentHistory.put(enrollmentKey("1001", uid), new EnrollmentState(
                        Integer.toString(50000 + index), "1001", uid, true, index == 1));
            }
        }
    }

    private EnrollmentStudent requireEnrollmentStudent(String uid) {
        EnrollmentStudent student = students.get(uid);
        if (student == null) throw notFound("学生不存在");
        return student;
    }

    private static <T> AdminEnrollmentPageView<T> enrollmentPage(List<T> rows, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw badRequest("页码必须大于 0，每页条数必须为 1 至 100");
        long offset = (long) (page - 1) * size;
        List<T> items = offset >= rows.size() ? List.of()
                : rows.subList((int) offset, (int) Math.min(offset + size, rows.size()));
        return new AdminEnrollmentPageView<>(items, rows.size(), page, size);
    }

    private static AdminEnrollmentRequestDTO enrollmentRequest(AdminEnrollmentRequestDTO request) {
        if (request == null) throw badRequest("请求不能为空");
        String operation = request.getOperationId();
        try {
            if (operation == null || !UUID.fromString(operation).toString().equalsIgnoreCase(operation)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw badRequest("operationId 必须是 UUID");
        }
        String reason = request.getOverrideReason() == null ? null : request.getOverrideReason().trim();
        if (request.isForce() && (reason == null || reason.isEmpty())) throw badRequest("强制操作必须填写原因");
        if (reason != null && reason.length() > 500) throw badRequest("强制原因不能超过 500 字符");
        return new AdminEnrollmentRequestDTO(UUID.fromString(operation).toString(),
                enrollmentOfferingId(request.getOfferingId()), enrollmentStudentUid(request.getStudentUid()),
                request.isForce(), reason);
    }

    private static String enrollmentOfferingId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw badRequest("offeringId 必须为正整数");
        try { Long.parseLong(value); }
        catch (NumberFormatException failure) { throw badRequest("offeringId 超出有效范围"); }
        return value;
    }

    private static String enrollmentStudentUid(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 32) {
            throw badRequest("studentUid 必须为 1 至 32 个字符");
        }
        return value.trim();
    }

    private static String enrollmentKey(String offeringId, String uid) { return offeringId + ":" + uid; }

    private record EnrollmentStudent(StudentSearchResultView view, List<ScheduleSlotDTO> busySlots,
                                     Set<String> passedPrerequisites) { }
    private record EnrollmentState(String enrollmentId, String offeringId, String studentUid,
                                   boolean enrolled, boolean gradeLocked) { }
    private record EnrollmentIntent(String action, String offeringId, String studentUid, boolean force, String reason) { }
    private record EnrollmentOperation(EnrollmentIntent intent, AdminOperationResultView<OfferingStudentView> result) { }

    @Override
    public CompletableFuture<SchedulePlanDTO> loadSchedulePlan(int academicYear, int semester) {
        if (academicYear <= 0) return failed(badRequest("学年无效"));
        if (semester < 1 || semester > 3) return failed(badRequest("学期无效"));
        PlanState current = plan;
        if (current.academicYear != academicYear || current.semester != semester) {
            return failed(notFound("该学期尚未创建排课方案"));
        }
        return CompletableFuture.completedFuture(new SchedulePlanDTO(current.planId,
                current.name, current.revision, current.status, current.current, List.of()));
    }

    @Override
    public CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
            String planId, String offeringId) {
        if (!PLAN_ID.equals(planId)) return failed(notFound("排课方案不存在"));
        List<ScheduleArrangementView> result = new ArrayList<>();
        for (ScheduleArrangementView arrangement : arrangements.values()) {
            if (offeringId == null || offeringId.equals(arrangement.getOfferingId())) {
                result.add(arrangement);
            }
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    @Override
    public CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
            SaveArrangementRequestDTO request) {
        try {
            return CompletableFuture.completedFuture(conflicts(request));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
            SaveArrangementRequestDTO request) {
        AdminOperationResultView<ScheduleArrangementView> replay =
                replay(request == null ? null : request.getOperationId());
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireArrangementRequest(request);
            if (!PLAN_ID.equals(request.getPlanId())) throw notFound("排课方案不存在");
            List<ScheduleConflictDTO> found = conflicts(request);
            for (ScheduleConflictDTO entry : found) {
                if (ScheduleConflictSeverityDTO.BLOCKING == entry.getSeverity()) {
                    throw conflict("存在阻断性冲突，无法保存");
                }
            }
            if (!request.isForce() && !found.isEmpty()) {
                throw conflict("存在可绕过冲突，请确认后强制保存");
            }
            if (request.isForce() && blankToNull(request.getOverrideReason()) == null) {
                throw badRequest("强制保存必须填写原因");
            }
            ScheduleArrangementView saved = applyArrangement(request);
            return CompletableFuture.completedFuture(
                    remember(request.getOperationId(), "教学安排已保存", saved));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
            String arrangementId, int expectedVersion, String operationId) {
        AdminOperationResultView<Void> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            ScheduleArrangementView current = requireArrangement(arrangementId);
            requireVersion(current.getVersion(), expectedVersion);
            arrangements.remove(current.getArrangementId());
            AdminOperationResultView<Void> result =
                    new AdminOperationResultView<>(operationId, "OK", "教学安排已删除", null);
            operationResults.put(operationId, result);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
            String planId, int expectedRevision, String operationId,
            boolean force, String overrideReason) {
        AdminOperationResultView<SchedulePlanView> replay = replay(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        try {
            requireOperationId(operationId);
            PlanState current = requirePlan(planId);
            if (current.revision != expectedRevision) {
                throw conflict("方案修订号已变化，请刷新后重试");
            }
            if (!DRAFT.equals(current.status)) throw conflict("只有草稿方案可以发布");
            if (force && blankToNull(overrideReason) == null) {
                throw badRequest("强制发布必须填写原因");
            }
            current.revision += 1;
            current.status = PUBLISHED;
            current.current = true;
            return CompletableFuture.completedFuture(
                    remember(operationId, "排课方案已发布", planView(current)));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    private ScheduleArrangementView applyArrangement(SaveArrangementRequestDTO request) {
        String arrangementId = blankToNull(request.getArrangementId());
        List<ScheduleSlotDTO> slots = List.copyOf(request.getSlots());
        if (arrangementId == null) {
            ScheduleArrangementView created = new ScheduleArrangementView(
                    Long.toString(nextArrangementId++), PLAN_ID, request.getOfferingId(),
                    teacherResource(request.getTeacherUid()),
                    assistantResource(request.getAssistantUid()),
                    classroomResource(request.getClassroomId()), slots,
                    request.getStartWeek(), request.getEndWeek(), DRAFT, 1);
            addArrangement(created);
            return created;
        }
        ScheduleArrangementView current = requireArrangement(arrangementId);
        requireVersion(current.getVersion(), request.getExpectedVersion());
        ScheduleArrangementView updated = new ScheduleArrangementView(current.getArrangementId(),
                PLAN_ID, request.getOfferingId(), teacherResource(request.getTeacherUid()),
                assistantResource(request.getAssistantUid()),
                classroomResource(request.getClassroomId()), slots,
                request.getStartWeek(), request.getEndWeek(), current.getStatus(),
                current.getVersion() + 1);
        addArrangement(updated);
        return updated;
    }

    private List<ScheduleConflictDTO> conflicts(SaveArrangementRequestDTO request) {
        requireArrangementRequest(request);
        if (!PLAN_ID.equals(request.getPlanId())) throw notFound("排课方案不存在");
        boolean selfOverlap = false;
        ScheduleSlotDTO selfOverlapSlot = null;
        ScheduleConflictDTO teacherWarning = null;
        String requestedArrangementId = blankToNull(request.getArrangementId());
        for (ScheduleArrangementView existing : arrangements.values()) {
            if (existing.getArrangementId().equals(requestedArrangementId)) continue;
            ScheduleSlotDTO overlap = overlappingSlot(existing.getSlots(), request.getSlots());
            if (overlap == null) continue;
            if (existing.getOfferingId().equals(request.getOfferingId())) {
                selfOverlap = true;
                if (selfOverlapSlot == null) selfOverlapSlot = overlap;
                continue;
            }
            if (teacherWarning == null && sameTeacher(existing, request)) {
                teacherWarning = new ScheduleConflictDTO("TEACHER",
                        ScheduleConflictSeverityDTO.OVERRIDABLE,
                        existing.getTeacher().getResourceId(), existing.getOfferingId(),
                        request.getStartWeek(), overlap.getDayOfWeek(),
                        overlap.getStartPeriod(), overlap.getEndPeriod(),
                        "任课教师在该时间段已有教学安排");
            }
        }
        if (selfOverlap) {
            return List.of(new ScheduleConflictDTO("OFFERING_SELF_OVERLAP",
                    ScheduleConflictSeverityDTO.BLOCKING, request.getOfferingId(),
                    request.getOfferingId(), request.getStartWeek(),
                    selfOverlapSlot.getDayOfWeek(), selfOverlapSlot.getStartPeriod(),
                    selfOverlapSlot.getEndPeriod(), "同一教学班的时间段与现有安排重叠"));
        }
        if (teacherWarning != null) return List.of(teacherWarning);
        return List.of();
    }

    private static ScheduleSlotDTO overlappingSlot(List<ScheduleSlotDTO> existing,
            List<ScheduleSlotDTO> requested) {
        for (ScheduleSlotDTO left : existing) {
            for (ScheduleSlotDTO right : requested) {
                if (left.getDayOfWeek() == right.getDayOfWeek()
                        && left.getStartPeriod() <= right.getEndPeriod()
                        && right.getStartPeriod() <= left.getEndPeriod()) {
                    return right;
                }
            }
        }
        return null;
    }

    private static boolean sameTeacher(ScheduleArrangementView existing,
            SaveArrangementRequestDTO request) {
        boolean teacher = existing.getTeacher() != null
                && existing.getTeacher().getBusinessId().equals(request.getTeacherUid());
        boolean assistant = existing.getAssistant() != null
                && existing.getAssistant().getBusinessId().equals(request.getAssistantUid());
        return teacher || assistant;
    }

    private ScheduleArrangementView requireArrangement(String arrangementId) {
        ScheduleArrangementView arrangement =
                arrangementId == null ? null : arrangements.get(arrangementId);
        if (arrangement == null) throw notFound("教学安排不存在");
        return arrangement;
    }

    private PlanState requirePlan(String planId) {
        if (!PLAN_ID.equals(planId)) throw notFound("排课方案不存在");
        return plan;
    }

    private static SchedulePlanView planView(PlanState state) {
        return new SchedulePlanView(state.planId, state.name, state.revision, state.status,
                state.current, List.of());
    }

    private void addArrangement(ScheduleArrangementView arrangement) {
        arrangements.put(arrangement.getArrangementId(), arrangement);
    }

    private static void collectResources(List<ScheduleResourceDTO> result,
            List<ScheduleResourceDTO> candidates, String query) {
        for (ScheduleResourceDTO resource : candidates) {
            if (query == null || matches(resource.getName(), query)
                    || matches(resource.getBusinessId(), query)) {
                result.add(resource);
            }
        }
    }

    private static List<ScheduleResourceDTO> teacherResources() {
        return List.of(new ScheduleResourceDTO("8001", "T1001", "张老师", TEACHER_RESOURCE, 0),
                new ScheduleResourceDTO("8002", "T2001", "李老师", TEACHER_RESOURCE, 0));
    }

    private static List<ScheduleResourceDTO> classroomResources() {
        return List.of(
                new ScheduleResourceDTO("8101", "3001", "A-101", CLASSROOM_RESOURCE, 120),
                new ScheduleResourceDTO("8102", "3002", "A-102", CLASSROOM_RESOURCE, 60));
    }

    private static ScheduleResourceDTO teacherResource(String uid) {
        for (ScheduleResourceDTO resource : teacherResources()) {
            if (resource.getBusinessId().equals(uid)) return resource;
        }
        return new ScheduleResourceDTO(uid, uid, uid, TEACHER_RESOURCE, 0);
    }

    private static ScheduleResourceDTO assistantResource(String uid) {
        String assistant = blankToNull(uid);
        return assistant == null ? null : teacherResource(assistant);
    }

    private static ScheduleResourceDTO classroomResource(String classroomId) {
        for (ScheduleResourceDTO resource : classroomResources()) {
            if (resource.getBusinessId().equals(classroomId)) return resource;
        }
        return new ScheduleResourceDTO(classroomId, classroomId, classroomId,
                CLASSROOM_RESOURCE, 0);
    }

    private static void requireArrangementRequest(SaveArrangementRequestDTO request) {
        if (request == null) throw badRequest("请求不能为空");
        requireOperationId(request.getOperationId());
        requireId(request.getPlanId(), "planId");
        requireId(request.getOfferingId(), "offeringId");
        requireId(request.getClassroomId(), "classroomId");
        requireText(request.getTeacherUid(), "任课教师");
        if (request.getSlots() == null || request.getSlots().isEmpty()) {
            throw badRequest("时间段不能为空");
        }
        if (request.getStartWeek() < 1 || request.getEndWeek() < request.getStartWeek()) {
            throw badRequest("周次范围无效");
        }
    }

    private static void requireId(String value, String field) {
        if (value == null || !value.matches("[0-9]+")) {
            throw badRequest(field + "必须为十进制字符串");
        }
    }

    private static final class PlanState {
        private final String planId;
        private final String name;
        private final int academicYear;
        private final int semester;
        private int revision;
        private String status;
        private boolean current;

        private PlanState(String planId, String name, int academicYear, int semester,
                int revision, String status, boolean current) {
            this.planId = planId;
            this.name = name;
            this.academicYear = academicYear;
            this.semester = semester;
            this.revision = revision;
            this.status = status;
            this.current = current;
        }
    }

    private void addCourse(AdminCourseView course) {
        courses.put(course.getCourseId(), course);
    }

    private void addOffering(AdminOfferingView offering) {
        offerings.put(offering.getOfferingId(), offering);
    }

    private void bumpOfferingCount(String courseId, int delta) {
        AdminCourseView course = courses.get(courseId);
        if (course == null) return;
        int count = Math.max(0, course.getOfferingCount() + delta);
        addCourse(new AdminCourseView(course.getCourseId(), course.getCourseCode(),
                course.getCourseName(), course.getCourseType(), course.getCredit(),
                course.getCreditHours(), course.getDescription(), course.getPrerequisites(),
                course.isAllowCrossMajor(), course.isFinalExam(), course.getStatus(), count,
                course.getVersion()));
    }

    private AdminCourseView findByCode(String courseCode) {
        for (AdminCourseView course : courses.values()) {
            if (course.getCourseCode().equals(courseCode)) return course;
        }
        return null;
    }

    private AdminCourseView requireCourse(String courseId) {
        AdminCourseView course = courseId == null ? null : courses.get(courseId);
        if (course == null) throw notFound("课程资源不存在");
        return course;
    }

    private AdminOfferingView requireOffering(String offeringId) {
        AdminOfferingView offering = offeringId == null ? null : offerings.get(offeringId);
        if (offering == null) throw notFound("教学班资源不存在");
        return offering;
    }

    private static AdminCourseView withCourseStatus(AdminCourseView course, String status) {
        return new AdminCourseView(course.getCourseId(), course.getCourseCode(),
                course.getCourseName(), course.getCourseType(), course.getCredit(),
                course.getCreditHours(), course.getDescription(), course.getPrerequisites(),
                course.isAllowCrossMajor(), course.isFinalExam(), status,
                course.getOfferingCount(), course.getVersion() + 1);
    }

    private static AdminOfferingView withOfferingStatus(AdminOfferingView offering, String status) {
        return new AdminOfferingView(offering.getOfferingId(), offering.getOfferingCode(),
                offering.getCourseId(), offering.getAcademicYear(), offering.getSemester(),
                offering.getCapacity(), offering.getEnrolledCount(), status,
                offering.getTeacherUid(), offering.getTeacherName(), offering.getAssistantUid(),
                offering.getAssistantName(), offering.getScheduleStatus(),
                offering.getVersion() + 1);
    }

    private static String statusName(int status, int maxWritable) {
        if (status == 1) return "NOT_OPEN";
        if (status == 2) return "OPEN";
        if (status == 3 && maxWritable >= 3) return "STOPPED";
        throw badRequest("教学班状态无效");
    }

    private static void requireCourseRequest(CourseEditorRequestDTO request) {
        if (request == null) throw badRequest("请求不能为空");
        requireOperationId(request.getOperationId());
        requireText(request.getCourseCode(), "课程代码");
        requireText(request.getCourseName(), "课程名称");
    }

    private static void requireOfferingRequest(OfferingEditorRequestDTO request) {
        if (request == null) throw badRequest("请求不能为空");
        requireOperationId(request.getOperationId());
        requireText(request.getOfferingCode(), "教学班代码");
        requireText(request.getCourseId(), "courseId");
        requireText(request.getTeacherUid(), "任课教师");
    }

    private static void requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw badRequest("操作编号不能为空");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw badRequest(field + "不能为空");
    }

    private static void requireVersion(int currentVersion, int expectedVersion) {
        if (currentVersion != expectedVersion) throw conflict("数据已被其他管理员修改");
    }

    private static boolean matches(String value, String query) {
        return value != null && value.toLowerCase().contains(query.toLowerCase());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private <T> AdminOperationResultView<T> replay(String operationId) {
        if (operationId == null) return null;
        return (AdminOperationResultView<T>) operationResults.get(operationId);
    }

    private <T> AdminOperationResultView<T> remember(String operationId, String message, T entity) {
        AdminOperationResultView<T> result =
                new AdminOperationResultView<>(operationId, "OK", message, entity);
        operationResults.put(operationId, result);
        return result;
    }

    private static AdminCourseServiceException conflict(String message) {
        return new AdminCourseServiceException(MessageCode.CONFLICT, message);
    }

    private static AdminCourseServiceException badRequest(String message) {
        return new AdminCourseServiceException(MessageCode.BAD_REQUEST, message);
    }

    private static AdminCourseServiceException notFound(String message) {
        return new AdminCourseServiceException(MessageCode.NOT_FOUND, message);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    // ------------------------------------------------------- temporary adjustments

    /**
     * The preview administrator is the signed-in test account, so the mock records it as the
     * reviewer instead of taking an identity from the request.
     */
    private static final String REVIEWER = "admin-alpha";
    private static final String MOCK_NOW = "2026-09-14T06:30:00Z";
    private static final String RESCHEDULED = "RESCHEDULED";
    private static final int MAX_REASON = 500;

    @Override
    public CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsPage(
            ApprovalStatusDTO status, int page, int size) {
        try {
            ApprovalStatusDTO filter = status == null ? ApprovalStatusDTO.PENDING : status;
            if (page < 1) throw badRequest("页码必须大于 0");
            if (size < 1 || size > 100) throw badRequest("每页条数必须为 1 至 100");
            List<AdjustmentRequestSummaryDTO> matching = new ArrayList<>();
            for (AdjustmentRequestDetailDTO request : adjustmentRequests.values()) {
                if (request.getStatus() == filter) matching.add(adjustmentSummary(request));
            }
            matching.sort(Comparator.comparing(AdjustmentRequestSummaryDTO::getSubmittedAt)
                    .reversed()
                    .thenComparing(AdjustmentRequestSummaryDTO::getRequestId,
                            Comparator.reverseOrder()));
            int from = (int) Math.min((long) (page - 1) * size, matching.size());
            int to = (int) Math.min((long) from + size, matching.size());
            return CompletableFuture.completedFuture(new AdjustmentRequestPageDTO(
                    matching.subList(from, to), matching.size(), page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
        try {
            return CompletableFuture.completedFuture(requireAdjustmentRequest(requestId));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>> reviewAdjustmentRequest(
            ApprovalDecisionRequestDTO raw) {
        try {
            ApprovalDecisionRequestDTO request = adjustmentDecision(raw);
            AdjustmentReview intent = new AdjustmentReview(request.getOperationId(),
                    request.getRequestId(), request.getExpectedVersion(), request.isApproved(),
                    request.isForce(), request.getOverrideReason(), request.getReviewComment());
            AdjustmentReviewStored stored = adjustmentReviews.get(request.getOperationId());
            if (stored != null) {
                if (!stored.intent().equals(intent)) throw conflict("operationId 已用于不同的业务请求");
                return CompletableFuture.completedFuture(stored.result());
            }
            AdjustmentRequestDetailDTO current = requireAdjustmentRequest(request.getRequestId());
            requirePending(current, request.getExpectedVersion());
            List<ScheduleConflictDTO> conflicts = current.getConflicts();
            AdminOperationResultView<AdjustmentRequestDetailDTO> result;
            if (!request.isApproved()) {
                result = rememberAdjustment(request.getOperationId(), "调课申请已驳回", intent,
                        decideAdjustment(current, ApprovalStatusDTO.REJECTED, request.getReviewComment()));
            } else {
                boolean blocking = conflicts.stream().anyMatch(
                        risk -> risk.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING);
                if (blocking || (!request.isForce() && !conflicts.isEmpty())) {
                    throw new AdminCourseServiceException(MessageCode.CONFLICT,
                            blocking ? "存在阻断性冲突，无法通过调课申请"
                                    : "存在可绕过冲突，请确认后强制通过",
                            current, conflicts);
                }
                AdjustmentRequestDetailDTO approved = decideAdjustment(current,
                        ApprovalStatusDTO.APPROVED, request.getReviewComment());
                for (AdjustmentTargetDTO target : approved.getTargets()) {
                    adjustmentRecordings.put(target.getOriginalOccurrenceId(), new AdjustmentRecord(
                            Long.toString(nextAdjustmentId++), approved.getRequestId(),
                            target.getOriginalOccurrenceId(), target.getWeek(),
                            "星期" + approved.getNewDayOfWeek() + " 第" + approved.getNewStartPeriod()
                                    + "-" + approved.getNewEndPeriod() + "节",
                            ACTIVE));
                }
                adjustmentNoticeRecordings.put(approved.getRequestId(), new AdjustmentNotice(
                        Long.toString(nextNoticeId++), approved.getRequestId(),
                        approved.getOfferingId(), "调课安排已生效", adjustmentNotice(approved),
                        RESCHEDULED, PUBLISHED, REVIEWER, MOCK_NOW));
                result = rememberAdjustment(request.getOperationId(), "调课申请已通过", intent, approved);
            }
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /** The deterministic adjustment and notice state a later UI test can assert on. */
    public List<AdjustmentRecord> adjustmentRecords() {
        return List.copyOf(adjustmentRecordings.values());
    }

    public List<AdjustmentNotice> adjustmentNotices() {
        return List.copyOf(adjustmentNoticeRecordings.values());
    }

    private void requirePending(AdjustmentRequestDetailDTO current, int expectedVersion) {
        if (current.getStatus() != ApprovalStatusDTO.PENDING) {
            throw new AdminCourseServiceException(MessageCode.CONFLICT, "调课申请已被处理，请刷新后重试",
                    current, current.getConflicts());
        }
        if (current.getVersion() != expectedVersion) {
            throw new AdminCourseServiceException(MessageCode.CONFLICT, "调课申请版本已变化，请刷新后重试",
                    current, current.getConflicts());
        }
    }

    /** Records the decision and republishes the request under its new immutable snapshot. */
    private AdjustmentRequestDetailDTO decideAdjustment(AdjustmentRequestDetailDTO current,
            ApprovalStatusDTO status, String reviewComment) {
        AdjustmentRequestDetailDTO decided = new AdjustmentRequestDetailDTO(current.getRequestId(),
                current.getOfferingId(), current.getApplicantUid(), current.getReason(), status,
                current.getVersion() + 1, current.getNewDayOfWeek(), current.getNewStartPeriod(),
                current.getNewEndPeriod(), current.getNewTeacher(), current.getNewAssistant(),
                current.getNewClassroom(), current.getTargets(), current.getConflicts(),
                current.getSubmittedAt(), REVIEWER, MOCK_NOW, reviewComment);
        adjustmentRequests.put(decided.getRequestId(), decided);
        return decided;
    }

    private AdminOperationResultView<AdjustmentRequestDetailDTO> rememberAdjustment(String operationId,
            String message, AdjustmentReview intent, AdjustmentRequestDetailDTO entity) {
        AdminOperationResultView<AdjustmentRequestDetailDTO> result =
                new AdminOperationResultView<>(operationId, "OK", message, entity);
        adjustmentReviews.put(operationId, new AdjustmentReviewStored(intent, result));
        return result;
    }

    private AdjustmentRequestDetailDTO requireAdjustmentRequest(String requestId) {
        if (requestId == null || !requestId.matches("[0-9]+")) {
            throw badRequest("requestId 必须为十进制字符串");
        }
        AdjustmentRequestDetailDTO request = adjustmentRequests.get(requestId);
        if (request == null) throw notFound("调课申请不存在");
        return request;
    }

    private AdjustmentRequestSummaryDTO adjustmentSummary(AdjustmentRequestDetailDTO request) {
        AdminOfferingView offering = offerings.get(request.getOfferingId());
        AdminCourseView course = offering == null ? null : courses.get(offering.getCourseId());
        return new AdjustmentRequestSummaryDTO(request.getRequestId(),
                course == null ? "" : course.getCourseName(),
                offering == null ? "" : offering.getOfferingCode(), request.getApplicantUid(),
                teacherName(request.getApplicantUid()), request.getTargets().size(),
                request.getStatus(), request.getSubmittedAt());
    }

    private static String teacherName(String uid) {
        return switch (uid) {
            case "T1001" -> "张老师";
            case "T2003" -> "王老师";
            case "T3001" -> "赵老师";
            default -> uid;
        };
    }

    private static String adjustmentNotice(AdjustmentRequestDetailDTO request) {
        List<String> weeks = new ArrayList<>();
        List<String> occurrences = new ArrayList<>();
        for (AdjustmentTargetDTO target : request.getTargets()) {
            weeks.add(Integer.toString(target.getWeek()));
            occurrences.add(target.getOriginalOccurrenceId());
        }
        return "临时调课已生效。周次：" + String.join("、", weeks) + "。新安排：星期"
                + request.getNewDayOfWeek() + " 第" + request.getNewStartPeriod() + "-"
                + request.getNewEndPeriod() + "节。受影响课程实例：" + String.join("、", occurrences)
                + "。";
    }

    private static ApprovalDecisionRequestDTO adjustmentDecision(ApprovalDecisionRequestDTO raw) {
        if (raw == null) throw badRequest("请求不能为空");
        String operationId = raw.getOperationId();
        try {
            if (operationId == null
                    || !UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw badRequest("operationId 必须是 UUID 字符串");
        }
        if (raw.getRequestId() == null || !raw.getRequestId().matches("[0-9]+")) {
            throw badRequest("requestId 必须为十进制字符串");
        }
        if (raw.getExpectedVersion() <= 0) throw badRequest("expectedVersion 必须为正整数");
        if (raw.isForce() && !raw.isApproved()) {
            throw badRequest("只有通过调课申请才支持强制覆盖");
        }
        String reason = trimmed(raw.getOverrideReason(), "强制原因", MAX_REASON);
        if (raw.isForce() && reason == null) throw badRequest("强制通过必须填写原因");
        String comment = trimmed(raw.getReviewComment(), "审批意见", MAX_REASON);
        if (!raw.isApproved() && comment == null) throw badRequest("驳回必须填写审批意见");
        return new ApprovalDecisionRequestDTO(operationId, raw.getRequestId(),
                raw.getExpectedVersion(), raw.isApproved(), raw.isForce(), reason, comment);
    }

    private static String trimmed(String value, String field, int maxLength) {
        String text = blankToNull(value == null ? null : value.trim());
        if (text != null && text.length() > maxLength) {
            throw badRequest(field + "不能超过 " + maxLength + " 字符");
        }
        return text;
    }

    private void seedAdjustmentRequests() {
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9001", "1001", "T1001", "带队参加学科竞赛",
                ApprovalStatusDTO.PENDING, 1, 5, 1, 2,
                new ScheduleResourceDTO("8001", "T1001", "张老师", TEACHER_RESOURCE, 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", CLASSROOM_RESOURCE, 120),
                List.of(target("7001", 1, "2026-09-08T00:00:00Z", "张老师"),
                        target("7002", 2, "2026-09-15T00:00:00Z", "张老师")),
                List.of(), "2026-09-10T09:00:00Z", null, null, null));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9002", "2002", "T2003", "临时出差",
                ApprovalStatusDTO.PENDING, 1, 1, 3, 4,
                new ScheduleResourceDTO("8003", "T2003", "王老师", TEACHER_RESOURCE, 0), null, null,
                List.of(target("7003", 3, "2026-09-22T02:00:00Z", "王老师")),
                List.of(new ScheduleConflictDTO("TEACHER_OVERLAP",
                        ScheduleConflictSeverityDTO.OVERRIDABLE, "T2003", "1001", 3, 1, 3, 4,
                        "任课教师在该时间已有其他课程")),
                "2026-09-10T08:00:00Z", null, null, null));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9003", "3001", "T3001", "实验室检修",
                ApprovalStatusDTO.APPROVED, 2, 3, 1, 2,
                new ScheduleResourceDTO("8005", "T3001", "赵老师", TEACHER_RESOURCE, 0), null, null,
                List.of(target("7004", 4, "2026-09-29T00:00:00Z", "赵老师")),
                List.of(), "2026-09-10T07:00:00Z", REVIEWER, MOCK_NOW, "同意"));
        addAdjustmentRequest(new AdjustmentRequestDetailDTO("9004", "1001", "T1001", "材料不足的申请",
                ApprovalStatusDTO.REJECTED, 2, 2, 1, 2,
                new ScheduleResourceDTO("8001", "T1001", "张老师", TEACHER_RESOURCE, 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", CLASSROOM_RESOURCE, 120),
                List.of(target("7005", 5, "2026-10-06T00:00:00Z", "张老师")),
                List.of(), "2026-09-10T06:00:00Z", REVIEWER, MOCK_NOW, "材料不足"));
    }

    private void addAdjustmentRequest(AdjustmentRequestDetailDTO request) {
        adjustmentRequests.put(request.getRequestId(), request);
    }

    private static AdjustmentTargetDTO target(String occurrenceId, int week, String startAt,
                                              String teacher) {
        return new AdjustmentTargetDTO(occurrenceId, week, startAt, startAt, teacher, null, "A-101");
    }

    private record AdjustmentReview(String operationId, String requestId, int expectedVersion,
                                    boolean approved, boolean force, String overrideReason,
                                    String reviewComment) { }

    private record AdjustmentReviewStored(AdjustmentReview intent,
            AdminOperationResultView<AdjustmentRequestDetailDTO> result) { }

    public record AdjustmentRecord(String adjustmentId, String requestId,
                                   String originalOccurrenceId, int week, String scheduleText,
                                   String status) { }

    public record AdjustmentNotice(String noticeId, String requestId, String offeringId,
                                   String title, String content, String noticeType, String status,
                                   String createdBy, String publishedAt) { }

    // ------------------------------------------------------------- grade approval

    @Override
    public CompletableFuture<GradeSubmissionPageDTO> listGradeSubmissionsPage(
            ApprovalStatusDTO status, int page, int size) {
        try {
            if (page < 1) throw badRequest("页码必须大于 0");
            if (size < 1 || size > 100) throw badRequest("每页条数必须为 1 至 100");
            ApprovalStatusDTO filter = status == null ? ApprovalStatusDTO.PENDING : status;
            List<GradeSubmissionSummaryDTO> matching = new ArrayList<>();
            for (GradeSubmissionDetailDTO detail : gradeSubmissions.values()) {
                if (detail.getSummary().getStatus() == filter) matching.add(detail.getSummary());
            }
            matching.sort(Comparator.comparing(GradeSubmissionSummaryDTO::getSubmittedAt)
                    .reversed()
                    .thenComparing(GradeSubmissionSummaryDTO::getSubmissionId,
                            Comparator.reverseOrder()));
            int from = (int) Math.min((long) (page - 1) * size, matching.size());
            int to = (int) Math.min((long) from + size, matching.size());
            return CompletableFuture.completedFuture(new GradeSubmissionPageDTO(
                    matching.subList(from, to), matching.size(), page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<GradeSubmissionDetailDTO> getGradeSubmission(String submissionId) {
        try {
            return CompletableFuture.completedFuture(requireGradeSubmission(submissionId));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<AdminOperationResultView<GradeSubmissionDetailDTO>> reviewGradeSubmission(
            ApprovalDecisionRequestDTO raw) {
        try {
            ApprovalDecisionRequestDTO request = gradeDecision(raw);
            GradeReview intent = new GradeReview(request.getOperationId(), request.getRequestId(),
                    request.getExpectedVersion(), request.isApproved(), request.getReviewComment());
            GradeReviewStored stored = gradeReviews.get(request.getOperationId());
            if (stored != null) {
                if (!stored.intent().equals(intent)) throw conflict("operationId 已用于不同的业务请求");
                return CompletableFuture.completedFuture(stored.result());
            }
            GradeSubmissionDetailDTO current = requireGradeSubmission(request.getRequestId());
            requirePendingGrade(current, request.getExpectedVersion());
            GradeSubmissionDetailDTO decided = decideGrade(current, request.isApproved()
                    ? ApprovalStatusDTO.APPROVED : ApprovalStatusDTO.REJECTED,
                    request.getReviewComment());
            // Only an approval publishes the batch; a rejection leaves the projection untouched.
            if (request.isApproved()) {
                for (GradeSubmissionItemDTO item : decided.getItems()) {
                    if (item.getScore() != null) {
                        publishedGrades.put(item.getEnrollmentId(), item.getScore());
                    }
                }
            }
            return CompletableFuture.completedFuture(rememberGrade(request.getOperationId(),
                    request.isApproved() ? "成绩提交已通过" : "成绩提交已驳回", intent, decided));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /** The current published-grade projection the mock owns, keyed by enrollment id. */
    public Map<String, Double> publishedGrades() {
        return Map.copyOf(publishedGrades);
    }

    private void requirePendingGrade(GradeSubmissionDetailDTO current, int expectedVersion) {
        if (current.getSummary().getStatus() != ApprovalStatusDTO.PENDING) {
            throw new AdminCourseServiceException(MessageCode.CONFLICT, "成绩提交已被处理，请刷新后重试",
                    current);
        }
        if (current.getSummary().getVersion() != expectedVersion) {
            throw new AdminCourseServiceException(MessageCode.CONFLICT, "成绩提交版本已变化，请刷新后重试",
                    current);
        }
    }

    /** Records the decision under a new immutable snapshot; the frozen batch itself is never edited. */
    private GradeSubmissionDetailDTO decideGrade(GradeSubmissionDetailDTO current,
            ApprovalStatusDTO status, String reviewComment) {
        GradeSubmissionSummaryDTO summary = current.getSummary();
        GradeSubmissionSummaryDTO decidedSummary = new GradeSubmissionSummaryDTO(
                summary.getSubmissionId(), summary.getOfferingId(), summary.getCourseName(),
                summary.getOfferingCode(), summary.getVersion(), summary.getTeacherUid(),
                summary.getTeacherName(), summary.getStudentCount(), summary.getAverage(),
                summary.getHighest(), summary.getLowest(), summary.getFailCount(), status,
                summary.getSubmittedAt());
        GradeSubmissionDetailDTO decided = new GradeSubmissionDetailDTO(decidedSummary,
                current.getDistribution(), current.getItems(), REVIEWER, MOCK_NOW, reviewComment);
        gradeSubmissions.put(decidedSummary.getSubmissionId(), decided);
        return decided;
    }

    private AdminOperationResultView<GradeSubmissionDetailDTO> rememberGrade(String operationId,
            String message, GradeReview intent, GradeSubmissionDetailDTO entity) {
        AdminOperationResultView<GradeSubmissionDetailDTO> result =
                remember(operationId, message, entity);
        gradeReviews.put(operationId, new GradeReviewStored(intent, result));
        return result;
    }

    private GradeSubmissionDetailDTO requireGradeSubmission(String submissionId) {
        if (submissionId == null || !submissionId.matches("[0-9]+")) {
            throw badRequest("submissionId 必须为十进制字符串");
        }
        GradeSubmissionDetailDTO detail = gradeSubmissions.get(submissionId);
        if (detail == null) throw notFound("成绩提交不存在");
        return detail;
    }

    private static ApprovalDecisionRequestDTO gradeDecision(ApprovalDecisionRequestDTO raw) {
        if (raw == null) throw badRequest("请求不能为空");
        String operationId = raw.getOperationId();
        try {
            if (operationId == null
                    || !UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw badRequest("operationId 必须是 UUID 字符串");
        }
        if (raw.getRequestId() == null || !raw.getRequestId().matches("[0-9]+")) {
            throw badRequest("requestId 必须为十进制字符串");
        }
        if (raw.getExpectedVersion() <= 0) throw badRequest("expectedVersion 必须为正整数");
        // Grade approval judges a frozen batch whole and never overrides a conflict (R12).
        if (raw.isForce()) throw badRequest("成绩审批不支持强制覆盖");
        String comment = trimmed(raw.getReviewComment(), "审批意见", MAX_REASON);
        if (!raw.isApproved() && comment == null) throw badRequest("驳回必须填写审批意见");
        return new ApprovalDecisionRequestDTO(operationId, raw.getRequestId(),
                raw.getExpectedVersion(), raw.isApproved(), false, null, comment);
    }

    private void seedGradeSubmissions() {
        addGradeSubmission(gradeDetail("9001", "1001", "数据结构", "OFF-1001", 1, "T1001", "张老师",
                "2026-09-11T09:00:00Z", ApprovalStatusDTO.PENDING, null, null, null,
                List.of(item("8001", "20240031", "陈晨", 88.0, 86.0, null, 84.0, 85.0, 3, 3.5),
                        item("8002", "20240032", "林晓", 90.0, 92.0, 91.0, 89.0, 90.0, 4, 4.0))));
        addGradeSubmission(gradeDetail("9002", "2001", "操作系统", "OFF-2001", 2, "T2001", "李老师",
                "2026-09-10T09:00:00Z", ApprovalStatusDTO.APPROVED, REVIEWER, MOCK_NOW, "同意",
                List.of(item("8003", "20240033", "王强", 70.0, 72.0, null, 68.0, 70.0, 1, 1.0))));
        addGradeSubmission(gradeDetail("9003", "1001", "数据结构", "OFF-1001", 1, "T1001", "张老师",
                "2026-09-10T08:00:00Z", ApprovalStatusDTO.REJECTED, REVIEWER, MOCK_NOW, "材料不足",
                List.of(item("8004", "20240034", "赵敏", 55.0, 58.0, null, 52.0, 55.0, 0, null))));
        addGradeSubmission(gradeDetail("9004", "1001", "数据结构", "OFF-1001", 2, "T1001", "张老师",
                "2026-09-12T09:00:00Z", ApprovalStatusDTO.PENDING, null, null, null,
                List.of(item("8001", "20240031", "陈晨", 92.0, 94.0, null, 90.0, 92.0, 4, 4.0),
                        item("8002", "20240032", "林晓", 94.0, 96.0, 95.0, 93.0, 94.0, 4, 4.0))));
    }

    private void addGradeSubmission(GradeSubmissionDetailDTO detail) {
        gradeSubmissions.put(detail.getSummary().getSubmissionId(), detail);
    }

    private static GradeSubmissionDetailDTO gradeDetail(String submissionId, String offeringId,
            String courseName, String offeringCode, int version, String teacherUid,
            String teacherName, String submittedAt, ApprovalStatusDTO status, String reviewedBy,
            String reviewedAt, String reviewComment, List<GradeSubmissionItemDTO> items) {
        List<Double> scores = new ArrayList<>();
        int failed = 0;
        for (GradeSubmissionItemDTO item : items) {
            if (item.getScore() == null) continue;
            scores.add(item.getScore());
            if (item.getScore() < 60) failed++;
        }
        double average = scores.isEmpty() ? 0.0 : round2(
                scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
        double highest = scores.isEmpty() ? 0.0 : round2(
                scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0));
        double lowest = scores.isEmpty() ? 0.0 : round2(
                scores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
        GradeSubmissionSummaryDTO summary = new GradeSubmissionSummaryDTO(submissionId, offeringId,
                courseName, offeringCode, version, teacherUid, teacherName, items.size(), average,
                highest, lowest, failed, status, submittedAt);
        return new GradeSubmissionDetailDTO(summary, gradeDistribution(items), List.copyOf(items),
                reviewedBy, reviewedAt, reviewComment);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static List<GradeDistributionBucketDTO> gradeDistribution(
            List<GradeSubmissionItemDTO> items) {
        String[] labels = {"90-100", "80-89", "70-79", "60-69", "0-59"};
        int[] counts = new int[labels.length];
        for (GradeSubmissionItemDTO item : items) {
            Double score = item.getScore();
            if (score == null) continue;
            if (score >= 90) counts[0]++;
            else if (score >= 80) counts[1]++;
            else if (score >= 70) counts[2]++;
            else if (score >= 60) counts[3]++;
            else counts[4]++;
        }
        List<GradeDistributionBucketDTO> buckets = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            buckets.add(new GradeDistributionBucketDTO(labels[index], counts[index]));
        }
        return buckets;
    }

    private static GradeSubmissionItemDTO item(String enrollmentId, String studentUid,
            String studentName, Double daily, Double midterm, Double experiment, Double finalterm,
            Double score, Integer level, Double point) {
        return new GradeSubmissionItemDTO(enrollmentId, studentUid, studentName, daily, midterm,
                experiment, finalterm, score, level, point);
    }

    private record GradeReview(String operationId, String requestId, int expectedVersion,
                               boolean approved, String reviewComment) { }

    private record GradeReviewStored(GradeReview intent,
            AdminOperationResultView<GradeSubmissionDetailDTO> result) { }
}
