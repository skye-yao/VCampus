package service;

import com.google.gson.*;
import network.SocketClient;
import protocol.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ChatClientService {
    public CompletableFuture<JsonObject> call(String action, Map<String,Object> data) {
        Message request=new Message(MessageType.REQUEST,"chat",action);
        request.setData(data);
        String token=session.ClientSession.getInstance().getToken();
        return CompletableFuture.supplyAsync(()->{
            if(token==null||!token.equals(session.ClientSession.getInstance().getToken()))throw new IllegalStateException("登录已失效，请重新登录");
            return SocketClient.getInstance().sendAsync(request);
        }).thenCompose(future->future).thenApply(response->{
            if(response.getCode()!=MessageCode.SUCCESS) throw new IllegalStateException(response.getMessage());
            return new Gson().toJsonTree(response.getData()).getAsJsonObject();
        });
    }
}
