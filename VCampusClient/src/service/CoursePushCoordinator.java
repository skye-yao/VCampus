package service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import model.course.CoursePushEventView;
import model.course.CourseTermView;

/**
 * 课程事件协调器：ACK 优先，ACK 成功后才通知用户并刷新权威快照。
 *
 * ACK 失败保留事件等待服务端重投递；ACK 成功后刷新失败使用本地有界退避。
 * 只有 ACK 与刷新都完成的事件才进入有界完成缓存。
 */
public final class CoursePushCoordinator implements CoursePushListener, AutoCloseable {
    public static final long RETRY_INITIAL_DELAY_MILLIS = 500L;
    public static final long RETRY_MAX_DELAY_MILLIS = 8000L;
    private static final int COMPLETED_CACHE_SIZE = 64;

    public interface RetryScheduler {
        RetryHandle schedule(Runnable task, long delayMillis);
    }

    public interface RetryHandle {
        void cancel();
    }

    @FunctionalInterface
    public interface AuthoritativeRefresh {
        CompletableFuture<Void> refresh();
    }

    private final CourseService service;
    private final BiConsumer<CoursePushEventView, Duration> notification;
    private final AuthoritativeRefresh refresh;
    private final Consumer<Runnable> fxExecutor;
    private final RetryScheduler scheduler;
    private final Supplier<CourseTermView> activeTerm;
    private final Clock clock;

    private final Object lock = new Object();
    private final Map<String, EventState> inFlight = new HashMap<>();
    private final Map<String, Boolean> completed = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > COMPLETED_CACHE_SIZE;
        }
    };
    private CourseSubscription subscription;
    private boolean disposed;

    public CoursePushCoordinator(CourseService service,
            BiConsumer<CoursePushEventView, Duration> notification,
            AuthoritativeRefresh refresh,
            Consumer<Runnable> fxExecutor,
            RetryScheduler scheduler,
            Supplier<CourseTermView> activeTerm,
            Clock clock) {
        this.service = service;
        this.notification = notification;
        this.refresh = refresh;
        this.fxExecutor = fxExecutor;
        this.scheduler = scheduler;
        this.activeTerm = activeTerm;
        this.clock = clock;
    }

    public void start() {
        synchronized (lock) {
            if (disposed || subscription != null) {
                return;
            }
        }
        CourseSubscription created = service.subscribe(this);
        synchronized (lock) {
            if (disposed) {
                created.close();
                return;
            }
            subscription = created;
        }
    }

    @Override
    public void onCourseEvent(CoursePushEventView event) {
        if (event == null || event.getEventId() == null) {
            return;
        }
        String eventId = event.getEventId();
        synchronized (lock) {
            if (disposed || completed.containsKey(eventId) || inFlight.containsKey(eventId)) {
                return;
            }
            inFlight.put(eventId, new EventState());
        }

        CompletableFuture<Void> ack;
        try {
            ack = service.ackCourseEvent(eventId);
        } catch (RuntimeException failure) {
            onAckFailure(eventId);
            return;
        }
        if (ack == null) {
            onAckFailure(eventId);
            return;
        }
        ack.whenComplete((ignored, error) -> {
            if (error != null) {
                onAckFailure(eventId);
            } else {
                onAckSuccess(event, eventId);
            }
        });
    }

    @Override
    public void onReconnect() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
        }
        try {
            refresh.refresh();
        } catch (RuntimeException ignored) {
            // 重连对账失败不阻塞后续事件；下一次重连会再次对账。
        }
    }

    @Override
    public void close() {
        CourseSubscription active;
        List<RetryHandle> handles = new ArrayList<>();
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
            for (EventState state : inFlight.values()) {
                if (state.retryHandle != null) {
                    handles.add(state.retryHandle);
                }
            }
            inFlight.clear();
            active = subscription;
            subscription = null;
        }
        for (RetryHandle handle : handles) {
            handle.cancel();
        }
        if (active != null) {
            active.close();
        }
    }

    private void onAckFailure(String eventId) {
        synchronized (lock) {
            // ACK 失败不做本地盲重试；移除 in-flight 状态，等待服务端重投递再次 ACK。
            inFlight.remove(eventId);
        }
    }

    private void onAckSuccess(CoursePushEventView event, String eventId) {
        boolean notify = false;
        synchronized (lock) {
            EventState state = inFlight.get(eventId);
            if (state == null || disposed) {
                return;
            }
            state.ackPending = false;
            state.ackDone = true;
            if (!state.notificationShown) {
                state.notificationShown = true;
                notify = true;
            }
            if (!state.refreshPending && !state.refreshDone) {
                state.refreshPending = true;
            }
        }
        if (notify) {
            fxExecutor.accept(() -> {
                synchronized (lock) {
                    if (disposed || !inFlight.containsKey(eventId)) {
                        return;
                    }
                }
                notification.accept(event, event.getRemainingTime(clock.instant()));
            });
        }
        startRefresh(event, eventId);
    }

    private void startRefresh(CoursePushEventView event, String eventId) {
        CompletableFuture<Void> future;
        try {
            future = refresh.refresh();
        } catch (RuntimeException failure) {
            handleRefreshFailure(event, eventId);
            return;
        }
        if (future == null) {
            handleRefreshSuccess(eventId);
            return;
        }
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                handleRefreshFailure(event, eventId);
            } else {
                handleRefreshSuccess(eventId);
            }
        });
    }

    private void handleRefreshSuccess(String eventId) {
        synchronized (lock) {
            EventState state = inFlight.get(eventId);
            if (state == null || disposed) {
                return;
            }
            state.refreshPending = false;
            state.refreshDone = true;
            if (state.ackDone) {
                state.retryHandle = null;
                inFlight.remove(eventId);
                completed.put(eventId, Boolean.TRUE);
            }
        }
    }

    private void handleRefreshFailure(CoursePushEventView event, String eventId) {
        long delay;
        synchronized (lock) {
            EventState state = inFlight.get(eventId);
            if (state == null || disposed) {
                return;
            }
            state.refreshPending = false;
            CourseTermView active = activeTerm.get();
            if (active == null || !active.equals(event.getTerm())) {
                inFlight.remove(eventId);
                return;
            }
            state.retryAttempt++;
            delay = retryDelay(state.retryAttempt);
            state.retryHandle = scheduler.schedule(() -> retryRefresh(event, eventId), delay);
        }
    }

    private void retryRefresh(CoursePushEventView event, String eventId) {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            EventState state = inFlight.get(eventId);
            if (state == null) {
                return;
            }
            CourseTermView active = activeTerm.get();
            if (active == null || !active.equals(event.getTerm())) {
                inFlight.remove(eventId);
                return;
            }
            state.retryHandle = null;
            state.refreshPending = true;
        }
        startRefresh(event, eventId);
    }

    private static long retryDelay(int attempt) {
        long delay = RETRY_INITIAL_DELAY_MILLIS;
        for (int index = 1; index < attempt; index++) {
            if (delay >= RETRY_MAX_DELAY_MILLIS) {
                return RETRY_MAX_DELAY_MILLIS;
            }
            delay *= 2L;
        }
        return Math.min(delay, RETRY_MAX_DELAY_MILLIS);
    }

    private static final class EventState {
        private boolean ackPending = true;
        private boolean ackDone;
        private boolean refreshPending;
        private boolean refreshDone;
        private boolean notificationShown;
        private int retryAttempt;
        private RetryHandle retryHandle;
    }
}
