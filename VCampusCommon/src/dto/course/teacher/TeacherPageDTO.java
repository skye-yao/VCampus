package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 教师端列表查询的统一分页响应。
 *
 * <p>分页从 1 开始，{@code size} 为 1..100；{@code totalCount} 是过滤后的总行数，
 * 不能用当前页行数代替。列表做防御性不可变复制，null 视为空列表。
 */
public final class TeacherPageDTO<T> {
    private final List<T> items;
    private final long totalCount;
    private final int page;
    private final int size;

    public TeacherPageDTO(List<T> items, long totalCount, int page, int size) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.totalCount = totalCount;
        this.page = page;
        this.size = size;
    }

    public List<T> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
    }

    public long getTotalCount() {
        return totalCount;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }
}
