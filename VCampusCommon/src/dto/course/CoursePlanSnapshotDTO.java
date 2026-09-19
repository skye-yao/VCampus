package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 CoursePlanSnapshotDTO 数据传输对象。 */
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

    /** 获取 Term。 */
    public CourseTermDTO getTerm() {
        return term;
    }

    /** 获取 PlanItems。 */
    public List<CourseSelectionItemDTO> getPlanItems() {
        return Collections.unmodifiableList(planItems);
    }

    /** 获取 WaitlistItems。 */
    public List<CourseSelectionItemDTO> getWaitlistItems() {
        return Collections.unmodifiableList(waitlistItems);
    }

    /** 获取 EnrolledItems。 */
    public List<CourseSelectionItemDTO> getEnrolledItems() {
        return Collections.unmodifiableList(enrolledItems);
    }
}
