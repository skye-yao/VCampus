package controller;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.embed.swing.SwingFXUtils;
import service.ChatClientService;
import session.ClientSession;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import javax.imageio.ImageIO;
import java.io.File;

/** Renders the embedded module off screen with a deterministic network service. */
public class ChatWindowSmokeTest {
    static Object field(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    static void invoke(Object o,String key)throws Exception{Method m=o.getClass().getDeclaredMethod(key);m.setAccessible(true);m.invoke(o);}
    static final CompletableFuture<Void> done=new CompletableFuture<>();
    public static void main(String[]args)throws Exception{
        Platform.startup(()->{
            try{
                ClientSession.getInstance().login("alice","学生","test",null);
                var pixels=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=pixels.createGraphics();graphics.setColor(new java.awt.Color(103,153,127));graphics.fillRect(0,0,64,64);graphics.setColor(java.awt.Color.WHITE);graphics.fillOval(23,12,18,18);graphics.fillRoundRect(15,33,34,25,18,18);graphics.dispose();
                var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(pixels,"png",bytes);
                String avatar=Base64.getEncoder().encodeToString(bytes.toByteArray());
                Map<String,Integer> avatarCalls=new HashMap<>();
                ChatClientService fake=new ChatClientService(){public CompletableFuture<JsonObject> call(String action,Map<String,Object> data){
                    if("AVATAR".equals(action)){String uid=data.get("peer").toString();avatarCalls.merge(uid,1,Integer::sum);JsonObject result=new JsonObject();if("bob".equals(uid))result.addProperty("avatar",avatar);return CompletableFuture.completedFuture(result);}
                    String json=switch(action){
                        case "CONTACTS" -> "{\"friends\":[{\"uid\":\"bob\",\"name\":\"李雨桐\",\"unread\":1}],\"requests\":[{\"uid\":\"eve\",\"name\":\"王浩然\",\"requester\":\"eve\",\"status\":\"PENDING\"}]}";
                        case "GROUP_LIST" -> "{\"groups\":[{\"groupId\":101,\"name\":\"课设研讨组\",\"ownerUid\":\"alice\",\"memberCount\":3,\"unread\":2,\"myRole\":\"OWNER\"}]}";
                        case "GROUP_INFO" -> "{\"group\":{\"groupId\":101,\"name\":\"课设研讨组\",\"ownerUid\":\"alice\",\"createdAt\":\"2026-09-16 07:00:00\"},\"members\":[{\"uid\":\"alice\",\"name\":\"alice\",\"role\":\"OWNER\"},{\"uid\":\"bob\",\"name\":\"李雨桐\",\"role\":\"MEMBER\"},{\"uid\":\"eve\",\"name\":\"王浩然\",\"role\":\"MEMBER\"}]}";
                        case "HISTORY" -> "{\"messages\":[{\"id\":1,\"sender\":\"bob\",\"content\":\"你好，好友申请通过啦！下午一起去图书馆吗？\",\"time\":\"2026-09-16 06:30:00\"},{\"id\":2,\"sender\":\"alice\",\"content\":\"好呀，我们三点在图书馆门口见。\",\"time\":\"2026-09-16 06:31:00\"}]}";
                        case "GROUP_HISTORY" -> "{\"messages\":[{\"id\":10,\"sender\":\"bob\",\"senderName\":\"李雨桐\",\"content\":\"大家好，我们的群聊创建好啦！\",\"time\":\"2026-09-16 07:00:00\"},{\"id\":11,\"sender\":\"alice\",\"senderName\":\"alice\",\"content\":\"收到，今天开始分工！\",\"time\":\"2026-09-16 07:05:00\"}]}";
                        default -> "{}";
                    };return CompletableFuture.completedFuture(JsonParser.parseString(json).getAsJsonObject());
                }};
                int windows=javafx.stage.Window.getWindows().size();
                ChatPane window=new ChatPane(fake);invoke(window,"refresh");
                Platform.runLater(()->{try{
                    ((ListView<?>)field(window,"friends")).getSelectionModel().select(0);
                    Platform.runLater(()->Platform.runLater(()->{try{
                        VBox bubbles=(VBox)field(window,"bubbles");if(bubbles.getChildren().size()!=2)throw new AssertionError("history bubbles missing");
                        if(((Button)field(window,"send")).isDisabled())throw new AssertionError("send disabled for friend");
                        var root=window.getView();BorderPane host=new BorderPane(root);new javafx.scene.Scene(host,1100,720);host.resize(1100,720);host.applyCss();host.layout();
                        if(javafx.stage.Window.getWindows().size()!=windows)throw new AssertionError("chat opened a separate window");
                        HBox header=(HBox)field(window,"conversationHeader");
                        if(!(((StackPane)header.getChildren().get(0)).getChildren().get(0) instanceof javafx.scene.image.ImageView))throw new AssertionError("profile avatar not rendered");
                        if(avatarCalls.get("bob")!=1)throw new AssertionError("avatar downloaded repeatedly");
                        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",new File("build-check/chat/chat-preview.png"));

                        // Now test selecting the group
                        TabPane tabs=(TabPane)((SplitPane)root.getCenter()).getItems().get(0);
                        tabs.getSelectionModel().select(1);
                        ((ListView<?>)field(window,"groups")).getSelectionModel().select(0);
                        Platform.runLater(()->Platform.runLater(()->{try{
                            if(bubbles.getChildren().size()!=2)throw new AssertionError("group history bubbles missing");
                            if(((Button)field(window,"send")).isDisabled())throw new AssertionError("send disabled for group");
                            host.applyCss();host.layout();
                            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",new File("build-check/chat/chat-group-preview.png"));
                            host.setCenter(new Label("主页"));
                            if(!(Boolean)field(window,"closed"))throw new AssertionError("chat polling survived navigation");
                            window.close();done.complete(null);
                        }catch(Throwable e){done.completeExceptionally(e);}}));
                    }catch(Throwable e){done.completeExceptionally(e);}}));
                }catch(Throwable e){done.completeExceptionally(e);}});
            }catch(Throwable e){done.completeExceptionally(e);}
        });
        try{done.get(30,TimeUnit.SECONDS);System.out.println("PASS: chat scene layout, contacts, requests, history bubbles and send availability");}finally{Platform.exit();}
    }
}
