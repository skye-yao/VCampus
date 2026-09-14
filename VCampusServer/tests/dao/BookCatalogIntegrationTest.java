package dao;

import entity.Book;
import java.math.BigDecimal;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import static dao.LibraryCirculationIntegrationTest.*;

/** 使用连接内临时表验证书目升级和多册流转，不修改业务数据库持久表。 */
public class BookCatalogIntegrationTest {
    public static void main(String[] args) throws Exception {
        try(Connection c=util.DBUtil.getConnection()) {
            tables(c);
            sql(c,"ALTER TABLE tblBook ADD catalogId INT NULL, DROP INDEX isbn");
            sql(c,"CREATE TEMPORARY TABLE tblBookCatalog(id INT PRIMARY KEY,isbn VARCHAR(20) UNIQUE,name VARCHAR(100),author VARCHAR(100),publisher VARCHAR(100),price DECIMAL(10,2)) ENGINE=InnoDB");
            sql(c,"CREATE TEMPORARY TABLE tblBookReview(id INT PRIMARY KEY,bookid INT) ENGINE=InnoDB");
            for(String table:List.of("tblBookCatalog","tblBookReview")) {
                try(Statement stmt=c.createStatement();ResultSet rows=stmt.executeQuery("SHOW CREATE TABLE "+table)) {
                    rows.next();check(rows.getString(2).contains("TEMPORARY"),"temporary shadow required");
                }
            }
            Connection shared=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close"))return null;
                try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            BookCatalogDAO catalogs=new BookCatalogDAO(()->shared);
            BookDAO copies=new BookDAO(()->shared);
            LibraryCirculationDAO circulation=new LibraryCirculationDAO(()->shared);
            sql(c,"INSERT INTO tbl_user(uid,role,name) VALUES('reader',2,'Reader'),('reader2',1,'Teacher'),('admin',0,'Admin')");
            sql(c,"INSERT INTO tblBook(id,isbn,name,author,publisher,status,price) VALUES(41,'legacy','Old title','Author','Press',2,50)");
            sql(c,"INSERT INTO tblReservation(id,userid,bookid,reserveTime,status) VALUES(1,'reader',41,NOW(),0)");
            LibrarySchema.migrateCatalogs(c);LibrarySchema.migrateCatalogs(c);
            equal(c,"SELECT COUNT(*) FROM tblBookCatalog","1");equal(c,"SELECT catalogId FROM tblBook WHERE id=41","41");
            equal(c,"SELECT bookid FROM tblReservation WHERE id=1","41");
            check(catalogs.findById(41).getAvailableCopies()==0,"legacy reserved copy not available");
            catalogs.addCopies(41,9);
            Book title=catalogs.findById(41);
            check(title.getTotalCopies()==10&&title.getAvailableCopies()==9,"ten copies in one title");
            check(catalogs.search("Old title").size()==1,"search returns one row per title");
            check(new HashSet<>(title.getCopyIds()).size()==10,"independent copy ids");
            circulation.lend(1);
            check(catalogs.findById(41).getAvailableCopies()==9,"checkout does not deduct again");
            check(catalogs.reserve("reader2",41),"second user can reserve same title");
            int reservation=Integer.parseInt(value(c,"SELECT MAX(id) FROM tblReservation"));
            int second=Integer.parseInt(value(c,"SELECT bookid FROM tblReservation WHERE id="+reservation));
            check(second!=41,"different copy allocated");circulation.lend(reservation);
            check(catalogs.findById(41).getAvailableCopies()==8,"two simultaneous loans");
            check(copies.changeLoss("reader",41,true),"one copy lost");
            check(catalogs.findById(41).getAvailableCopies()==8,"loss does not deduct twice");
            check(circulation.recoverLostNotice(41),"recover only lost copy");
            equal(c,"SELECT status FROM tblBook WHERE id="+second,"1");
            equal(c,"SELECT COUNT(*) FROM tblBorrowRecord WHERE bookid="+second+" AND returnTime IS NULL","1");
            check(catalogs.findById(41).getAvailableCopies()==9,"recovery releases exactly one copy");
            check(catalogs.reserve("reader",41),"reserve recovered copy");
            int cancelled=Integer.parseInt(value(c,"SELECT MAX(id) FROM tblReservation"));
            check(copies.cancelReservation("reader",cancelled),"cancel selected copy reservation");
            check(catalogs.findById(41).getAvailableCopies()==9,"cancel returns one available copy");
            check(!copies.cancelReservation("reader",cancelled),"cancel cannot release twice");
            check(catalogs.reserve("reader",41),"reserve before expiry");
            int expiring=Integer.parseInt(value(c,"SELECT MAX(id) FROM tblReservation"));
            sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR) WHERE id="+expiring);c.commit();
            circulation.expireReservations();circulation.expireReservations();
            check(catalogs.findById(41).getAvailableCopies()==9,"expiry releases once");
            for(int i=0;i<9;i++)check(catalogs.reserve("reader",41),"allocate remaining copy "+i);
            check(!catalogs.reserve("reader2",41),"no allocation beyond stock");
            check(catalogs.findById(41).getAvailableCopies()==0,"zero available when exhausted");
            equal(c,"SELECT COUNT(DISTINCT bookid) FROM tblReservation WHERE status=0","9");
            title.setName("Updated title");title.setPrice(new BigDecimal("75"));title.setStatus(0);
            catalogs.update(title);
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE name='Updated title'","10");
            equal(c,"SELECT bookPrice FROM tblBorrowRecord WHERE bookid="+second,"50.00");
            check(catalogs.findById(41).getAvailableCopies()==0,"metadata edit cannot reset copy states");
            Book fresh=new Book(0,"fresh","New title","Author","Press",0);fresh.setPrice(new BigDecimal("30"));fresh.setInitialCopies(3);
            check(catalogs.insert(fresh),"create title with three copies");
            check(catalogs.findById(fresh.getId()).getTotalCopies()==3,"initial count persisted");
            check(catalogs.search("Author").size()==2,"search grouped across titles");
            int total=Integer.parseInt(value(c,"SELECT COUNT(*) FROM tblBook"));
            try{catalogs.insert(fresh);throw new AssertionError("duplicate isbn accepted");}catch(SQLException expected){}
            equal(c,"SELECT COUNT(*) FROM tblBook",String.valueOf(total));
            denied(()->catalogs.addCopies(fresh.getId(),0));
            denied(()->catalogs.delete(41));
            check(catalogs.delete(fresh.getId()),"unused title may be removed");
            check(catalogs.findById(fresh.getId())==null,"title and copies removed together");
            System.out.println("PASS: legacy migration, grouped counts, copy allocation, checkout, isolated loss/recovery, cancellation/expiry, stock exhaustion, metadata, rollback and deletion");
        }
    }
}
