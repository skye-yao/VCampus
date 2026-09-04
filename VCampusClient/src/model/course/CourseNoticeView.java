package model.course;

public final class CourseNoticeView {
    private final String term;
    private final int week;
    private final String title;
    private final String content;

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
