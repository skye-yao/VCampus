package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlanConfirmationDTO {
    private final List<PlanConfirmationItemDTO> items;
    private final List<CourseOfferingDTO> offerings;

    public PlanConfirmationDTO(List<PlanConfirmationItemDTO> items,
            List<CourseOfferingDTO> offerings) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.offerings = Collections.unmodifiableList(new ArrayList<>(offerings));
    }

    public List<PlanConfirmationItemDTO> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<CourseOfferingDTO> getOfferings() {
        return Collections.unmodifiableList(offerings);
    }
}
