package model.course.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AdminEnrollmentPageView<T> {
    private final List<T> items;
    private final long totalCount;
    private final int pageNumber;
    private final int pageSize;

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
