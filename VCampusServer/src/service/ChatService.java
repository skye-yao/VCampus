package service;

import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import util.DBUtil;

/** Authenticated identity is supplied by the handler, never by request data. */
public final class ChatService {
    private static volatile boolean schemaReady;
    public static synchronized void ensureSchema() throws Exception {
        if (schemaReady) return;
        try (var in = ChatService.class.getResourceAsStream("/resources/migrations/v301_chat.sql")) {
            if (in == null) throw new SQLException("缺少聊天建表脚本");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
            try (Connection c = DBUtil.getConnection(); Statement s = c.createStatement()) {
                for (String part : sql.split(";")) if (!part.isBlank()) s.execute(part);
            }
        }
        schemaReady = true;
    }

    public Map<String,Object> execute(Connection c, String me, String action, Map<String,Object> data) throws SQLException {
        me = user(c, me);
        Map<String,Object> out = new HashMap<>();
        switch (action) {
            case "SUMMARY" -> {
                out.put("unread", rows(c,"SELECT COUNT(*) AS n FROM tbl_chat_message WHERE recipient=? AND read_at IS NULL",me).get(0).get("n"));
                out.put("pending", rows(c,"SELECT COUNT(*) AS n FROM tbl_chat_friend WHERE (user_low=? OR user_high=?) AND requester<>? AND status='PENDING'",me,me,me).get(0).get("n"));
            }
            case "SEARCH" -> {
                String q = text(data,"query",50);
                out.put("users",rows(c,"SELECT UID AS uid,name,role FROM tbl_user WHERE status='ACTIVE' AND UID<>? AND (UID=? OR LOCATE(?,name)>0) ORDER BY UID LIMIT 30",me,q,q));
            }
            case "CONTACTS" -> {
                out.put("friends",rows(c,"SELECT u.UID AS uid,u.name,u.role, (SELECT COUNT(*) FROM tbl_chat_message m WHERE m.sender=u.UID COLLATE utf8mb4_unicode_ci AND m.recipient=? AND m.read_at IS NULL) AS unread FROM tbl_chat_friend f JOIN tbl_user u ON u.UID COLLATE utf8mb4_unicode_ci=CASE WHEN f.user_low=? THEN f.user_high ELSE f.user_low END WHERE (f.user_low=? OR f.user_high=?) AND f.status='ACCEPTED' ORDER BY unread DESC,f.updated_at DESC",me,me,me,me));
                out.put("requests",rows(c,"SELECT u.UID AS uid,u.name,f.requester,f.status FROM tbl_chat_friend f JOIN tbl_user u ON u.UID COLLATE utf8mb4_unicode_ci=CASE WHEN f.user_low=? THEN f.user_high ELSE f.user_low END WHERE (f.user_low=? OR f.user_high=?) AND f.status='PENDING' ORDER BY f.updated_at DESC",me,me,me));
            }
            default -> {
                String peer = user(c,text(data,"peer",32));
                if (me.equals(peer)) throw new IllegalArgumentException("不能添加自己或给自己发消息");
                String low = me.compareTo(peer)<0?me:peer, high = me.compareTo(peer)<0?peer:me;
                boolean old = c.getAutoCommit(); c.setAutoCommit(false);
                try {
                    if ("REQUEST".equals(action)) update(c,"INSERT IGNORE INTO tbl_chat_friend(user_low,user_high,requester) VALUES(?,?,?)",low,high,me);
                    var links = rows(c,"SELECT requester,status FROM tbl_chat_friend WHERE user_low=? AND user_high=? FOR UPDATE",low,high);
                    if (links.isEmpty()) throw new IllegalArgumentException("请先申请好友，等待对方同意");
                    var link = links.get(0);
                    String state = link.get("status").toString();
                    switch (action) {
                        case "REQUEST" -> {
                            if ("ACCEPTED".equals(state)) throw new IllegalArgumentException("你们已经是好友");
                            if ("PENDING".equals(state) && !me.equals(link.get("requester"))) throw new IllegalArgumentException("对方已申请，请到好友申请中处理");
                            if ("REJECTED".equals(state)) update(c,"UPDATE tbl_chat_friend SET status='PENDING',requester=? WHERE user_low=? AND user_high=?",me,low,high);
                        }
                        case "ACCEPT", "REJECT" -> {
                            if (!"PENDING".equals(state) || me.equals(link.get("requester"))) throw new IllegalArgumentException("只能处理对方发来的待审核申请");
                            update(c,"UPDATE tbl_chat_friend SET status=? WHERE user_low=? AND user_high=?", "ACCEPT".equals(action)?"ACCEPTED":"REJECTED",low,high);
                        }
                        case "SEND", "HISTORY", "READ" -> {
                            if (!"ACCEPTED".equals(state)) throw new IllegalArgumentException("对方同意好友申请后才能聊天");
                            if ("SEND".equals(action)) {
                                String content = text(data,"content",2000), key = text(data,"clientId",36);
                                UUID.fromString(key);
                                var duplicate = rows(c,"SELECT id,recipient,content FROM tbl_chat_message WHERE sender=? AND client_id=?",me,key);
                                if (!duplicate.isEmpty()) {
                                    if (!peer.equals(duplicate.get(0).get("recipient")) || !content.equals(duplicate.get(0).get("content"))) throw new IllegalArgumentException("消息重试标识冲突");
                                } else update(c,"INSERT INTO tbl_chat_message(sender,recipient,client_id,content) VALUES(?,?,?,?)",me,peer,key,content);
                                update(c,"UPDATE tbl_chat_friend SET updated_at=CURRENT_TIMESTAMP WHERE user_low=? AND user_high=?",low,high);
                            } else if ("HISTORY".equals(action)) {
                                long before = number(data,"before",Long.MAX_VALUE);
                                boolean incremental=data.containsKey("after");
                                var messages = rows(c,"SELECT id,sender,recipient,content,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS time FROM tbl_chat_message WHERE ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) AND id"+(incremental?">? ORDER BY id ASC":"<? ORDER BY id DESC")+" LIMIT 50",me,peer,peer,me,incremental?number(data,"after",0):before);
                                if(!incremental) Collections.reverse(messages); out.put("messages",messages);
                            } else {
                                update(c,"UPDATE tbl_chat_message SET read_at=CURRENT_TIMESTAMP WHERE sender=? AND recipient=? AND id<=? AND read_at IS NULL",peer,me,number(data,"through",0));
                            }
                        }
                        default -> throw new IllegalArgumentException("不支持的聊天操作");
                    }
                    c.commit();
                } catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(old); }
            }
        }
        return out;
    }
    private static String user(Connection c,String id) throws SQLException {
        var users=rows(c,"SELECT UID AS uid FROM tbl_user WHERE UID=? AND status='ACTIVE'",id);
        if(users.isEmpty()) throw new IllegalArgumentException("用户不存在或账号不可用");
        return users.get(0).get("uid").toString();
    }
    public static String text(Map<String,Object> data,String key,int limit) {
        Object raw=data.get(key);
        if(!(raw instanceof String s) || s.isBlank() || s.length()>limit) throw new IllegalArgumentException(key+" 不能为空，且长度不能超过 "+limit);
        return s.strip();
    }
    private static long number(Map<String,Object> data,String key,long fallback) {
        Object v=data.get(key); if(v==null) return fallback;
        try { long n=new java.math.BigDecimal(v.toString()).longValueExact(); if(n<0) throw new ArithmeticException(); return n; }
        catch(Exception e) {throw new IllegalArgumentException("无效的消息分页参数");}
    }
    private static void update(Connection c,String sql,Object...args) throws SQLException {
        try(var s=c.prepareStatement(sql)){ bind(s,args); s.executeUpdate(); }
    }
    private static List<Map<String,Object>> rows(Connection c,String sql,Object...args) throws SQLException {
        try(var s=c.prepareStatement(sql)){bind(s,args); try(var r=s.executeQuery()){
            var list=new ArrayList<Map<String,Object>>(); var meta=r.getMetaData();
            while(r.next()){var row=new LinkedHashMap<String,Object>(); for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),r.getObject(i)); list.add(row);} return list;
        }}
    }
    private static void bind(PreparedStatement s,Object[] args) throws SQLException {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
}
