package dao;
import entity.StudentChangeItem; import java.sql.*; import java.util.*;
public class StudentChangeItemDAO {
 public void insertAll(Connection c,long id,List<StudentChangeItem>xs)throws SQLException{try(PreparedStatement p=c.prepareStatement("INSERT INTO tblStudentChangeItem(requestId,fieldName,oldValue,newValue)VALUES(?,?,?,?)")){for(StudentChangeItem x:xs){p.setLong(1,id);p.setString(2,x.getFieldName());p.setString(3,x.getOldValue());p.setString(4,x.getNewValue());p.addBatch();}p.executeBatch();}}
 public List<StudentChangeItem> findByRequestId(Connection c,long id)throws SQLException{List<StudentChangeItem>o=new ArrayList<>();try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudentChangeItem WHERE requestId=? ORDER BY itemId")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){while(r.next()){StudentChangeItem x=new StudentChangeItem();x.setItemId(r.getLong("itemId"));x.setRequestId(id);x.setFieldName(r.getString("fieldName"));x.setOldValue(r.getString("oldValue"));x.setNewValue(r.getString("newValue"));o.add(x);}}}return o;}
}
