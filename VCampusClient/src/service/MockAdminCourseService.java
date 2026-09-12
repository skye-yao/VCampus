package service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
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

    private final Map<String, AdminCourseView> courses = new LinkedHashMap<>();
    private final Map<String, AdminOfferingView> offerings = new LinkedHashMap<>();
    private final Map<String, AdminOperationResultView<?>> operationResults =
            new LinkedHashMap<>();

    private long nextCourseId = 401;
    private long nextOfferingId = 4001;

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
