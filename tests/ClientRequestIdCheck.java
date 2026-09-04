import com.google.gson.Gson;
import network.SocketClient;
import protocol.Message;
import protocol.MessageType;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 使用本地假服务端检查连续请求匹配，不访问业务数据库。 */
public class ClientRequestIdCheck {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     var writer = new PrintWriter(socket.getOutputStream(), true)) {
                    Gson gson = new Gson();
                    HashSet<Long> ids = new HashSet<>();
                    for (int i = 0; i < 200; i++) {
                        Message request = gson.fromJson(reader.readLine(), Message.class);
                        if (!ids.add(request.getUID())) throw new AssertionError("Duplicate UID");
                        request.setType(MessageType.RESPONSE);
                        writer.println(gson.toJson(request));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SocketClient client = SocketClient.getInstance();
            client.init("127.0.0.1", server.getLocalPort());
            var pending = new ArrayList<CompletableFuture<Message>>();
            for (int i = 0; i < 200; i++) {
                Message request = new Message(MessageType.REQUEST, "test", Integer.toString(i));
                request.setUID(1L); // 模拟同一毫秒内创建的所有请求。
                pending.add(client.sendAsync(request));
            }
            for (int i = 0; i < pending.size(); i++) {
                if (!Integer.toString(i).equals(pending.get(i).get(5, TimeUnit.SECONDS).getAction()))
                    throw new AssertionError("Response mismatch");
            }
            peer.get(5, TimeUnit.SECONDS);
            client.disconnect();
            System.out.println("PASS: 200 colliding input IDs replaced, all responses matched");
        }
    }
}
