package dao;

import entity.BorrowRecord;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 借阅记录数据访问对象 (BorrowRecordDAO)
 *
 * 负责 tblBorrowRecord 表的数据访问。
 */
public class BorrowRecordDAO {

    /**
     * 根据借阅记录编号查询。
     *
     * @param id 借阅记录编号
     * @return 借阅记录，不存在返回 null
     * @throws SQLException 数据库异常
     */
    public BorrowRecord findById(Integer id) throws SQLException {

        String sql =
                "SELECT id, userid, bookid, borrowTime, returnTime, dueTime, status " +
                        "FROM tblBorrowRecord WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapBorrowRecord(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据用户编号查询全部借阅记录。
     *
     * @param userId 用户一卡通号
     * @return 用户全部借阅记录
     * @throws SQLException 数据库异常
     */
    public List<BorrowRecord> findByUserId(String userId) throws SQLException {

        String sql =
                "SELECT id, userid, bookid, borrowTime, returnTime, dueTime, status " +
                        "FROM tblBorrowRecord " +
                        "WHERE userid = ? " +
                        "ORDER BY borrowTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<BorrowRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapBorrowRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据图书编号查询借阅记录。
     *
     * @param bookId 图书编号
     * @return 指定图书的全部借阅记录
     * @throws SQLException 数据库异常
     */
    public List<BorrowRecord> findByBookId(Integer bookId) throws SQLException {

        String sql =
                "SELECT id, userid, bookid, borrowTime, returnTime, dueTime, status " +
                        "FROM tblBorrowRecord " +
                        "WHERE bookid = ? " +
                        "ORDER BY borrowTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<BorrowRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, bookId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapBorrowRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增借阅记录。
     *
     * 借阅操作由图书馆设备完成，该方法供服务器端业务调用。
     *
     * @param record 借阅记录
     * @return 是否新增成功
     * @throws SQLException 数据库异常
     */
    public boolean insert(BorrowRecord record) throws SQLException {

        String sql =
                "INSERT INTO tblBorrowRecord " +
                        "(userid, bookid, borrowTime, returnTime, dueTime, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, record.getUserId());
            stmt.setInt(2, record.getBookId());

            stmt.setTimestamp(
                    3,
                    Timestamp.valueOf(record.getBorrowTime())
            );

            if (record.getReturnTime() != null) {
                stmt.setTimestamp(
                        4,
                        Timestamp.valueOf(record.getReturnTime())
                );
            } else {
                stmt.setTimestamp(4, null);
            }

            stmt.setTimestamp(
                    5,
                    Timestamp.valueOf(record.getDueTime())
            );

            stmt.setInt(6, record.getStatus());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改借阅记录。
     *
     * 主要用于归还图书、更新逾期状态等操作。
     *
     * @param record 借阅记录
     * @return 是否修改成功
     * @throws SQLException 数据库异常
     */
    public boolean update(BorrowRecord record) throws SQLException {

        String sql =
                "UPDATE tblBorrowRecord " +
                        "SET userid = ?, bookid = ?, borrowTime = ?, " +
                        "returnTime = ?, dueTime = ?, status = ? " +
                        "WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, record.getUserId());
            stmt.setInt(2, record.getBookId());

            stmt.setTimestamp(
                    3,
                    Timestamp.valueOf(record.getBorrowTime())
            );

            if (record.getReturnTime() != null) {
                stmt.setTimestamp(
                        4,
                        Timestamp.valueOf(record.getReturnTime())
                );
            } else {
                stmt.setTimestamp(4, null);
            }

            stmt.setTimestamp(
                    5,
                    Timestamp.valueOf(record.getDueTime())
            );

            stmt.setInt(6, record.getStatus());
            stmt.setInt(7, record.getId());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 查询用户当前尚未归还的图书。
     *
     * 状态：
     * 0 - 借阅中
     * 2 - 逾期
     *
     * @param userId 用户一卡通号
     * @return 当前借阅记录
     * @throws SQLException 数据库异常
     */
    public List<BorrowRecord> findActiveByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, borrowTime, returnTime, dueTime, status " +
                        "FROM tblBorrowRecord " +
                        "WHERE userid = ? AND status IN (0, 2) " +
                        "ORDER BY dueTime ASC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<BorrowRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapBorrowRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 将 ResultSet 映射为 BorrowRecord 实体。
     */
    private BorrowRecord mapBorrowRecord(ResultSet rs)
            throws SQLException {

        BorrowRecord record = new BorrowRecord();

        record.setId(rs.getInt("id"));
        record.setUserId(rs.getString("userid"));
        record.setBookId(rs.getInt("bookid"));

        Timestamp borrowTime = rs.getTimestamp("borrowTime");
        if (borrowTime != null) {
            record.setBorrowTime(borrowTime.toLocalDateTime());
        }

        Timestamp returnTime = rs.getTimestamp("returnTime");
        if (returnTime != null) {
            record.setReturnTime(returnTime.toLocalDateTime());
        }

        Timestamp dueTime = rs.getTimestamp("dueTime");
        if (dueTime != null) {
            record.setDueTime(dueTime.toLocalDateTime());
        }

        record.setStatus(rs.getInt("status"));

        return record;
    }
}