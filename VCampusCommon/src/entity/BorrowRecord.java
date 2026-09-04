package entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class BorrowRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private int bookId;

    private LocalDateTime borrowTime;
    private LocalDateTime returnTime;
    private LocalDateTime dueTime;

    /**
     * 借阅状态：
     * 0 - 借阅中
     * 1 - 已归还
     * 2 - 逾期
     */
    private int status;
    // 响应附加状态，不覆盖数据库中的借阅/逾期状态。
    private boolean lossReported;
    public boolean isLossReported() { return lossReported; }
    public void setLossReported(boolean value) { lossReported = value; }

    public BorrowRecord() {
    }

    public BorrowRecord(int id, String userId, int bookId,
                        LocalDateTime borrowTime,
                        LocalDateTime returnTime,
                        LocalDateTime dueTime,
                        int status) {
        this.id = id;
        this.userId = userId;
        this.bookId = bookId;
        this.borrowTime = borrowTime;
        this.returnTime = returnTime;
        this.dueTime = dueTime;
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

    public LocalDateTime getBorrowTime() {
        return borrowTime;
    }

    public void setBorrowTime(LocalDateTime borrowTime) {
        this.borrowTime = borrowTime;
    }

    public LocalDateTime getReturnTime() {
        return returnTime;
    }

    public void setReturnTime(LocalDateTime returnTime) {
        this.returnTime = returnTime;
    }

    public LocalDateTime getDueTime() {
        return dueTime;
    }

    public void setDueTime(LocalDateTime dueTime) {
        this.dueTime = dueTime;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "BorrowRecord{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", bookId=" + bookId +
                ", borrowTime=" + borrowTime +
                ", returnTime=" + returnTime +
                ", dueTime=" + dueTime +
                ", status=" + status +
                '}';
    }
}
