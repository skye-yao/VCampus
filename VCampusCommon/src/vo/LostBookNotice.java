package vo;

/** 公共挂失公告不包含挂失人的账号或联系方式。 */
public class LostBookNotice {
    private int bookId;
    private String name;
    private String author;
    private String lossTime;

    public LostBookNotice(int bookId, String name, String author, String lossTime) {
        this.bookId = bookId;
        this.name = name;
        this.author = author;
        this.lossTime = lossTime;
    }
    public int getBookId() { return bookId; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public String getLossTime() { return lossTime; }
}
