package model.course.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 管理端选课名单或搜索结果的通用分页视图。 */
public final class AdminEnrollmentPageView<T> {
    private final List<T> items;
    private final long totalCount;
    private final int pageNumber;
    private final int pageSize;

    /** 创建分页视图，并防御性复制页面数据。 */
    public AdminEnrollmentPageView(List<T> items, long totalCount, int pageNumber, int pageSize) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.totalCount = totalCount;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
    }

    public long getTotalCount() { return totalCount; }
    public int getPageNumber() { return pageNumber; }
    public int getPageSize() { return pageSize; }
}
