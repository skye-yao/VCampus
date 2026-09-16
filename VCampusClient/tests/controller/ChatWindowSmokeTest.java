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

/** Renders the actual window off screen using a deterministic fake network service. */
public class ChatWindowSmokeTest {
    static Object field(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    static void invoke(Object o,String key)throws Exception{Method m=o.getClass().getDeclaredMethod(key);m.setAccessible(true);m.invoke(o);}
    static final CompletableFuture<Void> done=new CompletableFuture<>();
    public static void main(String[]args)throws Exception{
        Platform.startup(()->{
            try{
                ClientSession.getInstance().login("alice","学生","test",null);
                ChatClientService fake=new ChatClientService(){public CompletableFuture<JsonObject> call(String action,Map<String,Object> data){
                    String json=switch(action){
                        case "CONTACTS" -> "{\"friends\":[{\"uid\":\"bob\",\"name\":\"李雨桐\",\"unread\":1}],\"requests\":[{\"uid\":\"eve\",\"name\":\"王浩然\",\"requester\":\"eve\",\"status\":\"PENDING\"}]}";
                        case "HISTORY" -> "{\"messages\":[{\"id\":1,\"sender\":\"bob\",\"content\":\"你好，好友申请通过啦！下午一起去图书馆吗？\",\"time\":\"2026-09-16 06:30:00\"},{\"id\":2,\"sender\":\"alice\",\"content\":\"好呀，我们三点在图书馆门口见。\",\"time\":\"2026-09-16 06:31:00\"}]}";
                        default -> "{}";
                    };return CompletableFuture.completedFuture(JsonParser.parseString(json).getAsJsonObject());
                }};
                ChatWindow window=new ChatWindow(null,fake);invoke(window,"refresh");
                Platform.runLater(()->{try{
                    ((ListView<?>)field(window,"friends")).getSelectionModel().select(0);
                    Platform.runLater(()->{try{
                        VBox bubbles=(VBox)field(window,"bubbles");if(bubbles.getChildren().size()!=2)throw new AssertionError("history bubbles missing");
                        if(((Button)field(window,"send")).isDisabled())throw new AssertionError("send disabled for friend");
                        Stage stage=(Stage)field(window,"stage");var root=stage.getScene().getRoot();root.resize(940,660);root.applyCss();root.layout();
                        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",new File("build-check/chat/chat-preview.png"));
                        window.close();done.complete(null);
                    }catch(Throwable e){done.completeExceptionally(e);}});
                }catch(Throwable e){done.completeExceptionally(e);}});
            }catch(Throwable e){done.completeExceptionally(e);}
        });
        try{done.get(30,TimeUnit.SECONDS);System.out.println("PASS: chat scene layout, contacts, requests, history bubbles and send availability");}finally{Platform.exit();}
    }
}
