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
            case "AVATAR" -> {
                String peer=user(c,text(data,"peer",32));
                out.put("avatar",rows(c,"SELECT avatar FROM tbl_user WHERE UID=?",peer).get(0).get("avatar"));
            }
            case "SUMMARY" -> {
                long privateUnread = ((Number)rows(c,"SELECT COUNT(*) AS n FROM tbl_chat_message WHERE recipient=? AND read_at IS NULL",me).get(0).get("n")).longValue();
                long groupUnread = ((Number)rows(c,"SELECT COUNT(*) AS n FROM tbl_chat_group_message m JOIN tbl_chat_group_member gm ON gm.group_id=m.group_id WHERE gm.uid=? AND m.sender<>? AND m.id>gm.last_read_id",me,me).get(0).get("n")).longValue();
                out.put("unread", privateUnread + groupUnread);
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
            case "GROUP_LIST" -> {
                var groups = rows(c, """
                    SELECT g.group_id AS groupId, g.name, g.owner_uid AS ownerUid,
                           my_gm.role AS myRole, my_gm.last_read_id AS lastReadId
                    FROM tbl_chat_group_member my_gm
                    JOIN tbl_chat_group g ON g.group_id = my_gm.group_id
                    WHERE my_gm.uid = ?
                    ORDER BY g.updated_at DESC
                """, me);
                for (var g : groups) {
                    long gid = ((Number) g.get("groupId")).longValue();
                    long lastRead = ((Number) g.get("lastReadId")).longValue();
                    long memberCount = ((Number) rows(c, "SELECT COUNT(*) AS n FROM tbl_chat_group_member WHERE group_id=?", gid).get(0).get("n")).longValue();
                    long unread = ((Number) rows(c, "SELECT COUNT(*) AS n FROM tbl_chat_group_message WHERE group_id=? AND sender<>? AND id>?", gid, me, lastRead).get(0).get("n")).longValue();
                    g.put("memberCount", memberCount);
                    g.put("unread", unread);
                }
                groups.sort((a, b) -> Long.compare(((Number)b.get("unread")).longValue(), ((Number)a.get("unread")).longValue()));
                out.put("groups", groups);
            }
            case "GROUP_CREATE" -> {
                String groupName = text(data, "name", 100);
                List<?> rawMembers = (List<?>) data.get("members");
                Set<String> memberUids = new LinkedHashSet<>();
                if (rawMembers != null) {
                    for (Object item : rawMembers) {
                        if (item != null && !item.toString().isBlank()) {
                            String uid = item.toString().strip();
                            if (!uid.equals(me)) {
                                memberUids.add(user(c, uid));
                            }
                        }
                    }
                }
                boolean old = c.getAutoCommit(); c.setAutoCommit(false);
                try {
                    update(c, "INSERT INTO tbl_chat_group(name, owner_uid) VALUES(?, ?)", groupName, me);
                    long groupId = ((Number)rows(c, "SELECT LAST_INSERT_ID() AS id").get(0).get("id")).longValue();
                    update(c, "INSERT INTO tbl_chat_group_member(group_id, uid, role) VALUES(?, ?, 'OWNER')", groupId, me);
                    for (String memberUid : memberUids) {
                        update(c, "INSERT IGNORE INTO tbl_chat_group_member(group_id, uid, role) VALUES(?, ?, 'MEMBER')", groupId, memberUid);
                    }
                    String initialNotice = me + " 创建了群聊";
                    update(c, "INSERT INTO tbl_chat_group_message(group_id, sender, client_id, content) VALUES(?, ?, ?, ?)",
                            groupId, me, UUID.randomUUID().toString(), initialNotice);
                    c.commit();
                    out.put("groupId", groupId);
                    out.put("name", groupName);
                    out.put("ownerUid", me);
                } catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(old); }
            }
            case "GROUP_INFO" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                var groupRows = rows(c, "SELECT group_id AS groupId, name, owner_uid AS ownerUid, DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt FROM tbl_chat_group WHERE group_id=?", groupId);
                if (groupRows.isEmpty()) throw new IllegalArgumentException("群聊不存在或已解散");
                var members = rows(c, """
                    SELECT gm.uid, u.name, gm.role, u.avatar, DATE_FORMAT(gm.joined_at, '%Y-%m-%d %H:%i:%s') AS joinedAt
                    FROM tbl_chat_group_member gm
                    JOIN tbl_user u ON u.UID COLLATE utf8mb4_unicode_ci = gm.uid COLLATE utf8mb4_unicode_ci
                    WHERE gm.group_id=?
                    ORDER BY CASE WHEN gm.role='OWNER' THEN 0 ELSE 1 END, gm.joined_at ASC
                """, groupId);
                out.put("group", groupRows.get(0));
                out.put("members", members);
            }
            case "GROUP_RENAME" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupOwner(c, groupId, me);
                String newName = text(data, "name", 100);
                update(c, "UPDATE tbl_chat_group SET name=? WHERE group_id=?", newName, groupId);
                out.put("success", true);
            }
            case "GROUP_ADD_MEMBERS" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                List<?> rawMembers = (List<?>) data.get("members");
                if (rawMembers == null || rawMembers.isEmpty()) throw new IllegalArgumentException("请选择要邀请的好友");
                int added = 0;
                boolean old = c.getAutoCommit(); c.setAutoCommit(false);
                try {
                    for (Object item : rawMembers) {
                        if (item != null && !item.toString().isBlank()) {
                            String memberUid = user(c, item.toString().strip());
                            var existing = rows(c, "SELECT uid FROM tbl_chat_group_member WHERE group_id=? AND uid=?", groupId, memberUid);
                            if (existing.isEmpty()) {
                                update(c, "INSERT INTO tbl_chat_group_member(group_id, uid, role) VALUES(?, ?, 'MEMBER')", groupId, memberUid);
                                added++;
                            }
                        }
                    }
                    update(c, "UPDATE tbl_chat_group SET updated_at=CURRENT_TIMESTAMP WHERE group_id=?", groupId);
                    c.commit();
                    out.put("addedCount", added);
                } catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(old); }
            }
            case "GROUP_KICK" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupOwner(c, groupId, me);
                String target = user(c, text(data, "memberUid", 32));
                if (me.equals(target)) throw new IllegalArgumentException("群主不能将自己移出群聊");
                var groupRows = rows(c, "SELECT name FROM tbl_chat_group WHERE group_id=?", groupId);
                String groupName = groupRows.isEmpty() ? "群聊" : groupRows.get(0).get("name").toString();
                update(c, "DELETE FROM tbl_chat_group_member WHERE group_id=? AND uid=?", groupId, target);
                update(c, "UPDATE tbl_chat_group SET updated_at=CURRENT_TIMESTAMP WHERE group_id=?", groupId);
                NotificationService.notify(c, target, "CHAT", "移出群聊通知", "您已被移出群聊「" + groupName + "」", "CHAT");
                out.put("success", true);
            }
            case "GROUP_DISSOLVE" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupOwner(c, groupId, me);
                var groupRows = rows(c, "SELECT name FROM tbl_chat_group WHERE group_id=?", groupId);
                String groupName = groupRows.isEmpty() ? "群聊" : groupRows.get(0).get("name").toString();
                var members = rows(c, "SELECT uid FROM tbl_chat_group_member WHERE group_id=? AND uid<>?", groupId, me);
                boolean old = c.getAutoCommit(); c.setAutoCommit(false);
                try {
                    update(c, "DELETE FROM tbl_chat_group_message WHERE group_id=?", groupId);
                    update(c, "DELETE FROM tbl_chat_group_member WHERE group_id=?", groupId);
                    update(c, "DELETE FROM tbl_chat_group WHERE group_id=?", groupId);
                    for (var m : members) {
                        NotificationService.notify(c, m.get("uid").toString(), "CHAT", "群聊解散通知", "群聊「" + groupName + "」已被群主解散", "CHAT");
                    }
                    c.commit();
                    out.put("success", true);
                } catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(old); }
            }
            case "GROUP_LEAVE" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                boolean old = c.getAutoCommit(); c.setAutoCommit(false);
                try {
                    update(c, "DELETE FROM tbl_chat_group_member WHERE group_id=? AND uid=?", groupId, me);
                    var remaining = rows(c, "SELECT uid FROM tbl_chat_group_member WHERE group_id=? ORDER BY joined_at ASC", groupId);
                    if (remaining.isEmpty()) {
                        update(c, "DELETE FROM tbl_chat_group_message WHERE group_id=?", groupId);
                        update(c, "DELETE FROM tbl_chat_group WHERE group_id=?", groupId);
                    } else {
                        var groupRows = rows(c, "SELECT owner_uid FROM tbl_chat_group WHERE group_id=?", groupId);
                        if (!groupRows.isEmpty() && me.equals(groupRows.get(0).get("owner_uid"))) {
                            String newOwner = remaining.get(0).get("uid").toString();
                            update(c, "UPDATE tbl_chat_group SET owner_uid=? WHERE group_id=?", newOwner, groupId);
                            update(c, "UPDATE tbl_chat_group_member SET role='OWNER' WHERE group_id=? AND uid=?", groupId, newOwner);
                        }
                    }
                    c.commit();
                    out.put("success", true);
                } catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(old); }
            }
            case "GROUP_SEND" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                String content = text(data, "content", 2000);
                String key = text(data, "clientId", 36);
                UUID.fromString(key);
                var duplicate = rows(c, "SELECT id, content FROM tbl_chat_group_message WHERE sender=? AND client_id=?", me, key);
                if (!duplicate.isEmpty()) {
                    if (!content.equals(duplicate.get(0).get("content"))) throw new IllegalArgumentException("消息重试标识冲突");
                    out.put("id", duplicate.get(0).get("id"));
                } else {
                    update(c, "INSERT INTO tbl_chat_group_message(group_id, sender, client_id, content) VALUES(?, ?, ?, ?)",
                            groupId, me, key, content);
                    long msgId = ((Number)rows(c, "SELECT LAST_INSERT_ID() AS id").get(0).get("id")).longValue();
                    update(c, "UPDATE tbl_chat_group_member SET last_read_id=GREATEST(last_read_id, ?) WHERE group_id=? AND uid=?", msgId, groupId, me);
                    update(c, "UPDATE tbl_chat_group SET updated_at=CURRENT_TIMESTAMP WHERE group_id=?", groupId);
                    out.put("id", msgId);
                }
            }
            case "GROUP_HISTORY" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                long before = number(data, "before", Long.MAX_VALUE);
                boolean incremental = data.containsKey("after");
                var messages = rows(c, """
                    SELECT m.id, m.sender, u.name AS senderName, m.content, DATE_FORMAT(m.created_at, '%Y-%m-%d %H:%i:%s') AS time
                    FROM tbl_chat_group_message m
                    JOIN tbl_user u ON u.UID COLLATE utf8mb4_unicode_ci = m.sender COLLATE utf8mb4_unicode_ci
                    WHERE m.group_id=? AND m.id""" + (incremental ? ">? ORDER BY m.id ASC" : "<? ORDER BY m.id DESC") + " LIMIT 50",
                    groupId, incremental ? number(data, "after", 0) : before);
                if (!incremental) Collections.reverse(messages);
                out.put("messages", messages);
            }
            case "GROUP_READ" -> {
                long groupId = number(data, "groupId", 0);
                checkGroupMember(c, groupId, me);
                long through = number(data, "through", 0);
                update(c, "UPDATE tbl_chat_group_member SET last_read_id=GREATEST(last_read_id, ?) WHERE group_id=? AND uid=?", through, groupId, me);
                out.put("success", true);
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
                            if ("ACCEPT".equals(action)) {
                                String requester = link.get("requester").toString();
                                var userRows = rows(c, "SELECT name FROM tbl_user WHERE UID=?", me);
                                String myName = userRows.isEmpty() ? me : userRows.get(0).get("name").toString();
                                NotificationService.notify(c, requester, "CHAT", "好友申请通过", "「" + myName + "」已通过您的好友申请", "CHAT");
                            }
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
    private static void checkGroupMember(Connection c, long groupId, String uid) throws SQLException {
        var rows = rows(c, "SELECT role FROM tbl_chat_group_member WHERE group_id=? AND uid=?", groupId, uid);
        if (rows.isEmpty()) throw new IllegalArgumentException("您不是该群聊成员");
    }
    private static void checkGroupOwner(Connection c, long groupId, String uid) throws SQLException {
        var rows = rows(c, "SELECT owner_uid FROM tbl_chat_group WHERE group_id=?", groupId);
        if (rows.isEmpty()) throw new IllegalArgumentException("群聊不存在");
        if (!uid.equals(rows.get(0).get("owner_uid"))) throw new IllegalArgumentException("只有群主可以执行此操作");
    }
    private static void bind(PreparedStatement s,Object[] args) throws SQLException {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
}
