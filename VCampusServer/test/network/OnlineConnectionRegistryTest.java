package network;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

public final class OnlineConnectionRegistryTest {
    private OnlineConnectionRegistryTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyBindUnbindSnapshot();
        verifyCloseAndBindInvariantUnderStress();
        System.out.println("Online connection registry test passed.");
    }

    private static void verifyBindUnbindSnapshot() {
        OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        ClientConnection first = connection();
        ClientConnection second = connection();
        ClientConnection stale = connection();
        registry.bind("student-alpha", first);
        registry.bind("student-alpha", second);
        require(registry.snapshot("student-alpha").size() == 2,
                "one account must retain every live connection");
        registry.unbind("student-alpha", stale);
        require(registry.snapshot("student-alpha").size() == 2,
                "stale unbind must not remove current connections");
        registry.unbind("student-alpha", first);
        require(registry.snapshot("student-alpha").equals(List.of(second)),
                "unbinding one connection must retain the other");
        registry.close();
        registry.close();
        require(registry.snapshot("student-alpha").isEmpty(),
                "close must clear every binding");
        require(!second.isOpen(), "registry close must close bound connections");
        stale.close();
    }

    private static void verifyCloseAndBindInvariantUnderStress() throws Exception {
        int iterations = 200;
        int binders = 8;
        int perBinder = 20;
        for (int iteration = 0; iteration < iterations; iteration++) {
            OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
            List<ClientConnection> created = Collections.synchronizedList(new ArrayList<>());
            CyclicBarrier barrier = new CyclicBarrier(binders + 1);
            Thread[] threads = new Thread[binders];

            for (int index = 0; index < binders; index++) {
                threads[index] = new Thread(() -> {
                    List<ClientConnection> mine = new ArrayList<>();
                    for (int count = 0; count < perBinder; count++) {
                        mine.add(connection());
                    }
                    created.addAll(mine);
                    await(barrier);
                    for (ClientConnection target : mine) {
                        registry.bind("student-alpha", target);
                    }
                });
                threads[index].start();
            }

            ClientConnection baseline = connection();
            created.add(baseline);
            registry.bind("student-alpha", baseline);

            barrier.await();
            registry.close();

            for (Thread thread : threads) {
                thread.join();
            }

            synchronized (created) {
                for (ClientConnection connection : created) {
                    require(!connection.isOpen(),
                            "every connection bound around close must end closed");
                }
            }
            require(registry.snapshot("student-alpha").isEmpty(),
                    "a closed registry must never retain a binding");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ClientConnection connection() {
        StringWriter writer = new StringWriter();
        return new ClientConnection(writer, writer);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
