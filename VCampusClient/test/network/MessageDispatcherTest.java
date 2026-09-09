package network;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import protocol.Message;

public class MessageDispatcherTest {

    public static void main(String[] args) {
        rejectsDuplicateRegistrationAndFailsOnlyMatchingRequest();
        System.out.println("MessageDispatcherTest passed");
    }

    private static void rejectsDuplicateRegistrationAndFailsOnlyMatchingRequest() {
        MessageDispatcher dispatcher = new MessageDispatcher();
        CompletableFuture<Message> futureA = new CompletableFuture<>();
        CompletableFuture<Message> futureB = new CompletableFuture<>();
        CompletableFuture<Message> unrelatedFuture = new CompletableFuture<>();

        boolean first = dispatcher.registerPendingRequest(42L, futureA);
        boolean duplicate = dispatcher.registerPendingRequest(42L, futureB);
        boolean unrelated = dispatcher.registerPendingRequest(84L, unrelatedFuture);

        require(first && !duplicate, "duplicate UID must not replace an existing future");
        require(unrelated, "a different UID must register successfully");

        dispatcher.failPending(42L, new TimeoutException("request timed out"));

        require(futureA.isCompletedExceptionally(), "registered future must fail");
        require(!futureB.isDone(), "unregistered duplicate must remain untouched");
        require(!unrelatedFuture.isDone(), "unrelated pending future must remain untouched");

        CompletableFuture<Message> replacement = new CompletableFuture<>();
        require(dispatcher.registerPendingRequest(42L, replacement),
                "failed request must be removed from pending registrations");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
