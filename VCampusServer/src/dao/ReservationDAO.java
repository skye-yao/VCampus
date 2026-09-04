package dao;

import entity.Reservation;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 图书预约数据访问对象 (ReservationDAO)
 *
 * 负责 tblReservation 表的数据访问。
 */
public class ReservationDAO {

    /**
     * 根据预约编号查询预约记录。
     *
     * @param id 预约编号
     * @return 预约记录，不存在返回 null
     * @throws SQLException 数据库异常
     */
    public Reservation findById(Integer id) throws SQLException {

        String sql =
                "SELECT id, userid, bookid, reserveTime, status " +
                        "FROM tblReservation WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapReservation(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据用户查询预约记录。
     *
     * @param userId 用户一卡通号
     * @return 用户的预约记录
     * @throws SQLException 数据库异常
     */
    public List<Reservation> findByUserId(String userId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, reserveTime, status " +
                        "FROM tblReservation " +
                        "WHERE userid = ? " +
                        "ORDER BY reserveTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<Reservation> reservations = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                reservations.add(mapReservation(rs));
            }

            return reservations;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据图书编号查询预约记录。
     *
     * @param bookId 图书编号
     * @return 该图书的预约记录
     * @throws SQLException 数据库异常
     */
    public List<Reservation> findByBookId(Integer bookId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, reserveTime, status " +
                        "FROM tblReservation " +
                        "WHERE bookid = ? " +
                        "ORDER BY reserveTime DESC";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<Reservation> reservations = new ArrayList<>();

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, bookId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                reservations.add(mapReservation(rs));
            }

            return reservations;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增图书预约。
     *
     * @param reservation 预约记录
     * @return 是否预约成功
     * @throws SQLException 数据库异常
     */
    public boolean insert(Reservation reservation)
            throws SQLException {

        String sql =
                "INSERT INTO tblReservation " +
                        "(userid, bookid, reserveTime, status) " +
                        "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, reservation.getUserId());
            stmt.setInt(2, reservation.getBookId());

            stmt.setTimestamp(
                    3,
                    Timestamp.valueOf(reservation.getReserveTime())
            );

            stmt.setInt(4, reservation.getStatus());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改预约状态。
     *
     * 状态：
     * 0 - 预约中
     * 1 - 已取消
     * 2 - 已借阅
     *
     * @param id 预约编号
     * @param status 新状态
     * @return 是否修改成功
     * @throws SQLException 数据库异常
     */
    public boolean updateStatus(Integer id, Integer status)
            throws SQLException {

        String sql =
                "UPDATE tblReservation " +
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
     * 查询指定图书当前是否存在有效预约。
     *
     * status = 0 表示预约中。
     *
     * @param bookId 图书编号
     * @return 当前有效预约；不存在返回 null
     * @throws SQLException 数据库异常
     */
    public Reservation findActiveByBookId(Integer bookId)
            throws SQLException {

        String sql =
                "SELECT id, userid, bookid, reserveTime, status " +
                        "FROM tblReservation " +
                        "WHERE bookid = ? AND status = 0 " +
                        "ORDER BY reserveTime ASC " +
                        "LIMIT 1";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, bookId);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapReservation(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 将 ResultSet 映射为 Reservation 实体。
     */
    private Reservation mapReservation(ResultSet rs)
            throws SQLException {

        Reservation reservation = new Reservation();

        reservation.setId(rs.getInt("id"));
        reservation.setUserId(rs.getString("userid"));
        reservation.setBookId(rs.getInt("bookid"));

        Timestamp reserveTime = rs.getTimestamp("reserveTime");

        if (reserveTime != null) {
            reservation.setReserveTime(
                    reserveTime.toLocalDateTime()
            );
        }

        reservation.setStatus(rs.getInt("status"));

        return reservation;
    }
}