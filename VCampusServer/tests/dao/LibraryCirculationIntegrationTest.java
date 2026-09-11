package dao;

import entity.Book;
import exception.BusinessException;
import service.LibraryFeePolicy;
import util.DBUtil;
import util.PasswordUtil;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;

/** 只使用当前连接的 TEMPORARY TABLE，不创建数据库、不改动任何持久业务表。 */
public class LibraryCirculationIntegrationTest {
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    static void sql(Connection c,String s) throws SQLException {try(Statement st=c.createStatement()){st.execute(s);}}
    static String value(Connection c,String s) throws SQLException {try(Statement st=c.createStatement();ResultSet r=st.executeQuery(s)){r.next();return r.getString(1);}}
    static void equal(Connection c,String sql,String expected) throws SQLException {check(expected.equals(value(c,sql)),sql+" expected "+expected);}
    static BigDecimal money(String amount){return new BigDecimal(amount);}
    interface Attempt {void run() throws Exception;}
    static void denied(Attempt action) throws Exception {try{action.run();throw new AssertionError("expected rejection");}catch(BusinessException | IllegalArgumentException expected){}}
    static void tables(Connection c) throws SQLException {
        sql(c,"CREATE TEMPORARY TABLE tblBook(id INT PRIMARY KEY AUTO_INCREMENT,isbn VARCHAR(20) UNIQUE,name VARCHAR(100),author VARCHAR(100),publisher VARCHAR(100),status INT,price DECIMAL(10,2)) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tblBorrowRecord(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,borrowTime DATETIME,returnTime DATETIME,dueTime DATETIME,status INT,bookPrice DECIMAL(10,2),feeStopTime DATETIME,settledTime DATETIME) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tblLossRecord(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,lossTime DATETIME,status INT) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tblReservation(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,reserveTime DATETIME,status INT) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tblFineRecord(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),amount DECIMAL(10,2),reason VARCHAR(200),status INT,borrowId INT UNIQUE,overdueAmount DECIMAL(10,2) DEFAULT 0,lossAmount DECIMAL(10,2) DEFAULT 0,paidAmount DECIMAL(10,2) DEFAULT 0,refundedAmount DECIMAL(10,2) DEFAULT 0,transactionNo VARCHAR(64)) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tbl_user(uid VARCHAR(32) PRIMARY KEY,password VARCHAR(128),salt VARCHAR(64),role INT,name VARCHAR(50),balance DECIMAL(12,2)) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tbl_bank_account(account_id BIGINT PRIMARY KEY,user_id VARCHAR(32) UNIQUE,balance DECIMAL(12,2),status VARCHAR(20),payment_password_hash VARCHAR(128),payment_password_salt VARCHAR(64),version INT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        sql(c,"CREATE TEMPORARY TABLE tbl_bank_transaction(transaction_id BIGINT PRIMARY KEY AUTO_INCREMENT,transaction_no VARCHAR(50) UNIQUE,account_id BIGINT,counterparty_user_id VARCHAR(32),transaction_type VARCHAR(30),amount DECIMAL(12,2),balance_after DECIMAL(12,2),related_order_id BIGINT,request_id VARCHAR(64) UNIQUE,remark VARCHAR(200),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        for(String table:new String[]{"tblBook","tblBorrowRecord","tblLossRecord","tblReservation","tblFineRecord","tbl_user","tbl_bank_account","tbl_bank_transaction"}) {
            try(Statement st=c.createStatement();ResultSet r=st.executeQuery("SHOW CREATE TABLE "+table)) {r.next();check(r.getString(2).contains("TEMPORARY"),"temporary shadow required");}
        }
    }
    static void seed(Connection c) throws SQLException {
        for(String t:new String[]{"tbl_bank_transaction","tbl_bank_account","tbl_user","tblFineRecord","tblBorrowRecord","tblLossRecord","tblReservation","tblBook"})sql(c,"DELETE FROM "+t);
        LibraryCirculationDAO.update(c,"INSERT INTO tbl_user VALUES('reader',?,?,2,'Reader',1000),('admin',?,?,0,'Admin',1000)",
                PasswordUtil.hashPassword("reader-pass","salt"),"salt",PasswordUtil.hashPassword("admin-pass","salt"),"salt");
        LibraryCirculationDAO.update(c,"INSERT INTO tbl_bank_account(account_id,user_id,balance,status,payment_password_hash,payment_password_salt) VALUES(1,'reader',1000,'ACTIVE',?,'salt'),(2,'admin',1000,'ACTIVE',?,'salt')",
                PasswordUtil.hashPassword("123456","salt"),PasswordUtil.hashPassword("654321","salt"));
        sql(c,"INSERT INTO tblBook VALUES(1,'test','Book','Author','Publisher',2,50)");
        sql(c,"INSERT INTO tblReservation VALUES(1,'reader',1,NOW(),0)");c.commit();
    }
    public static void main(String[] args) throws Exception {
        LocalDateTime due=LocalDateTime.of(2026,1,15,12,0);
        check(LibraryFeePolicy.overdue(due,due).compareTo(money("0"))==0,"exact due date");
        check(LibraryFeePolicy.overdue(due,due.plusNanos(1)).compareTo(money("0.5"))==0,"fractional day");
        check(LibraryFeePolicy.overdue(due,due.plusDays(1)).compareTo(money("0.5"))==0,"one full day");
        check(LibraryFeePolicy.overdue(due,due.plusDays(1).plusSeconds(1)).compareTo(money("1"))==0,"second partial day");
        try(Connection c=DBUtil.getConnection()) {
            tables(c);c.setAutoCommit(false);
            Connection shared=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close"))return null;
                try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            LibraryCirculationDAO dao=new LibraryCirculationDAO(()->shared);BookDAO books=new BookDAO(()->shared);
            FineRecordDAO fines=new FineRecordDAO(()->shared);
            LibraryAdminDAO adminRecords=new LibraryAdminDAO(()->shared);
            seed(c);
            LocalDateTime expiry=LocalDateTime.of(2026,1,20,12,0);
            LibraryCirculationDAO.update(c,"UPDATE tblReservation SET reserveTime=?",Timestamp.valueOf(expiry.minusHours(12)));
            LibraryCirculationDAO.expireBookReservations(c,1,expiry.minusSeconds(1));
            equal(c,"SELECT status FROM tblReservation","0");
            LibraryCirculationDAO.expireBookReservations(c,1,expiry);c.commit();
            equal(c,"SELECT status FROM tblReservation","3");equal(c,"SELECT status FROM tblBook","0");
            check(adminRecords.findRecords("checkout").isEmpty(),"expired reservation leaves checkout queue");
            check(books.reserveAvailableBook("reader",1),"expired book can be reserved again");
            denied(()->dao.lend(1));equal(c,"SELECT status FROM tblBook","2");
            seed(c);sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR)");c.commit();
            denied(()->dao.lend(1));
            equal(c,"SELECT status FROM tblReservation","3");equal(c,"SELECT status FROM tblBook","0");
            equal(c,"SELECT COUNT(*) FROM tblBorrowRecord","0");
            seed(c);sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR)");c.commit();
            dao.expireReservations();dao.expireReservations();
            equal(c,"SELECT status FROM tblReservation","3");equal(c,"SELECT status FROM tblBook","0");
            seed(c);dao.lend(1);sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR)");c.commit();
            dao.expireReservations();equal(c,"SELECT status FROM tblBook","1");equal(c,"SELECT status FROM tblReservation","2");
            seed(c);sql(c,"UPDATE tblBook SET status=3");sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR)");c.commit();
            dao.expireReservations();equal(c,"SELECT status FROM tblBook","3");
            for(int from=0;from<4;from++)for(int to=0;to<4;to++) {
                seed(c);
                if(from==0){sql(c,"UPDATE tblBook SET status=0");sql(c,"UPDATE tblReservation SET status=1");c.commit();}
                if(from==1||from==3)dao.lend(1);
                if(from==3)check(books.changeLoss("reader",1,true),"seed lost state");
                Book edit=new Book(1,"test","Edited","Author","Publisher",to);edit.setPrice(money("50"));
                if(from==to || (from==3&&to==0))check(books.update(edit),"valid admin transition");
                else {denied(()->books.update(edit));equal(c,"SELECT status FROM tblBook",String.valueOf(from));equal(c,"SELECT name FROM tblBook","Book");}
            }
            seed(c);dao.lend(1);
            check(adminRecords.findRecords("checkout").isEmpty(),"fulfilled reservation leaves admin queue");
            check(adminRecords.findRecords("returns").size()==1,"new loan appears in return queue");
            int loan=Integer.parseInt(value(c,"SELECT id FROM tblBorrowRecord"));
            equal(c,"SELECT TIMESTAMPDIFF(SECOND,borrowTime,dueTime) FROM tblBorrowRecord","1209600");
            equal(c,"SELECT status FROM tblReservation","2");equal(c,"SELECT status FROM tblBook","1");
            denied(()->dao.lend(1));equal(c,"SELECT COUNT(*) FROM tblBorrowRecord","1");
            dao.returnLoan(loan);equal(c,"SELECT status FROM tblBook","0");equal(c,"SELECT COUNT(*) FROM tblFineRecord","0");
            denied(()->dao.returnLoan(loan));

            seed(c);dao.lend(1);int overdueLoan=Integer.parseInt(value(c,"SELECT id FROM tblBorrowRecord"));
            sql(c,"UPDATE tblBorrowRecord SET dueTime=DATE_SUB(NOW(),INTERVAL 25 HOUR)");c.commit();
            dao.returnLoan(overdueLoan);
            int fine=Integer.parseInt(value(c,"SELECT id FROM tblFineRecord"));
            check(fines.findByUserId("reader").get(0).isPayable(),"returned overdue bill payable");
            equal(c,"SELECT amount FROM tblFineRecord","1.00");equal(c,"SELECT balance FROM tbl_bank_account WHERE user_id='reader'","1000.00");
            denied(()->dao.pay("reader",fine,"bad",money("1")));
            denied(()->dao.pay("other",fine,"123456",money("1")));
            denied(()->dao.pay("reader",fine,"123456",money("99")));
            dao.pay("reader",fine,"123456",money("1"));dao.pay("reader",fine,"123456",money("1"));
            equal(c,"SELECT balance FROM tbl_bank_account WHERE user_id='reader'","999.00");
            equal(c,"SELECT balance FROM tbl_user WHERE uid='reader'","999.00");
            equal(c,"SELECT balance FROM tbl_bank_account WHERE user_id='admin'","1001.00");
            equal(c,"SELECT COUNT(*) FROM tbl_bank_transaction","2");
            check(new BankTransactionDAO().findByAccountId(c,1,20).get(0).getTransactionType()==enums.BankTransactionType.LIBRARY_PAYMENT,"bank history includes fee");
            check(!fines.findById(fine).isPayable(),"paid bill cannot be paid again");
            check(adminRecords.findRecords("fine").get(0).get("实付").equals("1.00"),"admin sees actual payment");

            seed(c);dao.lend(1);int lostLoan=Integer.parseInt(value(c,"SELECT id FROM tblBorrowRecord"));
            sql(c,"UPDATE tblBorrowRecord SET dueTime=DATE_SUB(NOW(),INTERVAL 25 HOUR)");c.commit();
            check(books.changeLoss("reader",1,true),"report loss");
            int lossFine=Integer.parseInt(value(c,"SELECT id FROM tblFineRecord"));
            equal(c,"SELECT amount FROM tblFineRecord","51.00");
            check(fines.findById(lossFine).getLossAmount().compareTo(money("50"))==0,"loss amount visible");
            check(adminRecords.findRecords("returns").get(0).get("状态").equals("挂失中 / 逾期"),"lost and overdue both visible");
            sql(c,"UPDATE tblBook SET price=90");c.commit();
            LibraryCirculationDAO.syncBook(c,1,LocalDateTime.now().plusDays(20));c.commit();
            equal(c,"SELECT amount FROM tblFineRecord","51.00");equal(c,"SELECT status FROM tblBorrowRecord","2");
            dao.pay("reader",lossFine,"123456",money("51"));equal(c,"SELECT status FROM tblBorrowRecord","3");
            equal(c,"SELECT status FROM tblBook","3");equal(c,"SELECT COUNT(*) FROM tblLossRecord WHERE status=0","1");
            check(!books.changeLoss("reader",1,false),"compensated loan cannot reopen");
            Book recovered=new Book(1,"test","Book","Author","Publisher",0);recovered.setPrice(money("90"));
            check(books.update(recovered),"recover compensated book");equal(c,"SELECT status FROM tblBorrowRecord","1");
            equal(c,"SELECT paidAmount FROM tblFineRecord","51.00");equal(c,"SELECT COUNT(*) FROM tblLossRecord WHERE status=0","0");
            String refundId=UUID.randomUUID().toString();
            denied(()->dao.refund("admin",lossFine,"reader","admin","wrong",money("50"),refundId));
            denied(()->dao.refund("admin",lossFine,"other","admin","admin-pass",money("50"),refundId));
            denied(()->dao.refund("admin",lossFine,"reader","admin","admin-pass",money("52"),refundId));
            dao.refund("admin",lossFine,"reader","admin","admin-pass",money("50"),refundId);
            dao.refund("admin",lossFine,"reader","admin","admin-pass",money("50"),refundId);
            equal(c,"SELECT refundedAmount FROM tblFineRecord","50.00");
            equal(c,"SELECT balance FROM tbl_bank_account WHERE user_id='reader'","999.00");
            equal(c,"SELECT balance FROM tbl_user WHERE uid='reader'","999.00");
            equal(c,"SELECT COUNT(*) FROM tbl_bank_transaction","4");
            check(fines.findById(lossFine).getRefundedAmount().compareTo(money("50"))==0,"refund total visible");
            denied(()->dao.refund("admin",lossFine,"reader","admin","admin-pass",money("2"),UUID.randomUUID().toString()));

            seed(c);dao.lend(1);sql(c,"UPDATE tblBorrowRecord SET dueTime=DATE_SUB(NOW(),INTERVAL 25 HOUR)");c.commit();
            check(books.changeLoss("reader",1,true),"new loss");
            check(books.update(recovered),"recover before payment");equal(c,"SELECT amount FROM tblFineRecord","1.00");equal(c,"SELECT lossAmount FROM tblFineRecord","0.00");

            seed(c);dao.lend(1);
            sql(c,"SET @test_due=DATE_SUB(NOW(),INTERVAL 10 DAY)");
            sql(c,"UPDATE tblBorrowRecord SET dueTime=@test_due");
            sql(c,"UPDATE tblBook SET status=3");
            sql(c,"INSERT INTO tblLossRecord(userid,bookid,lossTime,status) VALUES('reader',1,DATE_ADD(@test_due,INTERVAL 1 DAY),0)");c.commit();
            check(books.update(recovered),"recover imported loss before first refresh");
            equal(c,"SELECT amount FROM tblFineRecord","0.50");

            seed(c);dao.lend(1);check(books.changeLoss("reader",1,true),"loss before due date");
            LibraryCirculationDAO.syncBook(c,1,LocalDateTime.now().plusDays(30));c.commit();
            equal(c,"SELECT status FROM tblBorrowRecord","2");equal(c,"SELECT overdueAmount FROM tblFineRecord","0.00");
            equal(c,"SELECT amount FROM tblFineRecord","50.00");

            seed(c);dao.lend(1);sql(c,"UPDATE tblBorrowRecord SET dueTime=DATE_SUB(NOW(),INTERVAL 25 HOUR)");c.commit();
            dao.returnLoan(Integer.parseInt(value(c,"SELECT id FROM tblBorrowRecord")));
            int unpaid=Integer.parseInt(value(c,"SELECT id FROM tblFineRecord"));
            sql(c,"ALTER TABLE tbl_bank_transaction MODIFY remark VARCHAR(1)");
            try{dao.pay("reader",unpaid,"123456",money("1"));throw new AssertionError("expected ledger insert failure");}catch(SQLException expected){}
            equal(c,"SELECT balance FROM tbl_bank_account WHERE user_id='reader'","1000.00");equal(c,"SELECT status FROM tblFineRecord","0");
            equal(c,"SELECT COUNT(*) FROM tbl_bank_transaction","0");
            System.out.println("PASS: 14-day lending, rounded fees, returns before payment, frozen loss, price snapshot, compensation, recovery, authenticated bounded refunds, bank history, idempotency and rollback (temporary tables only)");
        }
    }
}
