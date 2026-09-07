package entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class BookReview implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private int bookId;
    private String content;
    private LocalDateTime createTime;

    public BookReview() {
    }

    public BookReview(int id, String userId, int bookId,
                      String content, LocalDateTime createTime) {
        this.id = id;
        this.userId = userId;
        this.bookId = bookId;
        this.content = content;
        this.createTime = createTime;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getBookId() {
        return bookId;
    }

    public void setBookId(int bookId) {
        this.bookId = bookId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "BookReview{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", bookId=" + bookId +
                ", content='" + content + '\'' +
                ", createTime=" + createTime +
                '}';
    }
}