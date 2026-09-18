package controller;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import service.ChatClientService;
import session.ClientSession;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the real creation dialog and its asynchronous success callback. */
public class ChatGroupCreationTest {
    static Object field(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void fx(Runnable action)throws Exception{
        CompletableFuture<Void> done=new CompletableFuture<>();
        Platform.runLater(()->{try{action.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});
        done.get(10,TimeUnit.SECONDS);
    }
    static class Fake extends ChatClientService {
        final JsonArray groups=new JsonArray();
        Map<String,Object> sent;
        public CompletableFuture<JsonObject> call(String action,Map<String,Object> data){
            JsonObject result=new JsonObject();
            switch(action){
                case "CONTACTS" -> {result.add("friends",JsonParser.parseString("[{\"uid\":\"bob\",\"name\":\"Bob\"}]"));result.add("requests",new JsonArray());}
                case "GROUP_LIST" -> result.add("groups",groups.deepCopy());
                case "GROUP_CREATE" -> {
                    JsonObject g=new JsonObject();g.addProperty("groupId",groups.size()+1);
                    g.addProperty("name",data.get("name").toString());g.addProperty("ownerUid","alice");
                    g.addProperty("memberCount",2);g.addProperty("myRole","OWNER");groups.add(g);
                    result.add("groupId",g.get("groupId"));
                }
                case "HISTORY", "GROUP_HISTORY" -> result.add("messages",new JsonArray());
                case "GROUP_SEND" -> sent=new HashMap<>(data);
                case "SEND" -> throw new AssertionError("New group message sent to a friend");
            }
            return CompletableFuture.completedFuture(result);
        }
    }
    public static void main(String[] args)throws Exception{
        Platform.startup(()->{});Platform.setImplicitExit(false);
        try{
            for(int scenario=0;scenario<3;scenario++){
                Fake api=new Fake();ChatPane[] pane=new ChatPane[1];
                fx(()->{ClientSession.getInstance().login("alice","学生","test",null);pane[0]=new ChatPane(api);pane[0].start();});
                for(int i=0;i<6;i++)fx(()->{});
                final int mode=scenario;
                fx(()->{try{
                    if(mode==1)((ListView<?>)field(pane[0],"friends")).getSelectionModel().select(0);
                    if(mode==2){api.call("GROUP_CREATE",Map.of("name","Old group"));pane[0].openGroup(1);}
                }catch(Exception e){throw new RuntimeException(e);}});
                for(int i=0;i<6;i++)fx(()->{});
                CompletableFuture<Void> created=new CompletableFuture<>();
                Platform.runLater(()->{try{
                    Platform.runLater(()->{try{
                        Window dialog=Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
                        ((TextField)dialog.getScene().getRoot().lookup(".text-field")).setText("New group");
                        for(var node:dialog.getScene().getRoot().lookupAll(".check-box"))((CheckBox)node).setSelected(true);
                        dialog.getScene().getRoot().lookupAll(".button").stream().map(n->(Button)n)
                            .filter(b->"立即创建".equals(b.getText())).findFirst().orElseThrow().fire();
                    }catch(Throwable e){created.completeExceptionally(e);}});
                    ((Button)field(pane[0],"createGroupBtn")).fire();created.complete(null);
                }catch(Throwable e){created.completeExceptionally(e);}});
                created.get(10,TimeUnit.SECONDS);
                for(int i=0;i<8;i++)fx(()->{});
                fx(()->{try{
                    TextArea input=(TextArea)field(pane[0],"input");Button send=(Button)field(pane[0],"send");
                    if(input.isDisabled()||send.isDisabled())throw new AssertionError("Composer disabled after creation");
                    if(field(pane[0],"peer")!=null)throw new AssertionError("Previous friend still active");
                    if(!"New group".equals(((Label)field(pane[0],"title")).getText()))throw new AssertionError("Wrong group");
                    input.setText("Hello new group");send.fire();
                    if(api.sent==null||((Number)api.sent.get("groupId")).intValue()!=api.groups.size())throw new AssertionError("Wrong send target");
                }catch(Exception e){throw new RuntimeException(e);}});
                fx(pane[0]::close);
            }
            System.out.println("PASS: create and immediately send from empty, friend and group conversations");
        }finally{Platform.exit();}
    }
}
