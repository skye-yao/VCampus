package dao;

import entity.FinanceBill;
import entity.Reimbursement;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 学费账单与报销申请数据访问。 */
public class CampusFinanceDAO {
    public List<FinanceBill> findBills(Connection conn, String userId, boolean admin) throws SQLException {
        return findBills(conn, userId, admin, null, null, null);
    }

    public List<FinanceBill> findBills(Connection conn, String userId, boolean admin,
                                       String keyword, String billType, String status) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT b.*,u.name AS user_name FROM tbl_finance_bill b JOIN tbl_user u ON u.UID=b.user_id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!admin) { sql.append(" AND b.user_id=?"); params.add(userId); }
        if (admin && keyword != null && !keyword.isBlank()) {
            sql.append(" AND (b.user_id LIKE ? OR u.name LIKE ? OR b.title LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            params.add(value); params.add(value); params.add(value);
        }
        if (billType != null && !billType.isBlank()) { sql.append(" AND b.bill_type=?"); params.add(billType); }
        if (status != null && !status.isBlank()) { sql.append(" AND b.status=?"); params.add(status); }
        sql.append(" ORDER BY b.bill_id DESC");
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                List<FinanceBill> result = new ArrayList<>();
                while (rs.next()) result.add(mapBill(rs));
                return result;
            }
        }
    }

    /** 统计有账单用户中已全部缴清的人数，并同时返回账单数量。 */
    public Map<String, Object> billStatistics(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) total_people," +
                "COALESCE(SUM(unpaid_count=0),0) fully_paid_people," +
                "COALESCE(SUM(total_bills),0) total_bills," +
                "COALESCE(SUM(paid_bills),0) paid_bills FROM (" +
                "SELECT user_id,SUM(status='UNPAID') unpaid_count,COUNT(*) total_bills," +
                "SUM(status='PAID') paid_bills FROM tbl_finance_bill " +
                "WHERE status<>'CANCELLED' GROUP BY user_id) x";
        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            rs.next();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalPeople", rs.getLong("total_people"));
            result.put("fullyPaidPeople", rs.getLong("fully_paid_people"));
            result.put("totalBills", rs.getLong("total_bills"));
            result.put("paidBills", rs.getLong("paid_bills"));
            return result;
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
        try { bill.setUserName(rs.getString("user_name")); } catch (SQLException ignored) { }
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
