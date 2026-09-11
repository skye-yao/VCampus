package dao;

import entity.AdminPermission;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员权限数据访问对象 (AdminPermissionDAO)
 *
 * 负责管理管理员子系统权限数据表 tbl_admin_permission
 */
public class AdminPermissionDAO {

    private static volatile boolean tableReady = false;

    /**
     * 确保数据表 tbl_admin_permission 存在并具备最新字段（拆分商店权限与银行权限）
     */
    public static synchronized void ensureTable() {
        if (tableReady) {
            return;
        }
        String sql = "CREATE TABLE IF NOT EXISTS tbl_admin_permission (" +
                "uid VARCHAR(64) PRIMARY KEY, " +
                "academic_perm TINYINT(1) NOT NULL DEFAULT 0, " +
                "library_perm TINYINT(1) NOT NULL DEFAULT 0, " +
                "course_perm TINYINT(1) NOT NULL DEFAULT 0, " +
                "shop_perm TINYINT(1) NOT NULL DEFAULT 0, " +
                "bank_perm TINYINT(1) NOT NULL DEFAULT 0, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);

            // 针对已存在的旧表，热升级补全 shop_perm 与 bank_perm 列
            try (ResultSet cols = conn.getMetaData().getColumns(conn.getCatalog(), null, "tbl_admin_permission", "shop_perm")) {
                if (!cols.next()) {
                    stmt.execute("ALTER TABLE tbl_admin_permission ADD COLUMN shop_perm TINYINT(1) NOT NULL DEFAULT 0");
                }
            }
            try (ResultSet cols = conn.getMetaData().getColumns(conn.getCatalog(), null, "tbl_admin_permission", "bank_perm")) {
                if (!cols.next()) {
                    stmt.execute("ALTER TABLE tbl_admin_permission ADD COLUMN bank_perm TINYINT(1) NOT NULL DEFAULT 0");
                }
            }

            tableReady = true;
        } catch (SQLException e) {
            System.err.println("[AdminPermissionDAO] 初始化权限数据表失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有管理员（role = 0）的权限列表
     * UID 为 admin 的超级管理员固定为全 false，且排在列表首位
     */
    public List<AdminPermission> getAllAdminPermissions() throws SQLException {
        ensureTable();
        String sql = "SELECT u.UID, u.name, " +
                "COALESCE(p.academic_perm, 0) AS academic_perm, " +
                "COALESCE(p.library_perm, 0) AS library_perm, " +
                "COALESCE(p.course_perm, 0) AS course_perm, " +
                "COALESCE(p.shop_perm, 0) AS shop_perm, " +
                "COALESCE(p.bank_perm, 0) AS bank_perm " +
                "FROM tbl_user u " +
                "LEFT JOIN tbl_admin_permission p ON u.UID = p.uid " +
                "WHERE u.role = 0 " +
                "ORDER BY CASE WHEN LOWER(u.UID) = 'admin' THEN 0 ELSE 1 END, u.UID ASC";

        List<AdminPermission> result = new ArrayList<>();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String uid = rs.getString("UID");
                String name = rs.getString("name");

                AdminPermission perm = new AdminPermission();
                perm.setUid(uid);
                perm.setName(name != null ? name : "");
                perm.setAcademicPerm(rs.getInt("academic_perm") == 1);
                perm.setLibraryPerm(rs.getInt("library_perm") == 1);
                perm.setCoursePerm(rs.getInt("course_perm") == 1);
                perm.setShopPerm(rs.getInt("shop_perm") == 1);
                perm.setBankPerm(rs.getInt("bank_perm") == 1);
                result.add(perm);
            }
        }
        return result;
    }

    /**
     * 根据管理员 UID 获取其具体权限
     */
    public AdminPermission findByUid(String uid) throws SQLException {
        if (uid == null || uid.isBlank()) {
            return null;
        }

        ensureTable();
        String sql = "SELECT u.UID, u.name, " +
                "COALESCE(p.academic_perm, 0) AS academic_perm, " +
                "COALESCE(p.library_perm, 0) AS library_perm, " +
                "COALESCE(p.course_perm, 0) AS course_perm, " +
                "COALESCE(p.shop_perm, 0) AS shop_perm, " +
                "COALESCE(p.bank_perm, 0) AS bank_perm " +
                "FROM tbl_user u " +
                "LEFT JOIN tbl_admin_permission p ON u.UID = p.uid " +
                "WHERE u.UID = ?";

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    AdminPermission perm = new AdminPermission();
                    perm.setUid(rs.getString("UID"));
                    perm.setName(rs.getString("name"));
                    perm.setAcademicPerm(rs.getInt("academic_perm") == 1);
                    perm.setLibraryPerm(rs.getInt("library_perm") == 1);
                    perm.setCoursePerm(rs.getInt("course_perm") == 1);
                    perm.setShopPerm(rs.getInt("shop_perm") == 1);
                    perm.setBankPerm(rs.getInt("bank_perm") == 1);
                    return perm;
                }
            }
        }
        return new AdminPermission(uid, "", false, false, false, false, false);
    }

    /**
     * 批量更新管理员权限
     */
    public boolean updatePermissions(List<AdminPermission> permissions) throws SQLException {
        if (permissions == null || permissions.isEmpty()) {
            return true;
        }
        ensureTable();
        String sql = "INSERT INTO tbl_admin_permission (uid, academic_perm, library_perm, course_perm, shop_perm, bank_perm) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "academic_perm = VALUES(academic_perm), " +
                "library_perm = VALUES(library_perm), " +
                "course_perm = VALUES(course_perm), " +
                "shop_perm = VALUES(shop_perm), " +
                "bank_perm = VALUES(bank_perm)";

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (AdminPermission perm : permissions) {
                if (perm == null || perm.getUid() == null) continue;
                stmt.setString(1, perm.getUid());
                stmt.setInt(2, perm.isAcademicPerm() ? 1 : 0);
                stmt.setInt(3, perm.isLibraryPerm() ? 1 : 0);
                stmt.setInt(4, perm.isCoursePerm() ? 1 : 0);
                stmt.setInt(5, perm.isShopPerm() ? 1 : 0);
                stmt.setInt(6, perm.isBankPerm() ? 1 : 0);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
            return true;
        }
    }
}
