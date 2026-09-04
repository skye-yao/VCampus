package entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class LossRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private int bookId;
    private LocalDateTime lossTime;

    /**
     * 挂失状态：
     * 0 - 挂失中
     * 1 - 已解除
     */
    private int status;

    public LossRecord() {
    }

    public LossRecord(int id, String userId, int bookId,
                      LocalDateTime lossTime, int status) {
        this.id = id;
        this.userId = userId;
        this.bookId = bookId;
        this.lossTime = lossTime;
        this.status = status;
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

    public LocalDateTime getLossTime() {
        return lossTime;
    }

    public void setLossTime(LocalDateTime lossTime) {
        this.lossTime = lossTime;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "LossRecord{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", bookId=" + bookId +
                ", lossTime=" + lossTime +
                ", status=" + status +
                '}';
    }
}