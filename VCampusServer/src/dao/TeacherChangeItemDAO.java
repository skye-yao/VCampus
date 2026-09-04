package dao;
import entity.TeacherChangeItem;
import java.sql.*;
import java.util.*;
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class TeacherChangeItemDAO {
    public void insertAll(Connection c,long id,List<TeacherChangeItem>xs)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO tblTeacherChangeItem(requestId,fieldName,oldValue,newValue)VALUES(?,?,?,?)")) {
            for(TeacherChangeItem x:xs) {
                p.setLong(1,id);
                p.setString(2,x.getFieldName());
                p.setString(3,x.getOldValue());
                p.setString(4,x.getNewValue());
                p.addBatch();
            }
            p.executeBatch();
        }
    }
    public List<TeacherChangeItem> findByRequestId(Connection c,long id)throws SQLException {
        List<TeacherChangeItem>o=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblTeacherChangeItem WHERE requestId=? ORDER BY itemId")) {
            p.setLong(1,id);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    TeacherChangeItem x=new TeacherChangeItem();
                    x.setItemId(r.getLong("itemId"));
                    x.setRequestId(id);
                    x.setFieldName(r.getString("fieldName"));
                    x.setOldValue(r.getString("oldValue"));
                    x.setNewValue(r.getString("newValue"));
                    o.add(x);
                }
            }
        }
        return o;
    }
}

