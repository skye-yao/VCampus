package dao;

import entity.User;
import enums.Role;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 用户数据访问对象 (UserDAO)
 */
public class UserDAO {

    /**
     * 根据一卡通号查询用户信息
     *
     * @param UID 一卡通号
     * @return User 实体，未找到返回 null
     * @throws SQLException 数据库异常
     */
    public User findByUID(String UID) throws SQLException {
        String sql = "SELECT UID, name, gender, password, salt, role, college, major, phone, email, avatar ,balance " +
                     "FROM tbl_user WHERE UID = ?";
        
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, UID);
            rs = stmt.executeQuery();

            if (rs.next()) {
                User user = new User();
                user.setUID(rs.getString("UID"));
                user.setName(rs.getString("name"));
                user.setGender(rs.getString("gender"));
                user.setPassword(rs.getString("password"));
                user.setSalt(rs.getString("salt"));
                user.setRole(Role.fromCode(rs.getInt("role")));
                user.setCollege(rs.getString("college"));
                user.setMajor(rs.getString("major"));
                user.setPhone(rs.getString("phone"));
                user.setEmail(rs.getString("email"));
                user.setAvatar(rs.getString("avatar"));
                user.setBalance(rs.getInt("balance"));
                return user;
            }
            return null;
        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 修改密码与盐值
     */
    public boolean updatePassword(String UID, String newPasswordHash, String newSalt) throws SQLException {
        String sql = "UPDATE tbl_user SET password = ?, salt = ? WHERE UID = ?";
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, newPasswordHash);
            stmt.setString(2, newSalt);
            stmt.setString(3, UID);
            return stmt.executeUpdate() > 0;
        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 更新用户个人基本信息
     */
    public boolean updateProfile(User user) throws SQLException {
        String sql = "UPDATE tbl_user SET name = ?, gender = ?, college = ?, major = ?, phone = ?, email = ? WHERE UID = ?";
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getGender());
            stmt.setString(3, user.getCollege());
            stmt.setString(4, user.getMajor());
            stmt.setString(5, user.getPhone());
            stmt.setString(6, user.getEmail());
            stmt.setString(7, user.getUID());
            return stmt.executeUpdate() > 0;
        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改头像
     */
    public boolean updateAvatar(String UID, String avatarBase64) throws SQLException {
        String sql = "UPDATE tbl_user SET avatar = ? WHERE UID = ?";
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, avatarBase64);
            stmt.setString(2, UID);
            return stmt.executeUpdate() > 0;
        }finally {
            DBUtil.close(conn, stmt, null);
        }

    }
}
