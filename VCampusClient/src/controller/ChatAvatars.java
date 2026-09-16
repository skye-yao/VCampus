package controller;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import service.ChatClientService;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Cache avatars for the lifetime of the page, instead of downloading them on every poll. */
final class ChatAvatars implements AutoCloseable {
    private final ChatClientService api;
    private final BooleanSupplier active;
    private final Map<String,CompletableFuture<Image>> cache=new LinkedHashMap<>();
    ChatAvatars(ChatClientService api,BooleanSupplier active){this.api=api;this.active=active;}
    StackPane create(String uid,String name,double size){
        Label fallback=new Label(name==null||name.isBlank()?"人":name.substring(0,1));fallback.setStyle("-fx-text-fill:#48674e;-fx-font-size:"+(size*.40)+"px;-fx-font-weight:bold;");
        StackPane box=new StackPane(fallback);box.setMinSize(size,size);box.setPrefSize(size,size);box.setMaxSize(size,size);box.getStyleClass().add("chat-avatar");
        if(cache.size()>150)cache.remove(cache.keySet().iterator().next());
        cache.computeIfAbsent(uid,key->api.call("AVATAR",Map.of("peer",key)).thenApply(r->{
            if(!r.has("avatar")||r.get("avatar").isJsonNull())return null;
            String encoded=r.get("avatar").getAsString();if(encoded.isBlank())return null;
            if(encoded.startsWith("data:"))encoded=encoded.substring(encoded.indexOf(',')+1);
            try{Image image=new Image(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)),96,96,true,true);return image.isError()?null:image;}catch(IllegalArgumentException e){return null;}
        })).whenComplete((image,error)->Platform.runLater(()->{
            if(!active.getAsBoolean()||image==null||error!=null)return;
            ImageView view=new ImageView(image);double edge=Math.min(image.getWidth(),image.getHeight());view.setViewport(new Rectangle2D((image.getWidth()-edge)/2,(image.getHeight()-edge)/2,edge,edge));view.setFitWidth(size);view.setFitHeight(size);view.setClip(new Circle(size/2,size/2,size/2));box.getChildren().setAll(view);
        }));
        return box;
    }
    public void close(){cache.clear();}
}
