package service;

public final class CourseServices {
    private static final CourseService CURRENT = new MockCourseService();

    private CourseServices() {
    }

    public static CourseService current() {
        return CURRENT;
    }
}
