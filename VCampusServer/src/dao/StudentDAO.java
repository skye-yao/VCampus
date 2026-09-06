package dao;
import entity.*;
import util.DBUtil;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve", "SqlSourceToSinkFlow"})
public class StudentDAO {
    private static final List<String> COLUMNS = List.of(
            "studentId", "UID", "name", "gender", "politicalStatus", "nationality",
            "idType", "idNumber", "idIssueDate", "birthDate", "nativePlace", "householdType",
            "birthPlace", "sourcePlace", "registeredResidence", "leagueMember", "leagueJoinDate",
            "partyMember", "partyJoinDate", "healthStatus", "studentCategory", "registered",
            "inSchool", "studentStatus", "campus", "grade", "college", "major", "className",
            "educationLevel", "trainingMode", "schoolingLength", "counselorName", "counselorPhone",
            "candidateCategory", "admissionDate", "admissionMethod", "graduationSchool",
            "middleSchoolClass", "middleSchoolTeacher", "telephone", "mobile", "email", "qq",
            "wechat", "campusAddress", "emergencyContact", "emergencyPhone"
    );
    public Student findByUID(String v)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent WHERE UID=?")) {
            p.setString(1,v);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?map(r):null;
            }
        }
    }
    public Student findByStudentId(String v)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent WHERE studentId=?")) {
            p.setString(1,v);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?map(r):null;
            }
        }
    }
    public Student lockByUID(Connection c,String v)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent WHERE UID=? FOR UPDATE")) {
            p.setString(1,v);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?map(r):null;
            }
        }
    }
    public Student lockByStudentId(Connection c,String v)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent WHERE studentId=? FOR UPDATE")) {
            p.setString(1,v);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?map(r):null;
            }
        }
    }
    public List<Student> findAll()throws SQLException {
        List<Student>o=new ArrayList<>();
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent ORDER BY studentId");ResultSet r=p.executeQuery()) {
            while(r.next())o.add(map(r));
        }
        return o;
    }
    public boolean update(Student s)throws SQLException {
        List<String>f=COLUMNS.subList(1,COLUMNS.size());
        String set=String.join(",",f.stream().map(x->x+"=?").toList());
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("UPDATE tblStudent SET "+set+" WHERE studentId=?")) {
            bind(p,s,f);
            p.setString(f.size()+1,s.getStudentId());
            return p.executeUpdate()==1;
        }
    }
    public boolean updateApprovedFields(Connection c,String id,List<StudentChangeItem>items)throws SQLException {
        Set<String>a=new HashSet<>(COLUMNS);
        a.remove("studentId");
        a.remove("UID");
        if(items==null||items.isEmpty())return false;
        Set<String>seen=new HashSet<>();
        for(StudentChangeItem i:items)if(i.getFieldName()==null||!a.contains(i.getFieldName())||!seen.add(i.getFieldName()))throw new SQLException("修改字段无效");
        Student current;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudent WHERE studentId=? FOR UPDATE")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next())return false;
                current=map(r);
            }
        }
        for(StudentChangeItem i:items) {
            Object now=fieldValue(current,i.getFieldName());
            if(!normalized(now).equals(normalized(i.getOldValue())))throw new IllegalStateException("字段“"+i.getFieldName()+"”已被其他操作修改，请重新提交申请");
        }
        String set=String.join(",",items.stream().map(i->i.getFieldName()+"=?").toList());
        try(PreparedStatement p=c.prepareStatement("UPDATE tblStudent SET "+set+" WHERE studentId=?")) {
            int n=1;
            for(StudentChangeItem i:items)p.setObject(n++,convert(i.getFieldName(),i.getNewValue()));
            p.setString(n,id);
            return p.executeUpdate()==1;
        }
    }
    public String fieldValueAsString(Student student,String name)throws SQLException {
        return normalized(fieldValue(student,name));
    }
    private static Object fieldValue(Student s,String name)throws SQLException {
        try {
            Field f=Student.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(s);
        }
        catch(ReflectiveOperationException e) {
            throw new SQLException("字段不存在: "+name,e);
        }
    }
    private static String normalized(Object v) {
        if(v==null)return"";
        if(v instanceof Boolean b)return b.toString();
        return String.valueOf(v).trim();
    }
    private static Object convert(String name,String value)throws SQLException {
        try {
            Field f=Student.class.getDeclaredField(name);
            Class<?> t=f.getType();
            String v=value==null?"":value.trim();
            if(t==String.class)return v;
            if(t==boolean.class||t==Boolean.class) {
                if(Set.of("true","1","是","在籍","在校").contains(v))return true;
                if(Set.of("false","0","否","不在籍","不在校").contains(v))return false;
                throw new IllegalArgumentException("应填写是或否");
            }
            if(t==int.class||t==Integer.class)return Integer.parseInt(v);
            if(t==java.sql.Date.class)return v.isEmpty()?null:java.sql.Date.valueOf(v);
            return v;
        }
        catch(Exception e) {
            throw new SQLException("字段“"+name+"”格式错误: "+e.getMessage(),e);
        }
    }
    private static void bind(PreparedStatement p,Student s,List<String>fs)throws SQLException {
        try {
            int n=1;
            for(String x:fs) {
                Field f=Student.class.getDeclaredField(x);
                f.setAccessible(true);
                p.setObject(n++,f.get(s));
            }
        }
        catch(ReflectiveOperationException e) {
            throw new SQLException(e);
        }
    }
    private static Student map(ResultSet r)throws SQLException {
        Student s=new Student();
        try {
            for(String x:COLUMNS) {
                Field f=Student.class.getDeclaredField(x);
                f.setAccessible(true);
                if(f.getType()==boolean.class)f.setBoolean(s,r.getBoolean(x));
                else if(f.getType()==int.class)f.setInt(s,r.getInt(x));
                else if(f.getType()==java.sql.Date.class)f.set(s,r.getDate(x));
                else f.set(s,r.getString(x));
            }
            return s;
        }
        catch(ReflectiveOperationException e) {
            throw new SQLException(e);
        }
    }
}
