package dto.course;

public final class PlanConfirmationItemDTO {
    private final String offeringId;
    private final String courseName;
    private final SelectionStateDTO selectionState;
    private final boolean successful;
    private final String reason;

    public PlanConfirmationItemDTO(String offeringId, String courseName,
            SelectionStateDTO selectionState, boolean successful, String reason) {
        this.offeringId = offeringId;
        this.courseName = courseName;
        this.selectionState = selectionState;
        this.successful = successful;
        this.reason = reason;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getCourseName() {
        return courseName;
    }

    public SelectionStateDTO getSelectionState() {
        return selectionState;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getReason() {
        return reason;
    }
}
