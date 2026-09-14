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

    private long nextCourseId = 401;
    private long nextOfferingId = 4001;
    private long nextArrangementId = 9002;
    private long nextEnrollmentId = 50031;
    private PlanState plan = new PlanState(PLAN_ID, PLAN_NAME, PLAN_YEAR, PLAN_SEMESTER, 1,
            DRAFT, false);

    public MockAdminCourseService() {
        addCourse(new AdminCourseView("101", "CS203", "数据结构", "必修", 4.0, 64,
                "线性表、树和图", "程序设计基础", true, true, ACTIVE, 1, 1));
        addCourse(new AdminCourseView("201", "CS301", "操作系统", "必修", 3.5, 56,
                "进程、内存与文件系统", "数据结构", false, true, ACTIVE, 2, 1));
        addCourse(new AdminCourseView("301", "CS352", "人机交互", "限选", 2.0, 32,
                "交互设计与可用性评估", "无", true, false, ARCHIVED, 1, 1));

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
}
