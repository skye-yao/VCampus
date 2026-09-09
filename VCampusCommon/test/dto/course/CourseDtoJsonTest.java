package dto.course;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public final class CourseDtoJsonTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        preservesBigIntOfferingIdAsExactString();
        roundTripsCourseTermListWithTypeToken();
        collectionFieldsAreDefensiveAndUnmodifiable();
        deserializedCollectionFieldsAreUnmodifiable();
    }

    private static void preservesBigIntOfferingIdAsExactString() {
        CourseOfferingDTO source = new CourseOfferingDTO(
                "9007199254740993", "CS203", "数据结构", "必修", 4.0, 64,
                "张老师", "周二 3-4节", "教四-201", "线性表、树和图", "程序设计基础",
                96, 120, SelectionStateDTO.AVAILABLE);

        CourseOfferingDTO copy = GSON.fromJson(GSON.toJson(source), CourseOfferingDTO.class);

        require(source.getOfferingId().equals(copy.getOfferingId()),
                "BIGINT ID must stay exact");
    }

    private static void roundTripsCourseTermListWithTypeToken() {
        List<CourseTermDTO> source = Arrays.asList(
                new CourseTermDTO("2025-2026", 1, "2025-2026学年第一学期"),
                new CourseTermDTO("2024-2025", 2, "2024-2025学年第二学期"));
        Type type = new TypeToken<List<CourseTermDTO>>() {}.getType();

        List<CourseTermDTO> copy = GSON.fromJson(GSON.toJson(source), type);

        require(copy.size() == 2, "term list size must round-trip");
        require("2025-2026".equals(copy.get(0).getAcademicYear()),
                "academic year must round-trip");
        require(copy.get(0).getSemester() == 1, "semester must round-trip");
        require("2025-2026学年第一学期".equals(copy.get(0).getDisplayName()),
                "display name must round-trip");
    }

    private static void collectionFieldsAreDefensiveAndUnmodifiable() {
        List<GradeRecordDTO> records = new ArrayList<>();
        records.add(new GradeRecordDTO(
                "2025-2026-1", "CS203", "数据结构", 4.0, 92.0, 4.0,
                90.0, null, 94.0, 93.0));
        GradeSummaryDTO summary = new GradeSummaryDTO(
                "2025-2026-1", 4.0, 92.0, 90.0, 3.8, records);
        records.clear();
        require(summary.getRecords().size() == 1,
                "grade records must be a defensive copy");
        requireUnmodifiable(summary.getRecords(), "grade records must be unmodifiable");

        List<TrainingPlanCourseDTO> courses = new ArrayList<>();
        courses.add(new TrainingPlanCourseDTO("CS203", "数据结构", 4.0, "已修"));
        TrainingPlanGroupDTO group = new TrainingPlanGroupDTO("专业必修", 40.0, 20.0, courses);
        courses.clear();
        require(group.getCourses().size() == 1,
                "training plan courses must be a defensive copy");
        requireUnmodifiable(group.getCourses(), "training plan courses must be unmodifiable");

        List<PlanConfirmationItemDTO> items = new ArrayList<>();
        items.add(new PlanConfirmationItemDTO(
                "9007199254740993", "数据结构", SelectionStateDTO.ENROLLED, true, null));
        List<CourseOfferingDTO> offerings = new ArrayList<>();
        offerings.add(new CourseOfferingDTO(
                "9007199254740993", "CS203", "数据结构", "必修", 4.0, 64,
                "张老师", "周二 3-4节", "教四-201", "线性表、树和图", "程序设计基础",
                96, 120, SelectionStateDTO.ENROLLED));
        PlanConfirmationDTO confirmation = new PlanConfirmationDTO(items, offerings);
        items.clear();
        offerings.clear();
        require(confirmation.getItems().size() == 1,
                "confirmation items must be a defensive copy");
        require(confirmation.getOfferings().size() == 1,
                "refreshed offerings must be a defensive copy");
        requireUnmodifiable(confirmation.getItems(),
                "confirmation items must be unmodifiable");
        requireUnmodifiable(confirmation.getOfferings(),
                "refreshed offerings must be unmodifiable");
    }

    private static void deserializedCollectionFieldsAreUnmodifiable() {
        GradeSummaryDTO summary = new GradeSummaryDTO(
                "2025-2026-1", 4.0, 92.0, 90.0, 3.8,
                Arrays.asList(new GradeRecordDTO(
                        "2025-2026-1", "CS203", "数据结构", 4.0, 92.0, 4.0,
                        90.0, null, 94.0, 93.0)));
        GradeSummaryDTO copy = GSON.fromJson(GSON.toJson(summary), GradeSummaryDTO.class);

        requireUnmodifiable(copy.getRecords(),
                "deserialized grade records must be unmodifiable");
    }

    private static void requireUnmodifiable(List<?> values, String message) {
        try {
            values.clear();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
