package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public String getName() {
        return name;
    }

    public double getRequiredCredits() {
        return requiredCredits;
    }

    public double getEarnedCredits() {
        return earnedCredits;
    }

    public List<TrainingPlanCourseDTO> getCourses() {
        return Collections.unmodifiableList(courses);
    }
}
