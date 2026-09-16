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
            System.out.println("PASS: friend approval/rejection, authorization, retry deduplication, offline unread, history pagination and incremental catch-up; temporary tables only");
        }
    }
}
