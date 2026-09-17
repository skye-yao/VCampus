package service;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import entity.SystemNotification;
import network.SocketClient;
import protocol.*;
import session.ClientSession;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class NotificationClientService {

    private static final Gson GSON = new Gson();

    public CompletableFuture<JsonObject> call(String action, Map<String, Object> data) {
        Message request = new Message(MessageType.REQUEST, "notification", action);
        request.setData(data);
        String token = ClientSession.getInstance().getToken();
        return CompletableFuture.supplyAsync(() -> {
            if (token == null || !token.equals(ClientSession.getInstance().getToken())) {
                throw new IllegalStateException("登录已失效，请重新登录");
            }
            return SocketClient.getInstance().sendAsync(request);
        }).thenCompose(future -> future).thenApply(response -> {
            if (response.getCode() != MessageCode.SUCCESS) {
                throw new IllegalStateException(response.getMessage() != null ? response.getMessage() : "请求失败");
            }
            if (response.getData() == null) return new JsonObject();
            return GSON.toJsonTree(response.getData()).getAsJsonObject();
        });
    }

    public CompletableFuture<List<SystemNotification>> list(boolean unreadOnly, int limit, int offset) {
        return call("LIST", Map.of("unreadOnly", unreadOnly, "limit", limit, "offset", offset))
                .thenApply(json -> {
                    if (!json.has("notifications") || !json.get("notifications").isJsonArray()) {
                        return new ArrayList<>();
                    }
                    Type type = new TypeToken<List<SystemNotification>>() {}.getType();
                    return GSON.fromJson(json.get("notifications"), type);
                });
    }

    public CompletableFuture<Long> unreadCount() {
        return call("UNREAD_COUNT", Map.of()).thenApply(json -> {
            if (json.has("unreadCount")) {
                return json.get("unreadCount").getAsLong();
            }
            return 0L;
        });
    }

    public CompletableFuture<Boolean> markRead(long id) {
        return call("MARK_READ", Map.of("id", id)).thenApply(json ->
                json.has("success") && json.get("success").getAsBoolean());
    }

    public CompletableFuture<Integer> markAllRead() {
        return call("MARK_ALL_READ", Map.of()).thenApply(json ->
                json.has("count") ? json.get("count").getAsInt() : 0);
    }

    public CompletableFuture<Boolean> delete(long id) {
        return call("DELETE", Map.of("id", id)).thenApply(json ->
                json.has("success") && json.get("success").getAsBoolean());
    }
}
