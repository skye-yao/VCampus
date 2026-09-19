package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 GradeSubmissionPageDTO 数据传输对象。 */
public final class GradeSubmissionPageDTO {
    private final List<GradeSubmissionSummaryDTO> items;
    private final long totalCount;
    private final int pageNumber;
    private final int pageSize;

    public GradeSubmissionPageDTO(List<GradeSubmissionSummaryDTO> items,
            long totalCount, int pageNumber, int pageSize) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.totalCount = totalCount;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }

    /** 获取 Items。 */
    public List<GradeSubmissionSummaryDTO> getItems() {
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
