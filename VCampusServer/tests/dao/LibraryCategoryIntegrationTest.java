package dao;

import entity.Book;
import service.LibraryServerService;
import util.DBUtil;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import static dao.LibraryCirculationIntegrationTest.*;

/** 分类迁移、编目和副本一致性；所有 SQL 仅访问本连接的临时表。 */
public class LibraryCategoryIntegrationTest {
    public static void main(String[] args) throws Exception {
        Book defaults = new Book();
        check("其他".equals(defaults.getCategory()), "new books have a category");
        defaults.setCategory("  艺术  ");
        check("艺术".equals(defaults.getCategory()), "trim category");
        Field category = Book.class.getDeclaredField("category");
        category.setAccessible(true);
        category.set(defaults, null); // 旧版本序列化数据没有该字段。
        check("其他".equals(defaults.getCategory()), "missing serialized category has a default");
        Book invalid = new Book();
        invalid.setCategory("unsupported");
        denied(() -> new LibraryServerService().addBook(invalid));
        denied(() -> new LibraryServerService().updateBook(invalid));

        try (Connection c = DBUtil.getConnection()) {
            tables(c);
            sql(c,"INSERT INTO tblBook(id,isbn,name,author,publisher,status,price) VALUES" +
                    "(1,'978-7-302-12345-6','数据库系统概论','王珊','清华大学出版社',0,50)," +
                    "(2,'custom','用户录入书目','作者','出版社',0,30)");
            // 既有多册馆藏也要一次性补齐分类。
            sql(c,"INSERT INTO tblBook(isbn,name,author,publisher,status,price,titleId,copyNumber,copiesInitialized) " +
                    "VALUES('978-7-302-12345-6','数据库系统概论','王珊','清华大学出版社',1,50,1,2,TRUE)");
            LibrarySchema.initializeCategories(c);
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE category='计算机' AND categoryInitialized=TRUE","2");
            equal(c,"SELECT category FROM tblBook WHERE id=2","其他");
            sql(c,"DELETE FROM tblBook WHERE titleId=1");
            c.setAutoCommit(false);
            LibrarySchema.initializeCopies(c,1);
            LibrarySchema.initializeCopies(c,2);
            c.commit();
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE category='计算机'","10");
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE category='其他'","10");

            Connection shared=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close"))return null;
                try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            BookDAO books = new BookDAO(() -> shared);
            Book computer = books.findBooks("数据库").get(0);
            check(computer.getTotalCopies()==10 && "计算机".equals(computer.getCategory()),"catalog aggregation retains category");
            check("计算机".equals(books.findByIsbn(computer.getIsbn()).getCategory()),"ISBN detail retains category");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(computer); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                check("计算机".equals(((Book)input.readObject()).getCategory()),"category survives client/server serialization");
            }
            computer.setCategory("其他");
            check(books.updateCatalog(computer),"admin can explicitly select other");
            LibrarySchema.initializeCategories(c);
            LibrarySchema.initializeCategories(c);
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE isbn='978-7-302-12345-6' AND category='其他'","10");
            computer.setCategory("文学");
            check(books.updateCatalog(computer),"admin changes title category");
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE isbn='978-7-302-12345-6' AND category='文学'","10");
            Book added = new Book(0,"arts","绘画入门","作者","出版社",0);
            added.setCategory("艺术");
            check(books.insert(added),"admin adds category");
            LibrarySchema.initializeCategories(c);
            equal(c,"SELECT COUNT(*) FROM tblBook WHERE isbn='arts' AND category='艺术' AND categoryInitialized=TRUE","10");
            check("艺术".equals(books.findById(added.getId()).getCategory()),"individual copy retains category");
            equal(c,"SELECT COUNT(*) FROM tblBook","30");
            System.out.println("PASS: category defaults, validation, migration, serialization, aggregation, copy creation, admin edits and restart preservation (temporary tables only)");
        }
    }
}
