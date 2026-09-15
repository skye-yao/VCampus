package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CoursePlanSnapshotDTO {
    private final CourseTermDTO term;
    private final List<CourseSelectionItemDTO> planItems;
    private final List<CourseSelectionItemDTO> waitlistItems;
    private final List<CourseSelectionItemDTO> enrolledItems;

    public CoursePlanSnapshotDTO(CourseTermDTO term,
            List<CourseSelectionItemDTO> planItems,
            List<CourseSelectionItemDTO> waitlistItems,
            List<CourseSelectionItemDTO> enrolledItems) {
        this.term = term;
        this.planItems = Collections.unmodifiableList(new ArrayList<>(planItems));
        this.waitlistItems = Collections.unmodifiableList(new ArrayList<>(waitlistItems));
        this.enrolledItems = Collections.unmodifiableList(new ArrayList<>(enrolledItems));
    }

    public CourseTermDTO getTerm() {
        return term;
    }

    public List<CourseSelectionItemDTO> getPlanItems() {
        return Collections.unmodifiableList(planItems);
    }

    public List<CourseSelectionItemDTO> getWaitlistItems() {
        return Collections.unmodifiableList(waitlistItems);
    }

    public List<CourseSelectionItemDTO> getEnrolledItems() {
        return Collections.unmodifiableList(enrolledItems);
    }
}
