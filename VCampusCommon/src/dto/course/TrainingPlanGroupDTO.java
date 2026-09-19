package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 TrainingPlanGroupDTO 数据传输对象。 */
public final class TrainingPlanGroupDTO {
    private final String name;
    private final double requiredCredits;
    private final double earnedCredits;
    private final List<TrainingPlanCourseDTO> courses;

    public TrainingPlanGroupDTO(String name, double requiredCredits,
            double earnedCredits, List<TrainingPlanCourseDTO> courses) {
        this.name = name;
        this.requiredCredits = requiredCredits;
        this.earnedCredits = earnedCredits;
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
    }

    /** 获取 Name。 */
    public String getName() {
        return name;
    }

    /** 获取 RequiredCredits。 */
    public double getRequiredCredits() {
        return requiredCredits;
    }

    /** 获取 EarnedCredits。 */
    public double getEarnedCredits() {
        return earnedCredits;
    }

    /** 获取 Courses。 */
    public List<TrainingPlanCourseDTO> getCourses() {
        return Collections.unmodifiableList(courses);
    }
}
