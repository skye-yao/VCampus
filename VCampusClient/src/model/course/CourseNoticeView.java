package model.course;

/** 学期教学周内展示给学生的课程通知。 */
public final class CourseNoticeView {
    private final String term;
    private final int week;
    private final String title;
    private final String content;

    /** 创建一条课程通知视图。 */
    public CourseNoticeView(String term, int week, String title, String content) {
        this.term = term;
        this.week = week;
        this.title = title;
        this.content = content;
    }

    public String getTerm() {
        return term;
    }

    public int getWeek() {
        return week;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }
}
