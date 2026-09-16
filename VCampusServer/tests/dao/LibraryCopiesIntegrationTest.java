package dao;

import entity.Book;
import util.DBUtil;
import java.lang.reflect.*;
import java.sql.*;
import static dao.LibraryCirculationIntegrationTest.*;

/** 连接级临时表验证多册库存，不修改业务数据。 */
public class LibraryCopiesIntegrationTest {
    public static void main(String[] args) throws Exception {
        try (Connection c = DBUtil.getConnection()) {
            tables(c);
            c.setAutoCommit(false);
            seed(c);
            sql(c,"DELETE FROM tblReservation");
            sql(c,"UPDATE tblBook SET status=0");
            LibrarySchema.initializeCopies(c,1);
            LibrarySchema.initializeCopies(c,1);
            c.commit();
            equal(c,"SELECT COUNT(*) FROM tblBook","10");
            Connection shared=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close"))return null;
                try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            BookDAO books = new BookDAO(()->shared);
            LibraryCirculationDAO circulation = new LibraryCirculationDAO(()->shared);
            check(books.findBooks("").size()==1,"one catalog row per title");
            inventory(books,10);
            for(int i=0;i<10;i++) {
                sql(c,"INSERT INTO tbl_user(uid,role,name) VALUES('copy-reader-"+i+"',2,'Reader')");
            }
            c.commit();
            for(int i=0;i<10;i++) {
                check(books.reserveAvailableBook("copy-reader-"+i,1),"reserve copy "+i);
                inventory(books,9-i);
            }
            check(!books.reserveAvailableBook("overflow-reader",1),"cannot exceed ten copies");
            denied(()->books.reserveAvailableBook("copy-reader-0",1));
            int reservation = Integer.parseInt(value(c,"SELECT id FROM tblReservation WHERE userid='copy-reader-0'"));
            circulation.lend(reservation);
            inventory(books,0);
            int loan = Integer.parseInt(value(c,"SELECT id FROM tblBorrowRecord WHERE userid='copy-reader-0'"));
            int copy = Integer.parseInt(value(c,"SELECT bookid FROM tblBorrowRecord WHERE id="+loan));
            check(books.changeLoss("copy-reader-0",copy,true),"loss affects just one copy");
            inventory(books,0);
            check(books.updateCatalog(books.findBooks("").get(0)),"catalog edit while a copy is lost");
            equal(c,"SELECT status FROM tblBook WHERE id="+copy,"3");
            equal(c,"SELECT COUNT(*) FROM tblLossRecord WHERE status=0","1");
            circulation.returnLoan(loan);
            inventory(books,1);
            equal(c,"SELECT COUNT(*) FROM tblReservation WHERE status=0","9");
            denied(()->circulation.returnLoan(loan));
            inventory(books,1);
            int cancel = Integer.parseInt(value(c,"SELECT id FROM tblReservation WHERE userid='copy-reader-1'"));
            check(books.cancelReservation("copy-reader-1",cancel),"cancel releases one copy");
            inventory(books,2);
            check(!books.cancelReservation("copy-reader-1",cancel),"cancel cannot release twice");
            inventory(books,2);
            sql(c,"UPDATE tblReservation SET reserveTime=DATE_SUB(NOW(),INTERVAL 13 HOUR) WHERE status=0");
            c.commit();
            circulation.expireReservations();
            inventory(books,10);
            Book edit=books.findBooks("").get(0);
            edit.setName("Updated title");
            check(books.updateCatalog(edit),"edit entire catalog");
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE name='Updated title'","10");
            Book added = new Book(0,"new-isbn","New title","Author","Publisher",0);
            added.setPrice(new java.math.BigDecimal("30"));
            added.setTotalCopies(3);
            check(books.insert(added),"new title uses requested quantity");
            LibrarySchema.initializeCopies(c,added.getId());
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE isbn='new-isbn'","3");
            Book single = new Book(0,"single-isbn","Single title","Author","Publisher",0);
            check(books.insert(single),"default is one copy");
            LibrarySchema.initializeCopies(c,single.getId());
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE isbn='single-isbn'","1");
            for (int invalid : new int[]{0,-1,1001}) {
                single.setTotalCopies(invalid);
                try { books.insert(single); throw new AssertionError("accepted invalid quantity"); }
                catch (IllegalArgumentException expected) { }
            }
            System.out.println("PASS: ten copies, idempotent initialization, allocation, exhaustion, duplicate reservation, lending, loss, return, cancellation, expiration, catalog editing and new titles");
        }
    }
    private static void inventory(BookDAO books,int available) throws Exception {
        Book title=books.findBooks("test").get(0);
        check(title.getTotalCopies()==10,"total remains ten");
        check(title.getAvailableCopies()==available,"expected available="+available+", actual="+title.getAvailableCopies());
        check(title.getStatus()==(available>0?0:1),"catalog availability");
        check(title.getCopyIds().size()==10,"all copy IDs exposed for loan names");
    }
}
