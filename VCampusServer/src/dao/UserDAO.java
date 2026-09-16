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
import java.util.ArrayList;
import java.util.List;

/**
 * 用户数据访问对象 (UserDAO)
 */
@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlResolve",
        "SqlWithoutWhere"
})
public class UserDAO {

    private static volatile boolean userTableReady = false;

    /**
     * 确保数据表 tbl_user 具备 status 列（状态: ACTIVE-正常, FROZEN-已冻结, DELETED-已注销）
     */
    public static synchronized void ensureTable() {
        if (userTableReady) return;
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet cols = conn.getMetaData().getColumns(conn.getCatalog(), null, "tbl_user", "status")) {
                if (!cols.next()) {
                    stmt.execute("ALTER TABLE tbl_user ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, FROZEN, DELETED'");
                }
            }
            userTableReady = true;
        } catch (SQLException e) {
            System.err.println("[UserDAO] ensureTable 补列 status 异常: " + e.getMessage());
        }
    }

    /**
     * 注册新用户：在同一事务中创建用户账号、银行账户和学籍档案
     */
    public void register(User user) throws SQLException {
        String sqlUser = "INSERT INTO tbl_user (UID, name, gender, password, salt, role, college, major, phone, email, balance) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String sqlBank = "INSERT INTO tbl_bank_account (user_id, balance, status) VALUES (?, ?, 'ACTIVE') " +
                "ON DUPLICATE KEY UPDATE balance=balance";

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);
            BigDecimal openingBalance = user.getBalance() != null
                    ? user.getBalance() : new BigDecimal("10000.00");

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
                stmt.setBigDecimal(11, openingBalance);
                stmt.executeUpdate();
            }

            // 2. 银行表保存唯一可信余额，用户表中的 balance 是同一校园账户余额的镜像。
            long accountId = 0;
            try (PreparedStatement stmt = conn.prepareStatement(sqlBank, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, user.getUID());
                stmt.setBigDecimal(2, openingBalance);
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
                    stmt.setBigDecimal(3, openingBalance);
                    stmt.setBigDecimal(4, openingBalance);
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
        ensureTable();
        String sql = "SELECT u.UID,u.name,u.gender,u.password,u.salt,u.role,u.college,u.major," +
                "u.phone,u.email,u.avatar,COALESCE(b.balance,u.balance) AS balance,COALESCE(u.status,'ACTIVE') AS status " +
                "FROM tbl_user u LEFT JOIN tbl_bank_account b ON b.user_id=u.UID WHERE u.UID=?";
        
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
                user.setStatus(rs.getString("status"));
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
            boolean ok = stmt.executeUpdate() > 0;
            if (ok) {
                syncToStudentOrTeacher(conn, user);
            }
            return ok;
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
     * 将学籍信息（学院、专业、姓名、性别、手机、邮箱）与银行信息（余额）同步到 tbl_user
     */
    public void syncUserInfo(Connection conn, String uid) {
        if (uid == null || uid.isBlank()) return;
        try {
            // 1. 同步学生学籍信息（学院、专业、姓名、性别、手机、邮箱）
            String sqlStudent = "UPDATE tbl_user u " +
                    "JOIN tblStudent s ON (u.UID COLLATE utf8mb4_unicode_ci = s.UID COLLATE utf8mb4_unicode_ci " +
                    "OR u.UID COLLATE utf8mb4_unicode_ci = s.studentId COLLATE utf8mb4_unicode_ci) " +
                    "SET u.name = s.name, u.gender = s.gender, u.college = s.college, u.major = s.major, " +
                    "u.phone = COALESCE(NULLIF(s.mobile, ''), u.phone), u.email = COALESCE(NULLIF(s.email, ''), u.email) " +
                    "WHERE u.UID = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlStudent)) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }

            // 2. 同步教师信息（学院、职称、姓名、性别、手机、邮箱）
            String sqlTeacher = "UPDATE tbl_user u " +
                    "JOIN tblTeacher t ON (u.UID = t.UID OR u.UID = t.teacherId) " +
                    "SET u.name = t.name, u.gender = t.gender, u.college = t.college, u.major = t.title, " +
                    "u.phone = COALESCE(NULLIF(t.mobile, ''), u.phone), u.email = COALESCE(NULLIF(t.email, ''), u.email) " +
                    "WHERE u.UID = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlTeacher)) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }

            // 3. 将银行主余额同步到用户资料页使用的余额镜像。
            String sqlBalance = "UPDATE tbl_user u JOIN tbl_bank_account b ON u.UID=b.user_id " +
                    "SET u.balance=b.balance WHERE u.UID=?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlBalance)) {
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
     * 全量同步学生、教师基本资料，以及校园账户余额镜像到 tbl_user。
     */
    public void syncAllUsers() {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            // 1. 同步学生
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tblStudent s ON u.UID COLLATE utf8mb4_unicode_ci = s.UID COLLATE utf8mb4_unicode_ci " +
                    "SET u.name = s.name, u.gender = s.gender, u.college = s.college, u.major = s.major");
            // 2. 同步教师
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tblTeacher t ON u.UID = t.UID " +
                    "SET u.name = t.name, u.gender = t.gender, u.college = t.college, u.major = t.title");
            // 3. 银行账户余额是主数据，用户资料页余额与其保持一致。
            stmt.executeUpdate("UPDATE tbl_user u " +
                    "JOIN tbl_bank_account b ON u.UID = b.user_id " +
                    "SET u.balance = b.balance");
        } catch (SQLException e) {
            System.err.println("[UserDAO] syncAllUsers error: " + e.getMessage());
        }
    }

    /**
     * 多条件模糊查询用户列表
     *
     * @param keyword 关键字（模糊匹配 UID, name, phone, email）
     * @param roleStr 角色过滤（全部 / 学生 / 教师 / 管理员）
     * @param statusStr 状态过滤（全部 / ACTIVE / FROZEN / DELETED）
     */
    public List<User> listUsers(String keyword, String roleStr, String statusStr) throws SQLException {
        ensureTable();
        StringBuilder sql = new StringBuilder("SELECT u.UID, u.name, u.gender, u.role, u.college, u.major, " +
                "u.phone, u.email, u.avatar, COALESCE(b.balance, u.balance) AS balance, " +
                "COALESCE(u.status, 'ACTIVE') AS status, u.create_time " +
                "FROM tbl_user u LEFT JOIN tbl_bank_account b ON b.user_id = u.UID WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            sql.append("AND (u.UID LIKE ? OR u.name LIKE ? OR u.phone LIKE ? OR u.email LIKE ?) ");
            params.add(kw);
            params.add(kw);
            params.add(kw);
            params.add(kw);
        }

        if (roleStr != null && !roleStr.isBlank() && !"全部".equals(roleStr) && !"ALL".equalsIgnoreCase(roleStr)) {
            Role r = null;
            if ("学生".equals(roleStr) || "STUDENT".equalsIgnoreCase(roleStr)) r = Role.STUDENT;
            else if ("教师".equals(roleStr) || "TEACHER".equalsIgnoreCase(roleStr)) r = Role.TEACHER;
            else if ("管理员".equals(roleStr) || "ADMIN".equalsIgnoreCase(roleStr)) r = Role.ADMIN;
            if (r != null) {
                sql.append("AND u.role = ? ");
                params.add(r.getCode());
            }
        }

        if (statusStr != null && !statusStr.isBlank() && !"全部".equals(statusStr) && !"ALL".equalsIgnoreCase(statusStr)) {
            String st = statusStr.trim();
            if (st.contains("正常") || "ACTIVE".equalsIgnoreCase(st)) st = "ACTIVE";
            else if (st.contains("冻结") || "FROZEN".equalsIgnoreCase(st)) st = "FROZEN";
            else if (st.contains("注销") || "DELETED".equalsIgnoreCase(st)) st = "DELETED";
            sql.append("AND COALESCE(u.status, 'ACTIVE') = ? ");
            params.add(st);
        }

        sql.append("ORDER BY CASE WHEN LOWER(u.UID) = 'admin' THEN 0 ELSE 1 END, u.create_time DESC, u.UID ASC");

        List<User> list = new ArrayList<>();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    User user = new User();
                    user.setUID(rs.getString("UID"));
                    user.setName(rs.getString("name"));
                    user.setGender(rs.getString("gender"));
                    user.setRole(Role.fromCode(rs.getInt("role")));
                    user.setCollege(rs.getString("college"));
                    user.setMajor(rs.getString("major"));
                    user.setPhone(rs.getString("phone"));
                    user.setEmail(rs.getString("email"));
                    user.setAvatar(rs.getString("avatar"));
                    user.setBalance(rs.getBigDecimal("balance"));
                    user.setStatus(rs.getString("status"));
                    list.add(user);
                }
            }
        }
        return list;
    }

    /**
     * 更新账号状态（ACTIVE-正常, FROZEN-已冻结, DELETED-已注销）
     */
    public boolean updateStatus(String uid, String status) throws SQLException {
        ensureTable();
        String sql = "UPDATE tbl_user SET status = ? WHERE UID = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, uid);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * 管理员修改用户基本资料并同步关联表
     */
    public boolean adminUpdateUser(User user) throws SQLException {
        ensureTable();
        String sql = "UPDATE tbl_user SET name = ?, gender = ?, college = ?, major = ?, phone = ?, email = ? WHERE UID = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getGender());
            stmt.setString(3, user.getCollege() != null ? user.getCollege() : "");
            stmt.setString(4, user.getMajor() != null ? user.getMajor() : "");
            stmt.setString(5, user.getPhone() != null ? user.getPhone() : "");
            stmt.setString(6, user.getEmail() != null ? user.getEmail() : "");
            stmt.setString(7, user.getUID());
            boolean ok = stmt.executeUpdate() > 0;
            if (ok) {
                syncToStudentOrTeacher(conn, user);
            }
            return ok;
        }
    }

    private void syncToStudentOrTeacher(Connection conn, User user) {
        if (user == null || user.getUID() == null) return;
        try {
            Role role = user.getRole();
            if (role == null) {
                String roleSql = "SELECT role FROM tbl_user WHERE UID = ?";
                try (PreparedStatement ps = conn.prepareStatement(roleSql)) {
                    ps.setString(1, user.getUID());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            role = Role.fromCode(rs.getInt("role"));
                        }
                    }
                }
            }

            if (role == Role.STUDENT) {
                String sql = "UPDATE tblStudent SET name = ?, gender = ?, college = ?, major = ?, mobile = ?, email = ? WHERE UID = ? OR studentId = ?";
                try (PreparedStatement s = conn.prepareStatement(sql)) {
                    s.setString(1, user.getName());
                    s.setString(2, user.getGender());
                    s.setString(3, user.getCollege() != null ? user.getCollege() : "");
                    s.setString(4, user.getMajor() != null ? user.getMajor() : "");
                    s.setString(5, user.getPhone() != null ? user.getPhone() : "");
                    s.setString(6, user.getEmail() != null ? user.getEmail() : "");
                    s.setString(7, user.getUID());
                    s.setString(8, user.getUID());
                    s.executeUpdate();
                }
            } else if (role == Role.TEACHER) {
                String sql = "UPDATE tblTeacher SET name = ?, gender = ?, college = ?, title = ?, mobile = ?, email = ? WHERE UID = ? OR teacherId = ?";
                try (PreparedStatement s = conn.prepareStatement(sql)) {
                    s.setString(1, user.getName());
                    s.setString(2, user.getGender());
                    s.setString(3, user.getCollege() != null ? user.getCollege() : "");
                    s.setString(4, user.getMajor() != null ? user.getMajor() : "");
                    s.setString(5, user.getPhone() != null ? user.getPhone() : "");
                    s.setString(6, user.getEmail() != null ? user.getEmail() : "");
                    s.setString(7, user.getUID());
                    s.setString(8, user.getUID());
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserDAO] syncToStudentOrTeacher warn: " + e.getMessage());
        }
    }
}
