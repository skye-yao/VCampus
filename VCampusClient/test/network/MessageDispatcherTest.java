package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import protocol.Message;
import protocol.MessageType;

public class MessageDispatcherTest {

    public static void main(String[] args) throws Exception {
        rejectsDuplicateRegistrationAndTimeoutFailsOnlyMatchingRequest();
        closeRejectsNewRegistrationsAndFailsAcceptedRequests();
        concurrentRegistrationAndCloseLeaveNoAcceptedRequestPending();
        conditionalRemovalDoesNotDeleteReplacement();
        closedGenerationCannotFailNewGeneration();
        disconnectDoesNotWaitForSendLock();
        customAsyncTimeoutCleansPendingRegistration();
        sendSyncTimeoutCleansPendingRegistration();
        callerCancellationRemovesPendingRegistration();
        System.out.println("MessageDispatcherTest passed");
    }

    private static void rejectsDuplicateRegistrationAndTimeoutFailsOnlyMatchingRequest() {
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

    private static void closeRejectsNewRegistrationsAndFailsAcceptedRequests() {
        MessageDispatcher dispatcher = new MessageDispatcher();
        CompletableFuture<Message> acceptedFuture = new CompletableFuture<>();
        CompletableFuture<Message> rejectedFuture = new CompletableFuture<>();

        require(dispatcher.registerPendingRequest(1L, acceptedFuture),
                "open dispatcher must accept a request");

        dispatcher.failAllPending(new IllegalStateException("connection closed"));

        require(acceptedFuture.isCompletedExceptionally(),
                "closing dispatcher must fail every accepted request");
        require(!dispatcher.registerPendingRequest(2L, rejectedFuture),
                "closed dispatcher must reject new registrations");
        require(!rejectedFuture.isDone(),
                "dispatcher must not complete a future it rejected");
    }

    private static void concurrentRegistrationAndCloseLeaveNoAcceptedRequestPending()
            throws InterruptedException {
        for (int iteration = 0; iteration < 200; iteration++) {
            MessageDispatcher dispatcher = new MessageDispatcher();
            CompletableFuture<Message> future = new CompletableFuture<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean(false);

            Thread registerThread = new Thread(() -> {
                await(start);
                accepted.set(dispatcher.registerPendingRequest(11L, future));
            }, "ConcurrentRegister");
            Thread closeThread = new Thread(() -> {
                await(start);
                dispatcher.failAllPending(new IOException("connection closed"));
            }, "ConcurrentClose");

            registerThread.start();
            closeThread.start();
            start.countDown();
            registerThread.join();
            closeThread.join();

            require(!accepted.get() || future.isCompletedExceptionally(),
                    "every request accepted before close must be failed");
            require(!dispatcher.registerPendingRequest(12L, new CompletableFuture<>()),
                    "dispatcher must remain closed after concurrent registration");
        }
    }

    private static void conditionalRemovalDoesNotDeleteReplacement() {
        MessageDispatcher dispatcher = new MessageDispatcher();
        CompletableFuture<Message> original = new CompletableFuture<>();
        CompletableFuture<Message> replacement = new CompletableFuture<>();

        require(dispatcher.registerPendingRequest(7L, original),
                "original request must register");
        require(dispatcher.removePendingRequest(7L, original),
                "matching future must be removed");
        require(dispatcher.registerPendingRequest(7L, replacement),
                "UID must be reusable after cleanup");
        require(!dispatcher.removePendingRequest(7L, original),
                "stale future must not remove replacement registration");

        dispatcher.failPending(7L, new TimeoutException("replacement timeout"));
        require(replacement.isCompletedExceptionally(),
                "replacement registration must remain available for cleanup");
    }

    private static void closedGenerationCannotFailNewGeneration() {
        MessageDispatcher oldDispatcher = new MessageDispatcher();
        MessageDispatcher newDispatcher = new MessageDispatcher();
        CompletableFuture<Message> oldFuture = new CompletableFuture<>();
        CompletableFuture<Message> newFuture = new CompletableFuture<>();

        require(oldDispatcher.registerPendingRequest(9L, oldFuture),
                "old generation must accept its request before closing");
        oldDispatcher.failAllPending(new IOException("old connection closed"));
        require(oldFuture.isCompletedExceptionally(),
                "old generation request must fail when it closes");

        require(newDispatcher.registerPendingRequest(9L, newFuture),
                "new generation must own an independent UID mapping");
        oldDispatcher.failAllPending(new IOException("old receiver finished"));
        require(!newFuture.isDone(),
                "old generation cleanup must not affect new generation");

        newDispatcher.failAllPending(new IOException("test cleanup"));
    }

    private static void disconnectDoesNotWaitForSendLock() throws Exception {
        SocketClient client = SocketClient.getInstance();
        CountDownLatch disconnectFinished = new CountDownLatch(1);
        Thread sendThread = null;
        Thread disconnectThread = null;
        boolean disconnectedPromptly;

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> drainClientMessages(server), "SendLockTestPeer");
            peer.setDaemon(true);
            peer.start();

            client.init("127.0.0.1", server.getLocalPort());
            client.connect();

            Object sendLock = getField(client, "sendLock");
            synchronized (sendLock) {
                sendThread = new Thread(
                        () -> client.sendAsync(new Message(MessageType.REQUEST, "test", "blocked-send")),
                        "BlockedSocketSend");
                sendThread.setDaemon(true);
                sendThread.start();
                waitUntilBlocked(sendThread);

                disconnectThread = new Thread(() -> {
                    client.disconnect();
                    disconnectFinished.countDown();
                }, "ConcurrentDisconnect");
                disconnectThread.setDaemon(true);
                disconnectThread.start();

                disconnectedPromptly = disconnectFinished.await(2, TimeUnit.SECONDS);
            }
        } finally {
            if (sendThread != null) {
                sendThread.join(2_000);
            }
            if (disconnectThread != null) {
                disconnectThread.join(2_000);
            }
            client.disconnect();
        }

        require(disconnectedPromptly,
                "disconnect must not wait for a sender holding or waiting for sendLock");
    }

    private static void customAsyncTimeoutCleansPendingRegistration() throws Exception {
        SocketClient client = SocketClient.getInstance();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> drainClientMessages(server), "AsyncTimeoutTestPeer");
            peer.setDaemon(true);
            peer.start();

            client.init("127.0.0.1", server.getLocalPort());

            Message firstRequest = new Message(MessageType.REQUEST, "test", "async-timeout");
            Long reusedUID = firstRequest.getUID();
            CompletableFuture<Message> firstFuture =
                    client.sendAsync(firstRequest, 100, TimeUnit.MILLISECONDS);

            Exception timeoutFailure = null;
            try {
                firstFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                timeoutFailure = e;
            }

            require(isTimeout(timeoutFailure), "custom sendAsync timeout must fail the request");

            Message secondRequest = new Message(MessageType.REQUEST, "test", "after-async-timeout");
            secondRequest.setUID(reusedUID);
            CompletableFuture<Message> secondFuture =
                    client.sendAsync(secondRequest, 1, TimeUnit.SECONDS);

            require(!secondFuture.isCompletedExceptionally(),
                    "custom sendAsync timeout must remove its pending registration");
            secondFuture.cancel(false);
        } finally {
            client.disconnect();
        }
    }

    private static void sendSyncTimeoutCleansPendingRegistration() throws Exception {
        SocketClient client = SocketClient.getInstance();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> drainClientMessages(server), "SendSyncTimeoutTestPeer");
            peer.setDaemon(true);
            peer.start();

            client.init("127.0.0.1", server.getLocalPort());

            Message firstRequest = new Message(MessageType.REQUEST, "test", "sync-timeout");
            Long reusedUID = firstRequest.getUID();
            Exception timeoutFailure = null;
            try {
                client.sendSync(firstRequest, 1);
            } catch (Exception e) {
                timeoutFailure = e;
            }

            require(isTimeout(timeoutFailure), "sendSync must end with a timeout");

            Message secondRequest = new Message(MessageType.REQUEST, "test", "after-sync-timeout");
            secondRequest.setUID(reusedUID);
            CompletableFuture<Message> secondFuture = client.sendAsync(secondRequest);

            require(!secondFuture.isCompletedExceptionally(),
                    "sendSync timeout must remove its pending registration");
            secondFuture.cancel(false);
        } finally {
            client.disconnect();
        }
    }

    private static void callerCancellationRemovesPendingRegistration() throws Exception {
        SocketClient client = SocketClient.getInstance();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> drainClientMessages(server), "MessageDispatcherTestPeer");
            peer.setDaemon(true);
            peer.start();

            client.init("127.0.0.1", server.getLocalPort());

            Message firstRequest = new Message(MessageType.REQUEST, "test", "first");
            Long reusedUID = firstRequest.getUID();
            CompletableFuture<Message> firstFuture = client.sendAsync(firstRequest);

            Message duplicateRequest = new Message(MessageType.REQUEST, "test", "duplicate");
            duplicateRequest.setUID(reusedUID);
            CompletableFuture<Message> duplicateFuture = client.sendAsync(duplicateRequest);

            require(duplicateFuture.isCompletedExceptionally(),
                    "SocketClient must fail a request rejected by the dispatcher");
            require(!firstFuture.isDone(),
                    "rejected duplicate must not complete the accepted request");

            require(firstFuture.cancel(false), "caller must be able to cancel pending request");

            Message secondRequest = new Message(MessageType.REQUEST, "test", "second");
            secondRequest.setUID(reusedUID);
            CompletableFuture<Message> secondFuture = client.sendAsync(secondRequest);

            require(!secondFuture.isCompletedExceptionally(),
                    "caller cancellation must remove its pending registration");
            secondFuture.cancel(false);
        } finally {
            client.disconnect();
        }
    }

    private static void drainClientMessages(ServerSocket server) {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {
            while (reader.readLine() != null) {
                // Keep the connection open without replying.
            }
        } catch (IOException ignored) {
            // Test cleanup closes the socket.
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        require(thread.getState() == Thread.State.BLOCKED,
                "sender must reach the controlled sendLock wait");
    }

    private static boolean isTimeout(Exception failure) {
        return failure instanceof TimeoutException
                || (failure instanceof ExecutionException
                && failure.getCause() instanceof TimeoutException);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
