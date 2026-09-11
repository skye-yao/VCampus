package service;

import dao.BookDAO;
import dao.BookReviewDAO;
import dao.BorrowRecordDAO;
import dao.FineRecordDAO;
import dao.LossRecordDAO;
import dao.ReservationDAO;

import entity.Book;
import entity.BookReview;
import entity.BorrowRecord;
import entity.FineRecord;
import entity.LossRecord;
import entity.Reservation;

import enums.BookStatus;
import enums.FineStatus;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 图书馆服务器端业务服务类
 *
 * 负责处理图书馆模块的核心业务逻辑。
 *
 * DAO负责数据库访问，
 * Service负责业务规则判断以及多个DAO之间的组合调用。
 */
public class LibraryServerService {

    private final BookDAO bookDAO;
    private final BorrowRecordDAO borrowRecordDAO;
    private final ReservationDAO reservationDAO;
    private final BookReviewDAO bookReviewDAO;
    private final LossRecordDAO lossRecordDAO;
    private final FineRecordDAO fineRecordDAO;

    /**
     * 构造方法
     */
    public LibraryServerService() {
        this.bookDAO = new BookDAO();
        this.borrowRecordDAO = new BorrowRecordDAO();
        this.reservationDAO = new ReservationDAO();
        this.bookReviewDAO = new BookReviewDAO();
        this.lossRecordDAO = new LossRecordDAO();
        this.fineRecordDAO = new FineRecordDAO();
    }


    // =========================================================
    // 一、图书查询
    // =========================================================

    /**
     * 根据关键字查询图书。
     *
     * 支持：
     * 1. 书名
     * 2. 作者
     * 3. ISBN
     *
     * @param keyword 查询关键字
     * @return 图书列表
     */
    public List<Book> searchBook(String keyword)
            throws SQLException {

        if (keyword == null) {
            keyword = "";
        }

        keyword = keyword.trim();

        return bookDAO.findBooks(keyword);
    }


    /**
     * 查询图书详情。
     *
     * @param bookId 图书编号
     * @return 图书，不存在返回null
     */
    public Book getBookDetail(Integer bookId)
            throws SQLException {

        if (bookId == null || bookId <= 0) {
            return null;
        }

        return bookDAO.findById(bookId);
    }


    // =========================================================
    // 二、图书预约
    // =========================================================

    /**
     * 预约图书。
     *
     * 业务规则：
     * 1. 用户必须存在有效编号
     * 2. 图书必须存在
     * 3. 图书必须处于可借状态
     * 4. 当前图书不能已有有效预约
     * 5. 创建预约记录
     * 6. 修改图书状态为预约
     *
     * @param userId 用户编号
     * @param bookId 图书编号
     * @return 是否预约成功
     */
    public boolean reserveBook(String userId, Integer bookId)
            throws SQLException {

        if (userId == null || userId.isBlank()
                || bookId == null || bookId <= 0) {
            return false;
        }

        return bookDAO.reserveAvailableBook(userId, bookId);
    }


    /**
     * 查询用户预约记录。
     */
    public List<Reservation> getReservations(String userId)
            throws SQLException {

        return reservationDAO.findByUserId(userId);
    }


    // =========================================================
    // 三、借阅信息
    // =========================================================

    /**
     * 查询用户全部借阅历史。
     */
    public List<BorrowRecord> getBorrowHistory(String userId)
            throws SQLException {
        new dao.LibraryCirculationDAO().refresh(userId);
        return withLossStatus(userId, borrowRecordDAO.findByUserId(userId));
    }


    /**
     * 查询用户当前尚未归还的图书。
     *
     * 包括：
     * 0 - 借阅中
     * 2 - 逾期
     */
    public List<BorrowRecord> getCurrentBorrow(String userId)
            throws SQLException {
        new dao.LibraryCirculationDAO().refresh(userId);
        return withLossStatus(userId, borrowRecordDAO.findActiveByUserId(userId));
    }

    private List<BorrowRecord> withLossStatus(String userId, List<BorrowRecord> records) throws SQLException {
        java.util.Set<Integer> lostBooks = new java.util.HashSet<>();
        for (LossRecord loss : lossRecordDAO.findByUserId(userId)) {
            if (loss.getStatus() == 0) lostBooks.add(loss.getBookId());
        }
        for (BorrowRecord record : records) record.setLossReported(record.getReturnTime() == null
                && record.getStatus() != 1 && record.getStatus() != 3 && lostBooks.contains(record.getBookId()));
        return records;
    }

    public boolean cancelReservation(String userId, int reservationId) throws SQLException {
        return bookDAO.cancelReservation(userId, reservationId);
    }


    // =========================================================
    // 四、图书评价
    // =========================================================

    /**
     * 查询指定图书的全部书评。
     */
    public List<BookReview> getBookReviews(Integer bookId)
            throws SQLException {

        return bookReviewDAO.findByBookId(bookId);
    }


    /**
     * 添加图书评价。
     *
     * @param userId 用户编号
     * @param bookId 图书编号
     * @param content 评价内容
     */
    public boolean addBookReview(
            String userId,
            Integer bookId,
            String content
    ) throws SQLException {

        if (userId == null || userId.isBlank()
                || bookId == null || bookId <= 0
                || content == null || content.isBlank()) {

            return false;
        }

        // 图书必须存在
        Book book = bookDAO.findById(bookId);

        if (book == null) {
            return false;
        }

        BookReview review = new BookReview();

        review.setUserId(userId);
        review.setBookId(bookId);
        review.setContent(content.trim());
        review.setCreateTime(LocalDateTime.now());

        return bookReviewDAO.insert(review);
    }


    /**
     * 删除书评。
     *
     * 普通用户只能删除自己发表的书评。
     *
     * 管理员删除其他用户书评的权限，
     * 后续在消息分发/权限层中处理。
     *
     * @param userId 当前用户编号
     * @param reviewId 书评编号
     */
    public boolean deleteBookReview(
            String userId,
            Integer reviewId
    ) throws SQLException {
        return deleteBookReview(userId, reviewId, false);
    }

    public boolean deleteBookReview(String userId, Integer reviewId, boolean admin) throws SQLException {

        if (userId == null || userId.isBlank()
                || reviewId == null || reviewId <= 0) {

            return false;
        }

        BookReview review =
                bookReviewDAO.findById(reviewId);

        if (review == null) {
            return false;
        }

        // 普通用户只能删除自己的书评
        if (!admin && !userId.equals(review.getUserId())) {
            return false;
        }

        return bookReviewDAO.delete(reviewId);
    }


    // =========================================================
    // 五、图书挂失
    // =========================================================

    /**
     * 图书挂失。
     *
     * 用户只能挂失自己当前借阅中的图书。
     */
    public boolean reportLoss(
            String userId,
            Integer bookId
    ) throws SQLException {

        if (userId == null || userId.isBlank()
                || bookId == null || bookId <= 0) {

            return false;
        }

        return bookDAO.changeLoss(userId, bookId, true);
    }


    /**
     * 解除挂失。
     *
     * @param userId 用户编号
     * @param bookId 图书编号
     */
    public boolean cancelLoss(
            String userId,
            Integer bookId
    ) throws SQLException {

        if (userId == null || userId.isBlank()
                || bookId == null || bookId <= 0) {

            return false;
        }

        return bookDAO.changeLoss(userId, bookId, false);
    }


    // =========================================================
    // 六、罚款
    // =========================================================

    /**
     * 查询用户全部罚款记录。
     */
    public List<FineRecord> getFineRecords(String userId)
            throws SQLException {
        new dao.LibraryCirculationDAO().refresh(userId);
        return fineRecordDAO.findByUserId(userId);
    }


    /**
     * 查询用户未缴纳的罚款。
     */
    public List<FineRecord> getUnpaidFineRecords(String userId)
            throws SQLException {
        new dao.LibraryCirculationDAO().refresh(userId);
        return fineRecordDAO.findUnpaidByUserId(userId);
    }


    // =========================================================
    // 七、管理员图书管理
    // =========================================================

    /**
     * 图书上架。
     *
     * 权限检查由上层根据当前登录角色进行。
     */
    public boolean addBook(Book book)
            throws SQLException {
        validatePrice(book);

        if (book == null || book.getStatus() != BookStatus.AVAILABLE.getCode()) {
            return false;
        }

        if (book.getIsbn() == null
                || book.getIsbn().isBlank()
                || book.getName() == null
                || book.getName().isBlank()
                || book.getAuthor() == null
                || book.getAuthor().isBlank()) {

            return false;
        }

        // ISBN 已存在
        Book oldBook =
                bookDAO.findByIsbn(book.getIsbn());

        if (oldBook != null) {
            return false;
        }

        // 新图书默认可借
        book.setStatus(
                BookStatus.AVAILABLE.getCode()
        );

        return bookDAO.insert(book);
    }


    /**
     * 修改图书信息，并在同一事务中同步借阅、挂失和预约状态。
     */
    public boolean updateBook(Book book)
            throws SQLException {
        validatePrice(book);

        if (book == null || book.getId() <= 0) {
            return false;
        }

        Book oldBook =
                bookDAO.findById(book.getId());

        if (oldBook == null) {
            return false;
        }

        return bookDAO.update(book);
    }


    /**
     * 图书下架。
     *
     * 当前数据库设计采用实际删除。
     *
     * 如果图书存在借阅等外键记录，
     * MySQL会拒绝删除并抛出SQLException。
     */
    public boolean removeBook(Integer bookId)
            throws SQLException {

        if (bookId == null || bookId <= 0) {
            return false;
        }

        Book book =
                bookDAO.findById(bookId);

        if (book == null) {
            return false;
        }

        return bookDAO.delete(bookId);
    }


    /**
     * 查询图书当前状态。
     *
     * @return
     * 0 - 可借
     * 1 - 已借
     * 2 - 预约
     * 3 - 遗失
     *
     * 图书不存在返回null。
     */
    public Integer getBookStatus(Integer bookId)
            throws SQLException {

        Book book =
                bookDAO.findById(bookId);

        if (book == null) {
            return null;
        }

        return book.getStatus();
    }

    public List<vo.LostBookNotice> getPublicLossNotices() throws SQLException {
        return lossRecordDAO.findPublicNotices();
    }
    private void validatePrice(Book book) {
        if(book!=null && book.getPrice()!=null && (book.getPrice().signum()<=0 || book.getPrice().scale()>2
                || book.getPrice().compareTo(new java.math.BigDecimal("99999999.99"))>0))
            throw new IllegalArgumentException("书价须大于0，最多两位小数且不超过99999999.99元");
    }
}
