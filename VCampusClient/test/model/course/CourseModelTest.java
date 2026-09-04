package model.course;

public final class CourseModelTest {
    public static void main(String[] args) {
        CourseOfferingView original = new CourseOfferingView(
                1001L, "CS203", "数据结构", "必修", 4.0, 64,
                "张老师", "周二 3-4节", "教四-201",
                "线性表、树和图", "程序设计基础",
                96, 120, SelectionStatus.AVAILABLE);
        CourseOfferingView planned = original.withSelectionStatus(SelectionStatus.PLANNED);
        require(original.getSelectionStatus() == SelectionStatus.AVAILABLE, "original must stay immutable");
        require(planned.getSelectionStatus() == SelectionStatus.PLANNED, "copy must contain new status");
        require(planned.getOfferingId() == 1001L, "copy must preserve identity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
