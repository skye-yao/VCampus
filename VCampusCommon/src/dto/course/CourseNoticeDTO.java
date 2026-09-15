package dto.course;

public final class CourseNoticeDTO {
    private final String noticeId;
    private final String offeringId;
    private final String term;
    private final int week;
    private final String noticeType;
    private final String title;
    private final String content;

    public CourseNoticeDTO(String noticeId, String offeringId, String term, int week,
            String noticeType, String title, String content) {
        this.noticeId = noticeId;
        this.offeringId = offeringId;
        this.term = term;
        this.week = week;
        this.noticeType = noticeType;
        this.title = title;
        this.content = content;
    }

    public String getNoticeId() {
        return noticeId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getTerm() {
        return term;
    }

    public int getWeek() {
        return week;
    }

    public String getNoticeType() {
        return noticeType;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }
}
