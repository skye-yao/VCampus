package network;

import com.google.gson.Gson;
import protocol.Message;
import protocol.MessageType;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public final class ClientConnectionTest {
    private ClientConnectionTest() {
    }

    public static void main(String[] args) throws Exception {
        StringWriter output = new StringWriter();
        ClientConnection connection = new ClientConnection(output, output);
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        Thread responses = new Thread(() -> send(connection, start, MessageType.RESPONSE, perThread));
        Thread pushes = new Thread(() -> send(connection, start, MessageType.PUSH, perThread));
        responses.start();
        pushes.start();
        start.countDown();
        responses.join();
        pushes.join();

        String[] lines = output.toString().lines().toArray(String[]::new);
        require(lines.length == perThread * 2, "every concurrent message must produce one line");
        Gson gson = new Gson();
        require(Arrays.stream(lines).map(line -> gson.fromJson(line, Message.class))
                        .allMatch(message -> message.getType() == MessageType.RESPONSE
                                || message.getType() == MessageType.PUSH),
                "shared writes must never interleave JSON fragments");
        connection.close();
        require(!connection.isOpen(), "closed connection must report closed");
        System.out.println("Client connection test passed.");
    }

    private static void send(ClientConnection connection, CountDownLatch start,
                             MessageType type, int count) {
        try {
            start.await();
            for (int index = 0; index < count; index++) {
                Message message = new Message(type, "course", "test");
                message.putData("index", index);
                connection.send(message);
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
