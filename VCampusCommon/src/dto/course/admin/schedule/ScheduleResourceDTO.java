package dto.course.admin.schedule;

/** 教务模块的 ScheduleResourceDTO 数据传输对象。 */
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

    /** 获取 ResourceId。 */
    public String getResourceId() {
        return resourceId;
    }

    /** 获取 BusinessId。 */
    public String getBusinessId() {
        return businessId;
    }

    /** 获取 Name。 */
    public String getName() {
        return name;
    }

    /** 获取 ResourceType。 */
    public String getResourceType() {
        return resourceType;
    }

    /** 获取 Capacity。 */
    public int getCapacity() {
        return capacity;
    }
}
