package dao;

import entity.LossRecord;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 图书挂失记录数据访问对象 (LossRecordDAO)
 *
 * 负责 tblLossRecord 表的数据访问。
 */
public class LossRecordDAO {
    /** 每本仍在挂失的图书一条公告，已解除的记录不公开。 */
    public List<vo.LostBookNotice> findPublicNotices() throws SQLException {
        List<vo.LostBookNotice> notices = new ArrayList<>();
        String sql = "SELECT b.id,b.name,b.author,MAX(l.lossTime) AS lossTime " +
                "FROM tblLossRecord l JOIN tblBook b ON b.id=l.bookid WHERE l.status=0 " +
                "GROUP BY b.id,b.name,b.author ORDER BY lossTime DESC";
        try (Connection conn = DBUtil.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) notices.add(new vo.LostBookNotice(rows.getInt("id"), rows.getString("name"),
                    rows.getString("author"), rows.getTimestamp("lossTime").toLocalDateTime().toString()));
        }
        return notices;
    }

    /**
     * 根据挂失记录编号查询
     *
     * @param id 挂失记录编号
     * @return 挂失记录，不存在返回 null
     * @throws SQLException 数据库异常
     */
    public LossRecord findById(Integer id)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, lossTime, status " +
                        "FROM tblLossRecord WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapLossRecord(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据用户查询挂失记录
     *
     * @param userId 用户一卡通号
     * @return 用户的挂失记录
     * @throws SQLException 数据库异常
     */
    public List<LossRecord> findByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, lossTime, status " +
                        "FROM tblLossRecord " +
                        "WHERE userid = ? " +
                        "ORDER BY lossTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<LossRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapLossRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据图书编号查询挂失记录
     *
     * @param bookId 图书编号
     * @return 图书的挂失记录
     * @throws SQLException 数据库异常
     */
    public List<LossRecord> findByBookId(Integer bookId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, lossTime, status " +
                        "FROM tblLossRecord " +
                        "WHERE bookid = ? " +
                        "ORDER BY lossTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<LossRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, bookId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapLossRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增挂失记录
     *
     * @param record 挂失记录
     * @return 是否新增成功
     * @throws SQLException 数据库异常
     */
    public boolean insert(LossRecord record)
            throws SQLException {

        String sql =
                "INSERT INTO tblLossRecord " +
                        "(userid, bookid, lossTime, status) " +
                        "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, record.getUserId());
            stmt.setInt(2, record.getBookId());
            stmt.setTimestamp(
                    3,
                    Timestamp.valueOf(record.getLossTime())
            );
            stmt.setInt(4, record.getStatus());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改挂失状态
     *
     * @param id 挂失记录编号
     * @param status 新状态
     * @return 是否修改成功
     * @throws SQLException 数据库异常
     */
    public boolean updateStatus(Integer id, Integer status)
            throws SQLException {

        String sql =
                "UPDATE tblLossRecord " +
                        "SET status = ? " +
                        "WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, status);
            stmt.setInt(2, id);

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * ResultSet 转 LossRecord 实体
     */
    private LossRecord mapLossRecord(ResultSet rs)
            throws SQLException {

        LossRecord record = new LossRecord();

        record.setId(rs.getInt("id"));
        record.setUserId(rs.getString("userid"));
        record.setBookId(rs.getInt("bookid"));

        Timestamp lossTime = rs.getTimestamp("lossTime");

        if (lossTime != null) {
            record.setLossTime(
                    lossTime.toLocalDateTime()
            );
        }

        record.setStatus(rs.getInt("status"));

        return record;
    }
}
