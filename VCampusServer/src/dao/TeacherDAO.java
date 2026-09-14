package dao;
import entity.Teacher; import util.DBUtil; import java.lang.reflect.*; import java.sql.*; import java.util.*;
@SuppressWarnings({"SqlNoDataSourceInspection","SqlResolve"})
public class TeacherDAO {
 public static final List<String> COLUMNS=List.of("teacherId","UID","name","politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate","nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","partyMember","partyJoinDate","healthStatus","employed","employmentStatus","campus","college","department","title","position","education","employmentStartDate","telephone","mobile","email","qq","wechat","officeAddress","emergencyContact","emergencyPhone");
 public Teacher findByUID(String v)throws SQLException{return one("SELECT * FROM tblTeacher WHERE UID=?",v);}
 public Teacher findByTeacherId(String v)throws SQLException{return one("SELECT * FROM tblTeacher WHERE teacherId=?",v);}
 private Teacher one(String sql,String v)throws SQLException{try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,v);try(ResultSet r=p.executeQuery()){return r.next()?map(r):null;}}}
 public Teacher lockByTeacherId(Connection c,String id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblTeacher WHERE teacherId=? FOR UPDATE")){p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?map(r):null;}}}
 public List<Teacher> findAll()throws SQLException{List<Teacher> o=new ArrayList<>();try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM tblTeacher ORDER BY teacherId");ResultSet r=p.executeQuery()){while(r.next())o.add(map(r));}return o;}
 public boolean insert(Teacher t)throws SQLException{String cols=String.join(",",COLUMNS),qs=String.join(",",Collections.nCopies(COLUMNS.size(),"?"));try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO tblTeacher("+cols+")VALUES("+qs+")")){bind(p,t,COLUMNS);return p.executeUpdate()==1;}}
 public String fieldValueAsString(Teacher teacher,String field)throws SQLException {
  try {Field f=Teacher.class.getDeclaredField(field);f.setAccessible(true);Object value=f.get(teacher);return value==null?"":String.valueOf(value).trim();}
  catch(ReflectiveOperationException e){throw new SQLException("字段不存在: "+field,e);}
 }
 public String canonicalValue(String field,String value)throws SQLException {
  Object converted=convert(field,value);return converted==null?"":String.valueOf(converted).trim();
 }
 public boolean updateIfUnchanged(Teacher updated,Teacher original)throws SQLException {
  try(Connection c=DBUtil.getConnection()) {
   c.setAutoCommit(false);
   try {
    Teacher current=lockByTeacherId(c,updated.getTeacherId());
    if(current==null)throw new IllegalStateException("教师档案不存在");
    for(String field:COLUMNS)if(!fieldValueAsString(current,field).equals(fieldValueAsString(original,field)))
      throw new IllegalStateException("教师信息已被其他操作修改，请刷新后重新编辑");
    List<String> fields=COLUMNS.subList(1,COLUMNS.size());
    String set=String.join(",",fields.stream().map(x->x+"=?").toList());
    try(PreparedStatement p=c.prepareStatement("UPDATE tblTeacher SET "+set+" WHERE teacherId=?")) {
     bind(p,updated,fields);p.setString(fields.size()+1,updated.getTeacherId());
     if(p.executeUpdate()!=1)throw new IllegalStateException("教师信息更新失败");
    }
    c.commit();return true;
   }catch(SQLException|RuntimeException e){c.rollback();throw e;}
  }
 }
 public boolean apply(Connection c,String id,List<entity.TeacherChangeItem> items)throws SQLException {
  Set<String> allowed=new HashSet<>(COLUMNS);allowed.remove("teacherId");allowed.remove("UID");
  Set<String> seen=new HashSet<>();
  if(items==null||items.isEmpty())throw new IllegalArgumentException("修改项不能为空");
  Teacher current=lockByTeacherId(c,id);if(current==null)return false;
  for(var item:items) {
   String field=item.getFieldName();
   if(!allowed.contains(field)||!seen.add(field))throw new IllegalArgumentException("不可修改字段: "+field);
   if(!fieldValueAsString(current,field).equals(canonicalValue(field,item.getOldValue())))
    throw new IllegalStateException("字段“"+field+"”已被其他操作修改，请重新提交申请");
  }
  String set=String.join(",",items.stream().map(i->i.getFieldName()+"=?").toList());
  try(PreparedStatement p=c.prepareStatement("UPDATE tblTeacher SET "+set+" WHERE teacherId=?")) {
   int n=1;for(var item:items)p.setObject(n++,convert(item.getFieldName(),item.getNewValue()));
   p.setString(n,id);return p.executeUpdate()==1;
  }
 }
 private static Object convert(String name,String value)throws SQLException{try{Class<?> t=Teacher.class.getDeclaredField(name).getType();String v=value==null?"":value.trim();if(t==boolean.class){if(Set.of("true","1","是","在职").contains(v))return true;if(Set.of("false","0","否","离职").contains(v))return false;throw new IllegalArgumentException("应填写是或否");}if(t==java.sql.Date.class)return v.isBlank()?null:java.sql.Date.valueOf(v);return v;}catch(Exception e){throw new SQLException("字段格式错误: "+name,e);}}
 private static void bind(PreparedStatement p,Teacher t,List<String> fs)throws SQLException{try{int n=1;for(String x:fs){Field f=Teacher.class.getDeclaredField(x);f.setAccessible(true);p.setObject(n++,f.get(t));}}catch(Exception e){throw new SQLException(e);}}
 private static Teacher map(ResultSet r)throws SQLException{Teacher t=new Teacher();try{for(String x:COLUMNS){Field f=Teacher.class.getDeclaredField(x);f.setAccessible(true);if(f.getType()==boolean.class)f.setBoolean(t,r.getBoolean(x));else if(f.getType()==java.sql.Date.class)f.set(t,r.getDate(x));else f.set(t,r.getString(x));}return t;}catch(Exception e){throw new SQLException(e);}}
}
