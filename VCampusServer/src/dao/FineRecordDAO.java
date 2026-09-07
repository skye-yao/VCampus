package dao;

import entity.FineRecord;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 罚款记录数据访问对象 (FineRecordDAO)
 *
 * 负责 tblFineRecord 表的数据访问。
 */
public class FineRecordDAO {

    /**
     * 根据罚款记录编号查询
     *
     * @param id 罚款记录编号
     * @return 罚款记录，不存在返回 null
     * @throws SQLException 数据库异常
     */
    public FineRecord findById(Integer id)
            throws SQLException {

        String sql =
                "SELECT id, userid, amount, reason, status " +
                        "FROM tblFineRecord WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapFineRecord(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据用户编号查询全部罚款记录
     *
     * @param userId 用户一卡通号
     * @return 用户全部罚款记录
     * @throws SQLException 数据库异常
     */
    public List<FineRecord> findByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, amount, reason, status " +
                        "FROM tblFineRecord " +
                        "WHERE userid = ? " +
                        "ORDER BY id DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<FineRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapFineRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增罚款记录
     *
     * @param record 罚款记录
     * @return 是否新增成功
     * @throws SQLException 数据库异常
     */
    public boolean insert(FineRecord record)
            throws SQLException {

        String sql =
                "INSERT INTO tblFineRecord " +
                        "(userid, amount, reason, status) " +
                        "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, record.getUserId());
            stmt.setBigDecimal(2, record.getAmount());
            stmt.setString(3, record.getReason());
            stmt.setInt(4, record.getStatus());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改罚款缴费状态
     *
     * @param id 罚款记录编号
     * @param status 新状态
     * @return 是否修改成功
     * @throws SQLException 数据库异常
     */
    public boolean updateStatus(Integer id, Integer status)
            throws SQLException {

        String sql =
                "UPDATE tblFineRecord " +
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
     * 查询用户所有未缴费记录
     *
     * status = 0 表示未缴费
     *
     * @param userId 用户一卡通号
     * @return 用户未缴费的罚款记录
     * @throws SQLException 数据库异常
     */
    public List<FineRecord> findUnpaidByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, amount, reason, status " +
                        "FROM tblFineRecord " +
                        "WHERE userid = ? AND status = 0 " +
                        "ORDER BY id DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<FineRecord> records = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                records.add(mapFineRecord(rs));
            }

            return records;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * ResultSet 转 FineRecord 实体
     */
    private FineRecord mapFineRecord(ResultSet rs)
            throws SQLException {

        FineRecord record = new FineRecord();

        record.setId(rs.getInt("id"));
        record.setUserId(rs.getString("userid"));
        record.setAmount(rs.getBigDecimal("amount"));
        record.setReason(rs.getString("reason"));
        record.setStatus(rs.getInt("status"));

        return record;
    }
}