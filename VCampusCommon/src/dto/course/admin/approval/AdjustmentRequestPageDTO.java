package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 AdjustmentRequestPageDTO 数据传输对象。 */
public final class AdjustmentRequestPageDTO {
    private final List<AdjustmentRequestSummaryDTO> items;
    private final long totalCount;
    private final int pageNumber;
    private final int pageSize;

    public AdjustmentRequestPageDTO(List<AdjustmentRequestSummaryDTO> items,
            long totalCount, int pageNumber, int pageSize) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.totalCount = totalCount;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }

    /** 获取 Items。 */
    public List<AdjustmentRequestSummaryDTO> getItems() {
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
