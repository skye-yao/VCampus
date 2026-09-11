package network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import protocol.Message;

/**
 * 跨连接代际持久的推送与重连监听注册表。
 *
 * 每一代 {@link MessageDispatcher} 共享同一个注册表，因此重新连接不会丢失监听者。
 * 分发只读取监听者集合的副本，回调期间不持有任何请求相关的锁。
 */
public final class PushListenerRegistry {

    private final Map<String, CopyOnWriteArrayList<Consumer<Message>>> pushListeners =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();

    public Subscription registerPush(String module, String action,
            Consumer<Message> listener) {
        if (module == null || action == null || listener == null) {
            throw new IllegalArgumentException("Push listener requires module, action and listener");
        }
        String key = key(module, action);
        CopyOnWriteArrayList<Consumer<Message>> listeners =
                pushListeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return new Subscription(() -> listeners.remove(listener));
    }

    public Subscription registerReconnect(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Reconnect listener is required");
        }
        reconnectListeners.add(listener);
        return new Subscription(() -> reconnectListeners.remove(listener));
    }

    public void dispatch(Message push) {
        if (push == null || push.getModule() == null || push.getAction() == null) {
            return;
        }
        List<Consumer<Message>> listeners = pushListeners.get(key(push.getModule(), push.getAction()));
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Consumer<Message> listener : new ArrayList<>(listeners)) {
            try {
                listener.accept(push);
            } catch (RuntimeException failure) {
                System.out.println("课程推送监听器异常: " + failure.getMessage());
            }
        }
    }

    public void fireReconnect() {
        for (Runnable listener : new ArrayList<>(reconnectListeners)) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                System.out.println("课程重连监听器异常: " + failure.getMessage());
            }
        }
    }

    private static String key(String module, String action) {
        return module + '\u0000' + action;
    }

    public static final class Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Runnable cancelAction;

        private Subscription(Runnable cancelAction) {
            this.cancelAction = cancelAction;
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancelAction.run();
            }
        }
    }
}
