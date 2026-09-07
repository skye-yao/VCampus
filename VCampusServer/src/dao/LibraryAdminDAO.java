package dao;

import java.sql.*;
import java.util.*;
import util.DBUtil;

/** 管理员只读业务名单，查询类型固定，不接受客户端 SQL。 */
public class LibraryAdminDAO {
    public List<Map<String,String>> findRecords(String kind) throws SQLException {
        String sql;
        String person = "r.id AS '记录编号',r.userid AS '账号',u.name AS '姓名',";
        String book = "r.bookid AS '图书编号',b.name AS '书名',";
        String users = " LEFT JOIN tbl_user u ON u.uid=r.userid ";
        String books = " LEFT JOIN tblBook b ON b.id=r.bookid ";
        if ("borrow".equals(kind)) {
            sql="SELECT "+person+book+"r.borrowTime AS '借阅时间',r.dueTime AS '应还时间',r.returnTime AS '归还时间',"+
                "CASE WHEN r.returnTime IS NULL AND EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.userid=r.userid AND l.bookid=r.bookid AND l.status=0) THEN '挂失中' "+
                "WHEN r.status=0 THEN '借阅中' WHEN r.status=1 THEN '已归还' ELSE '逾期' END AS '状态' FROM tblBorrowRecord r"+users+books+"ORDER BY r.borrowTime DESC,r.id DESC";
        } else if ("fine".equals(kind)) {
            sql="SELECT "+person+"r.amount AS '金额',r.reason AS '原因',CASE WHEN r.status=0 THEN '未缴费' ELSE '已缴费' END AS '状态' FROM tblFineRecord r"+users+"ORDER BY r.id DESC";
        } else if ("loss".equals(kind)) {
            sql="SELECT "+person+book+"r.lossTime AS '挂失时间',CASE WHEN r.status=0 THEN '挂失中' ELSE '已解除' END AS '状态' FROM tblLossRecord r"+users+books+"ORDER BY r.lossTime DESC,r.id DESC";
        } else if ("reservation".equals(kind)) {
            sql="SELECT "+person+book+"r.reserveTime AS '预约时间',CASE r.status WHEN 0 THEN '预约中' WHEN 1 THEN '已取消' ELSE '已借阅' END AS '状态' FROM tblReservation r"+users+books+"ORDER BY r.reserveTime DESC,r.id DESC";
        } else throw new IllegalArgumentException("不支持的名单类型");
        List<Map<String,String>> result=new ArrayList<>();
        try(Connection conn=DBUtil.getConnection(); PreparedStatement stmt=conn.prepareStatement(sql); ResultSet rows=stmt.executeQuery()) {
            ResultSetMetaData meta=rows.getMetaData();
            while(rows.next()) {
                Map<String,String> row=new LinkedHashMap<>();
                for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),rows.getString(i)==null?"—":rows.getString(i));
                result.add(row);
            }
        }
        return result;
    }
}
