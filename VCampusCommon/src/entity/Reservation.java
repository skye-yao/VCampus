package entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class Reservation implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private int bookId;
    private LocalDateTime reserveTime;

    /**
     * 预约状态：
     * 0 - 预约中
     * 1 - 已取消
     * 2 - 已借阅
     */
    private int status;

    public Reservation() {
    }

    public Reservation(int id, String userId, int bookId,
                       LocalDateTime reserveTime, int status) {
        this.id = id;
        this.userId = userId;
        this.bookId = bookId;
        this.reserveTime = reserveTime;
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

    public LocalDateTime getReserveTime() {
        return reserveTime;
    }

    public void setReserveTime(LocalDateTime reserveTime) {
        this.reserveTime = reserveTime;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Reservation{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", bookId=" + bookId +
                ", reserveTime=" + reserveTime +
                ", status=" + status +
                '}';
    }
}