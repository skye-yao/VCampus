package dao;

import entity.BankAccount;
import enums.BankAccountStatus;
import java.math.BigDecimal;
import java.sql.*;

/** 校园银行账户数据访问。 */
public class BankAccountDAO {
    public BankAccount findByUserId(Connection conn, String userId, boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM tbl_bank_account WHERE user_id=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public String[] findPassword(Connection conn, long accountId) throws SQLException {
        String sql = "SELECT payment_password_hash,payment_password_salt FROM tbl_bank_account WHERE account_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
    }

    public boolean setPassword(Connection conn, long accountId, String hash, String salt) throws SQLException {
        String sql = "UPDATE tbl_bank_account SET payment_password_hash=?,payment_password_salt=?," +
                "failed_attempts=0,status='ACTIVE',version=version+1 WHERE account_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hash); stmt.setString(2, salt); stmt.setLong(3, accountId);
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean changeBalance(Connection conn, long accountId, BigDecimal delta) throws SQLException {
        String sql = "UPDATE tbl_bank_account SET balance=balance+?,version=version+1 " +
                "WHERE account_id=? AND status='ACTIVE' AND balance+?>=0";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, delta); stmt.setLong(2, accountId); stmt.setBigDecimal(3, delta);
            boolean ok = stmt.executeUpdate() == 1;
            if (ok) {
                // 实时同步更新 tbl_user.balance
                String syncSql = "UPDATE tbl_user u JOIN tbl_bank_account b ON u.UID = b.user_id " +
                        "SET u.balance = b.balance WHERE b.account_id = ?";
                try (PreparedStatement syncStmt = conn.prepareStatement(syncSql)) {
                    syncStmt.setLong(1, accountId);
                    syncStmt.executeUpdate();
                }
            }
            return ok;
        }
    }

    private BankAccount map(ResultSet rs) throws SQLException {
        BankAccount account = new BankAccount();
        account.setAccountId(rs.getLong("account_id"));
        account.setUserId(rs.getString("user_id"));
        account.setBalance(rs.getBigDecimal("balance"));
        account.setStatus(BankAccountStatus.fromCode(rs.getString("status")));
        account.setPaymentPasswordSet(rs.getString("payment_password_hash") != null);
        account.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
        account.setUpdatedAt(String.valueOf(rs.getTimestamp("updated_at")));
        return account;
    }
}
