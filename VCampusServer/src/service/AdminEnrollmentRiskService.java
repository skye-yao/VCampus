package service;

import dao.AdminEnrollmentDAO;
import dao.AdminScheduleConflictDAO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/** One risk calculation shared by read-only preview and the locked mutation. */
public class AdminEnrollmentRiskService {
    private static final Set<String> NO_PREREQUISITE = Set.of("", "无", "无先修要求", "无先修课程",
            "无先修课", "无要求", "none", "n/a", "no prerequisite", "no prerequisites");
    private final AdminEnrollmentDAO dao;
    private final CourseConflictService conflicts;

    /**
    * Handles the course-management responsibility of AdminEnrollmentRiskService.
    */
    public AdminEnrollmentRiskService() { this(new AdminEnrollmentDAO(), new CourseConflictService()); }

    /**
    * Handles the course-management responsibility of AdminEnrollmentRiskService.
    */
    public AdminEnrollmentRiskService(AdminEnrollmentDAO dao, CourseConflictService conflicts) {
        this.dao = dao;
        this.conflicts = conflicts;
    }

    /**
    * Handles the course-management responsibility of calculate.
    */
    public List<ScheduleConflictDTO> calculate(Connection connection, AdminEnrollmentDAO.StudentRow student,
            AdminEnrollmentDAO.OfferingRow offering, AdminEnrollmentDAO.EnrollmentRow enrollment,
            boolean removal, Instant now) throws SQLException {
        List<ScheduleConflictDTO> risks = new ArrayList<>();
        if (!student.active()) add(risks, "INVALID_STUDENT_STATUS", BLOCKING, student.uid(),
                offering.offeringId(), "学生角色或学籍状态不可用");
        if (offering.status() == 4 || !"ACTIVE".equals(offering.courseStatus())) {
            add(risks, "CANCELLED_OFFERING", BLOCKING, student.uid(), offering.offeringId(),
                    offering.status() == 4 ? "教学班已取消" : "课程已归档");
        }
        if (removal) {
            if (enrollment != null && dao.gradeWorkflowLocked(connection, enrollment.enrollmentId())) {
                add(risks, "GRADE_WORKFLOW_LOCKED", BLOCKING, student.uid(), offering.offeringId(),
                        "该学生已进入成绩审批或已有发布成绩，不能移除");
            }
            return List.copyOf(risks);
        }
        // Repeated add succeeds even if this seat has since filled capacity or a prerequisite changed.
        if (enrollment != null && enrollment.status() == 2) return List.copyOf(risks);
        if (!student.active()) return List.copyOf(risks);
        if ((long) offering.enrolledCount() + dao.otherOfferReservations(connection,
                offering.offeringId(), student.uid(), now) >= offering.capacity()) {
            add(risks, "CAPACITY", OVERRIDABLE, student.uid(), offering.offeringId(),
                    "教学班人数及其他学生的候补预留席位已达到容量");
        }
        Long sameCourseOfferingId = dao.sameCourseActiveOfferingId(connection, student.uid(), offering);
        if (sameCourseOfferingId != null) {
            add(risks, "SAME_COURSE_ACTIVE", BLOCKING, student.uid(), sameCourseOfferingId,
                    "该学生在同一学期已选中本课程的其他教学班");
        }
        try {
            risks.addAll(conflicts.checkStudent(connection, student.uid(), offering.offeringId(),
                    offering.academicYear(), offering.semester(), dao.activeOfferingIds(connection, student.uid())));
        } catch (AdminScheduleConflictDAO.PublishedPlanUnavailableException unavailable) {
            add(risks, "SCHEDULE_UNAVAILABLE", BLOCKING, student.uid(), offering.offeringId(),
                    "当前正式排课方案引用无效，请先修复排课方案");
        }
        prerequisites(connection, student.uid(), offering, risks);
        return List.copyOf(risks);
    }

    private void prerequisites(Connection connection, String uid, AdminEnrollmentDAO.OfferingRow offering,
                               List<ScheduleConflictDTO> risks) throws SQLException {
        String required = offering.prerequisites();
        if (required == null || noPrerequisite(required)) return;
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : required.split("[,;，；\\r\\n]+")) {
            String token = part.trim();
            if (!noPrerequisite(token)) tokens.add(token);
        }
        for (String token : tokens) {
            Long courseId = dao.prerequisiteCourseId(connection, token);
            if (courseId == null) {
                add(risks, "PREREQUISITE", OVERRIDABLE, uid, offering.offeringId(),
                        "先修要求需要管理员人工确认：" + token);
            } else if (!dao.hasPassingGrade(connection, uid, courseId)) {
                add(risks, "PREREQUISITE", OVERRIDABLE, uid, offering.offeringId(),
                        "尚无该先修课程已发布的及格成绩：" + token);
            }
        }
    }

    private static boolean noPrerequisite(String value) {
        return NO_PREREQUISITE.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static void add(List<ScheduleConflictDTO> risks, String type, ScheduleConflictSeverityDTO severity,
                            String uid, long offeringId, String message) {
        risks.add(new ScheduleConflictDTO(type, severity, uid, Long.toString(offeringId), 0, 0, 0, 0, message));
    }
}
