package service;

import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import util.DBUtil;

/** Real MySQL semantics with connection-local temporary tables only. */
public final class ChatServiceTest {
    private static final ChatService service=new ChatService();
    private static Connection c;
    private static Map<String,Object> run(String user,String action,Map<String,Object> data)throws Exception{return service.execute(c,user,action,data);}
    private static void blocked(String user,String action,Map<String,Object> data)throws Exception{
        try{run(user,action,data);throw new AssertionError("Expected rejection: "+action);}catch(IllegalArgumentException expected){}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static void main(String[]args)throws Exception{
        try(Connection connection=DBUtil.getConnection()){
            c=connection;
            try(var s=c.createStatement()){
                // Match MySQL 8's existing user tables while chat tables use unicode_ci.
                s.execute("CREATE TEMPORARY TABLE tbl_user(UID VARCHAR(32) PRIMARY KEY,name VARCHAR(50),role INT,status VARCHAR(20)) COLLATE=utf8mb4_0900_ai_ci");
                s.execute("INSERT INTO tbl_user VALUES('alice','甲',2,'ACTIVE'),('bob','乙',1,'ACTIVE'),('eve','丙',0,'ACTIVE'),('frozen','丁',2,'FROZEN')");
                s.execute("ALTER TABLE tbl_user ADD avatar LONGTEXT NULL");
                s.execute("UPDATE tbl_user SET avatar='test-avatar' WHERE UID='bob'");
                try(var in=ChatService.class.getResourceAsStream("/resources/migrations/v301_chat.sql")){
                    String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace("CREATE TABLE IF NOT EXISTS","CREATE TEMPORARY TABLE");
                    for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);
                }
                try(var in=NotificationService.class.getResourceAsStream("/resources/migrations/v302_notification.sql")){
                    if (in != null) {
                        String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace("CREATE TABLE IF NOT EXISTS","CREATE TEMPORARY TABLE");
                        for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);
                    }
                }
            }
            var peer=Map.<String,Object>of("peer","bob");
            check("test-avatar".equals(run("alice","AVATAR",peer).get("avatar")),"profile avatar");
            check(run("alice","AVATAR",Map.of("peer","eve")).get("avatar")==null,"default avatar");
            blocked("alice","AVATAR",Map.of("peer","frozen"));
            blocked("alice","HISTORY",peer);blocked("alice","REQUEST",Map.of("peer","alice"));blocked("alice","REQUEST",Map.of("peer","frozen"));
            run("alice","REQUEST",peer);run("alice","REQUEST",peer);
            check(((Number)run("bob","SUMMARY",Map.of()).get("pending")).intValue()==1,"deduplicate requests");
            blocked("alice","ACCEPT",peer);blocked("alice","SEND",Map.of("peer","bob","content","before approval","clientId",UUID.randomUUID().toString()));
            run("bob","REJECT",Map.of("peer","alice"));run("alice","REQUEST",peer);run("bob","ACCEPT",Map.of("peer","alice"));
            String key=UUID.randomUUID().toString();var send=Map.<String,Object>of("peer","bob","content","你好，好友！","clientId",key);
            run("alice","SEND",send);run("alice","SEND",send);
            blocked("alice","SEND",Map.of("peer","bob","content","different","clientId",key));
            blocked("eve","HISTORY",Map.of("peer","alice"));
            check(((Number)run("bob","SUMMARY",Map.of()).get("unread")).intValue()==1,"offline unread and retry");
            var history=(List<Map<String,Object>>)run("bob","HISTORY",Map.of("peer","alice")).get("messages");
            check(history.size()==1&&history.get(0).get("content").equals("你好，好友！"),"history");
            long first=((Number)history.get(0).get("id")).longValue();
            run("bob","READ",Map.of("peer","alice","through",first));
            check(((Number)run("bob","SUMMARY",Map.of()).get("unread")).intValue()==0,"mark read");
            for(int i=0;i<55;i++)run("alice","SEND",Map.of("peer","bob","content","消息"+i,"clientId",UUID.randomUUID().toString()));
            var latest=(List<Map<String,Object>>)run("bob","HISTORY",Map.of("peer","alice")).get("messages");
            check(latest.size()==50,"bounded history");
            var older=(List<?>)run("bob","HISTORY",Map.of("peer","alice","before",latest.get(0).get("id"))).get("messages");check(older.size()==6,"older history");
            var next=(List<Map<String,Object>>)run("bob","HISTORY",Map.of("peer","alice","after",first)).get("messages");check(next.size()==50,"incremental batch");
            check(((List<?>)run("bob","HISTORY",Map.of("peer","alice","after",next.get(49).get("id"))).get("messages")).size()==5,"no message gap");
            run("alice","CONTACTS",Map.of());run("alice","SEARCH",Map.of("query","乙"));
            var unauth=new handler.ChatHandler().handle(new protocol.Message(protocol.MessageType.REQUEST,"chat","SUMMARY"));
            check(unauth.getCode()==protocol.MessageCode.UNAUTHORIZED,"authentication");

            // Group chat tests
            var createRes = run("alice", "GROUP_CREATE", Map.of("name", "东南大学交流群", "members", List.of("bob")));
            long gid = ((Number)createRes.get("groupId")).longValue();
            check(gid > 0, "group created with id");

            var bobGroups = (List<Map<String, Object>>) run("bob", "GROUP_LIST", Map.of()).get("groups");
            check(bobGroups.size() == 1 && "东南大学交流群".equals(bobGroups.get(0).get("name")), "bob directly in group");
            check("MEMBER".equals(bobGroups.get(0).get("myRole")), "bob is member");
            check(((Number)bobGroups.get(0).get("memberCount")).intValue() == 2, "group has 2 members");

            var info = run("alice", "GROUP_INFO", Map.of("groupId", gid));
            var members = (List<Map<String, Object>>) info.get("members");
            check(members.size() == 2, "group info member count");

            blocked("eve", "GROUP_INFO", Map.of("groupId", gid));
            blocked("eve", "GROUP_SEND", Map.of("groupId", gid, "content", "hi", "clientId", UUID.randomUUID().toString()));

            String gMsgKey = UUID.randomUUID().toString();
            var sendGRes = run("alice", "GROUP_SEND", Map.of("groupId", gid, "content", "大家好！", "clientId", gMsgKey));
            long gMsgId = ((Number)sendGRes.get("id")).longValue();
            var retrySendGRes = run("alice", "GROUP_SEND", Map.of("groupId", gid, "content", "大家好！", "clientId", gMsgKey));
            check(((Number)retrySendGRes.get("id")).longValue() == gMsgId, "group send deduplicate");
            blocked("alice", "GROUP_SEND", Map.of("groupId", gid, "content", "diff", "clientId", gMsgKey));

            bobGroups = (List<Map<String, Object>>) run("bob", "GROUP_LIST", Map.of()).get("groups");
            check(((Number)bobGroups.get(0).get("unread")).intValue() >= 1, "bob sees group unread");

            var gHistory = (List<Map<String, Object>>) run("bob", "GROUP_HISTORY", Map.of("groupId", gid)).get("messages");
            check(!gHistory.isEmpty(), "bob sees group history");
            run("bob", "GROUP_READ", Map.of("groupId", gid, "through", gMsgId));
            bobGroups = (List<Map<String, Object>>) run("bob", "GROUP_LIST", Map.of()).get("groups");
            check(((Number)bobGroups.get(0).get("unread")).intValue() == 0, "bob marked read");

            run("alice", "GROUP_ADD_MEMBERS", Map.of("groupId", gid, "members", List.of("eve")));
            var eveGroups = (List<Map<String, Object>>) run("eve", "GROUP_LIST", Map.of()).get("groups");
            check(eveGroups.size() == 1, "eve directly added to group");

            blocked("bob", "GROUP_RENAME", Map.of("groupId", gid, "name", "黑客帝国"));
            run("alice", "GROUP_RENAME", Map.of("groupId", gid, "name", "东南大学技术交流群"));
            var updatedInfo = run("bob", "GROUP_INFO", Map.of("groupId", gid));
            check("东南大学技术交流群".equals(((Map<String, Object>)updatedInfo.get("group")).get("name")), "group renamed");

            blocked("bob", "GROUP_KICK", Map.of("groupId", gid, "memberUid", "eve"));
            blocked("alice", "GROUP_KICK", Map.of("groupId", gid, "memberUid", "alice"));
            run("alice", "GROUP_KICK", Map.of("groupId", gid, "memberUid", "eve"));
            blocked("eve", "GROUP_HISTORY", Map.of("groupId", gid));

            blocked("bob", "GROUP_DISSOLVE", Map.of("groupId", gid));
            run("alice", "GROUP_DISSOLVE", Map.of("groupId", gid));
            var aliceGroupsAfter = (List<Map<String, Object>>) run("alice", "GROUP_LIST", Map.of()).get("groups");
            check(aliceGroupsAfter.isEmpty(), "group dissolved");

            System.out.println("PASS: friend and group chat (create, direct entry, send, read, history, kick, rename, dissolve); temporary tables only");
        }
    }
}
