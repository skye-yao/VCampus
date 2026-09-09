package protocol;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class MessageUidTest {

    private static final int MESSAGE_COUNT = 20_000;

    public static void main(String[] args) {
        Set<Long> UIDs = ConcurrentHashMap.newKeySet();

        IntStream.range(0, MESSAGE_COUNT)
                .parallel()
                .mapToObj(index -> new Message().getUID())
                .forEach(UID -> {
                    require(UID != null, "message UID must not be null");
                    UIDs.add(UID);
                });

        require(UIDs.size() == MESSAGE_COUNT,
                "parallel message creation must produce unique UIDs");

        System.out.println("MessageUidTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
