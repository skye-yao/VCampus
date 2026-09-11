package dao;

import entity.BankTransaction;
import exception.BusinessException;
import service.BankService;
import service.LibraryFeePolicy;
import util.DBUtil;
import util.PasswordUtil;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** 图书行 -> 借阅行 -> 罚款行 -> 银行账户，所有写操作使用相同加锁顺序。 */
public class LibraryCirculationDAO {
    private final BookDAO.ConnectionFactory connections;
    public LibraryCirculationDAO() { this(DBUtil::getConnection); }
    LibraryCirculationDAO(BookDAO.ConnectionFactory connections) { this.connections = connections; }
    private interface Work<T> { T run(Connection conn) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try { T result = work.run(conn); conn.commit(); return result; }
            catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    static int update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) stmt.setObject(i+1,args[i]);
            return stmt.executeUpdate();
        }
    }
    private static int bookId(Connection conn, String table, int id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT bookid FROM " + table + " WHERE id=?")) {
            stmt.setInt(1,id);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) throw new BusinessException("记录不存在，请刷新");
                return rows.getInt(1);
            }
        }
    }
    private static void lockBook(Connection conn, int id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
            stmt.setInt(1,id);
            try (ResultSet rows = stmt.executeQuery()) { if (!rows.next()) throw new BusinessException("图书不存在"); }
        }
    }
    public void lend(int reservationId) throws SQLException {
        transaction(conn -> {
            int id = bookId(conn,"tblReservation",reservationId);
            lockBook(conn,id);
            String user;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT userid,status FROM tblReservation WHERE id=? FOR UPDATE")) {
                stmt.setInt(1,reservationId);
                try (ResultSet rows = stmt.executeQuery()) {
                    if (!rows.next() || rows.getInt("status")!=0) throw new BusinessException("预约已取消或已办理借书，请刷新");
                    user = rows.getString("userid");
                }
            }
            BigDecimal price;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT price,status FROM tblBook WHERE id=? FOR UPDATE")) {
                stmt.setInt(1,id);
                try (ResultSet rows = stmt.executeQuery()) {
                    rows.next(); price=rows.getBigDecimal("price");
                    if (rows.getInt("status")!=2) throw new BusinessException("该书当前不是预约状态");
                }
            }
            if (price==null || price.signum()<=0) throw new BusinessException("请先在图书信息中填写有效书价");
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tblBorrowRecord WHERE bookid=? AND status IN(0,2) AND returnTime IS NULL FOR UPDATE")) {
                stmt.setInt(1,id);
                try (ResultSet rows=stmt.executeQuery()) { if(rows.next()) throw new BusinessException("该书仍有未结束的借阅"); }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tblLossRecord WHERE bookid=? AND status=0 FOR UPDATE")) {
                stmt.setInt(1,id);
                try (ResultSet rows=stmt.executeQuery()) { if(rows.next()) throw new BusinessException("该书仍在挂失中"); }
            }
            LocalDateTime now = LocalDateTime.now();
            update(conn,"INSERT INTO tblBorrowRecord(userid,bookid,borrowTime,dueTime,status,bookPrice) VALUES(?,?,?,?,0,?)",
                    user,id,Timestamp.valueOf(now),Timestamp.valueOf(now.plusDays(14)),price);
            update(conn,"UPDATE tblReservation SET status=2 WHERE id=?",reservationId);
            update(conn,"UPDATE tblReservation SET status=1 WHERE bookid=? AND status=0",id);
            update(conn,"UPDATE tblBook SET status=1 WHERE id=?",id);
            return null;
        });
    }
    public void returnLoan(int borrowId) throws SQLException {
        transaction(conn -> {
            int id=bookId(conn,"tblBorrowRecord",borrowId);
            lockBook(conn,id);
            try (PreparedStatement stmt = conn.prepareStatement("SELECT status,returnTime FROM tblBorrowRecord WHERE id=? FOR UPDATE")) {
                stmt.setInt(1,borrowId);
                try (ResultSet rows=stmt.executeQuery()) {
                    rows.next();
                    if (rows.getTimestamp("returnTime")!=null || !Set.of(0,2).contains(rows.getInt("status")))
                        throw new BusinessException("该借阅已结束，请刷新；已赔偿图书找回请使用找回入库");
                }
            }
            recoverBook(conn,id,LocalDateTime.now());
            return null;
        });
    }
    /** 正常还书及遗失找回共用；已缴账单保留原实付，后续由管理员退款。 */
    static void recoverBook(Connection conn,int bookId,LocalDateTime now) throws SQLException {
        List<Integer> loans=new ArrayList<>();
        try (PreparedStatement stmt=conn.prepareStatement("SELECT id FROM tblBorrowRecord WHERE bookid=? AND status IN(0,2,3) AND returnTime IS NULL FOR UPDATE")) {
            stmt.setInt(1,bookId);
            try(ResultSet rows=stmt.executeQuery()) { while(rows.next()) loans.add(rows.getInt(1)); }
        }
        // 先读取仍有效的挂失时间，再解除挂失；兼容升级后导入的旧挂失记录。
        for(int loan:loans) syncLoan(conn,loan,now);
        update(conn,"UPDATE tblBorrowRecord SET status=1,returnTime=? WHERE bookid=? AND status IN(0,2,3) AND returnTime IS NULL",Timestamp.valueOf(now),bookId);
        update(conn,"UPDATE tblLossRecord SET status=1 WHERE bookid=? AND status=0",bookId);
        update(conn,"UPDATE tblReservation SET status=1 WHERE bookid=? AND status=0",bookId);
        update(conn,"UPDATE tblBook SET status=0 WHERE id=?",bookId);
        for(int loan:loans) syncLoan(conn,loan,now);
    }
    /** 挂失也会显示逾期，但费用的终点固定为 feeStopTime。 */
    static void syncBook(Connection conn,int bookId,LocalDateTime now) throws SQLException {
        List<Integer> loans=new ArrayList<>();
        try(PreparedStatement stmt=conn.prepareStatement("SELECT id FROM tblBorrowRecord WHERE bookid=? AND status IN(0,2) AND returnTime IS NULL FOR UPDATE")) {
            stmt.setInt(1,bookId);
            try(ResultSet rows=stmt.executeQuery()) { while(rows.next()) loans.add(rows.getInt(1)); }
        }
        for(int loan:loans) syncLoan(conn,loan,now);
    }
    private static void syncLoan(Connection conn,int loanId,LocalDateTime now) throws SQLException {
        try(PreparedStatement stmt=conn.prepareStatement("SELECT r.*,b.name,COALESCE(r.bookPrice,b.price) AS price," +
                "EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.bookid=r.bookid AND l.userid=r.userid AND l.status=0) AS lost " +
                "FROM tblBorrowRecord r JOIN tblBook b ON b.id=r.bookid WHERE r.id=? FOR UPDATE")) {
            stmt.setInt(1,loanId);
            try(ResultSet row=stmt.executeQuery()) {
                if(!row.next()) return;
                LocalDateTime due=row.getTimestamp("dueTime").toLocalDateTime();
                Timestamp returned=row.getTimestamp("returnTime"), stop=row.getTimestamp("feeStopTime");
                boolean lost=row.getBoolean("lost") && returned==null;
                // 升级或导入的挂失记录也应从原挂失时刻冻结，而不是从首次查询时冻结。
                if(lost && stop==null) {
                    try(PreparedStatement lossQuery=conn.prepareStatement("SELECT MIN(lossTime) FROM tblLossRecord WHERE bookid=? AND userid=? AND status=0")) {
                        lossQuery.setInt(1,row.getInt("bookid"));lossQuery.setString(2,row.getString("userid"));
                        try(ResultSet lossRow=lossQuery.executeQuery()) { if(lossRow.next()) stop=lossRow.getTimestamp(1); }
                    }
                    if(stop!=null) update(conn,"UPDATE tblBorrowRecord SET feeStopTime=? WHERE id=?",stop,loanId);
                }
                if(returned==null && row.getInt("status")!=3) update(conn,"UPDATE tblBorrowRecord SET status=? WHERE id=?",now.isAfter(due)?2:0,loanId);
                LocalDateTime end=returned==null?now:returned.toLocalDateTime();
                if(stop!=null && stop.toLocalDateTime().isBefore(end)) end=stop.toLocalDateTime();
                BigDecimal overdue=LibraryFeePolicy.overdue(due,end);
                BigDecimal price=row.getBigDecimal("price");
                if(row.getBigDecimal("bookPrice")==null && price!=null)
                    update(conn,"UPDATE tblBorrowRecord SET bookPrice=? WHERE id=?",price,loanId);
                BigDecimal loss=lost && price!=null?price:BigDecimal.ZERO;
                BigDecimal amount=overdue.add(loss);
                String reason="《"+row.getString("name")+"》逾期费 "+overdue+" 元"+(lost?"；遗失赔偿 "+(price==null?"待管理员补录书价":price+" 元"):"");
                Integer fine=null;
                try(PreparedStatement query=conn.prepareStatement("SELECT id,status FROM tblFineRecord WHERE borrowId=? FOR UPDATE")) {
                    query.setInt(1,loanId);
                    try(ResultSet existing=query.executeQuery()) {
                        if(existing.next()) { if(existing.getInt("status")==1) return; fine=existing.getInt("id"); }
                    }
                }
                if(fine==null && (amount.signum()>0 || lost)) {
                    update(conn,"INSERT INTO tblFineRecord(userid,borrowId,amount,reason,status,overdueAmount,lossAmount) VALUES(?,?,?,?,0,?,?)",
                            row.getString("userid"),loanId,amount,reason,overdue,loss);
                } else if(fine!=null) {
                    update(conn,"UPDATE tblFineRecord SET amount=?,reason=?,overdueAmount=?,lossAmount=? WHERE id=?",amount,reason,overdue,loss,fine);
                }
            }
        }
    }
    public void refresh(String userId) throws SQLException {
        List<Integer> books=new ArrayList<>();
        try(Connection conn=connections.open(); PreparedStatement stmt=conn.prepareStatement(
                "SELECT DISTINCT bookid FROM tblBorrowRecord WHERE status IN(0,2) AND returnTime IS NULL"+(userId==null?"":" AND userid=?"))) {
            if(userId!=null) stmt.setString(1,userId);
            try(ResultSet rows=stmt.executeQuery()) { while(rows.next()) books.add(rows.getInt(1)); }
        }
        for(int id:books) transaction(conn->{lockBook(conn,id);syncBook(conn,id,LocalDateTime.now());return null;});
    }
    public void pay(String userId,int fineId,String password,BigDecimal expectedAmount) throws SQLException {
        transaction(conn->{
            Integer loan=null;
            try(PreparedStatement stmt=conn.prepareStatement("SELECT borrowId FROM tblFineRecord WHERE id=? AND userid=?")) {
                stmt.setInt(1,fineId);stmt.setString(2,userId);
                try(ResultSet rows=stmt.executeQuery()) {
                    if(!rows.next()) throw new BusinessException("账单不存在或不属于当前用户");
                    int value=rows.getInt(1); if(!rows.wasNull()) loan=value;
                }
            }
            boolean lost=false;
            if(loan!=null) {
                int id=bookId(conn,"tblBorrowRecord",loan); lockBook(conn,id);
                syncLoan(conn,loan,LocalDateTime.now());
                try(PreparedStatement stmt=conn.prepareStatement("SELECT r.returnTime,r.status,COALESCE(r.bookPrice,b.price) price,"+
                        "EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.bookid=r.bookid AND l.userid=r.userid AND l.status=0) lost "+
                        "FROM tblBorrowRecord r JOIN tblBook b ON b.id=r.bookid WHERE r.id=? FOR UPDATE")) {
                    stmt.setInt(1,loan);
                    try(ResultSet rows=stmt.executeQuery()) {
                        rows.next(); lost=rows.getBoolean("lost") && rows.getTimestamp("returnTime")==null;
                        if(rows.getInt("status")!=3 && rows.getTimestamp("returnTime")==null && !lost)
                            throw new BusinessException("借阅尚未结束，请归还图书后结算；挂失可选择赔偿结清");
                        if(lost && (rows.getBigDecimal("price")==null || rows.getBigDecimal("price").signum()<=0))
                            throw new BusinessException("管理员尚未录入有效书价，暂不能赔偿结清");
                    }
                }
            }
            try(PreparedStatement stmt=conn.prepareStatement("SELECT * FROM tblFineRecord WHERE id=? AND userid=? FOR UPDATE")) {
                stmt.setInt(1,fineId);stmt.setString(2,userId);
                try(ResultSet rows=stmt.executeQuery()) {
                    if(!rows.next()) throw new BusinessException("账单不存在");
                    if(rows.getInt("status")==1) return null;
                    BigDecimal amount=rows.getBigDecimal("amount");
                    if(expectedAmount==null || amount.compareTo(expectedAmount)!=0) throw new BusinessException("费用已变化，请刷新后确认新金额");
                    if(amount.signum()<=0) throw new BusinessException("此记录无需缴费");
                    String tx=new BankService().libraryTransfer(conn,userId,amount,password,"LIB-PAY-"+fineId,false,fineId);
                    update(conn,"UPDATE tblFineRecord SET status=1,paidAmount=?,transactionNo=? WHERE id=?",amount,tx,fineId);
                    if(lost && loan!=null) update(conn,"UPDATE tblBorrowRecord SET status=3,settledTime=CURRENT_TIMESTAMP WHERE id=?",loan);
                }
            }
            return null;
        });
    }
    public void refund(String sessionAdmin,int fineId,String target,String admin,String password,BigDecimal amount,String requestId) throws SQLException {
        if(!Objects.equals(sessionAdmin,admin)) throw new BusinessException("请输入当前登录管理员的用户名");
        if(amount==null || amount.signum()<=0 || amount.scale()>2) throw new BusinessException("退款金额须大于0且最多两位小数");
        if(requestId==null || !requestId.matches("[a-fA-F0-9-]{36}")) throw new BusinessException("退款请求编号无效");
        transaction(conn->{
            try(PreparedStatement stmt=conn.prepareStatement("SELECT password,salt,role FROM tbl_user WHERE uid=?")) {
                stmt.setString(1,admin);
                try(ResultSet row=stmt.executeQuery()) {
                    if(!row.next() || row.getInt("role")!=0 || password==null || !PasswordUtil.verifyPassword(password,row.getString("salt"),row.getString("password")))
                        throw new BusinessException("管理员用户名或登录密码错误");
                }
            }
            try(PreparedStatement stmt=conn.prepareStatement("SELECT * FROM tblFineRecord WHERE id=? FOR UPDATE")) {
                stmt.setInt(1,fineId);
                try(ResultSet row=stmt.executeQuery()) {
                    if(!row.next() || !Objects.equals(target,row.getString("userid"))) throw new BusinessException("退款用户必须与所选缴费记录一致");
                    if(row.getInt("status")!=1 || row.getString("transactionNo")==null) throw new BusinessException("只有通过校园银行实付的账单可以退款");
                    String key="LIB-REF-"+fineId+"-"+requestId;
                    BankTransaction prior=new BankTransactionDAO().findByRequestIdForUpdate(conn,key);
                    if(prior!=null) {
                        if(prior.getAmount().compareTo(amount)!=0) throw new BusinessException("相同请求编号的退款金额不一致");
                        return null;
                    }
                    if(amount.compareTo(row.getBigDecimal("paidAmount").subtract(row.getBigDecimal("refundedAmount")))>0)
                        throw new BusinessException("退款不能超过此账单尚未退还的实付金额");
                    String refundTx=new BankService().libraryTransfer(conn,target,amount,null,key,true,fineId);
                    update(conn,"UPDATE tbl_bank_transaction SET remark=CONCAT(remark,?) WHERE transaction_no=?","；操作管理员 "+admin,refundTx);
                    update(conn,"UPDATE tblFineRecord SET refundedAmount=refundedAmount+? WHERE id=?",amount,fineId);
                }
            }
            return null;
        });
    }
}
