package dao;

import entity.FinanceBill;
import entity.Reimbursement;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 学费账单与报销申请数据访问。 */
public class CampusFinanceDAO {
    public List<FinanceBill> findBills(Connection conn, String userId, boolean admin) throws SQLException {
        String sql = "SELECT * FROM tbl_finance_bill" + (admin ? "" : " WHERE user_id=?") + " ORDER BY bill_id DESC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!admin) stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<FinanceBill> result = new ArrayList<>();
                while (rs.next()) result.add(mapBill(rs));
                return result;
            }
        }
    }

    public FinanceBill findBillForUpdate(Connection conn, long billId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tbl_finance_bill WHERE bill_id=? FOR UPDATE")) {
            stmt.setLong(1, billId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? mapBill(rs) : null; }
        }
    }

    public boolean markBillPaid(Connection conn, long billId, String transactionNo) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE tbl_finance_bill SET status='PAID',payment_transaction_no=?,paid_at=NOW() " +
                "WHERE bill_id=? AND status='UNPAID'")) {
            stmt.setString(1, transactionNo); stmt.setLong(2, billId);
            return stmt.executeUpdate() == 1;
        }
    }

    public long insertReimbursement(Connection conn, Reimbursement item) throws SQLException {
        String sql = "INSERT INTO tbl_finance_reimbursement(applicant_id,title,amount,reason,status) VALUES(?,?,?,?,'APPLIED')";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, item.getApplicantId()); stmt.setString(2, item.getTitle());
            stmt.setBigDecimal(3, item.getAmount()); stmt.setString(4, item.getReason()); stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) { keys.next(); return keys.getLong(1); }
        }
    }

    public List<Reimbursement> findReimbursements(Connection conn, String userId, boolean admin) throws SQLException {
        String sql = "SELECT * FROM tbl_finance_reimbursement" +
                (admin ? "" : " WHERE applicant_id=?") + " ORDER BY reimbursement_id DESC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!admin) stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Reimbursement> result = new ArrayList<>();
                while (rs.next()) result.add(mapReimbursement(rs));
                return result;
            }
        }
    }

    public Reimbursement findReimbursementForUpdate(Connection conn, long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM tbl_finance_reimbursement WHERE reimbursement_id=? FOR UPDATE")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? mapReimbursement(rs) : null; }
        }
    }

    public boolean review(Connection conn, long id, String status, String reviewer, String comment,
                          String transactionNo) throws SQLException {
        String sql = "UPDATE tbl_finance_reimbursement SET status=?,reviewer_id=?,review_comment=?," +
                "payment_transaction_no=?,reviewed_at=NOW() WHERE reimbursement_id=? AND status='APPLIED'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status); stmt.setString(2, reviewer); stmt.setString(3, comment);
            stmt.setString(4, transactionNo); stmt.setLong(5, id);
            return stmt.executeUpdate() == 1;
        }
    }

    private FinanceBill mapBill(ResultSet rs) throws SQLException {
        FinanceBill bill = new FinanceBill();
        bill.setBillId(rs.getLong("bill_id")); bill.setUserId(rs.getString("user_id"));
        bill.setBillType(rs.getString("bill_type")); bill.setTitle(rs.getString("title"));
        bill.setAmount(rs.getBigDecimal("amount")); bill.setStatus(rs.getString("status"));
        bill.setDueDate(String.valueOf(rs.getDate("due_date")));
        Timestamp paid = rs.getTimestamp("paid_at"); bill.setPaidAt(paid == null ? null : paid.toString());
        bill.setCreatedAt(String.valueOf(rs.getTimestamp("created_at"))); return bill;
    }

    private Reimbursement mapReimbursement(ResultSet rs) throws SQLException {
        Reimbursement item = new Reimbursement();
        item.setReimbursementId(rs.getLong("reimbursement_id")); item.setApplicantId(rs.getString("applicant_id"));
        item.setTitle(rs.getString("title")); item.setAmount(rs.getBigDecimal("amount"));
        item.setReason(rs.getString("reason")); item.setStatus(rs.getString("status"));
        item.setReviewerId(rs.getString("reviewer_id")); item.setReviewComment(rs.getString("review_comment"));
        item.setPaymentTransactionNo(rs.getString("payment_transaction_no"));
        item.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
        Timestamp reviewed = rs.getTimestamp("reviewed_at"); item.setReviewedAt(reviewed == null ? null : reviewed.toString());
        return item;
    }
}
