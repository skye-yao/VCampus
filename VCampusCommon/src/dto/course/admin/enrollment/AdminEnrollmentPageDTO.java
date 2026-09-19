package dto.course.admin.enrollment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 AdminEnrollmentPageDTO 数据传输对象。 */
public final class AdminEnrollmentPageDTO<T> {
    private final List<T> items;
    private final long totalCount;
    private final int pageNumber;
    private final int pageSize;

    public AdminEnrollmentPageDTO(List<T> items, long totalCount, int pageNumber, int pageSize) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.totalCount = totalCount;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }

    /** 获取 Items。 */
    public List<T> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
    }

    /** 获取 TotalCount。 */
    public long getTotalCount() {
        return totalCount;
    }

    /** 获取 PageNumber。 */
    public int getPageNumber() {
        return pageNumber;
    }

    /** 获取 PageSize。 */
    public int getPageSize() {
        return pageSize;
    }
}
