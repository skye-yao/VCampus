package com.vcampus.server.dao;
import java.sql.*;import java.util.*;import com.vcampus.common.entity.StudentChangeItem;
public class StudentChangeItemDAO {
    public void insertAll(Connection c,long requestId,List<StudentChangeItem> items)throws SQLException{try(PreparedStatement ps=c.prepareStatement("INSERT INTO tblStudentChangeItem(requestId,fieldName,oldValue,newValue) VALUES(?,?,?,?)")){for(StudentChangeItem i:items){ps.setLong(1,requestId);ps.setString(2,i.getFieldName());ps.setString(3,i.getOldValue());ps.setString(4,i.getNewValue());ps.addBatch();}ps.executeBatch();}}
    public List<StudentChangeItem> findByRequestId(Connection c,long id)throws SQLException{List<StudentChangeItem> out=new ArrayList<>();try(PreparedStatement ps=c.prepareStatement("SELECT * FROM tblStudentChangeItem WHERE requestId=? ORDER BY itemId")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){while(rs.next()){StudentChangeItem i=new StudentChangeItem();i.setItemId(rs.getLong("itemId"));i.setRequestId(id);i.setFieldName(rs.getString("fieldName"));i.setOldValue(rs.getString("oldValue"));i.setNewValue(rs.getString("newValue"));out.add(i);}}}return out;}
}
