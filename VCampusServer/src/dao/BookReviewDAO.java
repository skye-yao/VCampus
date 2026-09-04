package dao;

import entity.BookReview;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 图书书评数据访问对象 (BookReviewDAO)
 *
 * 负责 tblBookReview 表的数据访问。
 */
public class BookReviewDAO {

    /**
     * 根据书评编号查询书评
     *
     * @param id 书评编号
     * @return 书评，不存在返回 null
     * @throws SQLException 数据库异常
     */
    public BookReview findById(Integer id) throws SQLException {

        String sql =
                "SELECT id, userid, bookid, content, createTime " +
                        "FROM tblBookReview WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapBookReview(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据图书编号查询书评
     *
     * @param bookId 图书编号
     * @return 指定图书的全部书评
     * @throws SQLException 数据库异常
     */
    public List<BookReview> findByBookId(Integer bookId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, content, createTime " +
                        "FROM tblBookReview " +
                        "WHERE bookid = ? " +
                        "ORDER BY createTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<BookReview> reviews = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, bookId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                reviews.add(mapBookReview(rs));
            }

            return reviews;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据用户编号查询书评
     *
     * @param userId 用户一卡通号
     * @return 指定用户发表的全部书评
     * @throws SQLException 数据库异常
     */
    public List<BookReview> findByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, content, createTime " +
                        "FROM tblBookReview " +
                        "WHERE userid = ? " +
                        "ORDER BY createTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<BookReview> reviews = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                reviews.add(mapBookReview(rs));
            }

            return reviews;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增书评
     *
     * @param review 书评
     * @return 是否新增成功
     * @throws SQLException 数据库异常
     */
    public boolean insert(BookReview review)
            throws SQLException {

        String sql =
                "INSERT INTO tblBookReview " +
                        "(userid, bookid, content, createTime) " +
                        "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, review.getUserId());
            stmt.setInt(2, review.getBookId());
            stmt.setString(3, review.getContent());
            stmt.setTimestamp(
                    4,
                    Timestamp.valueOf(review.getCreateTime())
            );

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 删除书评
     *
     * @param id 书评编号
     * @return 是否删除成功
     * @throws SQLException 数据库异常
     */
    public boolean delete(Integer id)
            throws SQLException {

        String sql =
                "DELETE FROM tblBookReview WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * ResultSet 转 BookReview 实体
     */
    private BookReview mapBookReview(ResultSet rs)
            throws SQLException {

        BookReview review = new BookReview();

        review.setId(rs.getInt("id"));
        review.setUserId(rs.getString("userid"));
        review.setBookId(rs.getInt("bookid"));
        review.setContent(rs.getString("content"));

        Timestamp createTime = rs.getTimestamp("createTime");

        if (createTime != null) {
            review.setCreateTime(
                    createTime.toLocalDateTime()
            );
        }

        return review;
    }
}