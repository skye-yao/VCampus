package dao;

import entity.BankTransaction;
import enums.BankTransactionType;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 校园银行流水数据访问。 */
public class BankTransactionDAO {
    public void insert(Connection conn, BankTransaction tx, String requestId) throws SQLException {
        String sql = "INSERT INTO tbl_bank_transaction " +
                "(transaction_no,account_id,counterparty_user_id,transaction_type,amount,balance_after," +
                "related_order_id,request_id,remark) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tx.getTransactionNo());
            stmt.setLong(2, tx.getAccountId());
            stmt.setString(3, tx.getCounterpartyUserId());
            stmt.setString(4, tx.getTransactionType().name());
            stmt.setBigDecimal(5, tx.getAmount());
            stmt.setBigDecimal(6, tx.getBalanceAfter());
            if (tx.getRelatedOrderId() == null) stmt.setNull(7, Types.BIGINT); else stmt.setLong(7, tx.getRelatedOrderId());
            stmt.setString(8, requestId);
            stmt.setString(9, tx.getRemark());
            stmt.executeUpdate();
        }
    }

    public List<BankTransaction> findByAccountId(Connection conn, long accountId, int limit) throws SQLException {
        String sql = "SELECT * FROM tbl_bank_transaction WHERE account_id=? ORDER BY transaction_id DESC LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId); stmt.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = stmt.executeQuery()) {
                List<BankTransaction> result = new ArrayList<>();
                while (rs.next()) result.add(map(rs));
                return result;
            }
        }
    }

    public BankTransaction findByRequestId(Connection conn, String requestId) throws SQLException {
        if (requestId == null) return null;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM tbl_bank_transaction WHERE request_id=? LIMIT 1")) {
            stmt.setString(1, requestId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public BankTransaction findByTransactionNo(Connection conn, String transactionNo) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM tbl_bank_transaction WHERE transaction_no=?")) {
            stmt.setString(1, transactionNo);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    private BankTransaction map(ResultSet rs) throws SQLException {
        BankTransaction tx = new BankTransaction();
        tx.setTransactionId(rs.getLong("transaction_id"));
        tx.setTransactionNo(rs.getString("transaction_no"));
        tx.setAccountId(rs.getLong("account_id"));
        tx.setCounterpartyUserId(rs.getString("counterparty_user_id"));
        tx.setTransactionType(BankTransactionType.fromCode(rs.getString("transaction_type")));
        tx.setAmount(rs.getBigDecimal("amount"));
        tx.setBalanceAfter(rs.getBigDecimal("balance_after"));
        long orderId = rs.getLong("related_order_id");
        tx.setRelatedOrderId(rs.wasNull() ? null : orderId);
        tx.setRemark(rs.getString("remark"));
        tx.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
        return tx;
    }
}
