package dto.course;

/** 教务模块的 CourseNoticeDTO 数据传输对象。 */
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

    /** 获取 NoticeId。 */
    public String getNoticeId() {
        return noticeId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 Term。 */
    public String getTerm() {
        return term;
    }

    /** 获取 Week。 */
    public int getWeek() {
        return week;
    }

    /** 获取 NoticeType。 */
    public String getNoticeType() {
        return noticeType;
    }

    /** 获取 Title。 */
    public String getTitle() {
        return title;
    }

    /** 获取 Content。 */
    public String getContent() {
        return content;
    }
}
