package controller;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.util.Duration;
import service.ChatClientService;
import session.ClientSession;
import java.util.*;

/** Main-scene unread badge; the chat content itself follows ordinary module routing. */
public final class ChatEntry implements AutoCloseable {
    private final Button button;
    private final String token=ClientSession.getInstance().getToken();
    private final Timeline timer;
    private boolean busy,closed;
    private final java.util.function.BiConsumer<Integer, Integer> onSummary;

    public ChatEntry(Button button,Runnable open){
        this(button, open, null);
    }
    public ChatEntry(Button button,Runnable open, java.util.function.BiConsumer<Integer, Integer> onSummary){
        this.button=button;
        this.onSummary=onSummary;
        button.setOnAction(e->open.run());
        timer=new Timeline(new KeyFrame(Duration.seconds(3),e->refresh()));timer.setCycleCount(Timeline.INDEFINITE);
        button.sceneProperty().addListener((o,old,scene)->{if(scene==null){close();}else{
            scene.rootProperty().addListener((p,a,b)->{if(button.getScene()!=scene)close();});
            scene.windowProperty().addListener((p,a,b)->{if(b==null)close();});
            timer.play();refresh();
        }});
    }
    public void triggerRefresh(){
        refresh();
    }
    private void refresh(){
        if(closed)return;if(!Objects.equals(token,ClientSession.getInstance().getToken())){close();return;}if(busy)return;busy=true;
        new ChatClientService().call("SUMMARY",Map.of()).whenComplete((r,error)->Platform.runLater(()->{
            busy=false;if(closed||!Objects.equals(token,ClientSession.getInstance().getToken()))return;
            if(error!=null){
                button.setText("☏   聊天");
                if(onSummary!=null)onSummary.accept(-1,-1);
                return;
            }
            int unread=r.has("unread")?r.get("unread").getAsInt():0;
            int pending=r.has("pending")?r.get("pending").getAsInt():0;
            button.setText("☏   聊天");
            if(onSummary!=null)onSummary.accept(unread,pending);
        }));
    }
    public void close(){if(closed)return;closed=true;timer.stop();}
}
