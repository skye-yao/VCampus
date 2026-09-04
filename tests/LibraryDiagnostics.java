import com.google.gson.Gson;
import entity.Book;
import entity.BookReview;
import protocol.Message;
import protocol.MessageType;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** 无业务写入的序列化回归检查与数据库状态审计。 */
public class LibraryDiagnostics {
    public static void main(String[] args) throws Exception {
        Gson gson = util.JsonUtil.createGson();
        BookReview review = new BookReview();
        review.setCreateTime(LocalDateTime.of(2026, 9, 4, 12, 0));
        try {
            new Gson().toJson(review);
            System.out.println("Default Gson did not reproduce time error on this JVM.");
        } catch (RuntimeException expected) {
            System.out.println("Reproduced default Gson time failure: " + expected.getClass().getSimpleName());
        }
        BookReview restored = gson.fromJson(gson.toJson(review), BookReview.class);
        if (!review.getCreateTime().equals(restored.getCreateTime())) throw new AssertionError("Time round trip failed");

        Message request = new Message(MessageType.REQUEST, "library", "addbook");
        request.putData("book", new Book(1, "test-isbn", "test", "author", "publisher", 0));
        Message decoded = gson.fromJson(gson.toJson(request), Message.class);
        java.lang.reflect.Method readBook = handler.LibraryHandler.class.getDeclaredMethod("readBook", Message.class);
        readBook.setAccessible(true);
        Book book = (Book) readBook.invoke(new handler.LibraryHandler(), decoded);
        if (!"test-isbn".equals(book.getIsbn())) throw new AssertionError("Book conversion failed");
        System.out.println("PASS: time round trip and JSON book request conversion");

        if (args.length == 0 || !"--database".equals(args[0])) return;
        java.util.List<vo.LostBookNotice> notices = new dao.LossRecordDAO().findPublicNotices();
        String publicJson = gson.toJson(notices);
        if (publicJson.contains("userId") || publicJson.contains("userid")) throw new AssertionError("Private identity in notice");
        java.util.Set<Integer> noticeIds = new java.util.HashSet<>();
        for (vo.LostBookNotice notice : notices) if (!noticeIds.add(notice.getBookId())) throw new AssertionError("Duplicate notice");
        for (Book item : new dao.BookDAO().findBooks("")) {
            if (item.getStatus() != new dao.BookDAO().findById(item.getId()).getStatus()) throw new AssertionError("Status mismatch");
        }
        System.out.println("PASS: public notices unique and identity-free; list/detail statuses agree");
        try (Connection connection = util.DBUtil.getConnection()) {
            connection.setReadOnly(true);
            try (Statement s = connection.createStatement()) { s.execute("SET SESSION information_schema_stats_expiry=0"); }
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblBook'")) {
                if (r.next()) System.out.println("Next book AUTO_INCREMENT=" + r.getLong(1));
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (ResultSet rows = statement.executeQuery(
                        "SELECT b.id,b.name,b.status," +
                        "(SELECT COUNT(*) FROM tblReservation r WHERE r.bookid=b.id AND r.status=0) AS active_reservations," +
                        "(SELECT COUNT(*) FROM tblBorrowRecord r WHERE r.bookid=b.id AND r.status IN (0,2) AND r.returnTime IS NULL) AS active_borrows " +
                        "FROM tblBook b ORDER BY b.id")) {
                    while (rows.next()) {
                        System.out.printf("book=%d name=%s status=%d reservations=%d borrows=%d%n",
                                rows.getInt(1), rows.getString(2), rows.getInt(3), rows.getInt(4), rows.getInt(5));
                    }
                }
            }
        }
    }
}
