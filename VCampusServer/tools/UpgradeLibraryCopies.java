import dao.LibrarySchema;
import util.DBUtil;
import java.sql.*;

/** Apply the same idempotent library upgrade used at server startup. */
public class UpgradeLibraryCopies {
    public static void main(String[] args) throws Exception {
        LibrarySchema.ensure();
        try (Connection c=DBUtil.getConnection(); Statement s=c.createStatement();
             ResultSet rows=s.executeQuery("SELECT COUNT(*) AS titles,SUM(copies) AS copies," +
                     "SUM(CASE WHEN copies<>10 THEN 1 ELSE 0 END) AS unexpected FROM " +
                     "(SELECT COALESCE(titleId,id),COUNT(*) AS copies FROM tblBook GROUP BY COALESCE(titleId,id)) counts")) {
            rows.next();
            System.out.println("Titles="+rows.getInt("titles")+", copies="+rows.getInt("copies")+", titlesWithoutTenCopies="+rows.getInt("unexpected"));
            if (rows.getInt("unexpected")!=0) throw new IllegalStateException("Some titles do not have ten copies");
        }
        java.util.List<entity.Book> catalog=new dao.BookDAO().findBooks("");
        int available=0;
        for (entity.Book book : catalog) {
            if (book.getTotalCopies()!=10 || book.getAvailableCopies()<0 || book.getAvailableCopies()>10)
                throw new IllegalStateException("Invalid catalog inventory");
            available+=book.getAvailableCopies();
        }
        System.out.println("Catalog rows="+catalog.size()+", available copies="+available);
        try (Connection c=DBUtil.getConnection(); Statement s=c.createStatement()) {
            try (ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM " +
                    "(SELECT COALESCE(titleId,id) FROM tblBook GROUP BY COALESCE(titleId,id) " +
                    "HAVING COUNT(DISTINCT category)<>1 OR MIN(categoryInitialized)=FALSE) invalid")) {
                rows.next();
                if (rows.getInt(1)!=0) throw new IllegalStateException("Copies have inconsistent or uninitialized categories");
            }
            try (ResultSet rows=s.executeQuery("SELECT category,COUNT(DISTINCT COALESCE(titleId,id)) AS titles,COUNT(*) AS copies " +
                    "FROM tblBook GROUP BY category ORDER BY category")) {
                while (rows.next()) {
                    String category=rows.getString("category");
                    if (!entity.Book.CATEGORIES.contains(category)) throw new IllegalStateException("Unknown category: "+category);
                    System.out.println("Category="+category+", titles="+rows.getInt("titles")+", copies="+rows.getInt("copies"));
                }
            }
        }
    }
}
