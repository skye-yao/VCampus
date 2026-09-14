package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public List<AdjustmentRequestSummaryDTO> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
    }

    public long getTotalCount() {
        return totalCount;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int getPageSize() {
        return pageSize;
    }
}
