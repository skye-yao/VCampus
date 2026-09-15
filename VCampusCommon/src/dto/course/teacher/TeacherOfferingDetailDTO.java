package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleResourceDTO;

/**
 * 教学班详情：基本信息、任课教师与助教名单、开课学院和课程简介。
 *
 * <p>班级的课次安排由独立的课表查询加载，不放在详情里，避免一次返回整学期数据。
 * {@code offeringCollege} 来自 course.offering_college，历史数据没有维护时为 null，
 * 界面显示“未维护”，不能用教师个人学院冒充。
 */
public final class TeacherOfferingDetailDTO {
    private final TeacherOfferingDTO offering;
    private final List<ScheduleResourceDTO> teachers;
    private final String offeringCollege;
    private final String description;

    public TeacherOfferingDetailDTO(TeacherOfferingDTO offering,
            List<ScheduleResourceDTO> teachers, String offeringCollege, String description) {
        this.offering = offering;
        this.teachers = teachers == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(teachers));
        this.offeringCollege = offeringCollege;
        this.description = description;
    }

    public TeacherOfferingDTO getOffering() {
        return offering;
    }

    public List<ScheduleResourceDTO> getTeachers() {
        return teachers == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(teachers);
    }

    public String getOfferingCollege() {
        return offeringCollege;
    }

    public String getDescription() {
        return description;
    }
}
