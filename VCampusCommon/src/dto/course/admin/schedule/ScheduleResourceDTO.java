package dto.course.admin.schedule;

public final class ScheduleResourceDTO {
    private final String resourceId;
    private final String businessId;
    private final String name;
    private final String resourceType;
    private final int capacity;

    public ScheduleResourceDTO(String resourceId, String businessId, String name,
            String resourceType, int capacity) {
        this.resourceId = resourceId;
        this.businessId = businessId;
        this.name = name;
        this.resourceType = resourceType;
        this.capacity = capacity;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getBusinessId() {
        return businessId;
    }

    public String getName() {
        return name;
    }

    public String getResourceType() {
        return resourceType;
    }

    public int getCapacity() {
        return capacity;
    }
}
