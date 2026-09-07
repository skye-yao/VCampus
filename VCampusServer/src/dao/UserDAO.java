package dao;

import entity.User;
import enums.Role;
import util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 用户数据访问对象 (UserDAO)
 */
public class UserDAO {

    /**
     * 注册新用户：在同一事务中创建用户账号、银行账户和学籍档案
     */
    public boolean register(User user) throws SQLException {
        String sqlUser = "INSERT INTO tbl_user (UID, name, gender, password, salt, role, college, major, phone, email, balance) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String sqlBank = "INSERT INTO tbl_bank_account (user_id, balance, status) VALUES (?, ?, 'ACTIVE') " +
                "ON DUPLICATE KEY UPDATE balance=balance";

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. 写入用户基本表（未填字段全部置空）
            try (PreparedStatement stmt = conn.prepareStatement(sqlUser)) {
                stmt.setString(1, user.getUID());
                stmt.setString(2, user.getName());
                stmt.setString(3, user.getGender() != null && !user.getGender().isBlank() ? user.getGender() : "男");
                stmt.setString(4, user.getPassword());
                stmt.setString(5, user.getSalt());
                stmt.setInt(6, user.getRole() != null ? user.getRole().getCode() : Role.STUDENT.getCode());
                stmt.setString(7, user.getCollege() != null ? user.getCollege() : "");
                stmt.setString(8, user.getMajor() != null ? user.getMajor() : "");
                stmt.setString(9, user.getPhone() != null ? user.getPhone() : "");
                stmt.setString(10, user.getEmail() != null ? user.getEmail() : "");
                BigDecimal bonus = user.getBalance() != null ? user.getBalance() : new BigDecimal("1000.00");
                stmt.setBigDecimal(11, bonus);
                stmt.executeUpdate();
            }

            // 2. 初始化银行账户（发放 1000.00 新用户福利）
            BigDecimal bonus = user.getBalance() != null ? user.getBalance() : new BigDecimal("1000.00");
            long accountId = 0;
            try (PreparedStatement stmt = conn.prepareStatement(sqlBank, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, user.getUID());
                stmt.setBigDecimal(2, bonus);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) accountId = rs.getLong(1);
                }
            }
            if (accountId == 0) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT account_id FROM tbl_bank_account WHERE user_id=?")) {
                    stmt.setString(1, user.getUID());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) accountId = rs.getLong(1);
                    }
                }
            }

            // 写入银行交易流水（新用户福利）
            if (accountId > 0) {
                String sqlTx = "INSERT INTO tbl_bank_transaction (transaction_no, account_id, transaction_type, amount, balance_after, remark) " +
                        "VALUES (?, ?, 'INITIAL_BALANCE', ?, ?, '新用户福利')";
                try (PreparedStatement stmt = conn.prepareStatement(sqlTx)) {
                    stmt.setString(1, "TX-BONUS-" + System.currentTimeMillis() + "-" + user.getUID());
                    stmt.setLong(2, accountId);
                    stmt.setBigDecimal(3, bonus);
                    stmt.setBigDecimal(4, bonus);
                    stmt.executeUpdate();
                }
            }

            // 3. 根据身份分别初始化对应档案（学生或教师，未填字段全部置空）
            if (user.getRole() == Role.TEACHER) {
                String sqlTeacher = "INSERT INTO tblTeacher (teacherId, UID, name, politicalStatus, nationality, gender, " +
                        "idType, idNumber, idIssueDate, birthDate, nativePlace, householdType, birthPlace, registeredResidence, " +
                        "healthStatus, employed, employmentStatus, college, department, title, position, mobile) " +
                        "VALUES (?, ?, ?, '群众', '汉族', '男', '居民身份证', ?, CURRENT_DATE, '1990-01-01', '', '城镇户口', '', '', '健康', 1, 'ACTIVE', '', '', '', '', ?) " +
                        "ON DUPLICATE KEY UPDATE name=VALUES(name)";
                try (PreparedStatement stmt = conn.prepareStatement(sqlTeacher)) {
                    stmt.setString(1, user.getUID());
                    stmt.setString(2, user.getUID());
                    stmt.setString(3, user.getName());
                    stmt.setString(4, user.getUID());
                    stmt.setString(5, user.getPhone() != null ? user.getPhone() : "");
                    stmt.executeUpdate();
                }
            } else {
                String sqlStudent = "INSERT INTO tblStudent (studentId, UID, name, gender, college, major, studentStatus, mobile) " +
                        "VALUES (?, ?, ?, '男', '', '', '在籍', ?) " +
                        "ON DUPLICATE KEY UPDATE name=VALUES(name)";
                try (PreparedStatement stmt = conn.prepareStatement(sqlStudent)) {
                    stmt.setString(1, user.getUID());
                    stmt.setString(2, user.getUID());
                    stmt.setString(3, user.getName());
                    stmt.setString(4, user.getPhone() != null ? user.getPhone() : "");
                    stmt.executeUpdate();
                }
            }

            conn.commit();
            return true;
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw e;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                DBUtil.close(conn, null, null);
            }
        }
    }

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
                user.setBalance(rs.getBigDecimal("balance"));
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
        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 将学籍信息（学院、专业、姓名、性别）与银行信息（余额）同步到 tbl_user
     */
    public void syncUserInfo(Connection conn, String uid) {
        if (uid == null || uid.isBlank()) return;
        try {
            // 1. 同步学生学籍信息（学院、专业、姓名、性别）
            String sqlStudent = "UPDATE tbl_user u " +
                    "JOIN tblStudent s ON u.UID = s.UID " +
                    "SET u.name = s.name, u.gender = s.gender, u.college = s.college, u.major = s.major " +
                    "WHERE u.UID = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlStudent)) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }

            // 2. 同步教师信息（学院、职称、姓名、性别）
            String sqlTeacher = "UPDATE tbl_user u " +
                    "JOIN tblTeacher t ON u.UID = t.UID " +
                    "SET u.name = t.name, u.gender = t.gender, u.college = t.college, u.major = t.title " +
                    "WHERE u.UID = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlTeacher)) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }

            // 3. 同步银行账户余额
            String sqlBank = "UPDATE tbl_user u " +
                    "JOIN tbl_bank_account b ON u.UID = b.user_id " +
                    "SET u.balance = b.balance " +
                    "WHERE u.UID = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlBank)) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[UserDAO] syncUserInfo error: " + e.getMessage());
        }
    }

    public void syncUserInfo(String uid) {
        if (uid == null || uid.isBlank()) return;
        try (Connection conn = DBUtil.getConnection()) {
            syncUserInfo(conn, uid);
        } catch (SQLException e) {
            System.err.println("[UserDAO] syncUserInfo error: " + e.getMessage());
        }
    }

    /**
     * 全量同步所有用户的学籍与银行数据到 tbl_user
     */
    public void syncAllUsers() {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            // 1. 同步学生
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tblStudent s ON u.UID = s.UID " +
                    "SET u.name = s.name, u.gender = s.gender, u.college = s.college, u.major = s.major");
            // 2. 同步教师
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tblTeacher t ON u.UID = t.UID " +
                    "SET u.name = t.name, u.gender = t.gender, u.college = t.college, u.major = t.title");
            // 3. 同步银行余额
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tbl_bank_account b ON u.UID = b.user_id " +
                    "SET u.balance = b.balance");
        } catch (SQLException e) {
            System.err.println("[UserDAO] syncAllUsers error: " + e.getMessage());
        }
    }
}
