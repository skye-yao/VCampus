import java.sql.*;
import java.nio.file.*;

/** 仅校正下一编号，不重排已有主键。 */
public class ResetLibraryBookSequence {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Provide a new audit file path");
        try(Connection c=util.DBUtil.getConnection(); Statement s=c.createStatement()) {
            s.execute("SET SESSION information_schema_stats_expiry=0");
            long next, previous;
            try(ResultSet r=s.executeQuery("SELECT COALESCE(MAX(id),0)+1 FROM tblBook")) { r.next(); next=r.getLong(1); }
            try(ResultSet r=s.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblBook'")) {
                r.next(); previous=r.getLong(1);
            }
            Files.writeString(Path.of(args[0]),"-- Previous next ID: "+previous+"; requested next ID: "+next+"\n"+
                "-- To restore the former sequence (without changing rows):\nALTER TABLE tblBook AUTO_INCREMENT = "+previous+";\n",StandardOpenOption.CREATE_NEW);
            s.executeUpdate("ALTER TABLE tblBook AUTO_INCREMENT = "+next);
            try(ResultSet r=s.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblBook'")) {
                r.next(); System.out.println("Next book ID="+r.getLong(1)+"; previous="+previous);
            }
        }
    }
}
