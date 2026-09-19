package model.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 培养方案按类别分组的学分要求、已获学分和课程列表视图。 */
public final class TrainingPlanGroupView {
    private final String name;
    private final double requiredCredits;
    private final double earnedCredits;
    private final List<TrainingPlanCourseView> courses;

    /** 创建培养方案课程组，并防御性复制课程列表。 */
    public TrainingPlanGroupView(String name, double requiredCredits,
            double earnedCredits, List<TrainingPlanCourseView> courses) {
        this.name = name;
        this.requiredCredits = requiredCredits;
        this.earnedCredits = earnedCredits;
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
    }

    public String getName() {
        return name;
    }

    public double getRequiredCredits() {
        return requiredCredits;
    }

    public double getEarnedCredits() {
        return earnedCredits;
    }

    public List<TrainingPlanCourseView> getCourses() {
        return courses;
    }
}
